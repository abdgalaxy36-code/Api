package com.ruh.flight.live

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Where flights data is pushed to / pulled from on GitHub. */
data class BackupConfig(
    val repo: String = GithubBackup.DEFAULT_REPO,
    val branch: String = GithubBackup.DEFAULT_BRANCH,
    val path: String = GithubBackup.DEFAULT_PATH,
    val token: String = ""
) {
    fun isComplete(): Boolean =
        repo.contains("/") && branch.isNotBlank() && path.isNotBlank() && token.isNotBlank()
}

/**
 * Backup / restore of the flights database only (daily totals + every flight
 * row) to a private GitHub repo via the Contents API. No app code is uploaded.
 */
object GithubBackup {

    const val TAG = "Fr24Live"
    const val DEFAULT_REPO = "abdgalaxy36-code/ruh-flights-backup"
    const val DEFAULT_BRANCH = "main"
    const val DEFAULT_PATH = "ruh-flight-live-data.json"

    private const val API = "https://api.github.com"
    private const val UA = "ruh-flight-live"
    private const val VERSION = 2

    // ------------------------------------------------------------- payload

    fun exportJson(stats: List<DayStat>, flights: List<DayFlight>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("app", "ruh-flight-live")
        root.put("exported_at", java.time.Instant.now().toString())

        val s = JSONArray()
        for (d in stats) {
            s.put(
                JSONObject().apply {
                    put("date", d.date)
                    put("departures", d.departures)
                    put("arrivals", d.arrivals)
                    put("total", d.total)
                    put("updated_at", d.updatedAt)
                }
            )
        }
        root.put("stats", s)

        val f = JSONArray()
        for (d in flights) {
            f.put(
                JSONObject().apply {
                    put("date", d.date)
                    put("number", d.number)
                    put("direction", d.direction)
                    put("epoch", d.epoch)
                    put("scheduled", d.scheduled)
                    put("origin", d.origin)
                    put("destination", d.destination)
                    put("airline", d.airline)
                    put("terminal", d.terminal)
                    put("gate", d.gate)
                    put("aircraft", d.aircraft)
                    put("status", d.status)
                    put("color", d.color)
                }
            )
        }
        root.put("flights", f)
        return root.toString(2)
    }

    /** @return (daily stats, flights) parsed from an export payload. */
    fun importJson(json: String): Pair<List<DayStat>, List<DayFlight>> {
        val root = JSONObject(json)
        val stats = buildList {
            val arr = root.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.optString("date")
                if (date.isBlank()) continue
                val dep = o.optInt("departures")
                val arrn = o.optInt("arrivals")
                add(DayStat(date, dep, arrn, o.optInt("total", dep + arrn), o.optLong("updated_at")))
            }
        }
        val flights = buildList {
            val arr = root.optJSONArray("flights") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.optString("date")
                val number = o.optString("number")
                if (date.isBlank() || number.isBlank()) continue
                add(
                    DayFlight(
                        date = date,
                        number = number,
                        direction = o.optString("direction").ifBlank { "dep" },
                        epoch = o.optLong("epoch"),
                        scheduled = o.optLong("scheduled"),
                        origin = o.optString("origin"),
                        destination = o.optString("destination"),
                        airline = o.optString("airline"),
                        terminal = o.optString("terminal"),
                        gate = o.optString("gate"),
                        aircraft = o.optString("aircraft"),
                        status = o.optString("status"),
                        color = o.optString("color", "gray")
                    )
                )
            }
        }
        return stats to flights
    }

    // --------------------------------------------------------------- network

    fun push(cfg: BackupConfig, json: String): String {
        if (!cfg.isComplete()) throw IllegalStateException("Backup settings incomplete")
        val repo = cfg.repo.trim().removePrefix("https://github.com/").removeSuffix(".git")
        val path = cfg.path.trim().removePrefix("/")
        val encodedPath = path.split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val base = "$API/repos/$repo/contents/$encodedPath"

        // Contents API needs the current blob sha when the file already exists.
        val (getCode, getBody) = http("GET", "$base?ref=${cfg.branch.trim()}", cfg.token, null)
        var sha: String? = null
        when {
            getCode == 200 -> sha = JSONObject(getBody).optString("sha").ifBlank { null }
            getCode == 404 -> sha = null
            getCode == 401 -> throw IllegalStateException("GitHub rejected the token (401)")
            else -> throw IllegalStateException("GitHub lookup failed (HTTP $getCode)")
        }

        val body = JSONObject().apply {
            put("message", "RUH Live Flights backup ${java.time.Instant.now()}")
            put("content", Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", cfg.branch.trim())
            if (sha != null) put("sha", sha)
        }.toString()

        val (code, resp) = http("PUT", base, cfg.token, body)
        if (code !in 200..299) throw IllegalStateException("Upload failed (HTTP $code): ${brief(resp)}")
        val committed = JSONObject(resp).optJSONObject("content")?.optString("path") ?: path
        Log.i(TAG, "backup pushed -> $repo/$committed (${json.length} bytes)")
        return "Backed up to $repo/$committed (${json.length} bytes)"
    }

    fun pull(cfg: BackupConfig): String {
        if (!cfg.isComplete()) throw IllegalStateException("Backup settings incomplete")
        val repo = cfg.repo.trim().removePrefix("https://github.com/").removeSuffix(".git")
        val path = cfg.path.trim().removePrefix("/")
        val encodedPath = path.split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val url = "$API/repos/$repo/contents/$encodedPath?ref=${cfg.branch.trim()}"
        val (code, resp) = http("GET", url, cfg.token, null)
        when (code) {
            401 -> throw IllegalStateException("GitHub rejected the token (401)")
            404 -> throw IllegalStateException("No backup file at $repo/$path")
            200 -> Unit
            else -> throw IllegalStateException("Download failed (HTTP $code): ${brief(resp)}")
        }
        val o = JSONObject(resp)
        val content = o.optString("content").replace("\n", "")
        if (content.isBlank()) throw IllegalStateException("Backup file is empty")
        val json = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        Log.i(TAG, "backup pulled <- $repo/$path (${json.length} bytes)")
        return json
    }

    /** Cheap check that the token can reach the repo. */
    fun testConnection(cfg: BackupConfig): String {
        if (!cfg.isComplete()) throw IllegalStateException("Fill in repository, branch, file and token first")
        val repo = cfg.repo.trim().removePrefix("https://github.com/").removeSuffix(".git")
        val (code, body) = http("GET", "$API/repos/$repo", cfg.token, null)
        return when (code) {
            200 -> {
                val o = JSONObject(body)
                val private = o.optBoolean("private")
                val branch = o.optString("default_branch")
                "OK — token works.\nrepo  $repo\nprivate  $private\ndefault branch  $branch"
            }
            401 -> throw IllegalStateException("Token rejected (401) — check it")
            403 -> throw IllegalStateException("No access to $repo (403) — the token lacks repo scope?")
            404 -> throw IllegalStateException("Repo $repo not found — wrong name or no access")
            else -> throw IllegalStateException("HTTP $code — ${brief(body)}")
        }
    }

    private fun brief(s: String): String =
        s.replace(Regex("\\s+"), " ").take(160)

    private fun http(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 25_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }
}
