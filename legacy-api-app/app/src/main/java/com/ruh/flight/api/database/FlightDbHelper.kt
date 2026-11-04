package com.ruh.flight.api.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ruh.flight.api.DailyCount
import com.ruh.flight.api.FlightRecord
import com.ruh.flight.api.SourceHealth
import com.ruh.flight.api.processing.Deduplicator
import com.ruh.flight.api.processing.TimeUtil

class FlightDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        const val DB_NAME = "ruh_flight_api.db"
        const val DB_VERSION = 3
        const val T_MOVEMENTS = "confirmed_movements"
        const val T_COUNTS = "daily_counts"
        const val T_HEALTH = "source_health"
        const val T_RUNS = "collection_runs"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $T_MOVEMENTS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source TEXT NOT NULL,
            flight_number TEXT NOT NULL,
            direction TEXT NOT NULL,
            origin TEXT NOT NULL,
            destination TEXT NOT NULL,
            actual_time_utc TEXT NOT NULL,
            ruh_local_date TEXT NOT NULL,
            actual_event_label TEXT NOT NULL,
            evidence TEXT NOT NULL,
            cancelled INTEGER NOT NULL DEFAULT 0,
            airline TEXT NOT NULL DEFAULT '',
            other_city TEXT NOT NULL DEFAULT '',
            sources TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            UNIQUE(source, flight_number, direction, actual_time_utc)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mov_date ON $T_MOVEMENTS(ruh_local_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mov_src ON $T_MOVEMENTS(source)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS $T_COUNTS (
            date TEXT PRIMARY KEY,
            fr24_dep INTEGER DEFAULT 0, fr24_arr INTEGER DEFAULT 0,
            fa_dep INTEGER DEFAULT 0, fa_arr INTEGER DEFAULT 0,
            fs_dep INTEGER DEFAULT 0, fs_arr INTEGER DEFAULT 0,
            pf_dep INTEGER DEFAULT 0, pf_arr INTEGER DEFAULT 0,
            combined_dep INTEGER DEFAULT 0, combined_arr INTEGER DEFAULT 0,
            combined_total INTEGER DEFAULT 0,
            updated_at TEXT NOT NULL DEFAULT (datetime('now'))
        )""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS $T_HEALTH (
            source TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            last_error TEXT,
            confirmed_count INTEGER DEFAULT 0,
            pages_read INTEGER DEFAULT 0,
            updated_at TEXT NOT NULL DEFAULT (datetime('now'))
        )""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS $T_RUNS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at TEXT NOT NULL,
            finished_at TEXT,
            status TEXT NOT NULL,
            total_confirmed INTEGER DEFAULT 0,
            error_message TEXT
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            try { db.execSQL("ALTER TABLE $T_MOVEMENTS ADD COLUMN airline TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $T_MOVEMENTS ADD COLUMN other_city TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        }
        if (old < 3) {
            try { db.execSQL("ALTER TABLE $T_MOVEMENTS ADD COLUMN sources TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        }
    }

    /**
     * Insert only real Actual movements. Same flight from another source merges
     * into the existing row (sources + evidence) instead of double-counting.
     * Date is always derived from UTC so history days never mix.
     */
    fun insertMovements(flights: List<FlightRecord>): Int {
        if (flights.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        var merged = 0
        db.beginTransaction()
        try {
            for (raw in flights) {
                if (!Deduplicator.isCountable(raw)) continue
                val utc = raw.actualTimeUtc
                val trueDate = TimeUtil.utcIsoToRiyadhDate(utc)
                if (trueDate.isBlank()) continue
                val f = raw.copy(
                    ruhLocalDate = trueDate,
                    flightNumber = raw.flightNumber.uppercase().trim(),
                    sources = raw.sourceList().joinToString(","),
                    cancelled = false
                )

                val existing = findDuplicate(db, f)
                if (existing != null) {
                    mergeInto(db, existing.first, existing.second, f)
                    merged++
                    continue
                }

                val cv = ContentValues().apply {
                    put("source", f.source)
                    put("flight_number", f.flightNumber)
                    put("direction", f.direction)
                    put("origin", f.origin)
                    put("destination", f.destination)
                    put("actual_time_utc", f.actualTimeUtc)
                    put("ruh_local_date", f.ruhLocalDate)
                    put("actual_event_label", f.actualEventLabel)
                    put("evidence", f.evidence)
                    put("cancelled", 0)
                    put("airline", f.airline)
                    put("other_city", f.otherCity)
                    put("sources", f.sources)
                }
                val rowId = db.insertWithOnConflict(T_MOVEMENTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (rowId != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted + merged
    }

    private fun findDuplicate(db: SQLiteDatabase, f: FlightRecord): Pair<Long, FlightRecord>? {
        val c = db.query(
            T_MOVEMENTS, null,
            "ruh_local_date=? AND upper(flight_number)=? AND direction=?",
            arrayOf(f.ruhLocalDate, f.flightNumber.uppercase(), f.direction),
            null, null, null
        )
        c.use {
            while (it.moveToNext()) {
                val rec = readMovement(it)
                val id = it.getLong(it.getColumnIndexOrThrow("id"))
                if (TimeUtil.withinTolerance(rec.actualTimeUtc, f.actualTimeUtc, 10)) {
                    return id to rec
                }
            }
        }
        return null
    }

    private fun mergeInto(db: SQLiteDatabase, id: Long, existing: FlightRecord, incoming: FlightRecord) {
        val sources = (existing.sourceList() + incoming.sourceList()).distinct().joinToString(",")
        val evidence = (listOf(existing.evidence, incoming.evidence) + incoming.evidence.split(" + "))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")
        val airline = existing.airline.ifBlank { incoming.airline }
        val city = existing.otherCity.ifBlank { incoming.otherCity }
        val origin = when {
            existing.origin.isNotBlank() && existing.origin != "RUH" -> existing.origin
            incoming.origin.isNotBlank() && incoming.origin != "RUH" -> incoming.origin
            else -> existing.origin.ifBlank { incoming.origin }
        }
        val destination = when {
            existing.destination.isNotBlank() && existing.destination != "RUH" -> existing.destination
            incoming.destination.isNotBlank() && incoming.destination != "RUH" -> incoming.destination
            else -> existing.destination.ifBlank { incoming.destination }
        }
        val cv = ContentValues().apply {
            put("sources", sources)
            put("evidence", evidence)
            put("airline", airline)
            put("other_city", city)
            put("origin", origin)
            put("destination", destination)
        }
        db.update(T_MOVEMENTS, cv, "id=?", arrayOf(id.toString()))
    }

    /**
     * Date isolation + realness audit:
     * - fix ruh_local_date from UTC
     * - drop cancelled / non-Actual / empty flight numbers
     * Returns count of rows purged or date-fixed.
     */
    fun sanitizeMovements(): Int {
        val db = writableDatabase
        val all = getAllMovements()
        var touched = 0
        db.beginTransaction()
        try {
            for (m in all) {
                val trueDate = try {
                    TimeUtil.utcIsoToRiyadhDate(m.actualTimeUtc)
                } catch (_: Exception) { "" }
                val invalid = m.cancelled ||
                    !m.actualEventLabel.startsWith("Actual") ||
                    m.flightNumber.isBlank() ||
                    trueDate.isBlank() ||
                    // actuals in the future = wrong date parse — drop them
                    !TimeUtil.isPastOrNow(m.actualTimeUtc)
                if (invalid) {
                    db.delete(T_MOVEMENTS, "id=?", arrayOf(m.id.toString()))
                    touched++
                    continue
                }
                if (m.ruhLocalDate != trueDate) {
                    val cv = ContentValues().apply {
                        put("ruh_local_date", trueDate)
                        put("sources", m.sourceList().joinToString(","))
                    }
                    db.update(T_MOVEMENTS, cv, "id=?", arrayOf(m.id.toString()))
                    touched++
                } else if (m.sources.isBlank() || m.sources.split(",").none { it.isNotBlank() }) {
                    val cv = ContentValues().apply { put("sources", m.source) }
                    db.update(T_MOVEMENTS, cv, "id=?", arrayOf(m.id.toString()))
                    touched++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return touched
    }

    /**
     * Remove rows claiming an actual movement for flights that a source
     * reports as cancelled/diverted (scoped to the given dates so a flight
     * number that flew a different day is kept).
     */
    fun purgeCancelledFlights(flightNumbers: Set<String>, dates: List<String>): Int {
        if (flightNumbers.isEmpty() || dates.isEmpty()) return 0
        val db = writableDatabase
        var removed = 0
        db.beginTransaction()
        try {
            val placeholders = flightNumbers.joinToString(",") { "?" }
            for (date in dates) {
                // per-date so date params align with per-date flight params
                val args = flightNumbers.map { it.uppercase() }.toTypedArray() + date
                val c = db.query(
                    T_MOVEMENTS, arrayOf("id"),
                    "UPPER(flight_number) IN ($placeholders) AND ruh_local_date=?",
                    args, null, null, null
                )
                val ids = mutableListOf<Long>()
                c.use { while (it.moveToNext()) ids.add(it.getLong(0)) }
                for (id in ids) {
                    removed += db.delete(T_MOVEMENTS, "id=?", arrayOf(id.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed
    }

    private fun queryDayLight(date: String): List<FlightRecord> {
        val db = readableDatabase
        val out = mutableListOf<FlightRecord>()
        val c: Cursor = db.query(T_MOVEMENTS, null, "ruh_local_date=?", arrayOf(date), null, null, null)
        c.use {
            while (it.moveToNext()) {
                out.add(readMovement(it))
            }
        }
        return out
    }

    private fun readMovement(it: Cursor): FlightRecord {
        fun col(name: String, def: String = ""): String = try {
            it.getString(it.getColumnIndexOrThrow(name)) ?: def
        } catch (_: Exception) { def }
        val source = col("source")
        return FlightRecord(
            id = try { it.getLong(it.getColumnIndexOrThrow("id")) } catch (_: Exception) { 0L },
            source = source,
            flightNumber = col("flight_number"),
            direction = col("direction"),
            origin = col("origin"),
            destination = col("destination"),
            actualTimeUtc = col("actual_time_utc"),
            ruhLocalDate = col("ruh_local_date"),
            actualEventLabel = col("actual_event_label"),
            evidence = col("evidence"),
            cancelled = try { it.getInt(it.getColumnIndexOrThrow("cancelled")) == 1 } catch (_: Exception) { false },
            airline = col("airline"),
            otherCity = col("other_city"),
            sources = col("sources").ifBlank { source }
        )
    }

    fun getMovementsForDate(date: String): List<FlightRecord> {
        val db = readableDatabase
        val out = mutableListOf<FlightRecord>()
        val c: Cursor = db.query(
            T_MOVEMENTS, null, "ruh_local_date=?",
            arrayOf(date), null, null, "actual_time_utc ASC"
        )
        c.use {
            while (it.moveToNext()) {
                out.add(readMovement(it))
            }
        }
        return out
    }

    fun getAllMovements(): List<FlightRecord> {
        val db = readableDatabase
        val out = mutableListOf<FlightRecord>()
        val c: Cursor = db.query(
            T_MOVEMENTS, null, null, null, null, null,
            "ruh_local_date ASC, actual_time_utc ASC"
        )
        c.use {
            while (it.moveToNext()) {
                out.add(readMovement(it))
            }
        }
        return out
    }

    fun upsertDailyCount(count: DailyCount) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("date", count.date)
            put("fr24_dep", count.fr24Dep); put("fr24_arr", count.fr24Arr)
            put("fa_dep", count.faDep); put("fa_arr", count.faArr)
            put("fs_dep", count.fsDep); put("fs_arr", count.fsArr)
            put("pf_dep", count.pfDep); put("pf_arr", count.pfArr)
            put("combined_dep", count.combinedDep)
            put("combined_arr", count.combinedArr)
            put("combined_total", count.combinedTotal)
        }
        db.insertWithOnConflict(T_COUNTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDailyCounts(days: Int): List<DailyCount> {
        val db = readableDatabase
        val out = mutableListOf<DailyCount>()
        val c = db.rawQuery("SELECT * FROM $T_COUNTS ORDER BY date DESC LIMIT ?", arrayOf(days.toString()))
        c.use {
            while (it.moveToNext()) {
                out.add(DailyCount(
                    date = it.getString(it.getColumnIndexOrThrow("date")),
                    fr24Dep = it.getInt(it.getColumnIndexOrThrow("fr24_dep")),
                    fr24Arr = it.getInt(it.getColumnIndexOrThrow("fr24_arr")),
                    faDep = it.getInt(it.getColumnIndexOrThrow("fa_dep")),
                    faArr = it.getInt(it.getColumnIndexOrThrow("fa_arr")),
                    fsDep = it.getInt(it.getColumnIndexOrThrow("fs_dep")),
                    fsArr = it.getInt(it.getColumnIndexOrThrow("fs_arr")),
                    pfDep = it.getInt(it.getColumnIndexOrThrow("pf_dep")),
                    pfArr = it.getInt(it.getColumnIndexOrThrow("pf_arr")),
                    combinedDep = it.getInt(it.getColumnIndexOrThrow("combined_dep")),
                    combinedArr = it.getInt(it.getColumnIndexOrThrow("combined_arr")),
                    combinedTotal = it.getInt(it.getColumnIndexOrThrow("combined_total"))
                ))
            }
        }
        return out
    }

    fun upsertHealth(h: SourceHealth) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("source", h.source)
            put("status", h.status)
            put("last_error", h.lastError ?: "")
            put("confirmed_count", h.confirmedCount)
            put("pages_read", h.pagesRead)
        }
        db.insertWithOnConflict(T_HEALTH, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllHealth(): List<SourceHealth> {
        val db = readableDatabase
        val out = mutableListOf<SourceHealth>()
        val c = db.rawQuery("SELECT * FROM $T_HEALTH", null)
        c.use {
            while (it.moveToNext()) {
                out.add(SourceHealth(
                    source = it.getString(it.getColumnIndexOrThrow("source")),
                    domain = "",
                    status = it.getString(it.getColumnIndexOrThrow("status")),
                    lastError = it.getString(it.getColumnIndexOrThrow("last_error")),
                    confirmedCount = it.getInt(it.getColumnIndexOrThrow("confirmed_count")),
                    pagesRead = it.getInt(it.getColumnIndexOrThrow("pages_read"))
                ))
            }
        }
        return out
    }

    fun recordRun(status: String, totalConfirmed: Int, error: String?) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            val now = java.time.Instant.now().toString()
            put("started_at", now)
            put("finished_at", now)
            put("status", status)
            put("total_confirmed", totalConfirmed)
            put("error_message", error ?: "")
        }
        db.insert(T_RUNS, null, cv)
    }

    fun getLastRun(): Pair<String, String>? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT finished_at, status FROM $T_RUNS ORDER BY id DESC LIMIT 1", null)
        c.use {
            if (it.moveToFirst()) {
                return it.getString(0) to it.getString(1)
            }
        }
        return null
    }
}
