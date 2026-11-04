package com.ruh.flight.api.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ruh.flight.api.DailyCount
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.collectors.AirLabsCollector
import com.ruh.flight.api.collectors.FlightAwareCollector
import com.ruh.flight.api.collectors.FlightStatsCollector
import com.ruh.flight.api.collectors.Fr24Collector
import com.ruh.flight.api.collectors.PlaneFinderCollector
import com.ruh.flight.api.database.FlightDbHelper
import com.ruh.flight.api.processing.Deduplicator
import com.ruh.flight.api.processing.TimeUtil

class CollectionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val TAG = "CollectionWorker"
        const val WORK_NAME = "ruh-collection"
        const val PREFS = "ruh_flight_prefs"
        const val KEY_JINA_FR24 = "jina_fr24"
        const val KEY_JINA_FA = "jina_flightaware"
        const val KEY_JINA_FS = "jina_flightstats"
        const val KEY_JINA_PF = "jina_planefinder"
        const val KEY_AIRLABS = "airlabs_api_key"
        const val KEY_GH_REPO = "github_repo"
        const val KEY_GH_TOKEN = "github_token"
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kFr24 = prefs.getString(KEY_JINA_FR24, "") ?: ""
        val kFa = prefs.getString(KEY_JINA_FA, "") ?: ""
        val kFs = prefs.getString(KEY_JINA_FS, "") ?: ""
        val kPf = prefs.getString(KEY_JINA_PF, "") ?: ""
        val kAir = prefs.getString(KEY_AIRLABS, "") ?: ""

        val collectors = listOf(
            Fr24Collector() to kFr24,
            FlightAwareCollector() to kFa,
            FlightStatsCollector() to kFs,
            PlaneFinderCollector() to kPf,
            AirLabsCollector() to kAir
        )

        val db = FlightDbHelper(applicationContext)
        val today = TimeUtil.todayRiyadh()
        val allFlights = mutableListOf<FlightRecord>()
        val cancelledNums = mutableSetOf<String>()
        var anyError: String? = null

        for ((collector, key) in collectors) {
            try {
                val result = collector.fetchDay(today, key)
                cancelledNums.addAll(result.cancelledFlightNumbers)
                // Keep today AND yesterday (board actuals are mostly yesterday's)
                val confirmed = result.flights.filter { Deduplicator.isCountable(it) }
                if (result.error != null && confirmed.isEmpty() && result.pagesRead == 0) {
                    db.upsertHealth(com.ruh.flight.api.SourceHealth(
                        source = collector.id, domain = collector.domain,
                        status = "failed", lastError = result.error,
                        confirmedCount = 0, pagesRead = result.pagesRead
                    ))
                    anyError = "${collector.label}: ${result.error}"
                } else {
                 val lastErr = when {
                         confirmed.isNotEmpty() -> null
                         result.error != null -> result.error
                         result.flights.isNotEmpty() -> "scheduled/JS only (no actuals)"
                         else -> "page readable but 0 rows"
                     }
                     // Logcat debug: per-source counts
                     Log.i(TAG, "${collector.label} - counted=${confirmed.size} error=$lastErr pages=${result.pagesRead}")
                     // Logcat debug: purge targets
                     if (cancelledNums.isNotEmpty()) {
                         Log.i(TAG, "${collector.label} - purge targets: ${cancelledNums.joinToString(", ")}")
                     }
                     db.upsertHealth(com.ruh.flight.api.SourceHealth(
                         source = collector.id, domain = collector.domain,
                         status = if (confirmed.isNotEmpty()) "operational" else "partial",
                         lastError = lastErr,
                         confirmedCount = confirmed.size, pagesRead = result.pagesRead
                     ))
                     allFlights.addAll(confirmed)
                 }
            } catch (e: Exception) {
                Log.e(TAG, "Collector ${collector.id} failed", e)
                db.upsertHealth(com.ruh.flight.api.SourceHealth(
                    source = collector.id, domain = collector.domain,
                    status = "failed", lastError = e.message ?: "unknown",
                    confirmedCount = 0, pagesRead = 0
                ))
                anyError = "${collector.label}: ${e.message}"
            }
        }

        // Date isolation + realness audit before counting
        db.sanitizeMovements()
        if (cancelledNums.isNotEmpty()) {
            db.purgeCancelledFlights(cancelledNums, listOf(today, TimeUtil.daysAgoRiyadh(1)))
        }

        val yesterday = TimeUtil.daysAgoRiyadh(1)
        val deduped = Deduplicator.deduplicate(allFlights.filter {
            Deduplicator.isCountable(it) && (it.ruhLocalDate == today || it.ruhLocalDate == yesterday)
        })
        val inserted = db.insertMovements(deduped)

        for (day in listOf(yesterday, today)) {
            val rawDay = db.getMovementsForDate(day)
            val dayDeduped = Deduplicator.deduplicate(rawDay.filter { Deduplicator.isCountable(it) })
            val (dep, arr) = Deduplicator.countByDirection(dayDeduped)

            val bySrc = rawDay.filter { Deduplicator.isCountable(it) }.groupBy { it.source }
            fun cnt(src: String, dir: String) = bySrc[src]?.count {
                it.direction == dir && Deduplicator.isCountable(it)
            } ?: 0

            db.upsertDailyCount(DailyCount(
                date = day,
                fr24Dep = cnt("fr24", "departure"), fr24Arr = cnt("fr24", "arrival"),
                faDep = cnt("flightaware", "departure"), faArr = cnt("flightaware", "arrival"),
                fsDep = cnt("flightstats", "departure"), fsArr = cnt("flightstats", "arrival"),
                pfDep = cnt("planefinder", "departure"), pfArr = cnt("planefinder", "arrival"),
                combinedDep = dep, combinedArr = arr, combinedTotal = dep + arr
            ))
        }

        db.recordRun(if (anyError == null) "completed" else "partial", deduped.size, anyError)
        Log.i(TAG, "Collection done: ${deduped.size} deduped, $inserted inserted, error=$anyError")
        return Result.success()
    }
}
