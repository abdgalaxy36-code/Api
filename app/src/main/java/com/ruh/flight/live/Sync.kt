package com.ruh.flight.live

import android.content.Context
import android.util.Log

/**
 * The screen's refresh, without a screen.
 *
 * Fired by the background alarm so today's count keeps moving while the phone
 * is asleep — otherwise the numbers only ever change when someone opens the
 * app. Same rules as MainActivity: only real movements, written with a merge
 * so nothing collected earlier is thrown away.
 */
object Sync {

    fun refresh(context: Context) {
        val stats = StatsStore(context)
        val todayStr = Clock.todayKey()
        val midnight = Fr24Api.todayMidnightEpoch()

        val (dep, arr) = Fr24Api.fetchBoth(midnight)
        if (dep.error != null || arr.error != null) {
            Log.i(Fr24Api.TAG, "bg refresh skipped: ${dep.error ?: arr.error}")
            return
        }

        val depToday = Fr24Api.todayOnly(dep.flights)
        val arrToday = Fr24Api.todayOnly(arr.flights)
        if (depToday.isEmpty() && arrToday.isEmpty() && stats.get(todayStr) == null) {
            Log.i(Fr24Api.TAG, "bg refresh: nothing has moved yet")
            return
        }

        stats.mergeDayFlights(todayStr, depToday + arrToday)
        stats.recountDay(todayStr)
        stats.setCancelled(
            todayStr,
            (dep.cancelled[todayStr] ?: 0) + (arr.cancelled[todayStr] ?: 0)
        )
        val day = stats.get(todayStr)
        Log.i(
            Fr24Api.TAG,
            "bg refresh D=${day?.departures ?: 0} A=${day?.arrivals ?: 0} " +
                "T=${day?.total ?: 0} C=${day?.cancelled ?: 0} " +
                "pages=${dep.pagesRead}/${arr.pagesRead}"
        )
    }
}
