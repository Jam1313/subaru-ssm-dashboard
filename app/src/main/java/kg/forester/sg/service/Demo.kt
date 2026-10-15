package kg.forester.sg.service

import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Made-up but plausible ECU/TCU data for the demo mode (Настройки → «Демо-данные»): a 140-second loop of
 * idle, a flat-out run to ~95 km/h, a sharp lift, cruise, braking to a stop and a minute at a red light (long
 * enough for «Мониторинг → Проверка» to take its idle sample, with the values of a healthy warm EJ).
 * Nothing is written to trips.
 */
object Demo {
    private const val CYCLE = 140.0
    private val gearRatio = floatArrayOf(2.785f, 1.545f, 1.000f, 0.694f) // 4EAT
    private const val FINAL = 4.111f
    private const val TYRE_M = 2.0f

    private fun speedAt(t: Double): Double = when {
        t < 8 -> 0.0
        t < 26 -> 95 * (1 - Math.exp(-(t - 8) / 5))
        t < 60 -> 93 + 3 * sin((t - 26) / 5)
        t < 75 -> 93 * (1 - (t - 60) / 15)
        else -> 0.0
    }

    fun ecu(nowMs: Long): Pair<EcuSnapshot, TcuSnapshot> {
        val t = (nowMs / 1000.0) % CYCLE
        val v = speedAt(t)
        val accel = (speedAt(t + 0.5) - v) * 2 // km/h per s
        val load = when {
            t in 8.0..26.0 -> 0.95 // flat out, then a sharp lift at 26 s (backfire on the screen)
            accel > 3 -> min(1.0, 0.45 + accel / 12)
            v > 1 && accel > -1 -> 0.28
            v > 1 -> 0.02
            else -> 0.12
        }
        val gear = when {
            v < 1 -> 1
            v < 22 -> 1
            v < 45 -> 2
            v < 100 -> 3
            else -> 4
        }
        val wheelRpm = v / 3.6 / TYRE_M * 60
        val lockUp = gear >= 3 && accel < 4
        val turbine = wheelRpm * gearRatio[gear - 1] * FINAL
        val rpm = max(780.0, if (v < 1) 780.0 else turbine * (if (lockUp) 1.0 else 1.08))
        val wobble = sin(nowMs / 700.0)
        val idling = v < 1
        // each stop a little different, and a small air leak growing over the stops (so «Проверка» has a history
        // worth looking at): the MAF reads a bit less, the ECU learns a bit more fuel
        val stop = ((nowMs / 1000.0) / CYCLE).toLong()
        val leak = (stop % 8) / 7.0
        val jitter = sin(stop * 1.7)
        val boostKpa = if (load > 0.7) (load - 0.7) / 0.3 * 85 else -60 + load * 70
        val maf = 2.5 + rpm / 1000 * (3 + load * 28)
        // warms from 55° to 85° and back over an hour, smoothly (a sawtooth would look like a faulty sensor)
        val atf = (70 - 15 * cos(nowMs / 60000.0 / 60 * 2 * Math.PI)).toInt()
        val transfer = (25 + load * 45 + if (v < 15 && accel > 3) 20 else 0).coerceAtMost(100.0)
        val ecu = EcuSnapshot(
            timeMs = nowMs,
            coolantC = 88 + (wobble * 2).toInt(),
            rpm = if (idling) (715 + jitter * 25 + leak * 40 + wobble * 12).toInt() else rpm.toInt(),
            speedKmh = v.toInt(),
            timingDeg = if (idling) (13 + wobble * 0.5).toFloat() else (12 + 18 * (1 - load) + wobble).toFloat(),
            intakeC = 32,
            mafGs = if (idling) (2.9 - leak * 0.5 + jitter * 0.1 + wobble * 0.1).toFloat() else maf.toFloat(),
            throttlePct = if (idling) 1.5f else (load * 85 + 2).toFloat(),
            batteryV = (14.1 + wobble * 0.1).toFloat(),
            injPwMs = if (idling) (2.05 + leak * 0.3 + jitter * 0.05).toFloat() else (1.8 + load * 9).toFloat(),
            knockDeg = if (load > 0.9 && ((nowMs / 1000) % 17 == 0L)) -1.4f else 0f,
            atmKpa = 99f,
            afCorrPct = (wobble * 3).toFloat(),
            afLearnPct = if (idling) (1.5 + leak * 8).toFloat() else 2.3f,
            lambda = if (load > 0.8) 0.82f else (1 + wobble * 0.02).toFloat(),
            wastegatePct = if (load > 0.7) 45f else 0f,
            pedalPct = if (idling) 0f else (load * 100).toFloat(),
            mapKpa = if (idling) (33 + wobble).toFloat() else (99 + boostKpa).toFloat(),
            avcsDeg = if (idling) 0.5f else (load * 25).toFloat(),
            altDutyPct = 38f,
            throttleMotorPct = (load * 30).toFloat(),
        )
        val tcu = TcuSnapshot(
            timeMs = nowMs,
            atfC = atf,
            gear = gear,
            turbineRpm = turbine.toInt(),
            linePressurePct = (30 + load * 60).toFloat(),
            lockUpPct = if (lockUp) 95f else 0f,
            transferPct = transfer.toFloat(),
            frontWheelKmh = (v + if (v < 15 && accel > 3) 3 else 0).toInt(),
            rearWheelKmh = v.toInt(),
            range = if (v < 1 && t < 4) "P/N" else "D",
        )
        return ecu to tcu
    }

    /** Course for the fake GPS: gentle left-right bends while cruising (about ±10°/s). */
    fun bearing(nowMs: Long): Float {
        val t = (nowMs / 1000.0) % CYCLE
        val wiggle = if (t in 26.0..60.0) 40 * sin((t - 26) / 3.5) else 0.0
        return ((90 + wiggle) % 360).toFloat()
    }

    // demo drive starts in the centre of Bishkek and follows the demo speed and course
    private var lat = 42.8746
    private var lon = 74.6122
    private var lastPosMs = 0L

    fun position(nowMs: Long, speedKmh: Float, bearing: Float?): Pair<Double, Double> {
        val dt = if (lastPosMs == 0L) 0.0 else ((nowMs - lastPosMs) / 1000.0).coerceAtMost(2.0)
        lastPosMs = nowMs
        if (bearing != null) {
            val m = speedKmh / 3.6 * dt
            val a = Math.toRadians(bearing.toDouble())
            lat += m * cos(a) / 111_320.0
            lon += m * sin(a) / (111_320.0 * cos(Math.toRadians(lat)))
        }
        return lat to lon
    }

    /** Instant consumption for the demo, same formula as the real one. */
    fun litersPerHour(e: EcuSnapshot) = FuelCalc.litersPerHour(e, 1.0)

}
