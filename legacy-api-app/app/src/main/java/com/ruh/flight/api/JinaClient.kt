package com.ruh.flight.api

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object JinaClient {
    private const val READER = "https://r.jina.ai/"
    private const val TIMEOUT_MS = 90_000

    data class ReaderResult(
        val code: Int,
        val body: String?,
        val error: String?
    ) {
        val ok: Boolean get() = code == 200 && body != null
    }

    fun reader(
        url: String,
        apiKey: String = "",
        engine: String = "direct",
        noCache: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? = readerResult(url, apiKey, engine, noCache, extraHeaders = extraHeaders).body

    fun readerResult(
        url: String,
        apiKey: String = "",
        engine: String = "direct",
        noCache: Boolean = false,
        timeoutMs: Int = TIMEOUT_MS,
        jinaTimeoutSec: Int = 60,
        extraHeaders: Map<String, String> = emptyMap()
    ): ReaderResult {
        return try {
            val target = READER + URLEncoder.encode(url, "UTF-8")
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestMethod("GET")
            conn.setRequestProperty("X-Return-Format", "markdown")
            conn.setRequestProperty("X-Timeout", jinaTimeoutSec.toString())
            conn.setRequestProperty("X-Engine", engine)
            if (noCache) conn.setRequestProperty("X-No-Cache", "true")
            conn.setRequestProperty("Accept", "text/plain")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            for ((k, v) in extraHeaders) {
                if (k.isNotBlank() && v.isNotBlank()) conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                ReaderResult(200, body, null)
            } else {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                } catch (_: Exception) { null }
                val hint = when (code) {
                    401, 403 -> "bad API key"
                    429 -> "rate limited"
                    else -> null
                }
                val detail = listOfNotNull("HTTP $code", hint, errBody?.take(80))
                    .joinToString(" · ")
                ReaderResult(code, null, detail)
            }
        } catch (e: Exception) {
            ReaderResult(0, null, e.message ?: e.javaClass.simpleName)
        }
    }
}
