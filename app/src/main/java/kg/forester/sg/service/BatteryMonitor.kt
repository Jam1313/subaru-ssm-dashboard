package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.BatteryEvent
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks battery health from ECU voltage:
 *  - resting: ignition on, engine not running (before cranking)
 *  - cranking: minimum voltage while the starter turns the engine
 *  - charging: alternator output with the engine running
 */
class BatteryMonitor(private val ctx: Context) {
    private var wasRunning = false
    private var restingSamples = ArrayList<Float>()
    private var preCrankMin: Float? = null
    private var crankStartMs = 0L
    private var cranking = false
    private var runningSinceMs = 0L
    private var chargeSamples = ArrayList<Float>()
    private var chargeLogged = false
    private var restingLogged = false
    private var voltSum = 0.0
    private var voltN = 0
    private var voltSlot = 0L

    fun process(s: EcuSnapshot) {
        val v = s.batteryV ?: return
        val rpm = s.rpm ?: 0
        val now = s.timeMs
        val running = rpm > 600
        val state = Repo.battery.value

        // 1-minute averages for the history chart
        val slot = now / 60_000 * 60_000
        if (voltSlot != 0L && slot != voltSlot && voltN > 0) {
            Repo.db.addVolt(voltSlot, voltSum / voltN, wasRunning)
            voltSum = 0.0; voltN = 0
        }
        voltSlot = slot
        voltSum += v; voltN++

        if (rpm == 0) {
            if (cranking) cranking = false
            restingSamples.add(v)
            if (restingSamples.size > 40) restingSamples.removeAt(0)
            preCrankMin = null
            // After 5 s of steady ignition-on readings, publish the resting voltage.
            if (restingSamples.size >= 20 && (now - state.restingTimeMs > 60_000 || state.restingV == null)) {
                val med = restingSamples.sorted()[restingSamples.size / 2]
                Repo.battery.value = state.copy(restingV = med, restingTimeMs = now)
                if (!restingLogged) {
                    restingLogged = true
                    event(now, "resting", med.toDouble(), 0.0)
                }
            }
        } else if (!running) {
            // Starter is turning the engine (rpm between 1 and 600).
            if (!cranking) {
                cranking = true; crankStartMs = now; preCrankMin = v
            }
            preCrankMin = minOf(preCrankMin ?: v, v)
        }

        if (running && !wasRunning) {
            runningSinceMs = now
            chargeSamples.clear()
            chargeLogged = false
            val minV = preCrankMin ?: restingSamples.minOrNull()
            if (minV != null) {
                val dur = if (crankStartMs > 0) now - crankStartMs else 0
                Repo.battery.value = Repo.battery.value.copy(crankMinV = minV, crankTimeMs = now, crankMs = dur)
                event(now, "crank", minV.toDouble(), dur.toDouble())
            }
            cranking = false; preCrankMin = null; restingSamples.clear(); restingLogged = false
        }

        if (running && now - runningSinceMs > 60_000 && rpm > 1000) {
            chargeSamples.add(v)
            if (chargeSamples.size > 600) chargeSamples.removeAt(0)
            val avg = chargeSamples.average().toFloat()
            Repo.battery.value = Repo.battery.value.copy(
                chargingV = avg,
                chargingMinV = minOf(Repo.battery.value.chargingMinV ?: v, v),
                chargingMaxV = maxOf(Repo.battery.value.chargingMaxV ?: v, v),
            )
            if (!chargeLogged && chargeSamples.size >= 120) {
                chargeLogged = true
                event(now, "charging", avg.toDouble(), (s.altDutyPct ?: 0f).toDouble())
            }
        }
        wasRunning = running
    }

    private fun event(now: Long, type: String, value: Double, extra: Double) {
        Repo.db.addBatteryEvent(BatteryEvent(now, type, value, extra))
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val f = File(dir, "battery.csv")
            val newFile = !f.exists()
            FileWriter(f, true).use { w ->
                if (newFile) w.write("datetime,type,volts,extra\n")
                val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
                w.write("$t,$type,${"%.2f".format(Locale.US, value)},${"%.0f".format(Locale.US, extra)}\n")
            }
        }
        Repo.touch()
    }

    companion object {
        /** Approximate state of charge of a rested 12 V lead-acid battery. */
        fun socPct(v: Float): Int = when {
            v >= 12.7f -> 100
            v <= 11.8f -> 0
            else -> ((v - 11.8f) / 0.9f * 100).toInt()
        }
    }
}
