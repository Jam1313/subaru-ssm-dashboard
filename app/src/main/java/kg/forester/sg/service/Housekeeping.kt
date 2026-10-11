package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.Repo
import java.io.File

/**
 * Keeps storage bounded:
 *  - detailed per-run CSV logs: kept for [Settings.logRetentionHours] (default 24 h), folder capped at 200 MB
 *  - battery.csv: last 5000 lines
 *  - 10 s consumption buckets: compacted to 10 min after 2 days, dropped after 35 days
 *  - 1 min voltage history: dropped after 35 days
 * Trips, refills and battery events are tiny and kept forever.
 */
object Housekeeping {
    private const val MAX_LOG_BYTES = 200L * 1024 * 1024
    private const val DAY = 86_400_000L

    fun run(ctx: Context, currentLog: String?) {
        val now = System.currentTimeMillis()
        runCatching { cleanLogs(ctx, now, currentLog) }
        runCatching { trimBatteryCsv(ctx) }
        runCatching { Repo.db.compact(now - 2 * DAY, now - 35 * DAY) }
    }

    private fun cleanLogs(ctx: Context, now: Long, currentLog: String?) {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        val keepMs = Repo.settings.logRetentionHours * 3600_000L
        val files = dir.listFiles { f -> f.name.endsWith(".csv") && f.name != "battery.csv" && f.name != currentLog }
            ?.sortedBy { it.lastModified() } ?: return
        val remaining = files.filter { f ->
            val old = now - f.lastModified() > keepMs
            if (old) f.delete()
            !old
        }.toMutableList()
        var total = remaining.sumOf { it.length() }
        while (total > MAX_LOG_BYTES && remaining.isNotEmpty()) {
            val f = remaining.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }

    private fun trimBatteryCsv(ctx: Context) {
        val f = File(File(ctx.getExternalFilesDir(null), "logs"), "battery.csv")
        if (!f.exists() || f.length() < 400_000) return
        val lines = f.readLines()
        if (lines.size <= 5000) return
        f.writeText((listOf(lines.first()) + lines.takeLast(5000)).joinToString("\n") + "\n")
    }
}
