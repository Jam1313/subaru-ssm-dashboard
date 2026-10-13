package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.Repo
import java.io.File

/**
 * Keeps storage bounded:
 *  - detailed per-run CSV logs: kept for [Settings.logRetentionHours] (default 24 h), folder capped at 200 MB
 *  - battery.csv: last 5000 lines, knock.csv: last 2000 lines
 *  - 10 s consumption buckets: compacted to 10 min after 2 days, dropped after 35 days
 *  - 1 min voltage history: dropped after 35 days
 * Trips, refills and battery events are tiny and kept forever.
 */
object Housekeeping {
    private const val MAX_LOG_BYTES = 200L * 1024 * 1024
    private const val DAY = 86_400_000L
    /** Long-lived journals, trimmed by lines instead of deleted by age. */
    private val journals = setOf("battery.csv", "knock.csv")

    fun run(ctx: Context, currentLog: String?) {
        val now = System.currentTimeMillis()
        runCatching { cleanLogs(ctx, now, currentLog) }
        runCatching { trimCsv(ctx, "battery.csv", 5000) }
        runCatching { trimCsv(ctx, "knock.csv", 2000) }
        runCatching { Repo.db.compact(now - 2 * DAY, now - 35 * DAY) }
        runCatching { kg.forester.sg.data.Backup.autoBackupIfDue(ctx) }
    }

    private fun cleanLogs(ctx: Context, now: Long, currentLog: String?) {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        val keepMs = Repo.settings.logRetentionHours * 3600_000L
        val files = dir.listFiles { f -> f.name.endsWith(".csv") && f.name !in journals && f.name != currentLog }
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

    private fun trimCsv(ctx: Context, name: String, keep: Int) {
        val f = File(File(ctx.getExternalFilesDir(null), "logs"), name)
        if (!f.exists() || f.length() < keep * 80L) return
        val lines = f.readLines()
        if (lines.size <= keep) return
        f.writeText((listOf(lines.first()) + lines.takeLast(keep)).joinToString("\n") + "\n")
    }
}
