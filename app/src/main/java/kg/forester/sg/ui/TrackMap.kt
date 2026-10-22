package kg.forester.sg.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import java.io.File
import kotlin.math.cos

/** A GPS track: [lat] and [lon] side by side, NaN where there was no fix. */
class Track(val lat: DoubleArray, val lon: DoubleArray) {
    val size get() = lat.size
    fun ok(i: Int) = !lat[i].isNaN() && !lon[i].isNaN()
    val any: Boolean get() = (0 until size).count(::ok) >= 2

    companion object {
        fun of(points: List<kg.forester.sg.service.GeoPt>) =
            Track(DoubleArray(points.size) { points[it].lat }, DoubleArray(points.size) { points[it].lon })

        /** The gps_lat / gps_lon columns of a CSV log the app wrote; null without them. */
        fun read(f: File): Track? = runCatching {
            f.bufferedReader().use { r ->
                val head = r.readLine()?.split(',') ?: return null
                val a = head.indexOf("gps_lat"); val b = head.indexOf("gps_lon")
                if (a < 0 || b < 0) return null
                val la = ArrayList<Double>(); val lo = ArrayList<Double>()
                r.lineSequence().forEach { line ->
                    val c = line.split(',')
                    la += c.getOrNull(a)?.toDoubleOrNull() ?: Double.NaN
                    lo += c.getOrNull(b)?.toDoubleOrNull() ?: Double.NaN
                }
                Track(la.toDoubleArray(), lo.toDoubleArray()).takeIf { it.any }
            }
        }.getOrNull()
    }
}

/**
 * How a [Track] is drawn: [hi] is the part drawn bright (null: all of it), [mark] the point with a dot; with [start]
 * and [finish] its first point gets a green start mark and its last one the chequered flag.
 */
class TrackLine(
    val track: Track, val color: Color, val width: Float = 4f, val hi: IntRange? = null, val mark: Int? = null,
    val start: Boolean = false, val finish: Boolean = false,
)

/** Where the tracks land in a box of [w]×[h]: the same fit for drawing and for finding the point under a finger. */
class TrackFit(private val k: Double, private val scale: Double, private val ox: Double, private val oy: Double) {
    fun pt(la: Double, lo: Double) = Offset((lo * k * scale + ox).toFloat(), (oy - la * scale).toFloat())

    /** The index of [t]'s point nearest to [p], or null if none is within [reach] px. */
    fun nearest(t: Track, p: Offset, reach: Float = 60f): Int? {
        var best = -1; var bd = reach * reach
        for (i in 0 until t.size) if (t.ok(i)) {
            val q = pt(t.lat[i], t.lon[i])
            val d = (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
            if (d < bd) { bd = d; best = i }
        }
        return best.takeIf { it >= 0 }
    }

    companion object {
        fun of(lines: List<Track>, flag: Pair<Double, Double>?, w: Float, h: Float): TrackFit? {
            // the bounds, on the thinned points (a drive's log has hundreds of thousands, and this runs on every frame)
            var minLa = Double.MAX_VALUE; var maxLa = -Double.MAX_VALUE; var minLo = Double.MAX_VALUE; var maxLo = -Double.MAX_VALUE
            var n = 0
            fun take(la: Double, lo: Double) { minLa = minOf(minLa, la); maxLa = maxOf(maxLa, la); minLo = minOf(minLo, lo); maxLo = maxOf(maxLo, lo); n++ }
            for (t in lines) for (i in 0 until t.size step (t.size / 3000).coerceAtLeast(1)) if (t.ok(i)) take(t.lat[i], t.lon[i])
            flag?.let { take(it.first, it.second) }
            if (n == 0) return null
            val k = cos(Math.toRadians((minLa + maxLa) / 2))
            val minX = minLo * k; val maxX = maxLo * k
            // at least ~150 m across, so a slow start does not fill the box with GPS jitter
            val span = maxOf(maxX - minX, maxLa - minLa, 150.0 / 111_000)
            val pad = 10f
            val scale = (minOf(w, h) - pad * 2) / span
            return TrackFit(k, scale, w / 2 - (minX + maxX) / 2 * scale, h / 2 + (minLa + maxLa) / 2 * scale)
        }
    }
}

/**
 * Tracks drawn to fit the box together, north up, with a soft glow; [flag] marks a place (the start/finish line).
 * Long logs are thinned to a few thousand points per line.
 */
fun DrawScope.drawTracks(lines: List<TrackLine>, flag: Pair<Double, Double>? = null) {
    val fit = TrackFit.of(lines.map { it.track }, flag, size.width, size.height) ?: return
    fun pt(la: Double, lo: Double) = fit.pt(la, lo)
    for (l in lines) {
        val t = l.track
        val step = (t.size / 3000).coerceAtLeast(1)
        fun path(range: IntRange): Path? {
            val p = Path(); var n = 0
            for (i in range step step) if (t.ok(i)) pt(t.lat[i], t.lon[i]).let { if (n++ == 0) p.moveTo(it.x, it.y) else p.lineTo(it.x, it.y) }
            // the range's own last point, which the thinning may have skipped
            if (range.last >= 0 && t.ok(range.last) && n > 0) pt(t.lat[range.last], t.lon[range.last]).let { p.lineTo(it.x, it.y) }
            return if (n >= 1) p else null
        }
        val stroke = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        val whole = path(0 until t.size) ?: continue
        if (l.hi == null) {
            drawPath(whole, l.color.copy(alpha = l.color.alpha * 0.3f), style = stroke(l.width * 2.4f))
            drawPath(whole, l.color, style = stroke(l.width))
        } else {
            drawPath(whole, l.color.copy(alpha = l.color.alpha * 0.3f), style = stroke(l.width))
            path(l.hi.first.coerceAtLeast(0)..l.hi.last.coerceAtMost(t.size - 1))?.let {
                drawPath(it, l.color.copy(alpha = l.color.alpha * 0.3f), style = stroke(l.width * 2.4f))
                drawPath(it, l.color, style = stroke(l.width))
            }
        }
        if (l.start) (0 until t.size).firstOrNull(t::ok)?.let { i ->
            val p = pt(t.lat[i], t.lon[i])
            drawCircle(Color.White, 8f, p)
            drawCircle(Color(0xFF22C55E), 6f, p)
        }
        if (l.finish) (t.size - 1 downTo 0).firstOrNull(t::ok)?.let { i -> chequered(pt(t.lat[i], t.lon[i])) }
        // the dot: the given point, or the nearest one with a fix
        l.mark?.let { m ->
            val c = m.coerceIn(0, t.size - 1)
            var d = 0
            while (d < t.size && !(c - d >= 0 && t.ok(c - d)) && !(c + d < t.size && t.ok(c + d))) d++
            val i = if (c - d >= 0 && t.ok(c - d)) c - d else if (c + d < t.size && t.ok(c + d)) c + d else return@let
            val p = pt(t.lat[i], t.lon[i])
            drawCircle(Color(0xFF59B4FF).copy(alpha = 0.35f), 13f, p)
            drawCircle(Color.White, 7.5f, p)
            drawCircle(Color(0xFF2E8BFF), 5.5f, p)
        }
    }
    flag?.let { (la, lo) -> chequered(pt(la, lo)) }
}

/** A chequered flag standing at [p]: the pole and a small square. */
private fun DrawScope.chequered(p: Offset) {
    drawLine(Color.White, p, Offset(p.x, p.y - 22f), 2.5f)
    for (r in 0..1) for (c in 0..2) drawRect(
        if ((r + c) % 2 == 0) Color.White else Color.Black,
        Offset(p.x + c * 5f, p.y - 22f + r * 5f), androidx.compose.ui.geometry.Size(5f, 5f),
    )
    drawCircle(Color.White, 3.5f, p)
}
