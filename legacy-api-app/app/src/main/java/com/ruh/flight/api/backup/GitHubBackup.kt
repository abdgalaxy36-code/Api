package com.ruh.flight.api.backup

import com.ruh.flight.api.DailyCount
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.database.FlightDbHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object GitHubBackup {
    const val DEFAULT_REPO = "abdgalaxy36-code/ruh-flights-backup"
    const val PATH = "ruh-flight-data.json"
    private const val API = "https://api.github.com"

    fun backup(repo: String, token: String, db: FlightDbHelper): String {
        val r = repo.trim().removePrefix("https://github.com/").removeSuffix("/")
        val t = token.trim()
        if (r.isEmpty()) return "Enter GitHub repo (owner/name)"
        if (t.isEmpty()) return "Enter GitHub token (PAT)"

        val movements = db.getAllMovements()
        val counts = db.getDailyCounts(3650)

        val root = JSONObject()
        root.put("version", 1)
        root.put("exported_at", java.time.Instant.now().toString())
        root.put("app", "ruh-flight-api")

        val movArr = JSONArray()
        for (m in movements) {
            movArr.put(JSONObject().apply {
                put("source", m.source)
                put("flightNumber", m.flightNumber)
                put("direction", m.direction)
                put("origin", m.origin)
                put("destination", m.destination)
                put("actualTimeUtc", m.actualTimeUtc)
                put("ruhLocalDate", m.ruhLocalDate)
                put("actualEventLabel", m.actualEventLabel)
                put("evidence", m.evidence)
                put("cancelled", m.cancelled)
                put("airline", m.airline)
                put("otherCity", m.otherCity)
                put("sources", m.sources.ifBlank { m.source })
            })
        }
        root.put("movements", movArr)

        val cntArr = JSONArray()
        for (c in counts) {
            cntArr.put(JSONObject().apply {
                put("date", c.date)
                put("fr24Dep", c.fr24Dep); put("fr24Arr", c.fr24Arr)
                put("faDep", c.faDep); put("faArr", c.faArr)
                put("fsDep", c.fsDep); put("fsArr", c.fsArr)
                put("pfDep", c.pfDep); put("pfArr", c.pfArr)
                put("combinedDep", c.combinedDep)
                put("combinedArr", c.combinedArr)
                put("combinedTotal", c.combinedTotal)
            })
        }
        root.put("daily_counts", cntArr)

        val json = root.toString()
        val content = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val sha = getSha(r, t)
        val body = JSONObject().apply {
            put("message", "Backup RuH Flights data")
            put("content", content)
            if (sha != null) put("sha", sha)
        }

        val code = putContents(r, t, body.toString())
        return when (code) {
            200, 201 -> "Backed up ${movements.size} flights, ${counts.size} days → $r"
            401 -> "GitHub auth failed — check token"
            403 -> "GitHub forbidden — check token scopes (repo)"
            404 -> "Repo not found: $r"
            else -> "GitHub error HTTP $code"
        }
    }

    fun restore(repo: String, token: String, db: FlightDbHelper): String {
        val r = repo.trim().removePrefix("https://github.com/").removeSuffix("/")
        val t = token.trim()
        if (r.isEmpty()) return "Enter GitHub repo (owner/name)"
        if (t.isEmpty()) return "Enter GitHub token (PAT)"

        val raw = getContents(r, t)
            ?: return "No backup found at $r/$PATH (or auth failed)"

        val decoded = try {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        } catch (e: Exception) {
            return "Backup file is not valid base64"
        }

        val root = try { JSONObject(decoded) } catch (e: Exception) {
            return "Backup JSON parse failed: ${e.message}"
        }

        val movArr = root.optJSONArray("movements") ?: JSONArray()
        val flights = mutableListOf<FlightRecord>()
        for (i in 0 until movArr.length()) {
            val o = movArr.getJSONObject(i)
            flights.add(FlightRecord(
                source = o.optString("source", ""),
                flightNumber = o.optString("flightNumber", ""),
                direction = o.optString("direction", ""),
                origin = o.optString("origin", ""),
                destination = o.optString("destination", ""),
                actualTimeUtc = o.optString("actualTimeUtc", ""),
                ruhLocalDate = o.optString("ruhLocalDate", ""),
                actualEventLabel = o.optString("actualEventLabel", ""),
                evidence = o.optString("evidence", ""),
                cancelled = o.optBoolean("cancelled", false),
                airline = o.optString("airline", ""),
                otherCity = o.optString("otherCity", ""),
                sources = o.optString("sources", "").ifBlank { o.optString("source", "") }
            ))
        }
        val inserted = db.insertMovements(flights)

        val cntArr = root.optJSONArray("daily_counts") ?: JSONArray()
        var days = 0
        for (i in 0 until cntArr.length()) {
            val o = cntArr.getJSONObject(i)
            db.upsertDailyCount(DailyCount(
                date = o.optString("date", ""),
                fr24Dep = o.optInt("fr24Dep", 0), fr24Arr = o.optInt("fr24Arr", 0),
                faDep = o.optInt("faDep", 0), faArr = o.optInt("faArr", 0),
                fsDep = o.optInt("fsDep", 0), fsArr = o.optInt("fsArr", 0),
                pfDep = o.optInt("pfDep", 0), pfArr = o.optInt("pfArr", 0),
                combinedDep = o.optInt("combinedDep", 0),
                combinedArr = o.optInt("combinedArr", 0),
                combinedTotal = o.optInt("combinedTotal", 0)
            ))
            days++
        }

        return "Restored $inserted/${flights.size} flights, $days day counts from $r"
    }

    private fun getSha(repo: String, token: String): String? {
        val url = "$API/repos/$repo/contents/$PATH"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).optString("sha").ifEmpty { null }
            } else null
        } catch (e: Exception) { null }
    }

    private fun getContents(repo: String, token: String): String? {
        val url = "$API/repos/$repo/contents/$PATH"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).optString("content").ifEmpty { null }
            } else null
        } catch (e: Exception) { null }
    }

    private fun putContents(repo: String, token: String, jsonBody: String): Int {
        val url = "$API/repos/$repo/contents/$PATH"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) { -1 }
    }
}
