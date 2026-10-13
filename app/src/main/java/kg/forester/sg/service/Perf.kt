package kg.forester.sg.service

import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.max

/** One acceleration run: results by name ("0-100" → seconds, "402m" → seconds, "402v" → km/h at the line). */
data class PerfRun(val timeMs: Long, val results: Map<String, Float>, val maxSpeed: Float, val peakHp: Float? = null)

/** One point of the power curve: engine rpm, power and torque at the wheels. */
data class DynoPoint(val rpm: Int, val hp: Float, val nm: Float)

data class LapState(
    val lineSet: Boolean = false,
    val running: Boolean = false,
    val currentSec: Float = 0f,
    val laps: List<Float> = emptyList(),
) {
    val best get() = laps.minOrNull()
}

data class PerfState(
    /** Standing still with the engine running: the next pull-away is timed. */
    val armed: Boolean = false,
    val running: Boolean = false,
    val elapsedSec: Float = 0f,
    val distanceM: Float = 0f,
    val speedKmh: Float = 0f,
    /** Results of the run in progress. */
    val live: Map<String, Float> = emptyMap(),
    val runs: List<PerfRun> = emptyList(),
    val dyno: List<DynoPoint> = emptyList(),
    val lap: LapState = LapState(),
)

/**
 * Acceleration timer like a GPS performance meter, but on the ECU speed (several samples a second, crossings
 * interpolated): 0–60, 0–100, 0–150, 0–200 km/h, 201 m and 402 m from a standing start, and 60–100, 80–120,
 * 100–200 km/h rolling. While the pedal is floored the power at the wheels is estimated from mass × acceleration
 * plus air and rolling drag ("dyno"). And a lap timer on a GPS start/finish point.
 */
object Perf {
    private val standing = listOf(60f to "0-60", 100f to "0-100", 150f to "0-150", 200f to "0-200")
    private val rolling = listOf(Triple(60f, 100f, "60-100"), Triple(80f, 120f, "80-120"), Triple(100f, 200f, "100-200"))
    private var lastMs = 0L
    private var lastV = 0f
    private var stillSince = 0L
    private var startMs = 0L
    private var dist = 0.0
    private var maxV = 0f
    private val live = LinkedHashMap<String, Float>()
    private val upCross = HashMap<Float, Long>() // rolling: when the speed last went up through a lower bound
    private val pull = ArrayList<Triple<Long, Float, Int>>() // floored: time, speed m/s, rpm
    private var lapStart = 0L
    private var lapAway = false
    private var lapClosest = Double.MAX_VALUE
    private var lapClosestMs = 0L

    init {
        Repo.perf.value = Repo.perf.value.copy(runs = load(), lap = LapState(lineSet = Repo.settings.lapLine.isNotEmpty()))
    }

    fun process(e: EcuSnapshot, gps: GpsFix?, now: Long) {
        // the ECU speed with the correction learned from GPS (other tyre sizes)
        val v = (e.speedKmh?.let { it * Repo.settings.speedK.toFloat() } ?: gps?.speedKmh) ?: return
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f)
        val running = (e.rpm ?: 0) > 400
        if (dt <= 0f || dt > 2f) { lastMs = now; lastV = v; return }
        // when the speed went through [level] between the last sample and this one (in Long: a Float of epoch ms is ±1 min off)
        fun cross(level: Float): Long = now - (((v - level) / (v - lastV)).coerceIn(0f, 1f) * dt * 1000f).toLong()

        // standing start
        if (v < 0.5f) {
            if (stillSince == 0L) stillSince = now
            if (startMs != 0L) finish(now)
        } else stillSince = 0L
        val armed = running && stillSince != 0L && now - stillSince > 1000
        if (startMs == 0L && lastV < 0.5f && v >= 0.5f && running) {
            startMs = cross(0.5f)
            dist = 0.0; maxV = 0f; live.clear()
        }
        if (startMs != 0L) {
            dist += (lastV + v) / 2 / 3.6 * dt
            maxV = max(maxV, v)
            val t = (now - startMs) / 1000f
            for ((level, name) in standing) if (name !in live && lastV < level && v >= level) live[name] = (cross(level) - startMs) / 1000f
            for ((m, name) in listOf(201.168 to "201m", 402.336 to "402m")) {
                if (name !in live && dist >= m) { live[name] = t; live[name.replace("m", "v")] = v }
            }
            // the run is over when the driver lifts (speed falls) or after a minute
            if (v < maxV - 4f || t > 60f) finish(now)
        }
        // rolling intervals
        for ((lo, hi, name) in rolling) {
            if (lastV < lo && v >= lo) upCross[lo] = cross(lo)
            if (v < lo - 2f) upCross.remove(lo)
            val from = upCross[lo]
            if (from != null && lastV < hi && v >= hi) {
                val sec = (cross(hi) - from) / 1000f
                if (sec in 1f..40f) { live[name] = sec; if (startMs == 0L) saveRolling(name, sec, now) }
                upCross.remove(lo)
            }
        }
        // dyno: pedal (or throttle) floored
        val floored = ((e.pedalPct ?: e.throttlePct) ?: 0f) > 85f && v > 10f
        if (floored && e.rpm != null) pull += Triple(now, v / 3.6f, e.rpm)
        else if (pull.size > 8) { dyno(); pull.clear() } else pull.clear()

        laps(gps, now)
        lastMs = now
        lastV = v
        Repo.perf.value = Repo.perf.value.copy(
            armed = armed && startMs == 0L, running = startMs != 0L,
            elapsedSec = if (startMs != 0L) (now - startMs) / 1000f else Repo.perf.value.elapsedSec,
            distanceM = dist.toFloat(), speedKmh = v, live = LinkedHashMap(live),
        )
    }

    private fun finish(now: Long) {
        val res = LinkedHashMap(live)
        startMs = 0L
        // demo data is shown live but not kept, like trips
        if (!Repo.settings.demo && res.keys.any { it.startsWith("0-") || it.endsWith("m") }) {
            val run = PerfRun(now, res, maxV, Repo.perf.value.dyno.maxOfOrNull { it.hp })
            val runs = (listOf(run) + Repo.perf.value.runs).take(40)
            Repo.perf.value = Repo.perf.value.copy(runs = runs)
            save(runs)
            res["0-100"]?.let { Alerts.raise("run", "Разгон до сотни: %.2f секунды".format(it), Alerts.Level.INFO, 0L) }
        }
    }

    private fun saveRolling(name: String, sec: Float, now: Long) {
        if (Repo.settings.demo) return
        val run = PerfRun(now, mapOf(name to sec), 0f)
        val runs = (listOf(run) + Repo.perf.value.runs).take(40)
        Repo.perf.value = Repo.perf.value.copy(runs = runs)
        save(runs)
    }

    /** Power at the wheels over the pull: (m·a + air + rolling) · v, smoothed over ~0.6 s, binned by 250 rpm. */
    private fun dyno() {
        val m = Repo.settings.massKg.toDouble()
        val cda = 0.85 // Cd × frontal area, m² (Forester/Impreza class)
        val bins = HashMap<Int, MutableList<Pair<Double, Double>>>()
        for (i in pull.indices) {
            val (t, v, rpm) = pull[i]
            val a = pull.indices.filter { kotlin.math.abs(pull[it].first - t) <= 300 }.let { w ->
                if (w.size < 3) null else {
                    val t0 = pull[w.first()].first
                    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                    for (k in w) { val x = (pull[k].first - t0) / 1000.0; val y = pull[k].second.toDouble(); sx += x; sy += y; sxx += x * x; sxy += x * y }
                    val n = w.size
                    val den = n * sxx - sx * sx
                    if (den < 1e-6) null else (n * sxy - sx * sy) / den
                }
            } ?: continue
            if (a < 0.1) continue
            val force = m * a + 0.5 * 1.2 * cda * v * v + 0.015 * m * 9.81
            val watts = force * v
            val hp = watts / 735.5
            val nm = watts / (2 * PI * rpm / 60.0)
            bins.getOrPut((rpm / 250) * 250) { ArrayList() } += hp to nm
        }
        val curve = bins.entries.sortedBy { it.key }.filter { it.value.size >= 1 }.map { (r, l) ->
            DynoPoint(r, l.map { it.first }.average().toFloat(), l.map { it.second }.average().toFloat())
        }
        if (curve.size >= 4) Repo.perf.value = Repo.perf.value.copy(dyno = curve)
    }

    // --- lap timer

    fun setLapLine(gps: GpsFix?) {
        val lat = gps?.lat ?: return
        val lon = gps.lon ?: return
        Repo.settings.lapLine = "$lat,$lon"
        lapStart = 0L; lapAway = false
        Repo.perf.value = Repo.perf.value.copy(lap = LapState(lineSet = true))
    }

    fun clearLaps() {
        Repo.settings.lapLine = ""
        lapStart = 0L
        Repo.perf.value = Repo.perf.value.copy(lap = LapState())
    }

    private fun laps(gps: GpsFix?, now: Long) {
        val line = Repo.settings.lapLine.split(',').mapNotNull { it.toDoubleOrNull() }
        if (line.size != 2 || gps?.lat == null || gps.lon == null) return
        val d = Motion.metres(line[0], line[1], gps.lat, gps.lon)
        val lap = Repo.perf.value.lap
        if (d > 60) {
            // away from the line: the next approach is a pass
            if (lapClosestMs != 0L && lapClosest < 25) {
                val passMs = lapClosestMs
                val laps = if (lapStart != 0L) lap.laps + (passMs - lapStart) / 1000f else lap.laps
                if (lapStart != 0L) {
                    val sec = (passMs - lapStart) / 1000f
                    val best = lap.best
                    Alerts.raise("lap", "Круг %d:%05.2f".format((sec / 60).toInt(), sec % 60) +
                        if (best != null && sec < best) ", лучший!" else "", Alerts.Level.INFO, 0L)
                }
                lapStart = passMs
                Repo.perf.value = Repo.perf.value.copy(lap = lap.copy(laps = laps, running = true))
            }
            lapAway = true
            lapClosest = Double.MAX_VALUE; lapClosestMs = 0L
        } else if (lapAway && d < lapClosest) {
            lapClosest = d; lapClosestMs = gps.timeMs
        }
        if (lapStart != 0L) {
            Repo.perf.value = Repo.perf.value.copy(lap = Repo.perf.value.lap.copy(currentSec = (now - lapStart) / 1000f, lineSet = true))
        }
    }

    // --- storage

    private fun save(runs: List<PerfRun>) {
        val arr = JSONArray()
        runs.forEach { r ->
            val res = JSONObject(); r.results.forEach { (k, v) -> res.put(k, v.toDouble()) }
            arr.put(JSONObject().put("t", r.timeMs).put("r", res).put("v", r.maxSpeed.toDouble()).put("hp", r.peakHp?.toDouble()))
        }
        Repo.settings.perfRuns = arr.toString()
    }

    private fun load(): List<PerfRun> = runCatching {
        val arr = JSONArray(Repo.settings.perfRuns)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val r = o.getJSONObject("r")
            PerfRun(o.getLong("t"), r.keys().asSequence().associateWith { r.getDouble(it).toFloat() }, o.optDouble("v", 0.0).toFloat(),
                o.optDouble("hp").takeIf { !it.isNaN() }?.toFloat())
        }
    }.getOrDefault(emptyList())

    fun clearRuns() {
        Repo.settings.perfRuns = "[]"
        Repo.perf.value = Repo.perf.value.copy(runs = emptyList(), dyno = emptyList())
    }
}
