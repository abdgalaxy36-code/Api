package com.ruh.flight.live

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The device clock is the single source of truth for what "today" means.
 * A day key is only ever derived from ZoneId.systemDefault() (Riyadh on the
 * target device), so a flight belongs to a day when its ACTUAL time falls
 * inside that local day — and it rolls into history the moment the day ends.
 */
object Clock {

    val zone: ZoneId
        get() = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone)

    fun todayKey(): String = today().toString()

    fun dayKey(epochSec: Long): String =
        if (epochSec <= 0L) ""
        else Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate().toString()

    fun isToday(epochSec: Long): Boolean =
        epochSec > 0L && dayKey(epochSec) == todayKey()

    /** "10 Nov" — short enough for a one-line report. */
    fun shortDate(date: String): String = try {
        LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    } catch (e: Exception) {
        date
    }
}
