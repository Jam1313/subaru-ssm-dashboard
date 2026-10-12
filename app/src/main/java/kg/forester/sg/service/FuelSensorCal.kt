package kg.forester.sg.service

import android.content.Context

/**
 * Tank level from the ECU's fuel sender reading (only ECUs that report it, e.g. some SH; the SG does not).
 *
 * There is no reliable published table for the twin-sender saddle tank, so the mapping is learned:
 * while the car stands, the sender reading is paired with the litres known from the refills.
 * Once the pairs cover a good part of the tank, the level comes from the sender instead.
 */
class FuelSensorCal(ctx: Context) {
    private val p = ctx.getSharedPreferences("fuelcal", Context.MODE_PRIVATE)
    private val bins = 50
    private val sum = DoubleArray(bins)
    private val cnt = IntArray(bins)
    private var unit = p.getString("unit", "")!!
    private var smooth: Double? = null
    private var lastLearnMs = 0L
    private var lastSaveMs = 0L

    init {
        p.getString("bins", null)?.split(';')?.forEach { e ->
            val f = e.split(',')
            val i = f.getOrNull(0)?.toIntOrNull() ?: return@forEach
            if (i in 0 until bins) {
                sum[i] = f.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                cnt[i] = f.getOrNull(2)?.toIntOrNull() ?: 0
            }
        }
    }

    /** Volts span 0…5, ohms 0…400 (both senders in series). */
    private fun bin(x: Double, u: String) =
        ((x / (if (u == "Ohm") 400.0 else 5.0)) * bins).toInt().coerceIn(0, bins - 1)

    private fun center(i: Int, u: String) = (i + 0.5) / bins * (if (u == "Ohm") 400.0 else 5.0)

    /**
     * One sample: [v]/[ohm] from the ECU, [knownLitres] from the refills (null when unknown).
     * Returns the litres by the sender, or null while it is not learned yet.
     */
    fun process(v: Float?, ohm: Float?, knownLitres: Double?, stopped: Boolean, tankL: Double, now: Long): Double? {
        val (x, u) = when {
            ohm != null -> ohm.toDouble() to "Ohm"
            v != null -> v.toDouble() to "V"
            else -> return null
        }
        if (u != unit) { // another ECU or sender: start over
            unit = u; sum.fill(0.0); cnt.fill(0); smooth = null
            p.edit().putString("unit", u).remove("bins").apply()
        }
        // the sender swings with every bump and turn: smooth over ~30 s
        val sm = smooth?.let { it + (x - it) * 0.03 } ?: x
        smooth = sm
        if (stopped && knownLitres != null && now - lastLearnMs > 5000) {
            lastLearnMs = now
            val i = bin(sm, u)
            sum[i] += knownLitres; cnt[i]++
            if (now - lastSaveMs > 60_000) save(now)
        }
        return lookup(sm, u, tankL)
    }

    private fun lookup(x: Double, u: String, tankL: Double): Double? {
        val pts = (0 until bins).filter { cnt[it] >= 6 }.map { center(it, u) to sum[it] / cnt[it] }
        if (pts.size < 3) return null
        val span = pts.maxOf { it.second } - pts.minOf { it.second }
        val needSpan = if (tankL > 0) tankL * 0.35 else 20.0
        if (span < needSpan) return null
        val l = when {
            x <= pts.first().first -> pts.first().second
            x >= pts.last().first -> pts.last().second
            else -> {
                val j = pts.indexOfFirst { it.first >= x }
                val (x0, y0) = pts[j - 1]
                val (x1, y1) = pts[j]
                y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return if (tankL > 0) l.coerceIn(0.0, tankL) else l.coerceAtLeast(0.0)
    }

    private fun save(now: Long) {
        lastSaveMs = now
        p.edit().putString("bins", (0 until bins).filter { cnt[it] > 0 }.joinToString(";") { "$it,${sum[it]},${cnt[it]}" }).apply()
    }
}
