package com.ruh.flight.live

/**
 * FR24 paints a flight's status badge by how late it is, not by whether it
 * happened. Measured on live RUH data: green median +10m (range -41..+15),
 * yellow median +25m (18..46), red median +123m (49..215). The same
 * thresholds reproduce FR24's own colours from the schedule times.
 */
object StatusColor {

    const val GREEN = "green"
    const val YELLOW = "yellow"
    const val RED = "red"
    const val GRAY = "gray"

    /** Delay severity colour for an actual movement. */
    fun of(scheduled: Long, real: Long, fallback: String = GRAY): String {
        if (real <= 0L) return fallback.ifBlank { GRAY }
        if (scheduled <= 0L) return fallback.ifBlank { GRAY }
        val delay = (real - scheduled) / 60L
        return when {
            delay <= 15L -> GREEN
            delay <= 45L -> YELLOW
            else -> RED
        }
    }

    /** One word for what the badge colour means. */
    fun meaning(color: String): String = when (color.lowercase()) {
        GREEN -> "OnTime"
        YELLOW -> "Delayed"
        RED -> "Severe"
        else -> "Unrated"
    }
}
