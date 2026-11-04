package com.ruh.flight.api.processing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * All times use the device system zone only (no hardcoded airport zone).
 * Board times without a date are resolved against device "now".
 */
object TimeUtil {
    val DEVICE: ZoneId get() = ZoneId.systemDefault()
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val hhmmFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun todayRiyadh(): String =
        LocalDate.now(DEVICE).format(fmt)

    fun daysAgoRiyadh(days: Int): String =
        LocalDate.now(DEVICE).minusDays(days.toLong()).format(fmt)

    fun riyadhDateRange(days: Int): List<String> =
        (0 until days).map { daysAgoRiyadh(it) }

    fun hhmmToUtcIso(dateStr: String, hhmm: String): String? {
        return try {
            val parts = hhmm.split(":")
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            val ld = LocalDate.parse(dateStr)
            ld.atTime(h, m).atZone(DEVICE).toInstant().toString()
        } catch (e: Exception) { null }
    }

    fun utcIsoToRiyadhDate(utcIso: String): String {
        return try {
            Instant.parse(utcIso).atZone(DEVICE).format(fmt)
        } catch (e: Exception) { todayRiyadh() }
    }

    fun utcIsoToHhmm(utcIso: String): String {
        return try {
            Instant.parse(utcIso).atZone(DEVICE).format(hhmmFmt)
        } catch (e: Exception) { "--:--" }
    }

    /**
     * FR24 (and similar) boards show only clock times. Pick the calendar day
     * (preferred / preferred-1 / preferred+1) whose local datetime is not in
     * the future, closest to device now — so yesterday 2:22 PM stays yesterday.
     */
    fun resolveEventUtcIso(preferredDate: String, hhmm: String): String? {
        return try {
            val parts = hhmm.split(":")
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            val pref = try {
                LocalDate.parse(preferredDate)
            } catch (_: Exception) {
                LocalDate.now(DEVICE)
            }
            val now = ZonedDateTime.now(DEVICE)
            val skew = now.plusMinutes(2)
            val candidates = listOf(pref, pref.minusDays(1), pref.plusDays(1))
                .map { d -> d.atTime(h, m).atZone(DEVICE) }
                .filter { !it.isAfter(skew) }
            if (candidates.isEmpty()) return null
            candidates.maxBy { it.toInstant() }.toInstant().toString()
        } catch (e: Exception) { null }
    }

    /** Actual events must already have happened on the device clock. */
    fun isPastOrNow(utcIso: String, slackMinutes: Int = 2): Boolean {
        return try {
            val t = Instant.parse(utcIso)
            !t.isAfter(ZonedDateTime.now(DEVICE).plusMinutes(slackMinutes.toLong()).toInstant())
        } catch (_: Exception) { false }
    }

    fun withinTolerance(a: String, b: String, minutes: Int = 10): Boolean {
        return try {
            val ia = Instant.parse(a)
            val ib = Instant.parse(b)
            val diff = Math.abs(ia.epochSecond - ib.epochSecond) / 60
            diff <= minutes
        } catch (e: Exception) { false }
    }
}
