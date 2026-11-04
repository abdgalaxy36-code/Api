package com.ruh.flight.api

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ruh.flight.api.backup.GitHubBackup
import com.ruh.flight.api.collectors.AirLabsCollector
import com.ruh.flight.api.collectors.FlightAwareCollector
import com.ruh.flight.api.collectors.FlightStatsCollector
import com.ruh.flight.api.collectors.Fr24Collector
import com.ruh.flight.api.collectors.PlaneFinderCollector
import com.ruh.flight.api.collectors.SourceCollector
import com.ruh.flight.api.database.FlightDbHelper
import com.ruh.flight.api.databinding.ActivityMainBinding
import com.ruh.flight.api.processing.Deduplicator
import com.ruh.flight.api.processing.TimeUtil
import com.ruh.flight.api.worker.CollectionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: FlightDbHelper
    private var arrExpanded = false
    private var depExpanded = false
    private var fetching = false

    private val autoRefresh = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh(silent = true) }

    private val deviceZone: ZoneId = ZoneId.systemDefault()
    private val humanDateFmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")
    private val humanTimeFmt = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = FlightDbHelper(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + dp(6), v.paddingRight, bars.bottom + dp(8))
            insets
        }

        binding.btnRefresh.setOnClickListener { refresh(silent = false) }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.swipeRefresh.setOnRefreshListener { refresh(silent = true) }
        binding.headArrived.setOnClickListener { toggleArrived() }
        binding.headDeparted.setOnClickListener { toggleDeparted() }

        schedulePeriodic()
        // One-time date/realness audit before first paint
        try { db.sanitizeMovements() } catch (_: Exception) {}
        showCached()
        refresh(silent = true)
    }

    override fun onResume() {
        super.onResume()
        autoRefresh.removeCallbacks(tick)
        autoRefresh.postDelayed(tick, 3_600_000L)
    }

    override fun onPause() {
        autoRefresh.removeCallbacks(tick)
        super.onPause()
    }

    private fun prefs() = getSharedPreferences(CollectionWorker.PREFS, Context.MODE_PRIVATE)

    private fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<CollectionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CollectionWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    // ---------- collection ----------

    private fun refresh(silent: Boolean) {
        if (fetching) return
        fetching = true
        binding.progress.visibility = View.VISIBLE
        if (!silent) binding.txtStatus.setText(R.string.loading)

        lifecycleScope.launch {
            val summary = try {
                withContext(Dispatchers.IO) { collectAll() }
            } catch (e: Exception) {
                CollectionResult("Network error — ${e.message}", error = true, seen = 0, okSources = 0)
            }
            fetching = false
            binding.progress.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            // Single atomic UI pass after collection finishes (all panels together)
            applyUi(status = summary.status, statusError = summary.error)
        }
    }

    private data class CollectionResult(
        val status: String,
        val error: Boolean = false,
        val seen: Int = 0,
        val okSources: Int = 0
    )

    private fun collectAll(): CollectionResult {
        val kFr24 = prefs().getString(CollectionWorker.KEY_JINA_FR24, "") ?: ""
        val kFa = prefs().getString(CollectionWorker.KEY_JINA_FA, "") ?: ""
        val kFs = prefs().getString(CollectionWorker.KEY_JINA_FS, "") ?: ""
        val kPf = prefs().getString(CollectionWorker.KEY_JINA_PF, "") ?: ""
        val kAir = prefs().getString(CollectionWorker.KEY_AIRLABS, "") ?: ""

        val collectors = listOf(
            Fr24Collector() to kFr24,
            FlightAwareCollector() to kFa,
            FlightStatsCollector() to kFs,
            PlaneFinderCollector() to kPf,
            AirLabsCollector() to kAir
        )

        val today = TimeUtil.todayRiyadh()
        val allFlights = mutableListOf<FlightRecord>()
        val cancelledNums = mutableSetOf<String>()
        var errors = 0
        var okSources = 0
        var seen = 0

        for ((collector, key) in collectors) {
            try {
                val r = collector.fetchDay(today, key)
                cancelledNums.addAll(r.cancelledFlightNumbers)
                // Keep today AND yesterday: at 02:00 the actual board is mostly
                // yesterday's Landed/Departed — filtering to "today" alone
                // discarded every real flight.
                val actual = r.flights.filter { Deduplicator.isCountable(it) }
                seen += r.flights.size
                val failedHard = r.error != null && actual.isEmpty() && r.pagesRead == 0
                if (failedHard) {
                    db.upsertHealth(SourceHealth(collector.id, collector.domain, "failed", r.error, 0, r.pagesRead))
                    errors++
                } else {
                 val todayCount = actual.count { it.ruhLocalDate == today }
                     val lastErr = when {
                         todayCount > 0 || actual.isNotEmpty() -> null
                         r.error != null -> r.error
                         r.flights.isNotEmpty() -> "scheduled only (no actuals yet)"
                         else -> "page readable but 0 actuals"
                     }
                     // Logcat debug: per-source counts
                     android.util.Log.i("RuHFlights", "${collector.label} - counted=${actual.size} today=$todayCount error=$lastErr pages=${r.pagesRead}")
                     // Logcat debug: purge targets
                     if (cancelledNums.isNotEmpty()) {
                         android.util.Log.i("RuHFlights", "${collector.label} - purge targets: ${cancelledNums.joinToString(", ")}")
                     }
                     db.upsertHealth(SourceHealth(collector.id, collector.domain,
                         if (actual.isNotEmpty()) "operational" else "partial",
                         lastErr,
                         actual.size, r.pagesRead))
                     if (actual.isNotEmpty()) okSources++
                     allFlights.addAll(actual)
                 }
            } catch (e: Exception) {
                db.upsertHealth(SourceHealth(collector.id, collector.domain, "failed", e.message, 0, 0))
                errors++
            }
        }

        // Date isolation + drop non-real rows before merge/count
        db.sanitizeMovements()
        if (cancelledNums.isNotEmpty()) {
            db.purgeCancelledFlights(cancelledNums, listOf(today, TimeUtil.daysAgoRiyadh(1)))
        }
        val yesterday = TimeUtil.daysAgoRiyadh(1)
        val deduped = Deduplicator.deduplicate(allFlights.filter {
            Deduplicator.isCountable(it) && (it.ruhLocalDate == today || it.ruhLocalDate == yesterday)
        })
        db.insertMovements(deduped)
        writeDailyCounts(yesterday)
        writeDailyCounts(today)
        db.sanitizeMovements()

        db.recordRun(if (errors == 0) "completed" else "partial", deduped.size,
            if (errors > 0) "$errors source(s) failed" else null)

        val status = when {
            errors == collectors.size -> "All ${collectors.size} sources failed — check network"
            errors > 0 -> "${deduped.size} actual movements · ${collectors.size - errors}/${collectors.size} sources · $errors failed"
            else -> "${deduped.size} actual movements · ${collectors.size}/${collectors.size} sources"
        }
        return CollectionResult(status, error = errors == collectors.size, seen = deduped.size, okSources = collectors.size - errors)
    }

    private fun writeDailyCounts(date: String) {
        // Strict date isolation: only rows whose stored date AND UTC-derived date == date
        val raw = db.getMovementsForDate(date).filter { m ->
            Deduplicator.isCountable(m) &&
                m.ruhLocalDate == date &&
                TimeUtil.utcIsoToRiyadhDate(m.actualTimeUtc) == date
        }
        val deduped = Deduplicator.deduplicate(raw)
        val (dep, arr) = Deduplicator.countByDirection(deduped)
        val bySrc = raw.groupBy { it.source }
        fun cnt(src: String, dir: String) = bySrc[src]?.count {
            it.direction == dir && Deduplicator.isCountable(it)
        } ?: 0
        db.upsertDailyCount(DailyCount(
            date = date,
            fr24Dep = cnt("fr24", "departure"), fr24Arr = cnt("fr24", "arrival"),
            faDep = cnt("flightaware", "departure"), faArr = cnt("flightaware", "arrival"),
            fsDep = cnt("flightstats", "departure"), fsArr = cnt("flightstats", "arrival"),
            pfDep = cnt("planefinder", "departure"), pfArr = cnt("planefinder", "arrival"),
            combinedDep = dep, combinedArr = arr, combinedTotal = dep + arr
        ))
    }

    /**
     * Single source of truth for the front page:
     * recompute every history day from confirmed_movements, persist daily_counts,
     * return the same list used by big totals, Compare, and Daily history.
     */
    private fun syncHistoryCounts(): List<DailyCount> {
        val days = historyDays()
        for (date in days) writeDailyCounts(date)
        val byDate = db.getDailyCounts(365).associateBy { it.date }
        return days.map { d ->
            byDate[d] ?: DailyCount(d, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    // ---------- rendering ----------

    private fun historyDays(): List<String> {
        val start = LocalDate.parse(HISTORY_START)
        val end = LocalDate.now(deviceZone)
        if (end.isBefore(start)) return emptyList()
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .map { it.toString() }
            .toList()
            .asReversed()
    }

    /**
     * Atomic UI update — date, totals, meta, health, status, lists, compare, history
     * all refresh together once collection (or cache load) completes.
     */
    private fun applyUi(status: String? = null, statusError: Boolean = false) {
        // Drop future/wrong-dated rows before any count paints
        try { db.sanitizeMovements() } catch (_: Exception) {}
        val today = TimeUtil.todayRiyadh()
        binding.txtDate.text = LocalDate.now(deviceZone).format(humanDateFmt)

        val counts = syncHistoryCounts()
        val todayCount = counts.firstOrNull { it.date == today }

        binding.txtArrived.text = (todayCount?.combinedArr ?: 0).toString()
        binding.txtDeparted.text = (todayCount?.combinedDep ?: 0).toString()

        val lastRun = db.getLastRun()
        binding.txtMeta.text = if (lastRun != null) {
            val t = try {
                Instant.parse(lastRun.first).atZone(deviceZone).format(humanTimeFmt)
            } catch (e: Exception) { "--:--" }
            "Last collect $t (device time)"
        } else {
            "Not collected yet"
        }

        val health = db.getAllHealth()
        binding.txtApiStatus.text = if (health.isEmpty()) {
            "Sources: not yet collected"
        } else {
            val order = listOf("fr24", "flightaware", "flightstats", "planefinder", "airlabs")
            val names = mapOf(
                "fr24" to "Flightradar24",
                "flightaware" to "FlightAware",
                "flightstats" to "FlightStats",
                "planefinder" to "Plane Finder",
                "airlabs" to "AirLabs"
            )
            health.sortedBy { order.indexOf(it.source).let { i -> if (i < 0) 99 else i } }
                .joinToString("\n") { h ->
                    val icon = when (h.status) {
                        "operational" -> "✓"
                        "partial" -> "◐"
                        else -> "✗"
                    }
                    val err = when {
                        h.status == "failed" && !h.lastError.isNullOrBlank() -> " — ${h.lastError}"
                        h.status == "partial" && !h.lastError.isNullOrBlank() &&
                            h.lastError != "no actual times in page" -> " — ${h.lastError}"
                        h.status == "partial" -> " — scheduled/JS only (no actuals)"
                        else -> ""
                    }
                    "${names[h.source] ?: h.source} $icon${h.confirmedCount}$err"
                }
        }

        updateQuotaLabel()

        val statusText = when {
            status != null -> status
            todayCount == null || todayCount.combinedTotal == 0 -> getString(R.string.no_data)
            else -> getString(R.string.actual_only_status)
        }
        binding.txtStatus.setTextColor(getColor(if (statusError) R.color.error else R.color.text_secondary))
        binding.txtStatus.text = statusText

        renderFlightLists()
        renderCompare(counts, today, todayCount)
        renderHistory(counts, today)
    }

    private fun showCached() = applyUi()

    private fun updateQuotaLabel() {
        val keysSet = listOf(
            CollectionWorker.KEY_JINA_FR24, CollectionWorker.KEY_JINA_FA,
            CollectionWorker.KEY_JINA_FS, CollectionWorker.KEY_JINA_PF
        ).count { (prefs().getString(it, "") ?: "").isNotBlank() }
        binding.txtQuota.text = if (keysSet == 0) "Jina Reader · keyless (20 RPM)"
        else "Jina Reader · $keysSet/4 keys applied (500 RPM)"
    }

    private fun toggleArrived() {
        arrExpanded = !arrExpanded
        binding.listArrived.visibility = if (arrExpanded) View.VISIBLE else View.GONE
        binding.caretArrived.text = if (arrExpanded) "▾" else "▸"
        if (arrExpanded && binding.listArrived.childCount == 0) renderFlightLists()
    }

    private fun toggleDeparted() {
        depExpanded = !depExpanded
        binding.listDeparted.visibility = if (depExpanded) View.VISIBLE else View.GONE
        binding.caretDeparted.text = if (depExpanded) "▾" else "▸"
        if (depExpanded && binding.listDeparted.childCount == 0) renderFlightLists()
    }

    private fun renderFlightLists() {
        val today = TimeUtil.todayRiyadh()
        val flights = Deduplicator.deduplicate(
            db.getMovementsForDate(today).filter { Deduplicator.isCountable(it) && it.ruhLocalDate == today }
        )
        val arrivals = flights.filter { it.direction == "arrival" }.sortedBy { it.actualTimeUtc }
        val departures = flights.filter { it.direction == "departure" }.sortedBy { it.actualTimeUtc }

        binding.txtArrivedHeader.text = "ARRIVED · ${arrivals.size}"
        binding.txtArrivedHint.text = if (arrivals.isEmpty()) "0 confirmed" else ""
        binding.txtDepartedHeader.text = "DEPARTED · ${departures.size}"
        binding.txtDepartedHint.text = if (departures.isEmpty()) "0 confirmed" else ""

        fillFlightList(binding.listArrived, arrivals, R.color.arrived)
        fillFlightList(binding.listDeparted, departures, R.color.departed)
    }

    private fun fillFlightList(container: LinearLayout, flights: List<FlightRecord>, colorRes: Int) {
        container.removeAllViews()
        if (flights.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No actual movements saved"
            tv.setTextColor(getColor(R.color.text_secondary))
            tv.textSize = 13f
            tv.setPadding(dp(4), dp(8), dp(4), dp(8))
            container.addView(tv)
            return
        }
        for (f in flights) container.addView(flightRow(f, colorRes))
    }

    private fun routeLabel(f: FlightRecord): String {
        val city = f.otherCity
        return if (f.direction == "arrival") {
            val from = listOfNotNull(f.origin.takeIf { it != "RUH" }, city.takeIf { it.isNotBlank() && f.origin != "RUH" })
                .distinct().joinToString(" ")
            if (from.isEmpty()) "→ RUH" else "$from → RUH"
        } else {
            val to = listOfNotNull(f.destination.takeIf { it != "RUH" }, city.takeIf { it.isNotBlank() && f.destination != "RUH" })
                .distinct().joinToString(" ")
            if (to.isEmpty()) "RUH →" else "RUH → $to"
        }
    }

    /** Short source badge: FR24 · AL · FS … (all sources that confirmed this flight). */
    private fun sourceBadge(f: FlightRecord): String {
        val short = mapOf(
            "fr24" to "FR24",
            "flightaware" to "FA",
            "flightstats" to "FS",
            "planefinder" to "PF",
            "airlabs" to "AL"
        )
        val list = f.sourceList()
        if (list.isEmpty()) return f.source.ifBlank { "—" }
        return list.joinToString(" · ") { short[it] ?: it.uppercase() }
    }

    private fun flightRow(f: FlightRecord, colorRes: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            background = getDrawable(android.R.drawable.list_selector_background)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val numText = if (f.airline.isNotBlank()) "${f.flightNumber} · ${f.airline}" else f.flightNumber
        val num = TextView(this).apply {
            text = numText
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        val route = TextView(this).apply {
            text = routeLabel(f)
            setTextColor(getColor(colorRes))
            textSize = 13f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(num)
        top.addView(route)

        val time = TimeUtil.utcIsoToHhmm(f.actualTimeUtc)
        val srcBadge = sourceBadge(f)
        val line2 = TextView(this).apply {
            text = "$time (Riyadh)  ·  $srcBadge"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        }
        val line3 = TextView(this).apply {
            text = f.evidence
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            alpha = 0.85f
            maxLines = 2
        }
        row.addView(top)
        row.addView(line2)
        row.addView(line3)

        val divider = View(this).apply {
            setBackgroundColor(getColor(R.color.card_border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
            }
        }
        row.addView(divider)

        row.setOnClickListener { openFlight(f) }
        return row
    }

    /** Split "SV101"→SV/101, "F3201"→F3/201, "3L201"→3L/201 */
    private fun splitFlightNumber(raw: String): Pair<String, String>? {
        val s = raw.uppercase().trim().replace(" ", "")
        val m = Regex("^([A-Z]{2}|[A-Z][0-9]|[0-9][A-Z])([0-9]{1,4})$").find(s)
        return if (m != null) m.groupValues[1] to m.groupValues[2] else null
    }

    /**
     * Open this flight on FR24 (primary link — FlightStats date deep-links removed per request).
     */
    private fun openFlight(f: FlightRecord) {
        val url = "https://www.flightradar24.com/data/flights/${f.flightNumber.lowercase()}"
        openUrl(url, "FR24 ${f.flightNumber}")
    }

    private fun openUrl(url: String, label: String) {
        android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show()
        val chrome = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")
        try {
            startActivity(chrome)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "No browser found", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderCompare(counts: List<DailyCount>, todayKey: String, today: DailyCount?) {
        binding.compareContainer.removeAllViews()
        if (today == null) {
            binding.txtCompareTitle.visibility = View.GONE
            binding.txtCompareEmpty.visibility = View.GONE
            return
        }
        binding.txtCompareTitle.visibility = View.VISIBLE
        // Same list Daily history shows — only days with data, excluding today
        val previous = counts.filter { it.date != todayKey && it.combinedTotal > 0 }
        if (previous.isEmpty()) {
            binding.txtCompareEmpty.visibility = View.VISIBLE
            binding.txtCompareEmpty.text =
                "No previous days with data yet — history builds as the app collects"
            return
        }
        binding.txtCompareEmpty.visibility = View.GONE

        val arrBeats = previous.count { it.combinedArr < today.combinedArr }
        val depBeats = previous.count { it.combinedDep < today.combinedDep }
        val arrBest = today.combinedArr >= previous.maxOf { it.combinedArr }
        val depBest = today.combinedDep >= previous.maxOf { it.combinedDep }
        val bestPrev = previous.maxByOrNull { it.combinedTotal }
        val todayTotal = today.combinedTotal
        val prevBestTotal = bestPrev?.combinedTotal ?: 0
        val totalBeats = previous.count { it.combinedTotal < todayTotal }

        binding.compareContainer.addView(
            compareRow(
                "Arrived",
                "today ${today.combinedArr} · beat $arrBeats of ${previous.size} days",
                arrBest, R.color.arrived
            )
        )
        binding.compareContainer.addView(
            compareRow(
                "Departed",
                "today ${today.combinedDep} · beat $depBeats of ${previous.size} days",
                depBest, R.color.departed
            )
        )
        binding.compareContainer.addView(
            compareRow(
                "Total flights",
                "today $todayTotal · best prev $prevBestTotal (${bestPrev?.date ?: "—"}) · beat $totalBeats of ${previous.size}",
                todayTotal > prevBestTotal, R.color.accent
            )
        )
    }

    private fun compareRow(label: String, detail: String, best: Boolean, colorRes: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(colorRes))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = detail
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })
        row.addView(col)
        if (best) {
            row.addView(TextView(this).apply {
                text = "RECORD"
                setTextColor(getColor(R.color.accent))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }
        return row
    }

    private fun renderHistory(counts: List<DailyCount>, todayKey: String) {
        binding.historyContainer.removeAllViews()
        // counts already == historyDays() from syncHistoryCounts — same source as Compare
        binding.txtNoHistory.visibility = if (counts.isEmpty()) View.VISIBLE else View.GONE

        for (day in counts) {
            val date = day.date
            val hasData = day.combinedTotal > 0
            val isToday = date == todayKey

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), dp(10), dp(10))
                isClickable = true
                isFocusable = true
                background = getDrawable(android.R.drawable.list_selector_background)
            }
            val dateTv = TextView(this).apply {
                text = if (isToday) "$date (today)" else date
                setTextColor(getColor(if (hasData) R.color.text_primary else R.color.text_secondary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrTv = TextView(this).apply {
                text = "↓ ${day.combinedArr}"
                setTextColor(getColor(if (hasData) R.color.arrived else R.color.text_secondary))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0)
            }
            val depTv = TextView(this).apply {
                text = "↑ ${day.combinedDep}"
                setTextColor(getColor(if (hasData) R.color.departed else R.color.text_secondary))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0)
            }
            val caret = TextView(this).apply {
                text = "▸"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 16f
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(dateTv); row.addView(arrTv); row.addView(depTv); row.addView(caret)
            binding.historyContainer.addView(row)

            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(8), 0, 0, dp(4))
            }
            binding.historyContainer.addView(detail)

            row.setOnClickListener {
                if (detail.visibility == View.VISIBLE) {
                    detail.visibility = View.GONE
                    caret.text = "▸"
                } else {
                    caret.text = "▾"
                    if (detail.childCount == 0) fillHistoryDetail(detail, date, hasData)
                    detail.visibility = View.VISIBLE
                }
            }

            val divider = View(this).apply {
                setBackgroundColor(getColor(R.color.card_border))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            }
            binding.historyContainer.addView(divider)
        }
    }

    private fun fillHistoryDetail(container: LinearLayout, date: String, hasData: Boolean) {
        // Strict date isolation — only this calendar day's rows
        val flights = Deduplicator.deduplicate(
            db.getMovementsForDate(date).filter {
                Deduplicator.isCountable(it) &&
                    it.ruhLocalDate == date &&
                    TimeUtil.utcIsoToRiyadhDate(it.actualTimeUtc) == date
            }
        )
        if (flights.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (date < TimeUtil.daysAgoRiyadh(2))
                    "No data — free sources only cover ~recent days (FR24 ~12h, Plane Finder today+tomorrow)"
                else
                    "No flight details saved for this day yet"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            return
        }
        val arrivals = flights.filter { it.direction == "arrival" }.sortedBy { it.actualTimeUtc }
        val departures = flights.filter { it.direction == "departure" }.sortedBy { it.actualTimeUtc }
        for (f in arrivals) container.addView(flightRow(f, R.color.arrived))
        for (f in departures) container.addView(flightRow(f, R.color.departed))
    }

    // ---------- settings ----------

    private fun showSettingsDialog() {
        val scroll = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        scroll.addView(layout)

        layout.addView(TextView(this).apply {
            text = "Jina Reader keys (optional)"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        })

        val keys = listOf(
            CollectionWorker.KEY_JINA_FR24 to "Flightradar24 (Jina)",
            CollectionWorker.KEY_JINA_FA to "FlightAware (Jina)",
            CollectionWorker.KEY_JINA_FS to "FlightStats (Jina)",
            CollectionWorker.KEY_JINA_PF to "Plane Finder (Jina)",
            CollectionWorker.KEY_AIRLABS to "AirLabs API key (direct · 2 calls/collect)"
        )
        val jinaOnly = setOf(
            CollectionWorker.KEY_JINA_FR24, CollectionWorker.KEY_JINA_FA,
            CollectionWorker.KEY_JINA_FS, CollectionWorker.KEY_JINA_PF
        )
        val fields = mutableMapOf<String, EditText>()
        for ((prefKey, label) in keys) {
            layout.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(12), 0, dp(2))
            })
            val et = EditText(this).apply {
                hint = if (prefKey == CollectionWorker.KEY_AIRLABS) "AirLabs api_key (optional)"
                else "Jina API key (optional)"
                inputType = InputType.TYPE_CLASS_TEXT
                setText(prefs().getString(prefKey, ""))
                setSelection(text.length)
            }
            layout.addView(et)
            fields[prefKey] = et
        }
        val keyButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val useAllBtn = TextView(this).apply {
            text = getString(R.string.use_key_all)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        val applyBtn = TextView(this).apply {
            text = getString(R.string.apply_keys)
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        keyButtons.addView(useAllBtn)
        keyButtons.addView(applyBtn)
        layout.addView(keyButtons)

        val keyStatus = TextView(this).apply {
            text = "Keys optional · keyless works at 20 RPM · Apply saves and refreshes"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        layout.addView(keyStatus)

        val testBtn = TextView(this).apply {
            text = getString(R.string.test_key)
            setTextColor(getColor(R.color.departed))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        layout.addView(testBtn)

        fun saveJinaKeys(): Int {
            val editor = prefs().edit()
            var n = 0
            for ((prefKey, et) in fields) {
                val v = et.text.toString().trim()
                editor.putString(prefKey, v)
                if (v.isNotEmpty()) n++
            }
            editor.apply()
            updateQuotaLabel()
            return n
        }

        useAllBtn.setOnClickListener {
            val first = fields[CollectionWorker.KEY_JINA_FR24]?.text?.toString()?.trim() ?: ""
            if (first.isEmpty()) {
                keyStatus.text = "Enter a key in Flightradar24 first, then tap Use key for all 4"
                keyStatus.setTextColor(getColor(R.color.error))
                return@setOnClickListener
            }
            jinaOnly.forEach { k -> fields[k]?.setText(first) }
            keyStatus.text = "Copied to 4 Jina fields — tap Apply keys to save"
            keyStatus.setTextColor(getColor(R.color.accent))
        }

        var settingsDialog: AlertDialog? = null

        applyBtn.setOnClickListener {
            val n = saveJinaKeys()
            val msg = if (n == 0) "Saved 0 keys · still keyless (20 RPM)"
            else "Applied $n/4 keys · refreshing…"
            keyStatus.text = "$msg"
            keyStatus.setTextColor(getColor(if (n == 0) R.color.text_secondary else R.color.arrived))
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            settingsDialog?.dismiss()
            settingsDialog = null
            refresh(silent = false)
        }

        layout.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.card_border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(16); bottomMargin = dp(8)
            }
        })

        layout.addView(TextView(this).apply {
            text = "GitHub backup"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })

        layout.addView(TextView(this).apply {
            text = "Repository (owner/name)"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        })
        val repoEt = EditText(this).apply {
            hint = GitHubBackup.DEFAULT_REPO
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs().getString(CollectionWorker.KEY_GH_REPO, GitHubBackup.DEFAULT_REPO))
            setSelection(text.length)
        }
        layout.addView(repoEt)

        layout.addView(TextView(this).apply {
            text = "Personal access token (repo scope)"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        })
        val tokenEt = EditText(this).apply {
            hint = "ghp_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs().getString(CollectionWorker.KEY_GH_TOKEN, ""))
            setSelection(text.length)
        }
        layout.addView(tokenEt)

        val ghButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val backupBtn = TextView(this).apply {
            text = "Backup now"
            setTextColor(getColor(R.color.departed))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        val restoreBtn = TextView(this).apply {
            text = "Restore"
            setTextColor(getColor(R.color.arrived))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        ghButtons.addView(backupBtn)
        ghButtons.addView(restoreBtn)
        layout.addView(ghButtons)

        val ghStatus = TextView(this).apply {
            text = ""
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        layout.addView(ghStatus)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setMessage("Save uses Jina keys + GitHub fields below. Apply keys saves only Jina keys and refreshes immediately.")
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                val editor = prefs().edit()
                for ((prefKey, et) in fields) editor.putString(prefKey, et.text.toString().trim())
                editor.putString(CollectionWorker.KEY_GH_REPO, repoEt.text.toString().trim())
                editor.putString(CollectionWorker.KEY_GH_TOKEN, tokenEt.text.toString().trim())
                editor.apply()
                updateQuotaLabel()
                refresh(silent = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        testBtn.setOnClickListener {
            testBtn.text = "Testing…"
            testBtn.isEnabled = false
            val entered = fields.mapValues { it.value.text.toString().trim() }
            lifecycleScope.launch {
                val sources = listOf(
                    Fr24Collector() to (entered[CollectionWorker.KEY_JINA_FR24] ?: ""),
                    FlightAwareCollector() to (entered[CollectionWorker.KEY_JINA_FA] ?: ""),
                    FlightStatsCollector() to (entered[CollectionWorker.KEY_JINA_FS] ?: ""),
                    PlaneFinderCollector() to (entered[CollectionWorker.KEY_JINA_PF] ?: ""),
                    AirLabsCollector() to (entered[CollectionWorker.KEY_AIRLABS] ?: "")
                )
                val lines = sources.map { "… ${it.first.label}" }
                testBtn.text = lines.joinToString("\n")
                val done = lines.toMutableList()
                for ((i, pair) in sources.withIndex()) {
                    val line = withContext(Dispatchers.IO) {
                        try { pair.first.test(pair.second) }
                        catch (e: Exception) { "${pair.first.label} FAIL: ${e.message}" }
                    }
                    done[i] = line
                    testBtn.text = done.joinToString("\n")
                }
                testBtn.isEnabled = true
            }
        }

        fun runGh(label: String, action: () -> String) {
            ghStatus.text = "$label…"
            val repo = repoEt.text.toString().trim()
            val token = tokenEt.text.toString().trim()
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try { action() } catch (e: Exception) { "Error: ${e.message}" }
                }
                ghStatus.text = msg
            }
        }

        backupBtn.setOnClickListener {
            val repo = repoEt.text.toString().trim().ifEmpty { GitHubBackup.DEFAULT_REPO }
            val token = tokenEt.text.toString().trim()
            runGh("Backing up") { GitHubBackup.backup(repo, token, db) }
        }
        restoreBtn.setOnClickListener {
            val repo = repoEt.text.toString().trim().ifEmpty { GitHubBackup.DEFAULT_REPO }
            val token = tokenEt.text.toString().trim()
            AlertDialog.Builder(this)
                .setMessage("Restore from GitHub? Existing rows with same flight+time are kept (merge).")
                .setPositiveButton("Restore") { _, _ ->
                    runGh("Restoring") { GitHubBackup.restore(repo, token, db) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val HISTORY_START = "2026-10-25"
    }
}
