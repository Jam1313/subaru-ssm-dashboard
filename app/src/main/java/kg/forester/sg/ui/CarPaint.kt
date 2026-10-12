package kg.forester.sg.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/**
 * Body colours for the pre-rendered 3D car. The frames are rendered in the chameleon paint; another colour
 * is made by re-tinting the strongly coloured pixels (the paint). Shading and reflections stay, while
 * near-grey pixels (glass, chrome, tyres, cabin) and the red tail lights are left alone.
 */
data class CarPaint(val id: String, val name: String, val swatch: Color, val hue: Float? = null, val grey: Float = 0f, val lift: Float = 0f)

val CarPaints = listOf(
    CarPaint("chameleon", "Хамелеон", Color(0xFF6D28D9)),
    CarPaint("blue", "Синий WR", Color(0xFF1D4ED8), hue = 222f),
    CarPaint("red", "Красный", Color(0xFFB91C1C), hue = 356f),
    CarPaint("green", "Зелёный", Color(0xFF15803D), hue = 145f),
    CarPaint("orange", "Оранжевый", Color(0xFFEA580C), hue = 24f),
    CarPaint("black", "Чёрный", Color(0xFF111111), grey = 0.5f),
    CarPaint("graphite", "Графит", Color(0xFF4B5563), grey = 0.9f, lift = 0.05f),
    // White and silver need frames re-rendered with a body mask; re-tinting the chameleon looks blotchy.
)

fun carPaint(id: String) = CarPaints.firstOrNull { it.id == id } ?: CarPaints.first()

fun recolor(src: Bitmap, p: CarPaint): Bitmap {
    if (p.hue == null && p.grey == 0f) return src
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val c = px[i]
        val a = c ushr 24
        if (a == 0) continue
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (mx < 0.04f || d <= 0f) continue
        val s = d / mx
        if (s < 0.08f) continue
        var hue = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (hue < 0f) hue += 360f
        if (hue < 22f || hue > 338f) continue // tail lights
        // fade in between "a bit coloured" and "clearly paint" so edges don't band
        val k = ((s - 0.08f) / 0.17f).coerceIn(0f, 1f)
        var nh: Float
        var ns: Float
        var nv: Float
        if (p.hue != null) {
            nh = p.hue
            ns = maxOf(s, 0.6f)
            nv = minOf(1f, mx * 1.12f)
        } else {
            nh = hue
            ns = 0.03f
            nv = minOf(1f, p.lift + mx * p.grey)
        }
        // HSV -> RGB
        val cc = nv * ns
        val x = cc * (1f - kotlin.math.abs((nh / 60f) % 2f - 1f))
        val m = nv - cc
        val (r1, g1, b1) = when ((nh / 60f).toInt() % 6) {
            0 -> Triple(cc, x, 0f)
            1 -> Triple(x, cc, 0f)
            2 -> Triple(0f, cc, x)
            3 -> Triple(0f, x, cc)
            4 -> Triple(x, 0f, cc)
            else -> Triple(cc, 0f, x)
        }
        val rr = r + ((r1 + m) - r) * k
        val gg = g + ((g1 + m) - g) * k
        val bb = b + ((b1 + m) - b) * k
        px[i] = (a shl 24) or ((rr * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((gg * 255f).toInt().coerceIn(0, 255) shl 8) or (bb * 255f).toInt().coerceIn(0, 255)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}
