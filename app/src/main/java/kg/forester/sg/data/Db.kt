package kg.forester.sg.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Trip(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val cityKm: Double = 0.0,
    val cityL: Double = 0.0,
    val hwyKm: Double = 0.0,
    val hwyL: Double = 0.0,
    val mixKm: Double = 0.0,
    val mixL: Double = 0.0,
    val idleS: Double = 0.0,
    val hardAccel: Int = 0,
    val hardBrake: Int = 0,
    val maxBoostKpa: Double = 0.0,
    val maxRpm: Int = 0,
) {
    val durationS: Long get() = (endMs - startMs) / 1000
    val per100: Double? get() = if (distanceKm > 0.5) fuelL / distanceKm * 100 else null
    val avgSpeed: Double get() = if (durationS > 0) distanceKm / (durationS / 3600.0) else 0.0
}

data class Refill(
    val id: Long = 0,
    val timeMs: Long,
    val liters: Double,
    val pricePerL: Double,
    val full: Boolean,
    val odoComputedL: Double, // computed fuel used since previous full refill
)

/** 10-second consumption buckets for the charts. */
data class Bucket(val timeMs: Long, val fuelL: Double, val km: Double)

data class BatteryEvent(val timeMs: Long, val type: String, val value: Double, val extra: Double)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "forester.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER, end INTEGER,
               km REAL, fuel REAL, city_km REAL, city_l REAL, hwy_km REAL, hwy_l REAL, mix_km REAL, mix_l REAL,
               idle_s REAL, hard_accel INTEGER, hard_brake INTEGER, max_boost REAL, max_rpm INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE refills(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER, liters REAL,
               price REAL, full INTEGER, computed REAL)"""
        )
        db.execSQL("CREATE TABLE buckets(time INTEGER PRIMARY KEY, fuel REAL, km REAL)")
        createV2(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS battery(time INTEGER, type TEXT, value REAL, extra REAL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS volts(time INTEGER PRIMARY KEY, v REAL, running INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
    }

    fun addBatteryEvent(e: BatteryEvent) {
        val cv = ContentValues().apply { put("time", e.timeMs); put("type", e.type); put("value", e.value); put("extra", e.extra) }
        writableDatabase.insert("battery", null, cv)
    }

    /** Removes implausible crank records (a real start always pulls the battery well below 11.5 V). */
    fun purgeBogusCranks() {
        writableDatabase.delete("battery", "type='crank' AND value>11.5", null)
    }

    fun batteryEvents(limit: Int = 100): List<BatteryEvent> =
        readableDatabase.rawQuery("SELECT time,type,value,extra FROM battery ORDER BY time DESC LIMIT $limit", null).use { c ->
            val out = ArrayList<BatteryEvent>()
            while (c.moveToNext()) out += BatteryEvent(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3))
            out
        }

    fun addVolt(timeMs: Long, v: Double, running: Boolean) {
        val cv = ContentValues().apply { put("time", timeMs); put("v", v); put("running", if (running) 1 else 0) }
        writableDatabase.insertWithOnConflict("volts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** (time, avg volts) since [fromMs] grouped by [stepMs]. */
    fun volts(fromMs: Long, stepMs: Long): List<Pair<Long, Double>> =
        readableDatabase.rawQuery(
            "SELECT (time/$stepMs)*$stepMs AS t, AVG(v) FROM volts WHERE time>=? GROUP BY t ORDER BY t",
            arrayOf(fromMs.toString())
        ).use { c ->
            val out = ArrayList<Pair<Long, Double>>()
            while (c.moveToNext()) out += c.getLong(0) to c.getDouble(1)
            out
        }

    fun upsertTrip(t: Trip): Long {
        val cv = ContentValues().apply {
            put("start", t.startMs); put("end", t.endMs); put("km", t.distanceKm); put("fuel", t.fuelL)
            put("city_km", t.cityKm); put("city_l", t.cityL); put("hwy_km", t.hwyKm); put("hwy_l", t.hwyL)
            put("mix_km", t.mixKm); put("mix_l", t.mixL); put("idle_s", t.idleS)
            put("hard_accel", t.hardAccel); put("hard_brake", t.hardBrake)
            put("max_boost", t.maxBoostKpa); put("max_rpm", t.maxRpm)
        }
        return if (t.id == 0L) writableDatabase.insert("trips", null, cv)
        else {
            writableDatabase.update("trips", cv, "id=?", arrayOf(t.id.toString())); t.id
        }
    }

    fun trips(limit: Int = 200): List<Trip> =
        readableDatabase.rawQuery("SELECT * FROM trips ORDER BY start DESC LIMIT $limit", null).use { c ->
            val out = ArrayList<Trip>()
            while (c.moveToNext()) {
                out += Trip(
                    id = c.getLong(0), startMs = c.getLong(1), endMs = c.getLong(2),
                    distanceKm = c.getDouble(3), fuelL = c.getDouble(4),
                    cityKm = c.getDouble(5), cityL = c.getDouble(6), hwyKm = c.getDouble(7), hwyL = c.getDouble(8),
                    mixKm = c.getDouble(9), mixL = c.getDouble(10), idleS = c.getDouble(11),
                    hardAccel = c.getInt(12), hardBrake = c.getInt(13), maxBoostKpa = c.getDouble(14),
                    maxRpm = c.getInt(15),
                )
            }
            out
        }

    fun totals(): Pair<Double, Double> =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(km),0), COALESCE(SUM(fuel),0) FROM trips", null).use { c ->
            c.moveToFirst(); c.getDouble(0) to c.getDouble(1)
        }

    fun addRefill(r: Refill): Long {
        val cv = ContentValues().apply {
            put("time", r.timeMs); put("liters", r.liters); put("price", r.pricePerL)
            put("full", if (r.full) 1 else 0); put("computed", r.odoComputedL)
        }
        return writableDatabase.insert("refills", null, cv)
    }

    fun deleteRefill(id: Long) {
        writableDatabase.delete("refills", "id=?", arrayOf(id.toString()))
    }

    fun refills(): List<Refill> =
        readableDatabase.rawQuery("SELECT * FROM refills ORDER BY time DESC", null).use { c ->
            val out = ArrayList<Refill>()
            while (c.moveToNext()) out += Refill(
                c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3), c.getInt(4) == 1, c.getDouble(5)
            )
            out
        }

    /** Merges 10 s buckets older than [compactBefore] into 10 min ones and drops data older than [dropBefore]. */
    fun compact(compactBefore: Long, dropBefore: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("buckets", "time<?", arrayOf(dropBefore.toString()))
            db.delete("volts", "time<?", arrayOf(dropBefore.toString()))
            val step = 600_000L
            // Re-aggregate everything older than the cut-off into 10-min slots (idempotent; SQLite 3.22 has no upsert).
            db.execSQL("DROP TABLE IF EXISTS agg")
            db.execSQL(
                "CREATE TEMP TABLE agg AS SELECT (time/$step)*$step AS t, SUM(fuel) AS f, SUM(km) AS k " +
                    "FROM buckets WHERE time<$compactBefore GROUP BY t"
            )
            db.execSQL("DELETE FROM buckets WHERE time<$compactBefore")
            db.execSQL("INSERT INTO buckets(time,fuel,km) SELECT t, f, k FROM agg")
            db.execSQL("DROP TABLE agg")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addBucket(b: Bucket) {
        val cv = ContentValues().apply { put("time", b.timeMs); put("fuel", b.fuelL); put("km", b.km) }
        writableDatabase.insertWithOnConflict("buckets", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Buckets since [fromMs], aggregated into [stepMs] slots. */
    fun buckets(fromMs: Long, stepMs: Long): List<Bucket> =
        readableDatabase.rawQuery(
            "SELECT (time/$stepMs)*$stepMs AS t, SUM(fuel), SUM(km) FROM buckets WHERE time>=? GROUP BY t ORDER BY t",
            arrayOf(fromMs.toString())
        ).use { c ->
            val out = ArrayList<Bucket>()
            while (c.moveToNext()) out += Bucket(c.getLong(0), c.getDouble(1), c.getDouble(2))
            out
        }
}
