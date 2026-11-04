package com.ruh.flight.api.collectors

import com.ruh.flight.api.DayFetchResult
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.processing.TimeUtil
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/**
 * AirLabs schedules API — direct HTTPS (not Jina).
 * Free plan: max 2 calls per collect (dep_iata + arr_iata), limit 50.
 * Actuals come from dep_actual_utc / arr_actual_utc when present.
 */
class AirLabsCollector : SourceCollector {
    override val id = "airlabs"
    override val domain = "airlabs.co"
    override val label = "AirLabs"

    override fun fetchDay(date: String, apiKey: String): DayFetchResult {
        if (apiKey.isBlank()) {
            return DayFetchResult(emptyList(), 0, 0, id, "AirLabs key not set (Settings)")
        }
        val fields = "flight_iata,airline_iata,dep_iata,arr_iata,dep_time,arr_time," +
            "dep_actual_utc,arr_actual_utc,dep_estimated_utc,arr_estimated_utc,status"
        val depBody = get(
            "https://airlabs.co/api/v9/schedules?" + URLEncoder.encode("dep_iata", "UTF-8") + "=RUH" +
                "&" + URLEncoder.encode("_fields", "UTF-8") + "=" + URLEncoder.encode(fields, "UTF-8") +
                "&limit=50&" + URLEncoder.encode("api_key", "UTF-8") + "=" + URLEncoder.encode(apiKey, "UTF-8")
        ) ?: return DayFetchResult(emptyList(), 0, 0, id, "AirLabs departures request failed")
        val arrBody = get(
            "https://airlabs.co/api/v9/schedules?" + URLEncoder.encode("arr_iata", "UTF-8") + "=RUH" +
                "&" + URLEncoder.encode("_fields", "UTF-8") + "=" + URLEncoder.encode(fields, "UTF-8") +
                "&limit=50&" + URLEncoder.encode("api_key", "UTF-8") + "=" + URLEncoder.encode(apiKey, "UTF-8")
        ) ?: return DayFetchResult(emptyList(), 1, 0, id, "AirLabs arrivals request failed")

        val out = mutableListOf<FlightRecord>()
        out.addAll(parseSchedules(depBody, "departure", date))
        out.addAll(parseSchedules(arrBody, "arrival", date))
        return DayFetchResult(out, 2, out.size, id)
    }

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "RuH-Flights/1.0")
        val code = conn.responseCode
        if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) {
        null
    }

    private fun parseSchedules(body: String, direction: String, date: String): List<FlightRecord> {
        val out = mutableListOf<FlightRecord>()
        val root = try { JSONObject(body) } catch (_: Exception) { return out }
        val arr = root.optJSONArray("response") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val num = o.optString("flight_iata")
            if (num.isBlank()) continue
            val status = o.optString("status").lowercase()
            if (status == "cancelled" || status == "canceled") continue

            val actualUtc = if (direction == "departure") {
                o.optString("dep_actual_utc")
            } else {
                o.optString("arr_actual_utc")
            }
            // Only confirmed actual movements (skip pure scheduled)
            if (actualUtc.isBlank() || actualUtc == "null") continue
            // Prefer landed status for arrivals; departures: any non-scheduled with actual
            if (direction == "arrival" && status != "landed" && status != "active") continue

            val utcIso = try {
                Instant.parse(actualUtc).toString()
            } catch (_: Exception) {
                // sometimes without Z
                try {
                    Instant.parse(actualUtc + if (actualUtc.endsWith("Z")) "" else "Z").toString()
                } catch (_: Exception) { continue }
            }
            val ruhDate = TimeUtil.utcIsoToRiyadhDate(utcIso)
            if (ruhDate != date) continue
            if (!TimeUtil.isPastOrNow(utcIso)) continue

            val depIata = o.optString("dep_iata")
            val arrIata = o.optString("arr_iata")
            val airline = o.optString("airline_iata").ifBlank {
                num.take(2)
            }
            out.add(
                FlightRecord(
                    source = id,
                    flightNumber = num,
                    direction = direction,
                    origin = if (direction == "departure") "RUH" else depIata.ifBlank { "RUH" },
                    destination = if (direction == "arrival") "RUH" else arrIata.ifBlank { "RUH" },
                    actualTimeUtc = utcIso,
                    ruhLocalDate = ruhDate,
                    actualEventLabel = if (direction == "departure") "Actual takeoff" else "Actual landing",
                    evidence = "AirLabs $status $actualUtc",
                    airline = airline
                )
            )
        }
        return out
    }

    override fun test(apiKey: String): String {
        if (apiKey.isBlank()) return "AirLabs FAIL: key not set"
        // Single lightweight call — arrivals often include arr_actual
        val url = "https://airlabs.co/api/v9/schedules?arr_iata=RUH&limit=3" +
            "&_fields=flight_iata,arr_actual_utc,status&api_key=" + URLEncoder.encode(apiKey, "UTF-8")
        val body = get(url) ?: return "AirLabs FAIL: request failed"
        val root = try { JSONObject(body) } catch (_: Exception) {
            return "AirLabs FAIL: bad JSON ${body.take(80)}"
        }
        if (root.has("error")) {
            val err = root.optJSONObject("error")
            return "AirLabs FAIL: ${err?.optString("message") ?: root.optString("error")}"
        }
        val resp = root.optJSONArray("response")
            ?: return "AirLabs FAIL: no response"
        var withActual = 0
        for (i in 0 until resp.length()) {
            val o = resp.optJSONObject(i) ?: continue
            if (o.optString("arr_actual_utc").isNotBlank()) withActual++
        }
        return if (resp.length() == 0) "AirLabs PARTIAL — key ok, 0 arrivals returned"
        else "AirLabs OK — ${resp.length()} arrivals, $withActual with actual times"
    }
}
