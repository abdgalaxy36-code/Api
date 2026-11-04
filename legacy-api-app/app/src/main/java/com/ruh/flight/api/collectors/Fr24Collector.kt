package com.ruh.flight.api.collectors

import com.ruh.flight.api.DayFetchResult
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.processing.Deduplicator
import com.ruh.flight.api.processing.TimeUtil
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Fr24Collector : BaseCollector() {
    override val id = "fr24"
    override val domain = "flightradar24.com"
    override val label = "Flightradar24"

    override fun fetchDay(date: String, apiKey: String): DayFetchResult {
        val base = "https://www.flightradar24.com/data/airports/ruh"
        // page=-1 = actual Departed/Landed only (page=0 is Scheduled)
        val urls = listOf(
            "$base/departures?page=-1",
            "$base/arrivals?page=-1"
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
                error = pages.firstOrNull()?.let { it } ?: "All FR24 pages failed via Jina"
                // Continue to fallback if Jina fails
            }
        }

        // Fallback: Try Firecrawl for FR24 (no auth needed)
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

        return DayFetchResult(emptyList(), 0, 0, id, error ?: "All FR24 pages failed")
    }

    private fun processPages(pages: List<String>, date: String): DayFetchResult {
        val ok = pages.filterNotNull()
        if (ok.isEmpty()) {
            return DayFetchResult(emptyList(), 0, 0, id, "All FR24 pages failed")
        }

        val flights = mutableListOf<FlightRecord>()
        val depPages = listOfNotNull(pages.getOrNull(0))
        val arrPages = listOfNotNull(pages.getOrNull(1))
        val cancelled = mutableSetOf<String>()

        for (md in depPages) {
            flights.addAll(parseBoard(md, "departure", date))
            cancelled.addAll(extractCancelled(md))
        }
        for (md in arrPages) {
            flights.addAll(parseBoard(md, "arrival", date))
            cancelled.addAll(extractCancelled(md))
        }

        val countable = flights.filter {
            Deduplicator.isCountable(it) && it.flightNumber.uppercase() !in cancelled
        }
        return DayFetchResult(countable, ok.size, countable.size, id,
            cancelledFlightNumbers = cancelled)
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
            conn.outputStream.write(payload.toByteArray(Charsets.UTF_8))

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

    /**
     * Flight numbers whose board status is Canceled/Cancelled/Diverted —
     * never countable, and purge any stale DB rows that claimed them as actual.
     */
    private fun extractCancelled(md: String): Set<String> {
        val out = mutableSetOf<String>()
        val liveIdx = md.indexOf("## Live")
        val body = if (liveIdx >= 0) md.substring(liveIdx) else md
        val lines = body.lines()
        // Only real flight links — never aircraft (/aircraft/A320) or airline codes
        val bracketFlight = Regex("^\\[([A-Z]{1,3}[0-9]{3,4})\\]\\(https?://[^)]*flights/")
        val histRe = Regex("\\[History\\s+([A-Z]{1,3}[0-9]{1,4})\\]")
        val flightsPath = Regex("flightradar24\\.com/data/flights/([A-Za-z0-9]+)")
        val statusOrHist = Regex(
            "^(?:\\[History |(?:Departed|Landed)\\s+\\d|(?:Canceled|Cancelled|Scheduled|Estimated|Delayed)\\b)",
            RegexOption.IGNORE_CASE
        )
        var i = 0
        while (i < lines.size) {
            val lt = lines[i].trim()
            val isCancel = Regex("^(Canceled|Cancelled)$", RegexOption.IGNORE_CASE).matches(lt)
            val isDivert = Regex("Diverted to [A-Z]{3}").containsMatchIn(lt)
            if (isCancel || isDivert) {
                if (isDivert) {
                    // Diverted rows carry [History X] right after — authoritative
                    var got = false
                    for (k in i + 1..(i + 4).coerceAtMost(lines.size - 1)) {
                        val h = histRe.find(lines[k])
                        if (h != null) { out.add(h.groupValues[1].uppercase()); got = true; break }
                    }
                    if (got) { i++; continue }
                }
                // Cancelled card: flight link sits a few lines ABOVE the status;
                // stop at the previous row's status/History boundary.
                var got = false
                for (k in (i - 1) downTo (i - 12).coerceAtLeast(0)) {
                    val lk = lines[k]
                    val ltt = lk.trim()
                    if (statusOrHist.containsMatchIn(ltt)) break
                    val m = flightsPath.find(lk) ?: bracketFlight.find(ltt)
                    if (m != null) { out.add(m.groupValues[1].uppercase()); got = true; break }
                }
                if (!got) {
                    // forward: next card / expanded block may restate the flight link
                    for (k in i + 1..(i + 8).coerceAtMost(lines.size - 1)) {
                        val m = bracketFlight.find(lines[k].trim()) ?: flightsPath.find(lines[k])
                        if (m != null) { out.add(m.groupValues[1].uppercase()); break }
                    }
                }
            }
            i++
        }
        return out
    }

    private fun parseBoard(md: String, direction: String, date: String): List<FlightRecord> {
        val out = mutableListOf<FlightRecord>()
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            val statusMatch = Regex("^(Departed|Landed)\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)$", RegexOption.IGNORE_CASE).find(line)
            if (statusMatch != null) {
                val event = statusMatch.groupValues[1].lowercase()
                val timeStr = statusMatch.groupValues[2].trim()

                var flightNum = ""
                var otherIata = ""
                var otherCity = ""
                var airline = ""
                var evidence = line

                val fwdHi = (i + 10).coerceAtLeast(i + 1).coerceAtMost(lines.size - 1)
                val bwdLo = (i - 18).coerceAtLeast(0)
                // History link sits AFTER the status line — always prefer it.
                // Backward-only search grabs the previous row's flight (UL265 bug).
                for (k in i + 1..fwdHi) {
                    val fm = Regex("\\[History\\s+([A-Z]{1,3}[0-9]{1,4})\\]").find(lines[k])
                    if (fm != null) { flightNum = fm.groupValues[1]; break }
                    // History variant: [](https://www.flightradar24.com/sva1057/4268257c)
                    val fmH = Regex("flightradar24\\.com/([A-Za-z]{2}\\d{3,4})/\\w+").find(lines[k])
                    if (fmH != null) { flightNum = fmH.groupValues[1].uppercase(); break }
                }

                // From/To + airline of THIS row sit BEFORE the status line.
                // Stop at the previous row's boundary (History or status line).
                for (k in i - 1 downTo bwdLo) {
                    val lk = lines[k]
                    val lt = lk.trim()
                    if (Regex("^\\[History\\s").containsMatchIn(lt)) break
                    if (Regex("^(Departed|Landed)\\s+\\d", RegexOption.IGNORE_CASE).containsMatchIn(lt)) break
                    if (Regex("^(Canceled|Cancelled|Scheduled|Estimated|Delayed)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lt) && lt.length < 30) break

                    if (flightNum.isEmpty()) {
                        // Only flight pages — [A321](.../aircraft/a321) is an aircraft type
                        val fm2 = Regex("^\\[([A-Z]{1,3}[0-9]{1,4})\\]\\(https?://[^)]*/flights/").find(lt)
                        if (fm2 != null) flightNum = fm2.groupValues[1]
                    }
                    if (flightNum.isEmpty()) {
                        val fm3 = Regex("flightradar24\\.com/data/flights/([A-Za-z0-9]+)").find(lk)
                        if (fm3 != null) flightNum = fm3.groupValues[1].uppercase()
                    }

                    if (otherIata.isEmpty()) {
                        val tm = Regex("\\[(?:To|From):\\s*([A-Za-z][A-Za-z\\s'&.\\-]*?)\\s+([A-Z]{3})\\s+Airport").find(lk)
                        if (tm != null) {
                            otherCity = tm.groupValues[1].trim()
                            otherIata = tm.groupValues[2]
                        }
                    }
                    if (otherIata.isEmpty()) {
                        val tm2 = Regex("\\[(?:To|From):\\s*([A-Za-z][A-Za-z\\s'&.\\-]*?)!\\[[^\\]]*\\]\\([^)]*\\)\\s*([A-Z]{3})\\s+Airport").find(lk)
                        if (tm2 != null) {
                            otherCity = tm2.groupValues[1].trim()
                            otherIata = tm2.groupValues[2]
                        }
                    }

                    if (airline.isEmpty()) {
                        val am = Regex("\\[(?!\\])([^\\]]+)\\]\\(https?://www\\.flightradar24\\.com/data/airlines/").find(lk)
                        if (am != null) {
                            airline = am.groupValues[1].trim().substringBefore(" (").trim()
                        }
                    }
                    if (flightNum.isNotEmpty() && otherIata.isNotEmpty() && airline.isNotEmpty()) break
                }

                if (flightNum.isNotEmpty()) {
                    // Board times have no date — resolve against device now so
                    // yesterday 2:22 PM is NOT stored as today.
                    val utcIso = TimeUtil.resolveEventUtcIso(date, normalizeTime(timeStr)) ?: continue
                    if (!TimeUtil.isPastOrNow(utcIso)) continue
                    val actualLabel = if (event == "departed") "Actual takeoff" else "Actual landing"
                    out.add(FlightRecord(
                        source = id,
                        flightNumber = flightNum,
                        direction = direction,
                        origin = if (direction == "departure") "RUH" else otherIata.ifEmpty { "RUH" },
                        destination = if (direction == "arrival") "RUH" else otherIata.ifEmpty { "RUH" },
                        actualTimeUtc = utcIso,
                        ruhLocalDate = TimeUtil.utcIsoToRiyadhDate(utcIso),
                        actualEventLabel = actualLabel,
                        evidence = "FR24 $event $timeStr",
                        airline = airline,
                        otherCity = otherCity
                    ))
                }
            }
            i++
        }
        return out
    }

    private fun normalizeTime(t: String): String {
        var s = t.trim().uppercase()
        val ampm = when {
            s.endsWith("AM") -> { s = s.removeSuffix("AM").trim(); "AM" }
            s.endsWith("PM") -> { s = s.removeSuffix("PM").trim(); "PM" }
            else -> ""
        }
        val parts = s.split(":")
        if (parts.size != 2) return t
        var h = parts[0].toIntOrNull() ?: return t
        val m = parts[1]
        if (ampm == "PM" && h < 12) h += 12
        if (ampm == "AM" && h == 12) h = 0
        return String.format("%02d:%02d", h, m.toIntOrNull() ?: 0)
    }

    override fun test(apiKey: String): String {
        // page=0 is almost all Scheduled/Estimated; page=-1 has real Departed/Landed
        val r = lightFetch("https://www.flightradar24.com/data/airports/ruh/departures?page=-1", apiKey)
        if (r.error != null) return "FR24 FAIL: ${r.error}"
        val md = r.body ?: return "FR24 FAIL: empty page"
        val n = parseBoard(md, "departure", TimeUtil.todayRiyadh()).size
        return if (n > 0) "FR24 OK — $n actual departures"
        else "FR24 OK — board readable (0 actuals yet on this page)"
    }
}
