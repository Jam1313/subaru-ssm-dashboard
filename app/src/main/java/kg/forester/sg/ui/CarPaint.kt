package kg.forester.sg.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/**
 * Body colours for the pre-rendered 3D car.
 *
 * Models that ship white-body frames + a body mask (assets/<dir>/white_NNN.webp, mask_NNN.webp) can take any
 * colour: the white body is multiplied by the colour inside the mask, highlights stay white.
 * Models with only the chameleon frames get [hue]/[grey] re-tints of the strongly coloured pixels; light
 * colours ([needsWhite]) are not possible there.
 */
data class CarPaint(
    val id: String, val name: String, val rgb: Int,
    val hue: Float? = null, val grey: Float = 0f, val lift: Float = 0f, val needsWhite: Boolean = false,
) {
    val swatch get() = Color(0xFF000000.toInt() or rgb)
}

val CarPaints = listOf(
    CarPaint("chameleon", "Хамелеон", 0x6D28D9),
    CarPaint("white", "Белый", 0xF1F2F4, needsWhite = true),
    CarPaint("silver", "Серебро", 0xB4BAC3, needsWhite = true),
    CarPaint("graphite", "Графит", 0x4A5058, grey = 0.9f, lift = 0.05f),
    CarPaint("black", "Чёрный", 0x18191C, grey = 0.5f),
    CarPaint("blue", "Синий WR", 0x1D4ED8, hue = 222f),
    CarPaint("red", "Красный", 0xB91C1C, hue = 356f),
    CarPaint("green", "Зелёный", 0x15803D, hue = 145f),
    CarPaint("orange", "Оранжевый", 0xEA580C, hue = 24f),
)

/** "custom:RRGGBB" from the colour picker, or one of [CarPaints]. */
fun carPaint(id: String): CarPaint {
    if (id.startsWith("custom:")) {
        val rgb = id.removePrefix("custom:").toIntOrNull(16) ?: 0x808080
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(rgb or 0xFF000000.toInt(), it) }
        return if (hsv[1] > 0.25f && hsv[2] > 0.2f) CarPaint(id, "Свой цвет", rgb, hue = hsv[0])
        else CarPaint(id, "Свой цвет", rgb, grey = 0.25f + hsv[2] * 0.7f, needsWhite = hsv[2] > 0.6f)
    }
    return CarPaints.firstOrNull { it.id == id } ?: CarPaints.first()
}

/** Colour [white] (white-body frame) with [p] where [mask] says body. */
fun tint(white: Bitmap, mask: Bitmap, p: CarPaint): Bitmap {
    val w = white.width
    val h = white.height
    val px = IntArray(w * h)
    val mk = IntArray(w * h)
    white.getPixels(px, 0, w, 0, 0, w, h)
    val mb = if (mask.width == w && mask.height == h) mask else Bitmap.createScaledBitmap(mask, w, h, true)
    mb.getPixels(mk, 0, w, 0, 0, w, h)
    val cr = (p.rgb shr 16 and 0xFF) / 255f
    val cg = (p.rgb shr 8 and 0xFF) / 255f
    val cb = (p.rgb and 0xFF) / 255f
    for (i in px.indices) {
        val c = px[i]
        val a = c ushr 24
        if (a == 0) continue
        val m = (mk[i] shr 16 and 0xFF) / 255f
        if (m <= 0.01f) continue
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        // the white paint renders at ~0.82; brighter than that is a reflection, keep it as a highlight
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        val shade = (lum / 0.82f).coerceAtMost(1.15f)
        val spec = ((lum - 0.86f) / 0.14f).coerceIn(0f, 1f) * 0.85f
        fun ch(col: Float, orig: Float): Int {
            val base = (col * shade).coerceAtMost(1f)
            val v = base + (1f - base) * spec
            return ((orig + (v - orig) * m) * 255f).toInt().coerceIn(0, 255)
        }
        px[i] = (a shl 24) or (ch(cr, r) shl 16) or (ch(cg, g) shl 8) or ch(cb, b)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** Re-tint of the chameleon frames, for models without white-body frames. */
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
        val nh: Float
        val ns: Float
        val nv: Float
        if (p.hue != null) {
            nh = p.hue
            ns = maxOf(s, 0.6f)
            nv = minOf(1f, mx * 1.12f)
        } else {
            nh = hue
            ns = 0.03f
            nv = minOf(1f, p.lift + mx * p.grey)
        }
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
