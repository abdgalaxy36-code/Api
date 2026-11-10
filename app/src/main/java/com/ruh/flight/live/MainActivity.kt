package com.ruh.flight.live

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ruh.flight.live.databinding.ActivityMainBinding
import com.ruh.flight.live.databinding.ItemDayBinding
import com.ruh.flight.live.databinding.ItemFlightBinding
import com.ruh.flight.live.databinding.ItemStatBinding
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Single page, top to bottom:
 *   TODAY      total, then a departures square and an arrivals square,
 *              with the last-updated stamp underneath
 *   STATUS     always open, never behind a tap
 *   BEST DAYS  numbers only, shown against today's total
 *   HISTORY    one row per day; tap a day to open that day's flights
 *
 * Every flight row anywhere on the page opens its FR24 page on tap.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var stats: StatsStore
    private val handler = Handler(Looper.getMainLooper())

    private var fetching = false
    private var resumed = false
    private var lastSuccess = 0L
    private var lastAttempt = 0L
    private var liveDep = 0
    private var liveArr = 0
    private var lastErrorToast = ""
    private var lastErrorToastAt = 0L

    private var depCache: List<LiveFlight> = emptyList()
    private var arrCache: List<LiveFlight> = emptyList()

    /** History day currently tapped open, if any. */
    private var openDay: String? = null

    /** Which TODAY square is open: "dep", "arr" or null. */
    private var openDir: String? = null

    private val stampFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(Clock.zone)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM")

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            handler.postDelayed(this, refreshMs())
        }
    }

    /** Auto-refresh interval from Settings (default 5 min). */
    private fun refreshMs(): Long =
        if (AppPrefs.autoRefresh(this)) AppPrefs.interval(this) else Long.MAX_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        stats = StatsStore(this)
        stats.seedFromAssets(this)
        // First launch after the full-day repair update: let every day have
        // one more pass so the hours lost while the phone slept come back.
        AppPrefs.resetCatchUpIfOutdated(this)
        Fr24Api.quotaContext = applicationContext
        Notifier.ensureChannels(this)
        Notifier.scheduleAll(this)

        b.swipe.setOnRefreshListener { refresh() }
        b.refreshBtn.setOnClickListener { refresh() }
        b.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.status.text = getString(R.string.loading)
        b.status.visibility = View.VISIBLE

        // Paint from the database straight away — the network fetch can take
        // a while (or fail), but the stored days are already here.
        render()

        // Prove the stored data is real-time only, once per launch.
        thread(name = "verify") {
            Log.i(
                Fr24Api.TAG,
                "data check:\n" + stats.verify().joinToString("\n") { it.text }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(tick)
        if (AppPrefs.autoRefresh(this)) handler.postDelayed(tick, refreshMs())
        // Re-render from what we already have; only hit the network when the
        // data on screen is older than one poll interval.
        render()
        val age = System.currentTimeMillis() - lastAttempt
        Log.i(Fr24Api.TAG, "resume age=${age}ms stale=${STALE_MS}ms -> ${if (age >= STALE_MS) "REFRESH" else "keep"}")
        if (age >= STALE_MS) refresh()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    /** Show a fetch error once, not on every poll. */
    private fun errorToast(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastErrorToast && now - lastErrorToastAt < TOAST_COOLDOWN_MS) return
        lastErrorToast = msg
        lastErrorToastAt = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * FR24 refuses a timestamp at a past midnight, but a noon timestamp still
     * works and its 24 h window covers the previous evening. This runs once,
     * right after midnight, and merges whatever it finds into yesterday.
     */
    /**
     * The day that just ended, fetched in full.
     *
     * FR24's schedule window only reaches back to yesterday, so this is the
     * one chance to repair a day the phone slept through — and it has to start
     * at 00:00, not noon, or every morning movement is lost forever.
     *
     * Returns the flights that were scheduled yesterday but actually moved
     * after midnight: those belong to today's count.
     */
    private fun repairPreviousDay(): List<LiveFlight> {
        val todayStr = Clock.todayKey()
        val yDate = Clock.today().minusDays(1)
        val yStr = yDate.toString()
        if (AppPrefs.catchUpDone(this, yStr)) return emptyList()

        val dayStart = yDate.atStartOfDay(Clock.zone).toEpochSecond()
        val dep = Fr24Api.fetch("departures", dayStart)
        val arr = Fr24Api.fetch("arrivals", dayStart)
        // Both sides have to come back: a half day is what lost the mornings.
        if (dep.error != null || arr.error != null) {
            Log.i(Fr24Api.TAG, "repair $yStr deferred: ${dep.error ?: arr.error}")
            return emptyList()
        }
        AppPrefs.setCatchUpDone(this, yStr)

        val depY = dep.flights.filter { Clock.dayKey(it.realEpoch) == yStr }
        val arrY = arr.flights.filter { Clock.dayKey(it.realEpoch) == yStr }
        if (depY.isNotEmpty() || arrY.isNotEmpty()) {
            stats.mergeDayFlights(yStr, depY + arrY)
            stats.recountDay(yStr)
        }
        // never got a real time but its schedule has passed -> cancelled
        stats.setCancelled(yStr, (dep.cancelled[yStr] ?: 0) + (arr.cancelled[yStr] ?: 0))
        // scheduled yesterday, moved after midnight -> counted today
        val overnight = (dep.flights + arr.flights)
            .filter { Clock.dayKey(it.realEpoch) == todayStr }
            .distinctBy { it.dedupKey() }
        Log.i(
            Fr24Api.TAG,
            "repair $yStr: +${depY.size} dep +${arrY.size} arr (+${overnight.size} into today)"
        )
        // if the phone slept through midnight, the summary never fired
        if (!AppPrefs.summaryPosted(this, yStr)) {
            val day = stats.get(yStr)
            if (day != null) {
                Notifier.postDaySummary(this, yStr, day.departures, day.arrivals, day.total)
                AppPrefs.setSummaryPosted(this, yStr, true)
            }
        }
        return overnight
    }

    /** Loading bar + live counter text while a refresh is running. */
    private fun showRefreshing(pct: Int, stage: String, dep: Int = 0, arr: Int = 0) {
        if (dep > 0) liveDep = dep
        if (arr > 0) liveArr = arr
        b.refreshBar.visibility = View.VISIBLE
        b.refreshBar.progress = if (pct > 0) pct else 2
        b.refreshStatus.visibility = View.VISIBLE
        b.refreshStatus.setTextColor(Color.parseColor("#8B949E"))
        val counts = if (dep > 0 || arr > 0) "   D $dep   A $arr" else ""
        b.refreshStatus.text = "$stage   $pct%$counts"
    }

    /** Keep the bar and the message on screen when the fetch could not finish. */
    private fun failRefreshing(msg: String) {
        b.refreshBar.visibility = View.VISIBLE
        b.refreshBar.progress = 100
        b.refreshStatus.visibility = View.VISIBLE
        b.refreshStatus.setTextColor(Color.parseColor("#F57C00"))
        b.refreshStatus.text = "$msg   retrying…"
    }

    private fun refresh() {
        if (fetching) return
        fetching = true
        lastAttempt = System.currentTimeMillis()
        liveDep = 0
        liveArr = 0
        showRefreshing(0, "connecting…")
        render()
        // timestamp = local midnight, NOT now — the API window starts there,
        // so using "now" would hide every flight that already departed.
        val midnight = Fr24Api.todayMidnightEpoch()
        thread(name = "fr24-fetch") {
            // Recover yesterday in full first — FR24's window only reaches
            // back one day, so this is the only chance to repair a day the
            // phone slept through.
            val overnight = repairPreviousDay()

            val (dep, arr) = Fr24Api.fetchBoth(midnight) { stage, pct, depN, arrN ->
                runOnUiThread { showRefreshing(pct, stage, depN, arrN) }
            }
            // Only real movements count: parse() already drops anything without
            // an actual time, and todayOnly() drops other days in the 3-day window.
            // Overnight movers are scheduled yesterday, so today's own window
            // never sees them — they come from the repair above.
            val depToday = (Fr24Api.todayOnly(dep.flights) + overnight.filter { it.direction == "dep" })
                .distinctBy { it.dedupKey() }
            val arrToday = (Fr24Api.todayOnly(arr.flights) + overnight.filter { it.direction == "arr" })
                .distinctBy { it.dedupKey() }
            runOnUiThread {
                fetching = false

                val err = dep.error ?: arr.error
                val via = dep.via.ifBlank { arr.via }
                Log.i(
                    Fr24Api.TAG,
                    "refresh dep=${depToday.size} arr=${arrToday.size} " +
                        "pages=${dep.pagesRead}/${arr.pagesRead} via=$via err=$err midnight=$midnight"
                )

                // Keep the last good list when a side failed, so the screen
                // never drops to zero on a transient error.
                if (dep.error == null) depCache = depToday
                if (arr.error == null) arrCache = arrToday

                // A failed side would silently undercount the day, so only commit
                // when the whole picture came back clean.
                if (dep.error == null && arr.error == null) {
                    val todayStr = Clock.todayKey()
                    // Merge, never replace: rows already collected (overnight
                    // movers, the hourly background pass, a fix from Settings)
                    // must survive the next refresh — and the count is always
                    // read back from the rows, so TODAY and HISTORY can never
                    // show two different numbers.
                    stats.mergeDayFlights(todayStr, depToday + arrToday)
                    stats.recountDay(todayStr)
                    stats.setCancelled(
                        todayStr,
                        (dep.cancelled[todayStr] ?: 0) + (arr.cancelled[todayStr] ?: 0)
                    )
                    val stored = stats.get(todayStr)
                    liveDep = stored?.departures ?: depToday.size
                    liveArr = stored?.arrivals ?: arrToday.size
                    val total = stored?.total ?: (depToday.size + arrToday.size)
                    // the record, counted over every other day
                    val prevBest = stats.all()
                        .filter { it.date != todayStr }
                        .maxOfOrNull { it.total } ?: 0
                    if (total > prevBest && AppPrefs.notifiedBestDay(this) != todayStr) {
                        AppPrefs.setNotifiedBestDay(this, todayStr)
                        Notifier.postBestDay(this, todayStr, total, prevBest)
                    }
                    lastSuccess = System.currentTimeMillis()
                    b.updatedAt.text = stampFmt.format(Instant.now())
                    b.updatedAt.setTextColor(Color.parseColor("#9AA5B1"))
                } else {
                    liveDep = 0
                    liveArr = 0
                    b.updatedAt.text = "retrying…"
                    b.updatedAt.setTextColor(Color.parseColor("#F57C00"))
                }

                b.swipe.isRefreshing = false
                if (err != null) {
                    failRefreshing(err)
                    errorToast(err)
                } else {
                    b.refreshBar.visibility = View.GONE
                    b.refreshStatus.visibility = View.GONE
                }
                render()
            }
        }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val body = b.pageBody
        body.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val days = stats.all()
        val todayStr = Clock.todayKey()
        val today = days.find { it.date == todayStr }
        // The card reads the stored rows too — same rows as the list below it,
        // so "12 flights" and "7 arrived / 5 departed" are always one number.
        val depShown = today?.departures ?: 0
        val arrShown = today?.arrivals ?: 0
        val todayTotal = today?.total ?: 0
        Log.i(Fr24Api.TAG, "render days=${days.size} today=$todayStr total=$todayTotal")

        if (days.isEmpty() && depCache.isEmpty() && arrCache.isEmpty()) {
            b.status.visibility = View.VISIBLE
            b.status.text = if (lastSuccess == 0L) getString(R.string.loading)
            else getString(R.string.no_stats)
        } else {
            b.status.visibility = View.GONE
        }

        // ---- 1. TODAY: total on top, one square per direction ------------
        val card = inflater.inflate(R.layout.item_stat_today, body, false)
        card.findViewById<TextView>(R.id.todayLabel).text =
            "TODAY · " + Clock.today().format(dayFmt).uppercase()
        card.findViewById<TextView>(R.id.todayTotal).text = todayTotal.toString()
        card.findViewById<TextView>(R.id.sqDepartures).text = depShown.toString()
        card.findViewById<TextView>(R.id.sqArrivals).text = arrShown.toString()
        card.findViewById<TextView>(R.id.sqCancelled).text = (today?.cancelled ?: 0).toString()

        // tapping a square opens that direction's full list for today
        card.findViewById<TextView>(R.id.sqDeparturesHint).text =
            if (openDir == "dep") "▾" else "▸"
        card.findViewById<TextView>(R.id.sqArrivalsHint).text =
            if (openDir == "arr") "▾" else "▸"
        card.findViewById<View>(R.id.sqDeparturesBox).setOnClickListener {
            openDir = if (openDir == "dep") null else "dep"
            render()
        }
        card.findViewById<View>(R.id.sqArrivalsBox).setOnClickListener {
            openDir = if (openDir == "arr") null else "arr"
            render()
        }
        body.addView(card)

        body.addView(TextView(this).apply {
            text = if (lastSuccess != 0L) {
                val stamp = b.updatedAt.text
                if (todayTotal == 0) "Last updated $stamp  ·  no movements yet today"
                else "Last updated $stamp"
            } else "Waiting for data"
            setTextColor(Color.parseColor("#6B7785"))
            textSize = 11f
            setPadding(dp(8), dp(7), dp(8), dp(2))
        })

        // the list opened from a square sits right under the stamp
        val dir = openDir
        if (dir != null) {
            // Same single source as the counters: the stored rows (they hold
            // the overnight movers too — the live cache only sees the flights
            // from today's own window). Cache only as a pre-first-write fallback.
            var todayFlights = stats.flightsFor(todayStr)
                .filter { it.direction == dir }
                .map { it.toLiveFlight() }
                .sortedByDescending { it.realEpoch }
            if (todayFlights.isEmpty()) {
                val live = if (dir == "dep") depCache else arrCache
                todayFlights = live
                    .filter { Clock.isToday(it.realEpoch) }
                    .distinctBy { it.dedupKey() }
                    .sortedByDescending { it.realEpoch }
            }
            val title = if (dir == "dep") getString(R.string.departures)
            else getString(R.string.arrivals)
            body.addView(sectionLabel("${title.uppercase()} · ${todayFlights.size}"))
            if (todayFlights.isEmpty()) body.addView(emptyNote(getString(R.string.no_flights)))
            else todayFlights.forEachIndexed { i, f -> flightRow(body, f, i + 1) }
        }

        // ---- 2. BEST DAYS: today against the single best day -------------
        body.addView(sectionLabel(getString(R.string.section_best_days)))
        body.addView(
            statRow(
                inflater, body,
                today ?: DayStat(todayStr, 0, 0, 0, 0),
                null, "Today"
            )
        )
        val best = days.filter { it.date != todayStr }.maxByOrNull { it.total }
        if (best != null) body.addView(statRow(inflater, body, best, "#1"))

        // ---- 3. HISTORY: tap a day to open its flights -------------------
        body.addView(
            sectionLabel("${getString(R.string.section_history)} · ${days.size}")
        )
        if (days.isEmpty()) {
            body.addView(emptyNote(getString(R.string.no_stats)))
        }
        days.forEach { d ->
            body.addView(dayRow(inflater, body, d))
            if (openDay == d.date) dayFlights(body, d.date, todayStr)
        }
    }

    // ---------------------------------------------------------------- sections

    private fun sectionLabel(text: String): View =
        LayoutInflater.from(this).inflate(R.layout.item_label, null, false).apply {
            findViewById<TextView>(R.id.labelText).text = text
        }

    private fun dayRow(inflater: LayoutInflater, parent: LinearLayout, d: DayStat): View {
        val vb = ItemDayBinding.inflate(inflater, parent, false)
        val open = openDay == d.date
        vb.dayDate.text = try {
            LocalDate.parse(d.date).format(dayFmt)
        } catch (e: Exception) {
            d.date
        }
        vb.dayBreak.text = buildString {
            append("${d.departures} departures · ${d.arrivals} arrivals")
            append(" · ${d.cancelled} cancelled")
        }
        vb.dayTotal.text = d.total.toString()
        vb.dayChevron.text = if (open) "▾" else "▸"
        vb.dayRow.setOnClickListener {
            openDay = if (open) null else d.date
            render()
        }
        return vb.root
    }

    private fun dayFlights(body: LinearLayout, date: String, todayStr: String) {
        // One source of truth: the stored rows — exactly the set the D/A/total
        // counters are counted over, so the list can never disagree with them.
        // The live cache is only a fallback before the day's first write.
        var flights: List<LiveFlight> = stats.flightsFor(date).map { it.toLiveFlight() }
        if (flights.isEmpty() && date == todayStr) {
            flights = (depCache + arrCache)
                .filter { Clock.isToday(it.realEpoch) }
                .distinctBy { it.dedupKey() }
                .sortedByDescending { it.realEpoch }
        }

        if (flights.isEmpty()) {
            body.addView(emptyNote(getString(R.string.no_flights)))
            return
        }
        body.addView(TextView(this).apply {
            text = "${flights.size} flights  ·  tap one to open FR24"
            setTextColor(Color.parseColor("#6B7785"))
            textSize = 11f
            setPadding(dp(10), dp(2), dp(10), dp(4))
        })
        flights.forEachIndexed { i, f -> flightRow(body, f, i + 1) }
    }

    // -------------------------------------------------------------------- rows

    private fun statRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        d: DayStat,
        rank: String?,
        labelOverride: String? = null
    ): View {
        val vb = ItemStatBinding.inflate(inflater, parent, false)
        val label = labelOverride ?: try {
            LocalDate.parse(d.date).format(dayFmt)
        } catch (e: Exception) {
            d.date
        }
        vb.statDate.text = if (rank == null) label else "$rank   $label"
        vb.statDep.text = "${d.departures} D"
        vb.statArr.text = "${d.arrivals} A"
        if (d.cancelled > 0) {
            vb.statCancelled.visibility = View.VISIBLE
            vb.statCancelled.text = "${d.cancelled} C"
        } else {
            vb.statCancelled.visibility = View.GONE
        }
        vb.statTotal.text = d.total.toString()
        return vb.root
    }

    private fun flightRow(parent: LinearLayout, f: LiveFlight, index: Int = 0) {
        val vb = ItemFlightBinding.inflate(LayoutInflater.from(this), parent, false)
        FlightAdapter.bindRow(vb, f, { openFlight(it) }, index)
        parent.addView(vb.root)
    }

    private fun emptyNote(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#6B7785"))
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }

    // ------------------------------------------------------------------- misc

    private fun openFlight(f: LiveFlight) {
        val url = f.flightUrl()
        Log.i(Fr24Api.TAG, "open $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(Fr24Api.TAG, "open failed: ${e.message}")
            Toast.makeText(this, "Could not open $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TOAST_COOLDOWN_MS = 300_000L
        private const val STALE_MS = 120_000L
        private const val BEST_LIMIT = 5
    }
}
