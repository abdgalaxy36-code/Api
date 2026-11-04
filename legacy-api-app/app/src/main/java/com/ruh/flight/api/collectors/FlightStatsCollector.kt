package com.ruh.flight.api.collectors

import com.ruh.flight.api.DayFetchResult
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.processing.TimeUtil
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FlightStatsCollector : BaseCollector() {
    override val id = "flightstats"
    override val domain = "flightstats.com"
    override val label = "FlightStats"

    override fun fetchDay(date: String, apiKey: String): DayFetchResult {
        val urls = listOf(
            "https://www.flightstats.com/v2/flight-tracker/departures/RUH",
            "https://www.flightstats.com/v2/flight-tracker/arrivals/RUH"
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
                error = pages.firstOrNull()?.let { it } ?: "FlightStats page fetch failed"
                // Continue to fallback if Jina fails
            }
        }

        // Fallback: Try Firecrawl for FlightStats (no auth needed)
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
            error ?: "All FlightStats pages failed")
    }

    private fun processPages(pages: List<String>, date: String): DayFetchResult {
        val ok = pages.filterNotNull()
        if (ok.isEmpty()) {
            return DayFetchResult(emptyList(), 0, 0, id, "FlightStats page fetch failed")
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
        val rowRe = Regex("\\[([A-Z]{2,3})\\s+(\\d{1,4})\\s*##\\s*(\\d{1,2}:\\d{2})\\s*##\\s*(\\d{1,2}:\\d{2})\\s*##\\s*([A-Z]{3})\\s+([^\\]]+)\\]")
        for (m in rowRe.findAll(md)) {
            val airline = m.groupValues[1]
            val num = m.groupValues[1] + m.groupValues[2]
            val depTime = m.groupValues[3]
            val arrTime = m.groupValues[4]
            val destIata = m.groupValues[5]
            val destCity = m.groupValues[6].trim()

            val timeStr = if (direction == "departure") depTime else arrTime
            // Scheduled only — never stored as Actual; resolve date for display only
            val utcIso = TimeUtil.resolveEventUtcIso(date, timeStr) ?: continue

            out.add(FlightRecord(
                source = id,
                flightNumber = num,
                direction = direction,
                origin = if (direction == "departure") "RUH" else destIata,
                destination = if (direction == "arrival") "RUH" else destIata,
                actualTimeUtc = utcIso,
                ruhLocalDate = TimeUtil.utcIsoToRiyadhDate(utcIso),
                actualEventLabel = if (direction == "departure") "Scheduled departure" else "Scheduled arrival",
                evidence = "FlightStats sched $timeStr $destIata $destCity"
            ))
        }
        return out
    }

    override fun test(apiKey: String): String {
        val r = lightFetch("https://www.flightstats.com/v2/flight-tracker/departures/RUH", apiKey)
        if (r.error != null) return "FlightStats FAIL: ${r.error}"
        val md = r.body ?: return "FlightStats FAIL: empty page"
        val n = parseBoard(md, "departure", TimeUtil.todayRiyadh()).size
        return when {
            n > 0 -> "FlightStats OK — $n scheduled rows (no actual times on board)"
            else -> "FlightStats PARTIAL — page loads, 0 rows parsed"
        }
    }
}
