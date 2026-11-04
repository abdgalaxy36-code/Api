package com.ruh.flight.live

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The app's notifications. Each kind has its own channel and its own switch
 * in Settings. All of them are quiet, numbers only.
 */
object Notifier {

    const val TAG = "Fr24Live"

    const val CH_BEST = "ruh_best_day"
    const val CH_SUMMARY = "ruh_day_summary"
    const val CH_PROGRESS = "ruh_today_progress"
    const val CH_BACKUP = "ruh_backup_reminder"

    const val ID_BEST = 101
    const val ID_SUMMARY = 102
    const val ID_PROGRESS = 103
    const val ID_BACKUP = 104

    const val PREFS = "ruh_notifications"
    const val K_BEST = "best"
    const val K_SUMMARY = "summary"
    const val K_PROGRESS = "progress"
    const val K_BACKUP = "backup"

    // ----------------------------------------------------------- switches

    fun enabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, true)

    fun setEnabled(context: Context, key: String, on: Boolean) {
        prefs(context).edit().putBoolean(key, on).apply()
    }

    // ------------------------------------------------------------ channels

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_BEST, "New best day", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Fires when a day beats the previous record" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_SUMMARY, "Day summary", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "The finished day's totals, after midnight" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_PROGRESS, "Today's count", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "How many movements today so far" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_BACKUP, "Backup reminder", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Reminds you to back up the flights data" }
        )
    }

    // --------------------------------------------------------------- posts

    /** A day just beat the record. Numbers only. */
    fun postBestDay(context: Context, date: String, total: Int, oldBest: Int) {
        if (!enabled(context, K_BEST)) return
        val text = if (oldBest > 0) "$total  (was $oldBest)" else "$total"
        post(
            context, CH_BEST, ID_BEST,
            "New best day", "$date   $total",
            "Total  $text"
        )
    }

    /** The day that just ended. Numbers only. */
    fun postDaySummary(context: Context, date: String, dep: Int, arr: Int, total: Int) {
        if (!enabled(context, K_SUMMARY)) return
        post(
            context, CH_SUMMARY, ID_SUMMARY,
            "Day summary", "$date   $total",
            "Departures  $dep\nArrivals  $arr\nTotal  $total"
        )
    }

    /** How today is going. */
    fun postProgress(context: Context, dep: Int, arr: Int, total: Int) {
        if (!enabled(context, K_PROGRESS)) return
        post(
            context, CH_PROGRESS, ID_PROGRESS,
            "Today so far", "$dep  ·  $arr  ·  $total",
            "Departures  $dep\nArrivals  $arr\nTotal  $total"
        )
    }

    /** No backup for a while. */
    fun postBackupReminder(context: Context, daysAgo: Int) {
        if (!enabled(context, K_BACKUP)) return
        post(
            context, CH_BACKUP, ID_BACKUP,
            "Backup due", "Last backup  $daysAgo day(s) ago",
            "Open Settings to back up the flights data"
        )
    }

    private fun post(
        context: Context, channel: String, id: Int,
        title: String, big: String, body: String
    ) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body.replace("\n", "   "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#D29922"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
            val flat = body.replace("\n", " / ")
            Log.i(TAG, "notification $title: $flat")
        } catch (e: Exception) {
            // POST_NOTIFICATIONS not granted on Android 13+
            Log.w(TAG, "notification blocked: ${e.message}")
        }
    }

    // ------------------------------------------------------------- alarms

    /** Re-arm the scheduled notifications (midnight summary, progress, backup). */
    fun scheduleAll(context: Context) {
        scheduleSummary(context)
        scheduleProgress(context)
        scheduleBackup(context)
    }

    /** Fires just after midnight with the finished day's numbers. */
    fun scheduleSummary(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = nextMidnight()
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, next,
            PendingIntent.getBroadcast(
                context, 10,
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_SUMMARY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        Log.i(TAG, "summary alarm at $next")
    }

    /** Today's count, every 3 hours. */
    fun scheduleProgress(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_PROGRESS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 3 * 60 * 60 * 1000L,
            pi
        )
    }

    /** Reminds to back up once a week. */
    fun scheduleBackup(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 12,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_BACKUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L,
            pi
        )
    }

    /** Next 00:02 local, so the day's totals are settled. */
    fun nextMidnight(): Long {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        var next = now.toLocalDate().plusDays(1).atTime(0, 2)
        if (next.isBefore(now)) next = next.plusDays(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    const val ACTION_SUMMARY = "com.ruh.flight.live.SUMMARY"
    const val ACTION_PROGRESS = "com.ruh.flight.live.PROGRESS"
    const val ACTION_BACKUP = "com.ruh.flight.live.BACKUP"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Receives the scheduled alarms. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Notifier.ACTION_SUMMARY -> {
                // the day that ended is yesterday
                val y = java.time.LocalDate.now(Clock.zone).minusDays(1).toString()
                val stats = StatsStore(ctx)
                val day = stats.get(y)
                if (day != null) {
                    Notifier.postDaySummary(ctx, y, day.departures, day.arrivals, day.total)
                    AppPrefs.setSummaryPosted(ctx, y, true)
                } else {
                    Log.i(Notifier.TAG, "summary: no row for $y")
                }
                Notifier.scheduleSummary(ctx)
            }
            Notifier.ACTION_PROGRESS -> {
                val today = Clock.todayKey()
                val stats = StatsStore(ctx)
                val day = stats.get(today)
                if (day != null) {
                    Notifier.postProgress(ctx, day.departures, day.arrivals, day.total)
                }
                Notifier.scheduleProgress(ctx)
            }
            Notifier.ACTION_BACKUP -> {
                val days = BackupState.daysSinceLastBackup(ctx)
                if (days >= 7) Notifier.postBackupReminder(ctx, days)
                Notifier.scheduleBackup(ctx)
            }
        }
    }
}

/** Keeps the alarms alive after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Notifier.ensureChannels(context.applicationContext)
            Notifier.scheduleAll(context.applicationContext)
            Log.i(Notifier.TAG, "alarms re-armed after boot")
        }
    }
}
