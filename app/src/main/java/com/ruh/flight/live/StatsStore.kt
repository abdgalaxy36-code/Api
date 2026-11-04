package com.ruh.flight.live

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray

/** One 24h local day of ACTUAL movements (real departure + real arrival only). */
data class DayStat(
    val date: String,      // yyyy-MM-dd, device local (Riyadh)
    val departures: Int,
    val arrivals: Int,
    val total: Int,
    val updatedAt: Long
)

/** One stored movement, kept per day so a history day can reopen its flights. */
data class DayFlight(
    val date: String,
    val number: String,
    val direction: String,   // "dep" | "arr"
    val epoch: Long,         // actual time (real movement)
    val scheduled: Long,     // scheduled time, 0 when unknown
    val origin: String,
    val destination: String,
    val airline: String,
    val terminal: String,
    val gate: String,
    val aircraft: String,
    val status: String,      // DEPARTED | LANDED
    val color: String
) {
    fun toLiveFlight(): LiveFlight = LiveFlight(
        id = null,
        number = number,
        direction = direction,
        statusWord = status,
        statusColor = color,
        realEpoch = epoch,
        scheduledEpoch = scheduled,
        // item_flight shows the counterpart airport: destination for a
        // departure, origin for an arrival.
        otherIata = if (direction == "dep") destination else origin,
        otherName = "",
        otherCity = "",
        airline = airline,
        terminal = terminal,
        gate = gate,
        aircraft = aircraft,
        codeshare = emptyList(),
        live = false
    )
}

class StatsStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "date TEXT PRIMARY KEY," +
                "departures INTEGER NOT NULL," +
                "arrivals INTEGER NOT NULL," +
                "total INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL(CREATE_FLIGHTS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(CREATE_FLIGHTS)
        if (oldVersion < 3 && !hasColumn(db, FLIGHTS, "scheduled")) {
            db.execSQL(
                "ALTER TABLE $FLIGHTS ADD COLUMN scheduled INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (c.getString(idx) == column) return true
            }
            false
        }

    // ------------------------------------------------------------- daily stats

    /** Live collection always wins: a fresh full-day recount replaces the row. */
    fun record(date: String, departures: Int, arrivals: Int) {
        if (date.isBlank()) return
        val cv = ContentValues().apply {
            put("date", date)
            put("departures", departures)
            put("arrivals", arrivals)
            put("total", departures + arrivals)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
        Log.i(Fr24Api.TAG, "stats record $date D=$departures A=$arrivals T=${departures + arrivals}")
    }

    fun all(): List<DayStat> = queryStats("ORDER BY date DESC", null)

    fun best(limit: Int): List<DayStat> =
        queryStats("ORDER BY total DESC, date DESC LIMIT $limit", null)

    fun get(date: String): DayStat? =
        queryStats("WHERE date = ? LIMIT 1", arrayOf(date)).firstOrNull()

    private fun queryStats(where: String, args: Array<String>?): List<DayStat> =
        readableDatabase.rawQuery(
            "SELECT date,departures,arrivals,total,updated_at FROM $TABLE $where", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DayStat(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4)))
                }
            }
        }

    // ----------------------------------------------------------- flight detail

    /** Replace one day's movements (used for the live day on every refresh). */
    fun replaceDayFlights(date: String, flights: List<LiveFlight>) {
        if (date.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(FLIGHTS, "date = ?", arrayOf(date))
            for (f in flights) {
                db.insertWithOnConflict(
                    FLIGHTS, null, flightValues(date, f), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(Fr24Api.TAG, "flights stored for $date: ${flights.size}")
    }

    /**
     * Add rows for a day without dropping what is already stored. Used by the
     * previous-day catch-up, which only sees the last 12 h of that day.
     */
    fun mergeDayFlights(date: String, flights: List<LiveFlight>) {
        if (date.isBlank() || flights.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in flights) {
                db.insertWithOnConflict(
                    FLIGHTS, null, flightValues(date, f), SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Recount a day from its stored flight rows. */
    fun recountDay(date: String) {
        val rows = flightsFor(date)
        record(
            date,
            rows.count { it.direction == "dep" },
            rows.count { it.direction == "arr" }
        )
    }

    fun flightsFor(date: String): List<DayFlight> =
        readableDatabase.rawQuery(
            "SELECT date,number,direction,epoch,scheduled,origin,destination,airline," +
                "terminal,gate,aircraft,status,color FROM $FLIGHTS " +
                "WHERE date = ? ORDER BY epoch DESC",
            arrayOf(date)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DayFlight(
                            date = c.getString(0), number = c.getString(1),
                            direction = c.getString(2), epoch = c.getLong(3),
                            scheduled = c.getLong(4),
                            origin = c.getString(5), destination = c.getString(6),
                            airline = c.getString(7), terminal = c.getString(8),
                            gate = c.getString(9), aircraft = c.getString(10),
                            status = c.getString(11), color = c.getString(12)
                        )
                    )
                }
            }
        }

    private fun flightValues(date: String, f: LiveFlight): ContentValues =
        ContentValues().apply {
            put("date", date)
            put("number", f.number)
            put("direction", f.direction)
            put("epoch", f.realEpoch)
            put("scheduled", f.scheduledEpoch)
            // item_flight's counterpart is destination for departures and
            // origin for arrivals, so store both ends explicitly.
            put("origin", if (f.direction == "arr") f.otherIata else "RUH")
            put("destination", if (f.direction == "arr") "RUH" else f.otherIata)
            put("airline", f.airline)
            put("terminal", f.terminal)
            put("gate", f.gate)
            put("aircraft", f.aircraft)
            put("status", f.statusWord)
            put("color", f.statusColor)
        }

    // ------------------------------------------------------- export / restore

    /** Every stored movement, all days, oldest first. */
    fun allFlights(): List<DayFlight> =
        readableDatabase.rawQuery(
            "SELECT date,number,direction,epoch,scheduled,origin,destination,airline," +
                "terminal,gate,aircraft,status,color FROM $FLIGHTS " +
                "ORDER BY date, epoch",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DayFlight(
                            date = c.getString(0), number = c.getString(1),
                            direction = c.getString(2), epoch = c.getLong(3),
                            scheduled = c.getLong(4),
                            origin = c.getString(5), destination = c.getString(6),
                            airline = c.getString(7), terminal = c.getString(8),
                            gate = c.getString(9), aircraft = c.getString(10),
                            status = c.getString(11), color = c.getString(12)
                        )
                    )
                }
            }
        }

    /** Restore path: replace whole day totals. */
    fun insertStats(rows: List<DayStat>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in rows) {
                if (s.date.isBlank()) continue
                val cv = ContentValues().apply {
                    put("date", s.date)
                    put("departures", s.departures)
                    put("arrivals", s.arrivals)
                    put("total", s.total)
                    put("updated_at", s.updatedAt)
                }
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(Fr24Api.TAG, "restore stats: ${rows.size} day(s)")
    }

    /** Restore path: replace whole flight set. */
    fun insertFlights(rows: List<DayFlight>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(FLIGHTS, null, null)
            for (f in rows) {
                val cv = ContentValues().apply {
                    put("date", f.date)
                    put("number", f.number)
                    put("direction", f.direction)
                    put("epoch", f.epoch)
                    put("scheduled", f.scheduled)
                    put("origin", f.origin)
                    put("destination", f.destination)
                    put("airline", f.airline)
                    put("terminal", f.terminal)
                    put("gate", f.gate)
                    put("aircraft", f.aircraft)
                    put("status", f.status)
                    put("color", f.color)
                }
                db.insertWithOnConflict(FLIGHTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(Fr24Api.TAG, "restore flights: ${rows.size} row(s)")
    }

    /**
     * Proof that stored data is real-time only. A row fails when it has no
     * actual timestamp, or its status is still a planning state (SCHEDULED /
     * UNKNOWN / PREDICTED), or a day's flight count disagrees with its total.
     */
    fun verify(): String {
        val flights = allFlights()
        val planning = setOf("SCHEDULED", "UNKNOWN", "PREDICTED", "CANCELED", "CANCELLED")
        val noTime = flights.filter { it.epoch <= 0L }
        val notActual = flights.filter { it.status.trim().uppercase() in planning }

        val perDate = flights.groupingBy { it.date }.eachCount()
        val stats = all()
        val mismatch = stats.filter { s ->
            val stored = perDate[s.date] ?: 0
            stored != s.total
        }

        val sb = StringBuilder()
        sb.append("Scanned ${flights.size} flight(s) across ${perDate.size} day(s)\n")
        sb.append(
            if (noTime.isEmpty()) "PASS  no missing real time\n"
            else "FAIL  ${noTime.size} row(s) without real time\n"
        )
        sb.append(
            if (notActual.isEmpty()) "PASS  no scheduled/unknown rows\n"
            else "FAIL  ${notActual.size} scheduled/unknown row(s)\n"
        )
        sb.append(
            if (mismatch.isEmpty()) "PASS  every day's count matches its total\n"
            else "FAIL  ${mismatch.size} day(s) mismatch: " +
                mismatch.joinToString(", ") {
                    "${it.date}=${perDate[it.date] ?: 0}/${it.total}"
                } + "\n"
        )
        val bad = (noTime + notActual).distinct()
        if (bad.isNotEmpty()) {
            sb.append("\nProblem rows:\n")
            bad.take(20).forEach {
                sb.append("  ${it.date} ${it.number} ${it.direction} ")
                sb.append("epoch=${it.epoch} status=${it.status}\n")
            }
        }
        val ok = noTime.isEmpty() && notActual.isEmpty() && mismatch.isEmpty()
        sb.append("\n${if (ok) "ALL CHECKS PASSED" else "CHECKS FAILED"}")
        return sb.toString()
    }

    // ------------------------------------------------------------------- seed

    /**
     * Historic days the schedule API cannot reach (it rejects any timestamp
     * outside [today, today+2]). Seeded once from FR24 flight-history pages;
     * INSERT OR IGNORE keeps app-collected rows intact.
     */
    fun seedFromAssets(context: Context) {
        seedStats(context)
        seedFlights(context)
    }

    private fun seedStats(context: Context) {
        val json = readAsset(context, "backfill.json") ?: return
        var added = 0
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.optString("date")
                if (date.isBlank()) continue
                val cv = ContentValues().apply {
                    put("date", date)
                    put("departures", o.optInt("departures"))
                    put("arrivals", o.optInt("arrivals"))
                    put("total", o.optInt("departures") + o.optInt("arrivals"))
                    put("updated_at", 0L)
                }
                if (writableDatabase.insertWithOnConflict(
                        TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE
                    ) != -1L
                ) added++
            }
        } catch (e: Exception) {
            Log.w(Fr24Api.TAG, "backfill seed failed: ${e.message}")
            return
        }
        Log.i(Fr24Api.TAG, "backfill seed added $added day(s)")
    }

    private fun seedFlights(context: Context) {
        val json = readAsset(context, "backfill_flights.json") ?: return
        var added = 0
        try {
            val arr = JSONArray(json)
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("date", o.optString("date"))
                        put("number", o.optString("number"))
                        put("direction", o.optString("direction"))
                        put("epoch", o.optLong("epoch"))
                        put("scheduled", o.optLong("scheduled"))
                        put("origin", o.optString("origin"))
                        put("destination", o.optString("destination"))
                        put("airline", o.optString("airline"))
                        put("terminal", "")
                        put("gate", "")
                        put("aircraft", o.optString("aircraft"))
                        put("status", o.optString("status"))
                        put("color", o.optString("color", "gray"))
                    }
                    if (db.insertWithOnConflict(
                            FLIGHTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE
                        ) != -1L
                    ) added++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(Fr24Api.TAG, "flight seed failed: ${e.message}")
            return
        }
        Log.i(Fr24Api.TAG, "flight seed added $added row(s)")
    }

    private fun readAsset(context: Context, name: String): String? = try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.i(Fr24Api.TAG, "no asset $name: ${e.message}")
        null
    }

    companion object {
        private const val DB_NAME = "ruh_stats.db"
        private const val DB_VERSION = 3
        private const val TABLE = "daily_stats"
        private const val FLIGHTS = "daily_flights"

        private const val CREATE_FLIGHTS =
            "CREATE TABLE IF NOT EXISTS $FLIGHTS (" +
                "date TEXT NOT NULL," +
                "number TEXT NOT NULL," +
                "direction TEXT NOT NULL," +
                "epoch INTEGER NOT NULL," +
                "scheduled INTEGER NOT NULL DEFAULT 0," +
                "origin TEXT NOT NULL," +
                "destination TEXT NOT NULL," +
                "airline TEXT NOT NULL DEFAULT ''," +
                "terminal TEXT NOT NULL DEFAULT ''," +
                "gate TEXT NOT NULL DEFAULT ''," +
                "aircraft TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL," +
                "color TEXT NOT NULL DEFAULT 'gray'," +
                "PRIMARY KEY (date, number, direction, epoch))"
    }
}
