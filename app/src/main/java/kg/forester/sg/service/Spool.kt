package kg.forester.sg.service

import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * One spool test: the rpm where the boost went through 0 and 1 bar (and up to the target, if the ROM gives it),
 * the conditions, the engine speed per km/h at the start (which gear it was) and the boost curve, thinned.
 */
data class SpoolTest(
    val timeMs: Long, val rpm0: Int?, val rpm1: Int?, val rpmTarget: Int?, val targetKpa: Float?, val peakKpa: Float,
    val rpmPerKmh: Float?, val iatC: Int?, val baroKpa: Float?, val curve: List<SpoolPt>, val demo: Boolean = false,
)

data class SpoolState(
    /** Waiting for the pull. */
    val armed: Boolean = false,
    val pulling: Boolean = false,
    /** What is wrong with the pull being tried, "" = nothing. */
    val hint: String = "",
    val tests: List<SpoolTest> = emptyList(),
)

/**
 * Spool test: in 4th gear from 1500–2000 rpm, the pedal floored up to ~5000 rpm. The rpm where the boost comes is
 * the turbo's health over the months: a boost leak or a wastegate that does not close moves it up by 300 rpm and more
 * long before the peak boost suffers.
 */
object Spool {
    private val pts = ArrayList<SpoolPt>()
    private var startMs = 0L
    private var maxRpm = 0
    private var ratio: Float? = null
    private var target: Float? = null
    private var iat: Int? = null
    private var baro: Float? = null
    private var liftSince = 0L

    // under Perf's lock: the samples change the same state on the ECU's thread
    private fun set(f: (SpoolState) -> SpoolState) = synchronized(Perf) {
        Repo.perf.value = Repo.perf.value.let { it.copy(spool = f(it.spool)) }
    }

    fun arm(on: Boolean) {
        synchronized(this) { startMs = 0L; pts.clear() }
        set { it.copy(armed = on, pulling = false, hint = "") }
    }

    @Synchronized
    fun process(e: EcuSnapshot, now: Long) {
        val st = Repo.perf.value.spool
        if (!st.armed) return
        val gas = e.pedalPct ?: e.throttlePct ?: return
        val rpm = e.rpm ?: return
        val kpa = e.gaugeKpa ?: return
        val kmh = e.speedKmh ?: 0
        val floored = gas >= (if (e.pedalPct != null) PerfCalc.WOT_PEDAL else PerfCalc.WOT_THROTTLE)
        if (startMs == 0L) {
            val hint = when {
                !floored -> ""
                kmh < 15 -> "Тест — на ходу, на 4-й передаче"
                rpm > 2600 -> "Обороты уже высокие: начните с 1500–2000 об/мин"
                kpa >= 0 -> "Наддув уже есть: сбросьте газ и начните с меньших оборотов"
                else -> null
            }
            if (hint != null) { if (hint != st.hint) set { it.copy(hint = hint) }; return }
            startMs = now; maxRpm = rpm; liftSince = 0L
            pts.clear(); pts += SpoolPt(rpm, kpa)
            ratio = rpm.toFloat() / kmh
            target = null
            iat = e.intakeC; baro = e.atmKpa
            set { it.copy(pulling = true, hint = "") }
            return
        }
        Repo.guard.value.targetBoostKpa?.let { t -> target = maxOf(target ?: t, t) }
        liftSince = if (floored) 0L else if (liftSince == 0L) now else liftSince
        maxRpm = maxOf(maxRpm, rpm)
        // a lift for over a third of a second, a shift (the rpm falls), the limiter or 15 s: the pull is over
        val over = (liftSince != 0L && now - liftSince > 350) || rpm < maxRpm - 300 || rpm > 6000 || now - startMs > 15_000
        if (!over) { if (rpm >= (pts.lastOrNull()?.rpm ?: 0)) pts += SpoolPt(rpm, kpa); return }
        finish(now)
    }

    private fun finish(now: Long) {
        val curve = pts.toList()
        startMs = 0L; pts.clear()
        val r = PerfCalc.spool(curve, target)
        if (curve.size < 5 || r.rpm0 == null) {
            set { it.copy(pulling = false, hint = "Наддув не пришёл: держите газ в полу до ~4500 об/мин") }
            return
        }
        val step = (curve.size / 40).coerceAtLeast(1)
        val test = SpoolTest(now, r.rpm0, r.rpm1, r.rpmTarget, target, r.peakKpa, ratio, iat, baro,
            curve.filterIndexed { i, _ -> i % step == 0 || i == curve.size - 1 }, Repo.settings.demo)
        val tests = (listOf(test) + Repo.perf.value.spool.tests).take(100)
        set { it.copy(armed = false, pulling = false, hint = "", tests = tests) }
        save(tests)
        if (!test.demo) Alerts.raise("spool", "Спул: 1 бар на ${r.rpm1 ?: "—"} оборотах", Alerts.Level.INFO, 0L)
    }

    fun delete(timeMs: Long) {
        val tests = Repo.perf.value.spool.tests.filter { it.timeMs != timeMs }
        set { it.copy(tests = tests) }
        save(tests)
    }

    private fun save(tests: List<SpoolTest>) {
        val arr = JSONArray()
        tests.filter { !it.demo }.forEach { t ->
            val c = JSONArray(); t.curve.forEach { c.put(it.rpm).put(it.kpa.toDouble()) }
            arr.put(JSONObject().put("t", t.timeMs).put("r0", t.rpm0).put("r1", t.rpm1).put("rt", t.rpmTarget).put("tk", t.targetKpa?.toDouble())
                .put("pk", t.peakKpa.toDouble()).put("k", t.rpmPerKmh?.toDouble()).put("iat", t.iatC).put("baro", t.baroKpa?.toDouble()).put("c", c))
        }
        Repo.settings.perfSpool = arr.toString()
    }

    fun load(): List<SpoolTest> = runCatching {
        val arr = JSONArray(Repo.settings.perfSpool)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            fun fl(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k).toFloat() else null
            val c = o.optJSONArray("c") ?: JSONArray()
            SpoolTest(o.getLong("t"), int("r0"), int("r1"), int("rt"), fl("tk"), fl("pk") ?: 0f, fl("k"), int("iat"), fl("baro"),
                List(c.length() / 2) { SpoolPt(c.getInt(it * 2), c.getDouble(it * 2 + 1).toFloat()) })
        }
    }.getOrDefault(emptyList())
}
