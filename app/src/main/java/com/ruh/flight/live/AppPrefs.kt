package com.ruh.flight.live

import android.content.Context
import android.util.Log

/** App-level switches that are not about the network or the backup. */
object AppPrefs {

    private const val PREFS = "ruh_app"
    private const val K_AUTO = "auto_refresh"
    private const val K_INTERVAL = "interval_ms"
    private const val K_BEST_DAY = "notified_best_day"
    private const val K_SUMMARY = "summary_posted_"
    private const val K_CATCHUP = "catchup_done_"
    private const val K_CATCHUP_VER = "catchup_ver"
    private const val CATCHUP_VER = 2

    /** Offered auto-refresh intervals. */
    val INTERVALS = listOf(
        60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L
    )

    const val DEFAULT_INTERVAL = 300_000L

    fun label(ms: Long): String = when {
        ms % 60_000L == 0L -> "${ms / 60_000L} min"
        else -> "${ms / 1000L} s"
    }

    fun interval(context: Context): Long =
        prefs(context).getLong(K_INTERVAL, DEFAULT_INTERVAL)

    fun setInterval(context: Context, ms: Long) {
        prefs(context).edit().putLong(K_INTERVAL, ms).apply()
    }

    fun autoRefresh(context: Context): Boolean =
        prefs(context).getBoolean(K_AUTO, true)

    fun setAutoRefresh(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_AUTO, on).apply()
    }

    /** The day we last announced as the new record. */
    fun notifiedBestDay(context: Context): String =
        prefs(context).getString(K_BEST_DAY, "") ?: ""

    fun setNotifiedBestDay(context: Context, date: String) {
        prefs(context).edit().putString(K_BEST_DAY, date).apply()
    }

    /** Has the midnight summary for that day already gone out? */
    fun summaryPosted(context: Context, date: String): Boolean =
        prefs(context).getBoolean(K_SUMMARY + date, false)

    fun setSummaryPosted(context: Context, date: String, on: Boolean) {
        prefs(context).edit().putBoolean(K_SUMMARY + date, on).apply()
    }

    /** The rollover repair for that day has already run. */
    fun catchUpDone(context: Context, date: String): Boolean =
        prefs(context).getBoolean(K_CATCHUP + date, false)

    fun setCatchUpDone(context: Context, date: String) {
        prefs(context).edit().putBoolean(K_CATCHUP + date, true).apply()
    }

    /**
     * v1 only recovered the second half of a day the phone slept through;
     * v2 fetches it in full. Run this once after the update so the days that
     * were stored short get one more pass.
     */
    fun resetCatchUpIfOutdated(context: Context) {
        val p = prefs(context)
        if (p.getInt(K_CATCHUP_VER, 1) >= CATCHUP_VER) return
        clearCatchUp(context)
        p.edit().putInt(K_CATCHUP_VER, CATCHUP_VER).apply()
        Log.i("Fr24Live", "catch-up flags reset for a full-day repair pass")
    }

    /**
     * Rows that arrived from a restored file were not collected here, so the
     * repair is allowed to run again over them.
     */
    fun clearCatchUp(context: Context) {
        val p = prefs(context)
        val e = p.edit()
        p.all.keys.filter { it.startsWith(K_CATCHUP) }.forEach { e.remove(it) }
        e.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
