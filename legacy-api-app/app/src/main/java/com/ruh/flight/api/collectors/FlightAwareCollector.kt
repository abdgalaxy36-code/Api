package com.ruh.flight.api.collectors

import com.ruh.flight.api.DayFetchResult
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.processing.TimeUtil
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FlightAwareCollector : BaseCollector() {
    override val id = "flightaware"
    override val domain = "flightaware.com"
    override val label = "FlightAware"

    override fun fetchDay(date: String, apiKey: String): DayFetchResult {
        val urls = listOf(
            "https://flightaware.com/live/airport/OERK/departures",
            "https://flightaware.com/live/airport/OERK/arrivals"
        )

        var pages: List<String?> = emptyList()
        var error: String? = null

        // First try Jina (primary)
        if (apiKey.isNotBlank()) {
            pages = fetchPages(urls, apiKey)
            val ok = pages.filterNotNull()
            if (ok.isNotEmpty()) {
                return processPages(ok, date)
            } else {
                error = pages.firstOrNull()?.let { it } ?: "FlightAware blocked — no data via Jina"
                // Continue to fallback if Jina fails
            }
        }

        // Fallback: Try Firecrawl for FlightAware (no auth needed)
        try {
            val firecrawlPages = mutableListOf<String?>()
            for (url in urls) {
                val html = fetchWithFirecrawl(url) ?: continue
                firecrawlPages.add(html)
            }
            if (firecrawlPages.isNotEmpty()) {
                return processPages(firecrawlPages.filterNotNull(), date)
            }
        } catch (e: Exception) {
            error = error ?: "Firecrawl fallback failed: ${e.message}"
        }

        return DayFetchResult(emptyList(), 0, 0, id,
            error ?: "All FlightAware pages failed")
    }

    private fun processPages(pages: List<String>, date: String): DayFetchResult {
        val ok = pages.filterNotNull()
        if (ok.isEmpty()) {
            return DayFetchResult(emptyList(), 0, 0, id, "FlightAware blocked — no data via Jina")
        }

        val loginWall = ok.any {
            it.contains("login.flightaware.com") || it.contains("Sign in to FlightAware") ||
                it.contains("FlightAware Account") || it.contains("Just a moment") ||
                it.contains("Warning: Target URL returned error 403")
        }
        if (loginWall) {
            return DayFetchResult(emptyList(), ok.size, 0, id,
                "LOGIN WALL — Jina docs: no anti-bot bypass; needs your session cookies via X-Set-Cookie (free key cannot)")
        }

        val flights = mutableListOf<FlightRecord>()
        for ((idx, md) in ok.withIndex()) {
            val dir = if (idx == 0) "departure" else "arrival"
            flights.addAll(parseBoard(md, dir, date))
        }
        return DayFetchResult(flights, ok.size, flights.size, id)
    }

    private fun fetchWithFirecrawl(url: String): String? {
        return try {
            val conn = URL("https://api.firecrawl.dev/v1/scrape").openConnection() as HttpURLConnection
            conn.connectTimeout = 25_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.setRequestMethod("POST")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")

            val payload = """{
                "url": "$url",
                "formats": ["html"]
            }"""

            conn.doOutput = true
            conn.outputStream.write(payload.toByteArray(StandardCharsets.UTF_8))

            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseBoard(md: String, direction: String, date: String): List<FlightRecord> {
        val out = mutableListOf<FlightRecord>()
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val fm = Regex("^\\[([A-Z]{1,3}[0-9]{1,4}[A-Z]?)\\]").find(line)
            if (fm == null) { i++; continue }

            val num = fm.groupValues[1]
            var actual: String? = null
            var status = ""
            var otherIata = ""
            var otherCity = ""
            var airline = ""

            var k = i + 1
            while (k < lines.size && k < i + 15) {
                val lk = lines[k].trim()
                if (lk.startsWith("Departed") || lk.startsWith("Landed")) {
                    val parts = lk.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        actual = parts[1]
                        status = parts[0].lowercase()
                    }
                    break
                }
                if (airline.isEmpty()) {
                    val am = Regex("\\[(?!\\])([^\\]]+)\\]\\(https?://[^)]*flightaware[^)]*\\)").find(lk)
                    if (am != null) airline = am.groupValues[1].trim().substringBefore(" (").trim()
                }
                val iataM = Regex("\\b([A-Z]{3})\\b").find(lk)
                if (iataM != null && otherIata.isEmpty() && iataM.groupValues[1] != "RUH") {
                    otherIata = iataM.groupValues[1]
                    val cm = Regex("\\[From:\\s*([A-Za-z][A-Za-z\\s'&.\\-]*?)\\s+[A-Z]{3}").find(lk)
                        ?: Regex("\\[To:\\s*([A-Za-z][A-Za-z\\s'&.\\-]*?)\\s+[A-Z]{3}").find(lk)
                    if (cm != null) otherCity = cm.groupValues[1].trim()
                }
                k++
            }

            if (actual != null) {
                val utcIso = TimeUtil.resolveEventUtcIso(date, actual!!)
                if (utcIso != null && TimeUtil.isPastOrNow(utcIso)) {
                    val eventLabel = if (status == "departed") "Actual takeoff" else "Actual landing"
                    out.add(FlightRecord(
                        source = id,
                        flightNumber = num,
                        direction = direction,
                        origin = if (direction == "departure") "RUH" else otherIata.ifEmpty { "RUH" },
                        destination = if (direction == "arrival") "RUH" else otherIata.ifEmpty { "RUH" },
                        actualTimeUtc = utcIso,
                        ruhLocalDate = TimeUtil.utcIsoToRiyadhDate(utcIso),
                        actualEventLabel = eventLabel,
                        evidence = "FlightAware $status $actual",
                        airline = airline,
                        otherCity = otherCity
                    ))
                }
            }
            i = k + 1
        }
        return out
    }

    override fun test(apiKey: String): String {
        val r = lightFetch("https://flightaware.com/live/airport/OERK/departures", apiKey)
        if (r.error != null) return "FlightAware BLOCKED: ${r.error}"
        val md = r.body ?: return "FlightAware BLOCKED: empty page"
        val loginWall = md.contains("login.flightaware.com") ||
            md.contains("Sign in to FlightAware") ||
            md.contains("FlightAware Account") ||
            md.contains("Just a moment") ||
            md.contains("error 403")
        if (loginWall) {
            return "FlightAware LOGIN WALL — Jina does not bypass login/Cloudflare (needs X-Set-Cookie session; not a key issue)"
        }
        val n = parseBoard(md, "departure", TimeUtil.todayRiyadh()).size
        return "FlightAware OK — $n departure rows"
    }
}
