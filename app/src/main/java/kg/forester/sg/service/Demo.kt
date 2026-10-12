package kg.forester.sg.service

import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Made-up but plausible ECU/TCU data for the demo mode (Настройки → «Демо-данные»): a 90-second loop of
 * idle, a flat-out run to ~95 km/h, a sharp lift, cruise and braking to a stop. Nothing is written to trips.
 */
object Demo {
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
        val t = (nowMs / 1000.0) % 90
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
        val boostKpa = if (load > 0.7) (load - 0.7) / 0.3 * 85 else -60 + load * 70
        val maf = 2.5 + rpm / 1000 * (3 + load * 28)
        val atf = (55 + (nowMs / 60000.0 % 30)).toInt()
        val transfer = (25 + load * 45 + if (v < 15 && accel > 3) 20 else 0).coerceAtMost(100.0)
        val ecu = EcuSnapshot(
            timeMs = nowMs,
            coolantC = 88 + (wobble * 2).toInt(),
            rpm = rpm.toInt(),
            speedKmh = v.toInt(),
            timingDeg = (12 + 18 * (1 - load) + wobble).toFloat(),
            intakeC = 32,
            mafGs = maf.toFloat(),
            throttlePct = (load * 85 + 2).toFloat(),
            batteryV = (14.1 + wobble * 0.1).toFloat(),
            injPwMs = (1.8 + load * 9).toFloat(),
            knockDeg = if (load > 0.9 && ((nowMs / 1000) % 17 == 0L)) -1.4f else 0f,
            atmKpa = 99f,
            afCorrPct = (wobble * 3).toFloat(),
            afLearnPct = 2.3f,
            lambda = if (load > 0.8) 0.82f else (1 + wobble * 0.02).toFloat(),
            wastegatePct = if (load > 0.7) 45f else 0f,
            pedalPct = (load * 100).toFloat(),
            mapKpa = (99 + boostKpa).toFloat(),
            avcsDeg = (load * 25).toFloat(),
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
        val t = (nowMs / 1000.0) % 90
        val wiggle = if (t in 26.0..60.0) 40 * sin((t - 26) / 3.5) else 0.0
        return ((90 + wiggle) % 360).toFloat()
    }

    /** Instant consumption for the demo, same formula as the real one. */
    fun litersPerHour(e: EcuSnapshot) = FuelCalc.litersPerHour(e, 1.0)

}
