package kg.forester.sg.ssm

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * «Что идёт по CAN»: every id heard (from a CANable or the OpenPort listening), its last bytes and how often it
 * comes. To find where a stream keeps a value (an EGT a tuner added to the Link's stream) without a laptop.
 * A compound stream (one id, byte 0 = frame number) gets a row per frame.
 */
object CanMonitor {
    class Row(val id: Int, val ext: Boolean, val mux: Int?, val data: ByteArray, val count: Int, val hz: Float, val lastMs: Long) {
        val idText: String get() = (if (ext) "%08X" else "%03X").format(id) + (mux?.let { " #$it" } ?: "")
        val bytesText: String get() = data.joinToString(" ") { "%02X".format(it) }
    }

    private class Acc(val id: Int, val ext: Boolean, val mux: Int?) {
        var data = ByteArray(0)
        var count = 0
        var lastMs = 0L
        var hz = 0f
        var winStart = 0L
        var winCount = 0
    }

    private val map = ConcurrentHashMap<Long, Acc>()
    val rows = MutableStateFlow<List<Row>>(emptyList())
    private var publishedMs = 0L

    private fun key(id: Int, ext: Boolean, mux: Int?) = (id.toLong() shl 9) or (if (ext) 256L else 0L) or (mux?.toLong() ?: 255L)

    fun feed(id: Int, ext: Boolean, data: ByteArray, now: Long = System.currentTimeMillis(), mux: Int? = null) {
        val k = key(id, ext, mux)
        val a = map[k] ?: if (map.size >= 400) return else Acc(id, ext, mux).also { map[k] = it }
        a.data = data.copyOf()
        a.count++
        a.lastMs = now
        if (a.winStart == 0L) a.winStart = now
        a.winCount++
        if (now - a.winStart >= 1000) { a.hz = a.winCount * 1000f / (now - a.winStart); a.winStart = now; a.winCount = 0 }
        if (now - publishedMs >= 250) publish(now)
    }

    fun publish(now: Long = System.currentTimeMillis()) {
        publishedMs = now
        rows.value = map.values.filter { now - it.lastMs < 5000 }
            .sortedWith(compareBy<Acc>({ it.ext }, { it.id }, { it.mux ?: -1 }))
            .map { Row(it.id, it.ext, it.mux, it.data, it.count, it.hz, it.lastMs) }
    }

    /** Different ids heard in the last 5 s. */
    fun idsSeen(now: Long = System.currentTimeMillis()): Int = map.values.filter { now - it.lastMs < 5000 }.map { it.id }.distinct().size

    fun clear() { map.clear(); rows.value = emptyList() }
}
