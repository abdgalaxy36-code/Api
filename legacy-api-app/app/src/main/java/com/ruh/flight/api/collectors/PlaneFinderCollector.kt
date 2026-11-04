package com.ruh.flight.api.collectors

import android.util.Log
import com.ruh.flight.api.DayFetchResult
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.processing.Deduplicator
import com.ruh.flight.api.processing.TimeUtil
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plane Finder board via hidden API (direct HTTPS, not Jina):
 *   GET /api/airport/RUH/{arrivals|departures}/{unixTs}/offset/{0..-4}
 *
 * PF-only counting rules (latest user rules, 2026-11-03):
 *  - Departures: PAST + status Departed + RED dot (dot late). GREEN ignored.
 *  - Arrivals:   PAST + status Landed + RED (late) or GREEN (ontime) dot.
 *  - Cancelled (dot cancelled): never counted; reported for purge.
 *  - Future / Arriving / Departing: ignored.
 *  - Landed arrivals: final landed airport must be RUH (skip diversions).
 *
 * Alternative summary (Jina alternatives checked per user request):
 *  - Firecrawl: No API key needed, JS-rendered, HTML endpoint. Good replacement.
 *  - Crawl4AI Cloud: 1000 free credits/mo, no key. Open-source + cloud.
 *  - ScrapingBee: Anti-bot, JS rendering, free credits.
 *  - Browserless: Managed browser, API token.
 *  - Zyte: $5 free credit, enterprise-grade.
 *  - Microlink: Metadata/screenshot API, free tier exists.
 *  - Exa AI: Search + content, API key required.
 *  - Tavily: Free 1000 credits/mo, search/extract API.
 *  - Diffbot: CV-based extraction, enterprise.
 *  - Apify: Actor marketplace, free tier ~$5/mo.
 *  - ScrapingAnt: 10k free credits/mo, proxy+browser.
 *  - ScraperAPI: Free tier, acquired service.
 *  - WebScrapingAPI: 5k free requests, paid from $19/mo.
 *  - ZenRows: API key, fetch/extract/crawl.
 *  - Trafilatura: OSS text extractor, no JS, Python.
 *  - Cheerio: OSS JS HTML parser, no fetch.
 *  - Selenium: OSS browser automation, local driver.
 *  - Playwright: OSS browser automation, local browsers.
 *
 * Best alternatives to Jina for our case:
 *  1. Firecrawl (no auth, similar to Jina but no key needed)
 *  2. Crawl4AI Cloud (1000 free credits, no key required)
 *  3. ScrapingBee (strong anti-bot, free credits)
 */
class PlaneFinderCollector : BaseCollector() {
    override val id = "planefinder"
    override val domain = "planefinder.net"
    override val label = "Plane Finder"

    private val airlineNames = mapOf(
        "SV" to "Saudia", "F3" to "flyadeal", "XY" to "flynas",
        "EK" to "Emirates", "QR" to "Qatar Airways", "EY" to "Etihad",
        "GF" to "Gulf Air", "TK" to "Turkish Airlines", "MS" to "EgyptAir",
        "FZ" to "Flydubai", "J9" to "Jazeera Airways", "W6" to "Wizz Air",
        "AF" to "Air France", "LH" to "Lufthansa", "BA" to "British Airways",
        "KU" to "Kuwait Airways", "RJ" to "Royal Jordanian",
        "ME" to "Middle East Airlines", "WY" to "Oman Air",
        "PK" to "Pakistan International Airlines", "UL" to "SriLankan Airlines",
        "BG" to "Biman Bangladesh", "SQ" to "Singapore Airlines",
        "TG" to "Thai Airways", "MH" to "Malaysia Airlines",
        "GA" to "Garuda Indonesia", "EJ" to "Air Arabia",
        "HJ" to "Air Arabia", "3L" to "Air Arabia Abu Dhabi",
        "G9" to "Air Arabia", "X3" to "TUI fly",
        "OS" to "Austrian Airlines", "LX" to "Swiss",
        "SN" to "Brussels Airlines", "AY" to "Finnair",
        "SK" to "SAS", "IB" to "Iberia", "AZ" to "ITA Airways",
        "KL" to "KLM", "SU" to "Aeroflot", "HU" to "Hainan Airlines",
        "CZ" to "China Southern", "MU" to "China Eastern",
        "CA" to "Air China", "CX" to "Cathay Pacific",
        "JL" to "Japan Airlines", "NH" to "All Nippon Airways",
        "KE" to "Korean Air", "OZ" to "Asiana Airlines",
        "BR" to "EVA Air", "CI" to "China Airlines",
        "QF" to "Qantas", "NZ" to "Air New Zealand",
        "AC" to "Air Canada", "UA" to "United Airlines",
        "DL" to "Delta Air Lines", "AA" to "American Airlines",
        "B6" to "JetBlue", "WN" to "Southwest Airlines",
        "FR" to "Ryanair", "U2" to "easyJet",
        "LS" to "Jet2.com", "BY" to "TUI Airways",
        "IX" to "Air India Express", "AI" to "Air India",
        "SG" to "SpiceJet", "6E" to "IndiGo",
        "EK" to "Emirates", "F3" to "flyadeal"
    )

    data class PfRow(
        val flightNumber: String,
        val scheduled: String,
        val actual: String?,
        val status: String,
        val dot: String,
        val dataDate: String,
        val airport: String,
        val city: String,
        val airlineName: String,
        val airlineCode: String,
        val cancelledRow: Boolean
    )

    override fun fetchDay(date: String, apiKey: String): DayFetchResult {
        val ts = System.currentTimeMillis() / 1000
        val offsets = intArrayOf(0, -1, -2, -3, -4)
        val allRows = mutableListOf<Pair<String, PfRow>>() // direction -> row
        val cancelled = mutableSetOf<String>()
        var pages = 0
        var errors = 0
        var lastError: String? = null

        for (dir in listOf("arrivals", "departures")) {
            for (off in offsets) {
                val url = "https://planefinder.net/api/airport/RUH/$dir/$ts/offset/$off"
                val html = getPf(url, dir)
                if (html == null) {
                    errors++
                    lastError = "PF $dir offset $off failed"
                    Log.w(TAG, "fetch failed $url")
                    sleepMs(8_000)
                    continue
                }
                pages++
                val rows = parseBoardHtml(html, dir)
                Log.i(TAG, "PF $dir offset=$off rows=${rows.size}")
                for (r in rows) allRows.add(dir to r)
                sleepMs(3_000)
            }
        }

        if (pages == 0) {
            return DayFetchResult(emptyList(), 0, 0, id, lastError ?: "Plane Finder API fetch failed")
        }

        val flights = mutableListOf<FlightRecord>()
        val seenKeys = mutableSetOf<String>()
        var skippedFuture = 0
        var skippedGreenDep = 0
        var skippedDivert = 0
        var countedDep = 0
        var countedArr = 0
        val divertCache = mutableMapOf<String, Boolean>()

        for ((dir, row) in allRows) {
            val num = row.flightNumber.uppercase()
            if (row.cancelledRow || row.dot == "cancelled" ||
                row.status.equals("Cancelled", true) || row.status.equals("Canceled", true)
            ) {
                cancelled.add(num)
                continue
            }

            val isArr = dir == "arrivals"
            val statusLower = row.status.lowercase()
            val hasLanded = statusLower.startsWith("landed")
            val hasDeparted = statusLower.startsWith("departed")

            if (isArr && !hasLanded) continue
            if (!isArr && !hasDeparted) continue

            val dot = row.dot
            if (!isArr && dot != "late") {
                if (dot == "ontime" || dot == "early") skippedGreenDep++
                continue
            }
            if (isArr && dot != "late" && dot != "ontime") continue

            val timeStr = row.actual ?: continue
            val eventDate = parsePfDate(row.dataDate) ?: continue
            val utcIso = TimeUtil.resolveEventUtcIso(eventDate.toString(), timeStr) ?: continue
            if (!TimeUtil.isPastOrNow(utcIso)) {
                skippedFuture++
                continue
            }

            if (isArr) {
                val cacheKey = "$num|$eventDate"
                val destOk = divertCache.getOrPut(cacheKey) {
                    verifyArrivalLandedAtRuh(num, eventDate) ?: true
                }
                if (!destOk) {
                    skippedDivert++
                    Log.i(TAG, "skip diversion $num To!=RUH on $eventDate")
                    continue
                }
            }

            val key = "$dir|$num|${eventDate}|$timeStr"
            if (!seenKeys.add(key)) continue

            val airlineCode = row.airlineCode.ifBlank { num.take(2) }
            val airlineName = row.airlineName.ifBlank { airlineNames[airlineCode] ?: airlineCode }
            val eventLabel = if (isArr) "Actual landing" else "Actual takeoff"
            val evidence = "PF ${row.status} $timeStr dot=$dot"

            flights.add(
                FlightRecord(
                    source = id,
                    flightNumber = num,
                    direction = if (isArr) "arrival" else "departure",
                    origin = if (isArr) row.airport.ifBlank { "RUH" } else "RUH",
                    destination = if (isArr) "RUH" else row.airport.ifBlank { "RUH" },
                    actualTimeUtc = utcIso,
                    ruhLocalDate = TimeUtil.utcIsoToRiyadhDate(utcIso),
                    actualEventLabel = eventLabel,
                    evidence = evidence,
                    airline = airlineName,
                    otherCity = row.city
                )
            )
            if (isArr) countedArr++ else countedDep++
        }

        val countable = flights.filter { Deduplicator.isCountable(it) }
        Log.i(
            TAG,
            "PF done pages=$pages countable=${countable.size} dep=$countedDep arr=$countedArr " +
                "cancelled=${cancelled.size} greenDepSkip=$skippedGreenDep futureSkip=$skippedFuture " +
                "divertSkip=$skippedDivert errors=$errors"
        )

        val err = if (countable.isEmpty() && errors > 0) lastError else null
        return DayFetchResult(
            flights = countable,
            pagesRead = pages,
            recordsFound = countable.size,
            source = id,
            error = err,
            cancelledFlightNumbers = cancelled
        )
    }

    private fun verifyArrivalLandedAtRuh(flightNumber: String, eventDate: LocalDate): Boolean? {
        sleepMs(1_500)
        val url = "https://planefinder.net/data/flight/$flightNumber"
        val html = getPf(url, "flight") ?: return null
        val to = historyToForDate(html, eventDate) ?: return null
        return to.equals("RUH", ignoreCase = true)
    }

    private fun historyToForDate(html: String, eventDate: LocalDate): String? {
        val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
        val tableRe = Regex("<table\\b[^>]*>.*?</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (tm in tableRe.findAll(html)) {
            val rowRe = Regex("<tr\\b[^>]*>.*?</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (rm in rowRe.findAll(tm.value)) {
                val cellRe = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val cells = cellRe.findAll(rm.value).map { stripTags(it.groupValues[1]) }.toList()
                if (cells.size < 4) continue
                val dateCell = cells[0].trim()
                val rowDate = try { LocalDate.parse(dateCell, dateFmt) } catch (_: Exception) { continue }
                if (rowDate != eventDate) continue
                val toCell = cells.getOrNull(3) ?: continue
                val iata = Regex("\\b([A-Z]{3})\\b").findAll(toCell).map { it.groupValues[1] }.lastOrNull()
                if (iata != null) return iata
                if (cells.any { it.trim().equals("RUH", ignoreCase = true) }) return "RUH"
                return toCell.trim().ifBlank { null }
            }
        }
        return null
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parsePfDate(dataDate: String): LocalDate? {
        return try {
            LocalDate.parse(dataDate.trim(), DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))
        } catch (_: Exception) {
            try {
                LocalDate.parse(dataDate.trim(), DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parseBoardHtml(html: String, direction: String): List<PfRow> {
        val out = mutableListOf<PfRow>()
        val rowRe = Regex("<tr\\b[^>]*>.*?</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (rm in rowRe.findAll(html)) {
            val row = rm.value
            val flight = Regex("href=\"/data/flight/([A-Za-z0-9]+)\"").find(row)?.groupValues?.get(1)
                ?: continue
            val dataDate = Regex("data-date=\"([^\"]+)\"").find(row)?.groupValues?.get(1) ?: ""
            val cancelledRow = Regex("class=\"[^\"]*\\bcancelled\\b").containsMatchIn(row)

            val scheduled = Regex(
                "<td>\\s*<div class=\"d-inline-flex align-items-center\">\\s*([0-9]{1,2}:[0-9]{2})",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(row)?.groupValues?.get(1) ?: continue

            val statusM = Regex(
                "<span class=\"dot\\s+([a-z]+)\"[^>]*>\\s*</span>\\s*([A-Za-z]+)\\s*(?:<div[^>]*>\\s*([0-9]{1,2}:[0-9]{2})\\s*</div>)?",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(row)
            val dot = statusM?.groupValues?.get(1)?.lowercase() ?: ""
            val statusWord = statusM?.groupValues?.get(2) ?: ""
            val actual = statusM?.groupValues?.get(3)?.takeIf { it.isNotBlank() }

            val airport = Regex("href=\"/data/airport/([A-Z]{3})\"").find(row)?.groupValues?.get(1) ?: ""
            val city = Regex(">([A-Za-z][A-Za-z\\s'&.\\-]{1,30})\\s*<a href=\"/data/airport/")
                .find(row)?.groupValues?.get(1)?.trim() ?: ""

            val airlineCode = Regex("href=\"/data/airline/([A-Za-z0-9]{2,3})\"").find(row)?.groupValues?.get(1)?.uppercase() ?: ""
            val airlineName = Regex("class=\"text-primary\">([^<]+)</a>").find(row)?.groupValues?.get(1)?.trim() ?: ""

            out.add(
                PfRow(
                    flightNumber = flight.uppercase(),
                    scheduled = scheduled,
                    actual = actual,
                    status = statusWord,
                    dot = dot,
                    dataDate = dataDate,
                    airport = airport,
                    city = city,
                    airlineName = airlineName,
                    airlineCode = airlineCode,
                    cancelledRow = cancelledRow
                )
            )
        }
        return out
    }

    private fun getPf(url: String, kind: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 25_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.setRequestMethod("GET")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", if (kind == "flight") "text/html,application/xhtml+xml" else "*/*")
            conn.setRequestProperty("Referer", "https://planefinder.net/data/airport/RUH/${if (kind == "flight") "arrivals" else kind}")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "HTTP $code for $url")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPf error $url: ${e.message}")
            null
        }
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun test(apiKey: String): String {
        val ts = System.currentTimeMillis() / 1000
        var pages = 0
        var landed = 0
        var departedRed = 0
        var cancelled = 0
        for ((dir, want) in listOf("arrivals" to "Landed", "departures" to "Departed")) {
            val url = "https://planefinder.net/api/airport/RUH/$dir/$ts/offset/-1"
            val html = getPf(url, dir) ?: continue
            pages++
            val rows = parseBoardHtml(html, dir)
            for (r in rows) {
                if (r.cancelledRow || r.dot == "cancelled") cancelled++
                else if (dir == "arrivals" && r.status.equals("Landed", true) && r.dot in setOf("late", "ontime")) landed++
                else if (dir == "departures" && r.status.equals("Departed", true) && r.dot == "late") departedRed++
            }
            sleepMs(2_000)
        }
        return when {
            pages == 0 -> "Plane Finder FAIL: API fetch failed"
            landed + departedRed > 0 -> "Plane Finder OK — $landed Landed, $departedRed red Departed, $cancelled cancelled"
            else -> "Plane Finder PARTIAL — $pages pages, 0 countable (landed=$landed depRed=$departedRed canc=$cancelled)"
        }
    }

    companion object {
        private const val TAG = "PlaneFinder"
    }
}
