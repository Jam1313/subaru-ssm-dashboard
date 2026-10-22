package kg.forester.sg.service

import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import java.io.File

/**
 * The last minutes of samples in the CSV log's format, so an acceleration run or a lap can be kept as a log of its
 * own and looked at on the log viewer's charts and map. The GPS and G columns are always in, whatever the «Лог»
 * screen has switched off: without them there is no map.
 */
object PerfLog {
    private class Row(val ms: Long, val line: String)

    private val rows = ArrayDeque<Row>()
    private var keys: List<String> = emptyList()
    private var columns: List<LogColumn> = emptyList()

    @Synchronized
    fun record(e: EcuSnapshot, gps: GpsFix?, now: Long) {
        val on = LogColumns.enabled().map { it.key }.toSet()
        val cols = LogColumns.all.filter { it.key in on || it.group == "GPS" || it.group == "Перегрузки" }
        if (cols.size != columns.size || cols.map { it.key } != keys) {
            // other columns: what is in the buffer no longer fits the header
            columns = cols; keys = cols.map { it.key }; rows.clear()
        }
        val row = LogRow(e, gps, Repo.trip.value.instLh, Repo.tcu.value, Repo.guard.value)
        rows.addLast(Row(now, columns.joinToString(",") { it.get(row) }))
        // a lap of a big track takes a few minutes; 20 minutes or 20 000 samples is plenty
        while (rows.isNotEmpty() && (rows.size > 20_000 || now - rows.first().ms > 20 * 60_000L)) rows.removeFirst()
    }

    /**
     * Writes the samples from [fromMs] to [toMs] to [dir]/"[title] <start>.csv", the time as in the drive logs (ms from the
     * start to the second first, epoch ms last); the file name, or null with too little to keep.
     */
    @Synchronized
    fun save(dir: File, title: String, fromMs: Long, toMs: Long): String? = runCatching {
        val part = rows.filter { it.ms in fromMs..toMs }
        if (part.size < 5) return@runCatching null
        dir.mkdirs()
        val t0 = part.first().ms / 1000 * 1000
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date(t0))
        val f = File(dir, "$title $stamp.csv")
        f.bufferedWriter().use { w ->
            w.write(LogColumns.header(keys) + "\n")
            part.forEach { w.write(LogColumns.line(it.ms, t0, it.line) + "\n") }
        }
        f.name
    }.getOrNull()
}
