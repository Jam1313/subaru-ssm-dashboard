package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.KnockEvent
import kg.forester.sg.data.KnockStats
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the ECU knock correction (SSM 0x000022) into timing-pull events for the current trip.
 * An event starts at [KnockStats.START_DEG] or deeper and ends once the correction is back at 0,
 * so a value flickering between -0.5° and 0 is not counted again and again.
 * Finished events are appended to logs/knock.csv.
 */
class KnockWatch(ctx: Context) {
    private val file = File(File(ctx.getExternalFilesDir(null), "logs"), "knock.csv")
    private var stats = KnockStats(sinceMs = System.currentTimeMillis())

    init {
        Repo.knock.value = stats
    }

    /** [record] false keeps demo data out of knock.csv. */
    @Synchronized
    fun process(s: EcuSnapshot, record: Boolean = true) {
        val k = s.knockDeg ?: return
        val open = stats.events.lastOrNull()?.takeIf { it.active }
        when {
            // engine stopped: the byte means nothing then
            (s.rpm ?: 0) < 300 -> if (open != null) close(open, s.timeMs, record)
            open == null -> if (k <= KnockStats.START_DEG) publish(stats.events + event(s, k, s.timeMs))
            k >= 0f -> close(open, s.timeMs, record)
            k < open.maxPullDeg -> publish(stats.events.dropLast(1) + event(s, k, open.startMs))
        }
    }

    /** A new trip: keep the old trip's pulls in knock.csv only. */
    @Synchronized
    fun reset(now: Long, record: Boolean = true) {
        stats.events.lastOrNull()?.takeIf { it.active }?.let { close(it, now, record) }
        publish(emptyList(), now)
    }

    private fun event(s: EcuSnapshot, k: Float, startMs: Long) = KnockEvent(
        startMs = startMs, maxPullDeg = k, rpm = s.rpm, loadGRev = s.loadGRev, throttlePct = s.throttlePct,
        speedKmh = s.speedKmh, coolantC = s.coolantC, timingDeg = s.timingDeg,
    )

    private fun close(e: KnockEvent, now: Long, record: Boolean) {
        val done = e.copy(durationMs = now - e.startMs, active = false)
        publish(stats.events.dropLast(1) + done)
        if (record) runCatching { append(done) }
    }

    private fun publish(events: List<KnockEvent>, sinceMs: Long = stats.sinceMs) {
        stats = KnockStats(sinceMs, events)
        Repo.knock.value = stats
    }

    private fun append(e: KnockEvent) {
        file.parentFile?.mkdirs()
        val fresh = !file.exists()
        FileWriter(file, true).use { w ->
            if (fresh) w.write("time,duration_s,max_pull_deg,rpm,load_g_rev,throttle_pct,speed_kmh,coolant_c,timing_deg\n")
            fun f(x: Float?) = x?.let { "%.2f".format(Locale.US, it) } ?: ""
            w.write(
                listOf(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.startMs)),
                    "%.1f".format(Locale.US, e.durationMs / 1000.0), f(e.maxPullDeg), e.rpm?.toString() ?: "",
                    f(e.loadGRev), f(e.throttlePct), e.speedKmh?.toString() ?: "", e.coolantC?.toString() ?: "", f(e.timingDeg),
                ).joinToString(",") + "\n"
            )
        }
    }
}
