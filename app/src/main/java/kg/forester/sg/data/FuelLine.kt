package kg.forester.sg.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The fuel gauge wire's own calibration, done by hand as with Multitronics: with the tank nearly empty, note the
 * litres in it, then pour a step (5–10 l) at a time and mark each point. Two points or more and the litres in the
 * tank come from them (between points on a straight line, beyond the ends at the end values); fewer, and the app
 * learns from the refills by itself (service/FuelSensorCal).
 */
object FuelLine {
    /** (line volts, litres), sorted by volts. */
    val points = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    /** The litres the next point is marked with, and the step a «долил» adds. */
    val litres = MutableStateFlow(0f)
    val step = MutableStateFlow(10f)

    fun load() {
        points.value = runCatching { parse(Repo.settings.fuelLinePoints) }.getOrDefault(emptyList())
    }

    fun parse(s: String): List<Pair<Float, Float>> = s.split(';').mapNotNull { e ->
        val f = e.split(',').mapNotNull { it.toFloatOrNull() }
        if (f.size == 2) f[0] to f[1] else null
    }.sortedBy { it.first }

    fun format(l: List<Pair<Float, Float>>) = l.joinToString(";") { "%.3f,%.1f".format(java.util.Locale.US, it.first, it.second) }

    private fun save() { runCatching { Repo.settings.fuelLinePoints = format(points.value) } }

    /** A point at [volts] for the litres set now; a point at almost the same volts is replaced, not doubled. */
    fun mark(volts: Float) {
        val l = litres.value
        points.value = (points.value.filter { kotlin.math.abs(it.first - volts) > 0.03f && it.second != l } + (volts to l)).sortedBy { it.first }
        save()
    }

    /**
     * A point the app put itself, at a stop with the litres known from a full tank: a new one where none is within
     * [gap] litres; else the nearest within half of it drifts a third of the way to this reading (a later tank corrects
     * an earlier one, a single odd reading moves little).
     */
    fun learn(volts: Float, litres: Float, gap: Float = 3f) {
        val near = points.value.minByOrNull { kotlin.math.abs(it.second - litres) }
        val d = near?.let { kotlin.math.abs(it.second - litres) }
        points.value = when {
            near == null || d!! >= gap -> (points.value.filter { kotlin.math.abs(it.first - volts) > 0.03f } + (volts to litres)).sortedBy { it.first }
            d <= gap / 2 -> points.value.map { if (it == near) (it.first + (volts - it.first) / 3f) to it.second else it }.sortedBy { it.first }
            else -> return
        }
        save()
    }

    fun remove(p: Pair<Float, Float>) { points.value = points.value - p; save() }
    fun clear() { points.value = emptyList(); save() }

    /** Litres in the tank for [volts] by the points, or null with fewer than two. */
    fun litresAt(volts: Float, pts: List<Pair<Float, Float>> = points.value): Float? {
        if (pts.size < 2) return null
        if (volts <= pts.first().first) return pts.first().second
        if (volts >= pts.last().first) return pts.last().second
        val j = pts.indexOfFirst { it.first >= volts }
        val (x0, y0) = pts[j - 1]
        val (x1, y1) = pts[j]
        return if (x1 == x0) y0 else y0 + (y1 - y0) * (volts - x0) / (x1 - x0)
    }

    /**
     * Litres for [volts] only where the points really say it: inside their range (a little margin), or anywhere once
     * they cover most of the tank. Below the lowest point of a tank learnt only from the top, null: count as before.
     */
    fun covered(volts: Float, tankL: Float, pts: List<Pair<Float, Float>> = points.value): Float? {
        if (pts.size < 2) return null
        val span = pts.maxOf { it.second } - pts.minOf { it.second }
        val inside = volts >= pts.first().first - 0.1f && volts <= pts.last().first + 0.1f
        return if (inside || (tankL > 0 && span >= tankL * 0.8f)) litresAt(volts, pts) else null
    }

    /** The points read the same way round: litres only rise (or only fall) with the volts. */
    fun consistent(pts: List<Pair<Float, Float>> = points.value): Boolean {
        if (pts.size < 3) return true
        val d = pts.zipWithNext { a, b -> b.second - a.second }.filter { it != 0f }
        return d.all { it > 0 } || d.all { it < 0 }
    }
}
