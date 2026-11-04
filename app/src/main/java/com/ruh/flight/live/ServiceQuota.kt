package com.ruh.flight.live

import android.content.Context
import java.util.Locale

/** What one free proxy service has left. */
data class QuotaInfo(
    val name: String,
    val limit: String = "—",          // e.g. "20 req / 60 s"
    val remaining: String = "—",      // e.g. "19"
    val tokens: String = "—",         // jina tokens used
    val requests: Int = 0,            // requests this install
    val lastStatus: Int = 0,
    val note: String = "",
    val at: Long = 0L
) {
    /** Plain-English label for the last HTTP status. */
    fun statusText(): String = when (lastStatus) {
        0 -> "not contacted yet"
        200 -> "HTTP 200  OK — service answered"
        401 -> "HTTP 401  unauthorized (bad key)"
        403 -> "HTTP 403  forbidden (blocked)"
        404 -> "HTTP 404  not found"
        429 -> "HTTP 429  too many requests / out of credits"
        in 500..599 -> "HTTP $lastStatus  server error"
        else -> "HTTP $lastStatus"
    }

    /** One line summary for the settings screen, with every field explained. */
    fun line(): String = buildString {
        append(name).append('\n')
        append("  used for      ").append(ServiceQuota.purpose(name)).append('\n')
        append("  rate limit    ").append(limit)
        append("   ← how many requests the service allows per window\n")
        append("  remaining     ").append(remaining)
        append("   ← requests still free in the current window\n")
        if (tokens != "—") {
            append("  tokens used   ").append(tokens)
            append("   ← size of the text the service fetched for us\n")
        }
        append("  my requests   ").append(requests.toString())
        append("   ← requests this install has sent\n")
        append("  last status   ").append(statusText()).append('\n')
        if (note.isNotBlank()) append("  note          ").append(note).append('\n')
    }
}

/**
 * Tracks the free-tier allowance of the proxies the app uses, so the settings
 * screen can show what is left before the data stops coming in.
 *
 * Jina reports `x-ratelimit-limit: 20, 20;w=60` and `x-ratelimit-remaining`.
 * Firecrawl's keyless tier answers 429 with `"reason":"credits"`; with a free
 * API key it reports its own quota headers.
 */
object ServiceQuota {

    const val JINA = "Jina (r.jina.ai)"
    const val FIRECRAWL = "Firecrawl"

    private const val PREFS = "ruh_quota"

    // ------------------------------------------------------------- recording

    fun record(
        context: Context,
        name: String,
        limit: String?,
        remaining: String?,
        tokens: String?,
        status: Int,
        note: String = ""
    ) {
        val p = prefs(context)
        val key = "name_" + name
        val prev = p.getInt("req_" + name, 0)
        p.edit()
            .putInt("req_" + name, prev + 1)
            .putString(key, name)
            .putString("limit_" + name, limit ?: p.getString("limit_" + name, "—"))
            .putString("rem_" + name, remaining ?: p.getString("rem_" + name, "—"))
            .putString("tok_" + name, tokens ?: p.getString("tok_" + name, "—"))
            .putInt("status_" + name, status)
            .putString("note_" + name, note)
            .putLong("at_" + name, System.currentTimeMillis())
            .apply()
    }

    fun get(context: Context, name: String): QuotaInfo {
        val p = prefs(context)
        return QuotaInfo(
            name = name,
            limit = p.getString("limit_" + name, "—") ?: "—",
            remaining = p.getString("rem_" + name, "—") ?: "—",
            tokens = p.getString("tok_" + name, "—") ?: "—",
            requests = p.getInt("req_" + name, 0),
            lastStatus = p.getInt("status_" + name, 0),
            note = p.getString("note_" + name, "") ?: "",
            at = p.getLong("at_" + name, 0L)
        )
    }

    fun all(context: Context): List<QuotaInfo> =
        listOf(get(context, JINA), get(context, FIRECRAWL))

    /** What the service is for, in one sentence. */
    fun purpose(name: String): String = when (name) {
        JINA -> "main proxy — fetches FR24's live board for the app"
        FIRECRAWL -> "backup proxy — used only when Jina fails"
        else -> ""
    }

    /** The legend printed above the numbers. */
    const val LEGEND =
        "How to read this\n" +
        "  rate limit   how many requests the free service allows per window\n" +
        "  remaining    requests still free in the current window\n" +
        "  tokens used  size of the text the service fetched for us\n" +
        "  my requests  requests this install has sent so far\n" +
        "  last status  what the service answered on the last try\n"

    // -------------------------------------------------------------- parsing

    /** "20, 20;w=60" -> "20 req / 60 s" */
    fun parseLimit(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val window = Regex("w=(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val first = raw.substringBefore(',').trim().toIntOrNull()
            ?: Regex("\\d+").find(raw)?.value?.toIntOrNull()
            ?: return raw.trim()
        return if (window != null) "$first req / ${window}s" else "$first"
    }

    /** Firecrawl's 429 body explains itself in JSON. */
    fun parseNote(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val o = org.json.JSONObject(body)
            val reason = o.optString("reason")
            val err = o.optString("error")
            when {
                reason.equals("credits", true) && err.isNotBlank() ->
                    "credits exhausted (keyless tier)"
                err.isNotBlank() -> err.lines().first().take(90)
                else -> ""
            }
        } catch (e: Exception) {
            body.replace(Regex("\\s+"), " ").take(90)
        }
    }

    /** Any quota-ish header, whatever the service calls it. */
    fun header(conn: java.net.HttpURLConnection, vararg names: String): String? {
        for (n in names) {
            val v = conn.getHeaderField(n)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun tokens(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val n = raw.toLongOrNull() ?: return null
        return when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
            else -> n.toString()
        }
    }

    // ------------------------------------------------------ firecrawl key

    fun firecrawlKey(context: Context): String =
        prefs(context).getString("fc_key", "") ?: ""

    fun setFirecrawlKey(context: Context, key: String) {
        prefs(context).edit().putString("fc_key", key.trim()).apply()
    }

    // -------------------------------------------------------------- probes

    /** Cheap request that only exists to read the remaining allowance. */
    fun probeJina(context: Context) {
        Thread {
            try {
                val conn = java.net.URL("https://r.jina.ai/https://example.com")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "ruh-flight-live/2.0")
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                record(
                    context, JINA,
                    parseLimit(header(conn, "x-ratelimit-limit")),
                    header(conn, "x-ratelimit-remaining")?.trim(),
                    tokens(header(conn, "x-usage-tokens")),
                    code,
                    if (code == 429) "rate limited — wait a minute" else ""
                )
                conn.disconnect()
            } catch (e: Exception) {
                record(context, JINA, null, null, null, 0, e.message ?: "probe failed")
            }
        }.start()
    }

    /** Firecrawl only reports a quota when it is given an API key. */
    fun probeFirecrawl(context: Context) {
        val key = firecrawlKey(context)
        if (key.isBlank()) {
            record(
                context, FIRECRAWL, null, null, null, 0,
                "no API key — keyless tier has no credit left"
            )
            return
        }
        Thread {
            try {
                val payload = org.json.JSONObject()
                    .put("url", "https://example.com")
                    .put("formats", org.json.JSONArray().put("html"))
                    .toString()
                val conn = java.net.URL("https://api.firecrawl.dev/v1/scrape")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 45_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val errBody = if (code in 200..299) "" else
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                record(
                    context, FIRECRAWL,
                    parseLimit(header(conn, "x-firecrawl-ratelimit-limit", "x-ratelimit-limit")),
                    header(conn, "x-firecrawl-ratelimit-remaining", "x-ratelimit-remaining")?.trim(),
                    null, code, parseNote(errBody)
                )
                conn.disconnect()
            } catch (e: Exception) {
                record(context, FIRECRAWL, null, null, null, 0, e.message ?: "probe failed")
            }
        }.start()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
