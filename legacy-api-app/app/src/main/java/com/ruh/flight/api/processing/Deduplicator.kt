package com.ruh.flight.api.processing

import com.ruh.flight.api.FlightRecord

object Deduplicator {
    fun deduplicate(flights: List<FlightRecord>): List<FlightRecord> {
        if (flights.isEmpty()) return emptyList()

        val groups = flights.groupBy { f ->
            listOf(f.direction, f.flightNumber.uppercase(), f.ruhLocalDate).joinToString("|")
        }

        val out = mutableListOf<FlightRecord>()
        for ((_, list) in groups) {
            val sorted = list.sortedBy { it.actualTimeUtc }
            val clusters = mutableListOf<MutableList<FlightRecord>>()
            for (f in sorted) {
                val last = clusters.lastOrNull()
                if (last != null && TimeUtil.withinTolerance(last.last().actualTimeUtc, f.actualTimeUtc, 10)) {
                    last.add(f)
                } else {
                    clusters.add(mutableListOf(f))
                }
            }
            for (c in clusters) out.add(mergeGroup(c))
        }
        return out.sortedBy { it.actualTimeUtc }
    }

    private fun mergeGroup(group: List<FlightRecord>): FlightRecord {
        if (group.size == 1) {
            val g = group[0]
            return if (g.sources.isBlank()) g.copy(sources = g.source) else g
        }
        val best = group.maxByOrNull { f ->
            f.evidence.length +
                (if (f.airline.isNotBlank()) 50 else 0) +
                (if (f.otherCity.isNotBlank()) 30 else 0) +
                (if (f.origin != "RUH" || f.destination != "RUH") 20 else 0)
        } ?: group[0]

        val airline = group.map { it.airline }.firstOrNull { it.isNotBlank() } ?: ""
        val city = group.map { it.otherCity }.firstOrNull { it.isNotBlank() } ?: ""
        val origin = if (best.direction == "departure") "RUH"
            else group.map { it.origin }.firstOrNull { it != "RUH" } ?: "RUH"
        val destination = if (best.direction == "arrival") "RUH"
            else group.map { it.destination }.firstOrNull { it != "RUH" } ?: "RUH"
        val evidence = group.map { it.evidence }.distinct().joinToString(" + ")
        val sources = group.flatMap { it.sourceList() }.distinct().joinToString(",")

        return best.copy(
            airline = airline,
            otherCity = city,
            origin = origin,
            destination = destination,
            evidence = evidence,
            sources = sources
        )
    }

    fun countByDirection(flights: List<FlightRecord>): Pair<Int, Int> {
        val dep = flights.count { isCountable(it) && it.direction == "departure" }
        val arr = flights.count { isCountable(it) && it.direction == "arrival" }
        return dep to arr
    }

    /** Only real, dated, non-cancelled Actual movements that already happened count. */
    fun isCountable(f: FlightRecord): Boolean =
        !f.cancelled &&
            f.actualEventLabel.startsWith("Actual") &&
            f.flightNumber.isNotBlank() &&
            f.ruhLocalDate.isNotBlank() &&
            f.actualTimeUtc.isNotBlank() &&
            TimeUtil.isPastOrNow(f.actualTimeUtc)
}
