package com.ruh.flight.live

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LiveFlight(
    val id: String?,
    val number: String,
    val direction: String,   // "dep" | "arr" — from the schedule page it came from
    val statusWord: String,
    val statusColor: String,
    val realEpoch: Long,
    val scheduledEpoch: Long,
    val otherIata: String,
    val otherName: String,
    val otherCity: String,
    val airline: String,
    val terminal: String,
    val gate: String,
    val aircraft: String,
    val codeshare: List<String>,
    val live: Boolean
) {
    /**
     * FR24's per-flight route is /data/flights/{NUMBER}. The older
     * /data/flight/{hexId} path 404s for every id we have seen.
     */
    fun flightUrl(): String =
        if (number.isBlank()) "https://www.flightradar24.com/data/airports/ruh"
        else "https://www.flightradar24.com/data/flights/$number"

    /** One schedule entry = one flight occurrence (same number repeats on other days). */
    fun dedupKey(): String = "$number|$scheduledEpoch|$realEpoch"
}

data class FetchResult(
    val flights: List<LiveFlight>,
    val pagesRead: Int,
    val error: String?,
    val via: String = ""
)

object Fr24Api {

    const val TAG = "Fr24Live"

    /** Set once so the fetchers can report their remaining allowance. */
    @Volatile
    var quotaContext: android.content.Context? = null
    private const val AIRPORT = "RUH"
    private const val FIRECRAWL = "https://api.firecrawl.dev/v1/scrape"
    private const val JINA = "https://r.jina.ai/"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val URL_TPL =
        "https://api.flightradar24.com/common/v1/airport.json" +
            "?code=%s&plugin[]=schedule" +
            "&plugin-setting[schedule][mode]=%s" +
            "&plugin-setting[schedule][timestamp]=%d&page=%d"

    private const val DAY_SECS = 86_400L

    /**
     * The API `timestamp` is the START of the schedule window, not "now".
     * Passing the current time yields zero already-departed flights, so we must
     * use local midnight to cover the whole day.
     */
    fun todayMidnightEpoch(): Long =
        Clock.today().atStartOfDay(Clock.zone).toEpochSecond()

    /**
     * One schedule entry is limited to 100 rows and the window spans three
     * calendar days, so page 1 alone misses both the tail of today and most of
     * the day's real movements. Walk pages in scheduled order and stop as soon
     * as a page reaches the end of the local day (later pages are tomorrow).
     */
    fun fetch(
        mode: String,
        midnightSec: Long,
        onProgress: (stage: String, pct: Int, count: Int) -> Unit = { _, _, _ -> }
    ): FetchResult {
        val dayEnd = midnightSec + DAY_SECS
        val merged = LinkedHashMap<String, LiveFlight>()
        var via = ""
        var pagesRead = 0
        var err: String? = null
        var page = 1

        while (true) {
            val url = String.format(URL_TPL, AIRPORT, mode, midnightSec, page)
            val bust = url + "&cb=" + System.nanoTime()

            var body = get(url, mode)
            var source = if (body != null) "direct" else ""
            if (body == null) {
                // Jina is the workhorse; a DNS blip or a dropped connection
                // should not cost us the whole refresh.
                body = jinaWithRetry(bust, mode)
                if (body != null) source = "jina"
            }
            if (body == null) {
                // Firecrawl's keyless tier is rate-limited, so it is a last
                // resort with a short retry instead of a long stall.
                body = firecrawl(url, mode)
                if (body != null) source = "firecrawl"
            }
            if (body == null) {
                if (pagesRead == 0) err = "fetch failed ($mode)"
                break
            }
            if (via.isEmpty()) via = source

            val totalPages: Int
            val lastSched: Long
            try {
                val meta = scheduleMeta(body, mode)
                totalPages = meta.first
                lastSched = meta.second
            } catch (e: Exception) {
                if (pagesRead == 0) err = "parse ${e.message}"
                break
            }

            try {
                for (f in parse(body, mode)) merged[f.dedupKey()] = f
            } catch (e: Exception) {
                if (pagesRead == 0) err = "parse ${e.message}"
                break
            }
            pagesRead++
            // only today's movements count, same rule as todayOnly()
            val todayCount = merged.values.count {
                it.realEpoch >= midnightSec && it.realEpoch < dayEnd
            }
            onProgress(
                "$mode page $page/$totalPages",
                (page * 100 / totalPages).coerceIn(1, 100),
                todayCount
            )

            if (lastSched <= 0L || lastSched >= dayEnd || page >= totalPages) break
            page++
        }

        // Zero rows is not a failure: right after midnight no flight has
        // actually departed yet, and the pages were read fine.
        if (merged.isEmpty() && err == null && pagesRead == 0) err = "fetch failed ($mode)"
        android.util.Log.i(TAG, "$mode pages=$pagesRead rows=${merged.size} via=$via err=$err")
        return FetchResult(merged.values.toList(), pagesRead, err, via)
    }

    fun fetchBoth(
        midnightSec: Long,
        onProgress: (stage: String, pct: Int, dep: Int, arr: Int) -> Unit = { _, _, _, _ -> }
    ): Pair<FetchResult, FetchResult> {
        var depCount = 0
        var arrCount = 0
        onProgress("starting", 0, 0, 0)
        val dep = fetch("departures", midnightSec) { stage, pct, count ->
            onProgress(stage, pct / 2, count, arrCount)
        }
        depCount = dep.flights.size
        onProgress("departures done", 50, depCount, arrCount)
        val arr = fetch("arrivals", midnightSec) { stage, pct, count ->
            onProgress(stage, 50 + pct / 2, depCount, count)
        }
        arrCount = arr.flights.size
        onProgress("done", 100, depCount, arrCount)
        return dep to arr
    }

    /** page count + scheduled time of the page's last row (pages sort ascending). */
    private fun scheduleMeta(body: String, mode: String): Pair<Int, Long> {
        val sch = JSONObject(body)
            .getJSONObject("result")
            .getJSONObject("response")
            .getJSONObject("airport")
            .getJSONObject("pluginData")
            .getJSONObject("schedule")
            .getJSONObject(mode)

        val total = sch.optJSONObject("page")?.optInt("total", 1) ?: 1
        val arr = sch.optJSONArray("data")
        val key = if (mode == "departures") "departure" else "arrival"
        val last = if (arr == null || arr.length() == 0) 0L
        else {
            val t = arr.getJSONObject(arr.length() - 1)
                .getJSONObject("flight")
                .getJSONObject("time")
                .getJSONObject("scheduled")
            if (t.isNull(key)) 0L else t.optLong(key)
        }
        return total to last
    }

    /** A flight belongs to a day when its ACTUAL time falls inside that local day. */
    fun isToday(epochSec: Long): Boolean = Clock.isToday(epochSec)

    fun todayOnly(flights: List<LiveFlight>): List<LiveFlight> =
        flights.filter { Clock.isToday(it.realEpoch) }

    private fun get(url: String, mode: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestMethod("GET")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty(
            "Referer",
            "https://www.flightradar24.com/data/airports/ruh/$mode"
        )
        val code = conn.responseCode
        if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else {
            android.util.Log.i(TAG, "direct $mode -> HTTP $code, falling back to proxy")
            null
        }
    } catch (e: Exception) {
        android.util.Log.i(TAG, "direct $mode error ${e.message}, falling back to proxy")
        null
    }

    /**
     * Jina (r.jina.ai) proxies the raw FR24 JSON. The keyless Firecrawl tier
     * is hard rate-limited, so Jina is tried first; Jina prefixes the body
     * with "Title: ... Markdown Content:", which is stripped here.
     */
    private fun jina(url: String, mode: String): String? {
        return try {
            val conn = URL(JINA + url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.requestMethod = "GET"
            // Jina answers 403 to browser user-agents, so use our own name.
            conn.setRequestProperty("User-Agent", "ruh-flight-live/2.0")
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            val limitRaw = ServiceQuota.header(conn, "x-ratelimit-limit")
            val remRaw = ServiceQuota.header(conn, "x-ratelimit-remaining")
            val tokRaw = ServiceQuota.header(conn, "x-usage-tokens")
            if (code !in 200..299) {
                android.util.Log.w(TAG, "jina $mode -> HTTP $code")
                recordQuota("jina", limitRaw, remRaw, tokRaw, code, "")
                return null
            }
            recordQuota("jina", limitRaw, remRaw, tokRaw, code, "")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val body = jinaContent(raw)
            if (body.isBlank()) {
                android.util.Log.w(TAG, "jina $mode empty body")
                return null
            }
            android.util.Log.i(TAG, "jina $mode ok (${body.length} bytes)")
            body
        } catch (e: Exception) {
            android.util.Log.w(TAG, "jina $mode failed: ${e.message}")
            null
        }
    }

    /** Jina answers either plain text or a JSON envelope; both hold the body. */
    private fun jinaContent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            try {
                val data = JSONObject(trimmed).optJSONObject("data")
                val content = data?.optString("content").orEmpty()
                if (content.isNotBlank()) {
                    val brace = content.indexOf('{')
                    return if (brace >= 0) content.substring(brace).trim() else content.trim()
                }
            } catch (e: Exception) {
                // fall through to the plain-text handling
            }
        }
        val marker = "Markdown Content:"
        val idx = trimmed.indexOf(marker)
        if (idx >= 0) return trimmed.substring(idx + marker.length).trim()
        val brace = trimmed.indexOf('{')
        return if (brace >= 0) trimmed.substring(brace).trim() else trimmed
    }

    /** Store what the service just told us about its allowance. */
    private fun recordQuota(
        key: String, limitRaw: String?, remRaw: String?, tokRaw: String?,
        status: Int, note: String
    ) {
        try {
            val ctx = quotaContext ?: return
            val name = if (key == "jina") ServiceQuota.JINA else ServiceQuota.FIRECRAWL
            ServiceQuota.record(
                ctx, name,
                ServiceQuota.parseLimit(limitRaw),
                remRaw?.trim(),
                ServiceQuota.tokens(tokRaw),
                status,
                note
            )
        } catch (e: Exception) {
            // quota tracking must never break a fetch
        }
    }

    /** Jina retry ladder — a transient failure should not end the refresh. */
    private fun jinaWithRetry(url: String, mode: String): String? {
        val waits = longArrayOf(0L, 6_000L, 18_000L)
        for (i in waits.indices) {
            if (waits[i] > 0L) {
                android.util.Log.i(TAG, "jina $mode retry ${i + 1} in ${waits[i]}ms")
                try {
                    Thread.sleep(waits[i])
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            val body = jina(url, mode)
            if (body != null) return body
        }
        return null
    }

    private fun firecrawl(url: String, mode: String): String? {
        // Firecrawl rate-limits bursts (HTTP 429). Wait it out instead of
        // dropping the refresh, because the app polls every minute.
        val backoff = longArrayOf(0L, 10_000L)
        for (attempt in backoff.indices) {
            if (backoff[attempt] > 0L) {
                android.util.Log.i(TAG, "firecrawl $mode retry ${attempt + 1} in ${backoff[attempt]}ms")
                try {
                    Thread.sleep(backoff[attempt])
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            val r = firecrawlOnce(url, mode)
            if (r.first != null) return r.first
            val code = r.second
            if (code != 429 && code !in 500..599) return null
        }
        return null
    }

    /** @return (html, http code) */
    private fun firecrawlOnce(url: String, mode: String): Pair<String?, Int> {
        return try {
            // Firecrawl caches by URL and this schedule URL is byte-identical on
            // every refresh, which froze the board at whatever it first saw
            // (counts stopped moving while real flights kept departing).
            // Its own cacheBust flag returns HTTP 400, but a unique query
            // param on the target URL forces a fresh fetch.
            val bust = url + (if (url.contains('?')) "&" else "?") + "cb=" +
                System.nanoTime()

            val payload = JSONObject()
                .put("url", bust)
                .put("formats", org.json.JSONArray().put("html"))
                .toString()

            val conn = URL(FIRECRAWL).openConnection() as HttpURLConnection
            conn.connectTimeout = 45_000
            conn.readTimeout = 45_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            quotaContext?.let {
                val key = ServiceQuota.firecrawlKey(it)
                if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            }

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val limitRaw = ServiceQuota.header(
                conn, "x-firecrawl-ratelimit-limit", "x-ratelimit-limit"
            )
            val remRaw = ServiceQuota.header(
                conn, "x-firecrawl-ratelimit-remaining", "x-ratelimit-remaining"
            )
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
                android.util.Log.w(TAG, "firecrawl $mode -> HTTP $code")
                recordQuota(
                    "firecrawl", limitRaw, remRaw, null, code,
                    ServiceQuota.parseNote(errBody)
                )
                return null to code
            }
            recordQuota("firecrawl", limitRaw, remRaw, null, code, "")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            if (!root.optBoolean("success", false)) {
                android.util.Log.w(TAG, "firecrawl $mode success=false")
                return null to code
            }
            val html = root.getJSONObject("data").optString("html")
            val body = unwrap(html)
            android.util.Log.i(TAG, "firecrawl $mode ok (${body.length} bytes)")
            body to code
        } catch (e: Exception) {
            android.util.Log.w(TAG, "firecrawl $mode failed: ${e.message}")
            null to -1
        }
    }

    /** Firecrawl wraps the JSON API body as <html><body>…</body></html>. */
    private fun unwrap(html: String): String {
        var s = html.trim()
        if (s.startsWith("<html>")) s = s.substring(6)
        if (s.startsWith("<body>")) s = s.substring(6)
        val end = s.lastIndexOf("</body>")
        if (end >= 0) s = s.substring(0, end)
        val endHtml = s.lastIndexOf("</html>")
        if (endHtml >= 0) s = s.substring(0, endHtml)
        return s.trim()
    }

    fun parse(body: String, mode: String): List<LiveFlight> {
        val want = if (mode == "departures") "departed" else "landed"
        val realKey = if (mode == "departures") "departure" else "arrival"

        val root = JSONObject(body)
        val data = root.getJSONObject("result")
            .getJSONObject("response")
            .getJSONObject("airport")
            .getJSONObject("pluginData")
            .getJSONObject("schedule")
            .getJSONObject(mode)
            .getJSONArray("data")

        val out = ArrayList<LiveFlight>(24)
        val seen = HashSet<String>()

        for (i in 0 until data.length()) {
            val flight = data.getJSONObject(i).getJSONObject("flight")

            val status = flight.optJSONObject("status") ?: continue
            val generic = status.optJSONObject("generic") ?: continue
            val gstatus = generic.optJSONObject("status") ?: continue
            if (gstatus.optString("text") != want) continue

            val time = flight.optJSONObject("time") ?: continue
            val real = time.optJSONObject("real") ?: continue
            val realEpoch = if (real.isNull(realKey)) 0L else real.optLong(realKey)
            if (realEpoch <= 0L) continue

            val ident = flight.optJSONObject("identification") ?: continue
            val number = ident.optJSONObject("number")
                ?.optString("default")?.uppercase()?.trim().orEmpty()
            if (number.isEmpty()) continue
            if (!seen.add(number)) continue

            val id = if (ident.isNull("id")) null
            else ident.optString("id").takeIf { it.isNotBlank() }

            val eventTime = generic.optJSONObject("eventTime")
            val evtUtc = eventTime?.let { if (it.isNull("utc")) 0L else it.optLong("utc") } ?: 0L
            val actual = if (evtUtc > 0) evtUtc else realEpoch

            val scheduled = time.optJSONObject("scheduled")?.let {
                if (it.isNull(realKey)) 0L else it.optLong(realKey)
            } ?: 0L

            val airport = flight.optJSONObject("airport")
            // counterpart airport = where the flight is going / came from
            val other = airport?.let {
                if (mode == "departures") it.optJSONObject("destination")
                else it.optJSONObject("origin")
            }
            // terminal + gate are always at RUH, i.e. the origin for departures
            // and the destination for arrivals
            val ruhSide = airport?.let {
                if (mode == "departures") it.optJSONObject("origin")
                else it.optJSONObject("destination")
            }
            val ruhInfo = ruhSide?.optJSONObject("info")

            out.add(
                LiveFlight(
                    id = id,
                    number = number,
                    direction = if (mode == "departures") "dep" else "arr",
                    statusWord = want.uppercase(),
                    statusColor = gstatus.optString("color").ifBlank { "gray" },
                    realEpoch = actual,
                    scheduledEpoch = scheduled,
                    otherIata = other?.optJSONObject("code")?.optString("iata").orEmpty(),
                    otherName = other?.optString("name").orEmpty(),
                    otherCity = other?.optJSONObject("position")
                        ?.optJSONObject("region")?.optString("city").orEmpty(),
                    airline = flight.optJSONObject("airline")?.optString("name").orEmpty()
                        .ifBlank { flight.optJSONObject("owner")?.optString("name").orEmpty() },
                    terminal = ruhInfo?.optString("terminal").orEmpty(),
                    gate = ruhInfo?.optString("gate").orEmpty(),
                    aircraft = flight.optJSONObject("aircraft")
                        ?.optJSONObject("model")?.optString("code").orEmpty(),
                    codeshare = flight.optJSONObject("identification")
                        ?.optJSONArray("codeshare")
                        ?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                arr.optString(it).takeIf { s -> s.isNotBlank() }
                            }
                        } ?: emptyList(),
                    live = status.optBoolean("live")
                )
            )
        }
        return out.sortedByDescending { it.realEpoch }
    }
}
