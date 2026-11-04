package com.ruh.flight.api

data class FlightRecord(
    val id: Long = 0L,
    val source: String,
    val flightNumber: String,
    val direction: String,
    val origin: String,
    val destination: String,
    val actualTimeUtc: String,
    val ruhLocalDate: String,
    val actualEventLabel: String,
    val evidence: String,
    val cancelled: Boolean = false,
    val airline: String = "",
    val otherCity: String = "",
    /** Comma-separated source ids that confirmed this flight (e.g. "fr24,airlabs"). */
    val sources: String = ""
) {
    fun sourceList(): List<String> {
        val raw = sources.ifBlank { source }
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}

data class DayFetchResult(
    val flights: List<FlightRecord>,
    val pagesRead: Int,
    val recordsFound: Int,
    val source: String,
    val error: String? = null,
    /** Flight numbers this source reports as cancelled/diverted on the board (purge targets). */
    val cancelledFlightNumbers: Set<String> = emptySet()
)

data class SourceHealth(
    val source: String,
    val domain: String,
    val status: String,
    val lastError: String?,
    val confirmedCount: Int,
    val pagesRead: Int
)

data class DailyCount(
    val date: String,
    val fr24Dep: Int, val fr24Arr: Int,
    val faDep: Int, val faArr: Int,
    val fsDep: Int, val fsArr: Int,
    val pfDep: Int, val pfArr: Int,
    val combinedDep: Int, val combinedArr: Int, val combinedTotal: Int
)
