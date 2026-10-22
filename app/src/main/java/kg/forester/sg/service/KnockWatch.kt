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
 * Turns the ECU knock correction (SSM 0x000022, or the FBKC where that pulls deeper) into timing-pull events for the
 * current trip. An event starts at [KnockStats.START_DEG] or deeper and ends once the correction is back at 0; one
 * starting again within [KnockStats.MERGE_MS] carries on, so a value flickering between -0.35° and 0 counts once.
 * Finished events are appended to logs/knock.csv. A pull of [kg.forester.sg.data.Settings.knockBeepDeg] or deeper,
 * from [kg.forester.sg.data.Settings.knockBeepRpm] up, beeps once (the BTSSM beeper, without −1° low down beeping all the time).
 */
class KnockWatch(ctx: Context) {
    private val file = File(File(ctx.getExternalFilesDir(null), "logs"), "knock.csv")
    private var stats = KnockStats(sinceMs = System.currentTimeMillis())
    /** The last pull, ended but not yet in knock.csv: it may still carry on (see [KnockStats.MERGE_MS]). */
    private var pending: KnockEvent? = null
    /** The pull that has beeped already (its start), and the last beep. */
    private var beepedStart = -1L
    private var beepMs = 0L

    init {
        Repo.knock.value = stats
    }

    /** [record] false keeps demo data out of knock.csv. */
    @Synchronized
    fun process(s: EcuSnapshot, record: Boolean = true) {
        val k = s.knockDeg ?: return
        pending?.let { if (s.timeMs - (it.startMs + it.durationMs) > KnockStats.MERGE_MS) flush() }
        val open = stats.events.lastOrNull()?.takeIf { it.active }
        when {
            // engine stopped: the byte means nothing then
            (s.rpm ?: 0) < 300 -> if (open != null) close(open, s.timeMs, record)
            open == null -> if (k <= KnockStats.START_DEG) {
                val last = stats.events.lastOrNull()
                // flicker: the pull that just ended goes on (its deepest point kept)
                if (last != null && s.timeMs - (last.startMs + last.durationMs) in 0..KnockStats.MERGE_MS) {
                    pending = null
                    publish(stats.events.dropLast(1) + (if (k < last.maxPullDeg) event(s, k, last.startMs) else last.copy(active = true)))
                } else publish(stats.events + event(s, k, s.timeMs))
            }
            k >= 0f -> close(open, s.timeMs, record)
            k < open.maxPullDeg -> publish(stats.events.dropLast(1) + event(s, k, open.startMs))
        }
        if (record) stats.events.lastOrNull()?.takeIf { it.active }?.let { beep(it, k, s.rpm ?: 0, s.timeMs) }
    }

    private fun beep(e: KnockEvent, k: Float, rpm: Int, now: Long) {
        val deg = Repo.settings.knockBeepDeg
        if (deg >= 0f || k > deg || rpm < Repo.settings.knockBeepRpm || e.startMs == beepedStart || now - beepMs < 2000L) return
        beepedStart = e.startMs; beepMs = now
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 250)
        }
    }

    /** A new trip: keep the old trip's pulls in knock.csv only. */
    @Synchronized
    fun reset(now: Long, record: Boolean = true) {
        stats.events.lastOrNull()?.takeIf { it.active }?.let { close(it, now, record) }
        flush()
        publish(emptyList(), now)
    }

    private fun event(s: EcuSnapshot, k: Float, startMs: Long) = KnockEvent(
        startMs = startMs, maxPullDeg = k, rpm = s.rpm, loadGRev = s.loadGRev, throttlePct = s.throttlePct,
        speedKmh = s.speedKmh, coolantC = s.coolantC, timingDeg = s.timingDeg,
    )

    private fun close(e: KnockEvent, now: Long, record: Boolean) {
        val done = e.copy(durationMs = now - e.startMs, active = false)
        publish(stats.events.dropLast(1) + done)
        if (record) pending = done
    }

    private fun flush() {
        pending?.let { runCatching { append(it) } }
        pending = null
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
