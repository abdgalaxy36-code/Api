package com.ruh.flight.api.collectors

import com.ruh.flight.api.DayFetchResult
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.JinaClient
import com.ruh.flight.api.processing.TimeUtil
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface SourceCollector {
    val id: String
    val domain: String
    val label: String
    fun fetchDay(date: String, apiKey: String): DayFetchResult
    fun test(apiKey: String): String
}

abstract class BaseCollector : SourceCollector {
    protected fun fetchPages(urls: List<String>, apiKey: String): List<String?> {
        if (urls.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(urls.size.coerceAtMost(4))
        try {
            val futures = urls.map { url ->
                pool.submit(Callable { JinaClient.reader(url, apiKey) })
            }
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    /** Single-page probe for Test keys — short timeout, real HTTP errors. */
    protected fun lightFetch(
        url: String,
        apiKey: String,
        engine: String = "direct",
        noCache: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()
    ): JinaClient.ReaderResult {
        return JinaClient.readerResult(
            url = url,
            apiKey = apiKey,
            engine = engine,
            noCache = noCache,
            timeoutMs = 35_000,
            jinaTimeoutSec = 25,
            extraHeaders = extraHeaders
        )
    }

    /** Jina browser headers that help JS boards (Plane Finder) settle. */
    protected fun boardBrowserHeaders(): Map<String, String> = mapOf(
        "X-Respond-Timing" to "network-idle",
        "X-Wait-For-Selector" to "table, [class*=flight], .board, #board"
    )
}
