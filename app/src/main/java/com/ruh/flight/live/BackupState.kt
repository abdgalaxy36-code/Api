package com.ruh.flight.live

import android.content.Context

/** When the flights data was last pushed to GitHub, and how it went. */
object BackupState {

    private const val PREFS = "ruh_backup_state"
    private const val KEY_LAST = "last_backup_at"
    private const val KEY_OK = "last_backup_ok"
    private const val KEY_MSG = "last_backup_msg"

    fun markBackup(context: Context, ok: Boolean, message: String) {
        prefs(context).edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putBoolean(KEY_OK, ok)
            .putString(KEY_MSG, message)
            .apply()
    }

    fun lastBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)

    fun lastOk(context: Context): Boolean = prefs(context).getBoolean(KEY_OK, false)

    fun lastMessage(context: Context): String = prefs(context).getString(KEY_MSG, "") ?: ""

    /** -1 when there has never been a backup. */
    fun daysSinceLastBackup(context: Context): Int {
        val at = lastBackupAt(context)
        if (at <= 0L) return -1
        val days = ((System.currentTimeMillis() - at) / (24 * 60 * 60 * 1000L)).toInt()
        return if (days < 0) 0 else days
    }

    /** "04 Nov 2026 21:40" in the device timezone. */
    fun formatAt(context: Context): String {
        val at = lastBackupAt(context)
        if (at <= 0L) return "never"
        val fmt = java.time.format.DateTimeFormatter
            .ofPattern("dd MMM yyyy  HH:mm")
            .withZone(Clock.zone)
        return fmt.format(java.time.Instant.ofEpochMilli(at))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
