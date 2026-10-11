package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.BatteryEvent
import kg.forester.sg.data.BatteryState
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
 *  - cranking: minimum voltage in the last seconds before the engine catches
 *  - charging: alternator output once the engine has been running for a while (idle counts)
 * The last value of each kind is restored from the event log on start.
 */
class BatteryMonitor(private val ctx: Context) {
    private data class Sample(val t: Long, val v: Float, val rpm: Int)

    private val recent = ArrayDeque<Sample>()
    private var wasRunning = false
    private var lastStoppedMs = 0L
    private var runningSinceMs = 0L
    private val restingSamples = ArrayList<Float>()
    private var restingLogged = false
    private val chargeSamples = ArrayList<Float>()
    private var chargeLogged = false
    private var voltSum = 0.0
    private var voltN = 0
    private var voltSlot = 0L

    init {
        restoreFromLog()
    }

    fun process(s: EcuSnapshot) {
        val v = s.batteryV ?: return
        val rpm = s.rpm ?: 0
        val now = s.timeMs
        val running = rpm > 500

        recent.addLast(Sample(now, v, rpm))
        while (recent.isNotEmpty() && now - recent.first().t > 4000) recent.removeFirst()

        // 1-minute averages for the history chart
        val slot = now / 60_000 * 60_000
        if (voltSlot != 0L && slot != voltSlot && voltN > 0) {
            Repo.db.addVolt(voltSlot, voltSum / voltN, wasRunning)
            voltSum = 0.0; voltN = 0
        }
        voltSlot = slot
        voltSum += v; voltN++

        if (rpm == 0) {
            lastStoppedMs = now
            restingSamples.add(v)
            if (restingSamples.size > 40) restingSamples.removeAt(0)
            // after ~5 s of steady ignition-on readings publish the resting voltage
            if (restingSamples.size >= 20) {
                val med = restingSamples.sorted()[restingSamples.size / 2]
                Repo.battery.value = Repo.battery.value.copy(restingV = med, restingTimeMs = now)
                if (!restingLogged) {
                    restingLogged = true
                    event(now, "resting", med.toDouble(), 0.0)
                }
            }
        }

        if (running && !wasRunning) {
            runningSinceMs = now
            chargeSamples.clear()
            chargeLogged = false
            // A real start: the engine was stopped within the last 10 s. The ECU can report 0 rpm while
            // the starter turns, so take the minimum over the last few seconds of samples.
            if (now - lastStoppedMs < 10_000) {
                val window = recent.filter { it.rpm < 500 }
                val minV = window.minOfOrNull { it.v }
                if (minV != null) {
                    val crankStart = window.firstOrNull { it.v < (restingSamples.lastOrNull() ?: minV) - 0.3f }?.t
                    val dur = if (crankStart != null) now - crankStart else 0L
                    Repo.battery.value = Repo.battery.value.copy(crankMinV = minV, crankTimeMs = now, crankMs = dur)
                    event(now, "crank", minV.toDouble(), dur.toDouble())
                }
            }
            restingSamples.clear()
            restingLogged = false
        }

        if (running && now - runningSinceMs > 30_000) {
            chargeSamples.add(v)
            if (chargeSamples.size > 600) chargeSamples.removeAt(0)
            val b = Repo.battery.value
            Repo.battery.value = b.copy(
                chargingV = chargeSamples.average().toFloat(),
                chargingMinV = minOf(b.chargingMinV ?: v, v),
                chargingMaxV = maxOf(b.chargingMaxV ?: v, v),
            )
            if (!chargeLogged && chargeSamples.size >= 60) {
                chargeLogged = true
                event(now, "charging", chargeSamples.average(), (s.altDutyPct ?: 0f).toDouble())
            }
        }
        wasRunning = running
    }

    private fun restoreFromLog() {
        runCatching { Repo.db.purgeBogusCranks() }
        val events = runCatching { Repo.db.batteryEvents(200) }.getOrDefault(emptyList())
        val resting = events.firstOrNull { it.type == "resting" }
        val crank = events.firstOrNull { it.type == "crank" }
        val charging = events.firstOrNull { it.type == "charging" }
        Repo.battery.value = BatteryState(
            restingV = resting?.value?.toFloat(), restingTimeMs = resting?.timeMs ?: 0,
            crankMinV = crank?.value?.toFloat(), crankTimeMs = crank?.timeMs ?: 0, crankMs = crank?.extra?.toLong(),
            chargingV = charging?.value?.toFloat(),
        )
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
