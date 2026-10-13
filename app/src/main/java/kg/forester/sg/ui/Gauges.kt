package kg.forester.sg.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Look of a round gauge, NFS Underground style "custom gauges": face, backlight colour of the scale and
 * numbers, needle, glow. Picked per gauge with a long press on it.
 */
data class DialStyle(
    val id: String,
    val name: String,
    val face: List<Color>,             // radial, centre → edge
    val rim: Color,
    val track: Color,                  // unlit part of the arc
    val arc: List<Color>?,             // lit part up to the value; null = no arc
    val red: Color,                    // red zone
    val tickMajor: Color,
    val tickMinor: Color,
    val numbers: Color,
    val value: Color,
    val unit: Color,
    val needle: Color,
    val glow: Color? = null,           // backlight halo around numbers / needle
    val carbon: Boolean = false,
    val minimal: Boolean = false,
)

val DialStyles = listOf(
    DialStyle(
        "neon", "Неон",
        face = listOf(Color(0xE60B0F1C), Color(0xCC0B0F1C), Color(0x660B0F1C)), rim = C.Border, track = Color(0xFF1E2438),
        arc = listOf(C.Blue, C.Violet, C.Purple, C.Blue), red = C.Red.copy(alpha = 0.55f),
        tickMajor = Color(0xFFCBD5E1), tickMinor = Color(0xFF475069), numbers = Color(0xFFAAB2C8),
        value = Color.White, unit = C.Sub, needle = Color(0xFFFF4D6D),
    ),
    DialStyle(
        "underground", "Underground",
        face = listOf(Color(0xF2020A10), Color(0xF2041218), Color(0xCC00151C)), rim = Color(0xFF22D3EE), track = Color(0xFF0B2A33),
        arc = listOf(Color(0xFF22D3EE), Color(0xFF67E8F9), Color(0xFF22D3EE)), red = Color(0xFFFF3B3B),
        tickMajor = Color(0xFF67E8F9), tickMinor = Color(0xFF155E75), numbers = Color(0xFF67E8F9),
        value = Color(0xFFE0FBFF), unit = Color(0xFF67E8F9), needle = Color(0xFFFF8A00), glow = Color(0xFF22D3EE),
    ),
    DialStyle(
        "acid", "Кислота",
        face = listOf(Color(0xF2050A04), Color(0xF2071006), Color(0xCC0A1A06)), rim = Color(0xFFA3E635), track = Color(0xFF1A2E0B),
        arc = listOf(Color(0xFF84CC16), Color(0xFFD9F99D), Color(0xFF84CC16)), red = Color(0xFFFF2D55),
        tickMajor = Color(0xFFBEF264), tickMinor = Color(0xFF3F6212), numbers = Color(0xFFBEF264),
        value = Color(0xFFF7FEE7), unit = Color(0xFFA3E635), needle = Color(0xFFFF2D55), glow = Color(0xFF84CC16),
    ),
    DialStyle(
        "sti", "STI",
        face = listOf(Color(0xFA0A0A0C), Color(0xFA121214), Color(0xF01A0B0B)), rim = Color(0xFF7F1D1D), track = Color(0xFF2A1212),
        arc = listOf(Color(0xFFB91C1C), Color(0xFFEF4444), Color(0xFFB91C1C)), red = Color(0xFFFF1F1F),
        tickMajor = Color(0xFFFF5A5A), tickMinor = Color(0xFF7F1D1D), numbers = Color(0xFFFF6B6B),
        value = Color.White, unit = Color(0xFFFCA5A5), needle = Color(0xFFF8FAFC), glow = Color(0xFFDC2626),
    ),
    DialStyle(
        "white", "Белая классика",
        face = listOf(Color(0xFFF8FAFC), Color(0xFFE2E8F0), Color(0xFFCBD5E1)), rim = Color(0xFF94A3B8), track = Color(0xFFCBD5E1),
        arc = null, red = Color(0xFFDC2626),
        tickMajor = Color(0xFF0F172A), tickMinor = Color(0xFF475569), numbers = Color(0xFF0F172A),
        value = Color(0xFF0F172A), unit = Color(0xFF475569), needle = Color(0xFFDC2626),
    ),
    DialStyle(
        "carbon", "Карбон",
        face = listOf(Color(0xFF1C1F24), Color(0xFF15171B), Color(0xFF0E1013)), rim = Color(0xFFC0C7D1), track = Color(0xFF2A2E35),
        arc = listOf(Color(0xFFC0C7D1), Color(0xFFF1F5F9)), red = Color(0xFFE11D48),
        tickMajor = Color(0xFFF1F5F9), tickMinor = Color(0xFF64748B), numbers = Color(0xFFE2E8F0),
        value = Color.White, unit = Color(0xFF94A3B8), needle = Color(0xFFE11D48), carbon = true,
    ),
    DialStyle(
        "retro", "Ретро",
        face = listOf(Color(0xF2120C02), Color(0xF2160F04), Color(0xCC1F1404)), rim = Color(0xFFB45309), track = Color(0xFF2E1F06),
        arc = listOf(Color(0xFFF59E0B), Color(0xFFFCD34D), Color(0xFFF59E0B)), red = Color(0xFFEF4444),
        tickMajor = Color(0xFFFBBF24), tickMinor = Color(0xFF78350F), numbers = Color(0xFFFBBF24),
        value = Color(0xFFFFF7E0), unit = Color(0xFFFBBF24), needle = Color(0xFFFFE08A), glow = Color(0xFFF59E0B),
    ),
    DialStyle(
        "minimal", "Минимал",
        face = listOf(Color.Transparent, Color.Transparent), rim = Color.Transparent, track = Color(0x33FFFFFF),
        arc = listOf(Color.White, Color.White), red = Color(0xFFEF4444),
        tickMajor = Color(0x99FFFFFF), tickMinor = Color.Transparent, numbers = Color(0x99FFFFFF),
        value = Color.White, unit = Color(0x99FFFFFF), needle = Color.Transparent, minimal = true,
    ),
)

fun dialStyle(id: String) = DialStyles.firstOrNull { it.id == id } ?: DialStyles.first()

/**
 * Round needle gauge (tachometer / speedometer): 270° scale with numbered major ticks, an optional red
 * zone, a lit arc up to the value and the number in the middle, drawn in [style].
 */
@Composable
fun Dial(
    value: Float?, max: Float, step: Float, labelDiv: Float, unit: String, scaleNote: String?,
    redFrom: Float?, modifier: Modifier, style: DialStyle = DialStyles.first(),
    min: Float = 0f, format: String = "%.0f",
) {
    val v by animateFloatAsState((value ?: min).coerceIn(min, max), tween(240), label = "dial")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawDial(v, min, max, step, labelDiv, redFrom, style) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth(if (style.minimal) 0.62f else 0.5f)) {
                val d = LocalDensity.current
                val sp = with(d) { (maxWidth / 3.1f).toSp() }
                T(value?.let { format.format(it) } ?: "—", sp, style.value, FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.align(Alignment.Center))
            }
            T(unit, 12.sp, style.unit, maxLines = 1)
            if (scaleNote != null) T(scaleNote, 10.sp, style.unit.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

/** The scale runs [min]..[max]; the lit arc starts at 0 when the scale has both signs (trims, timing). */
private fun DrawScope.drawDial(v: Float, min: Float, max: Float, step: Float, labelDiv: Float, redFrom: Float?, st: DialStyle) {
    val r = size.minDimension / 2f
    val c = center
    val start = 135f
    val sweep = 270f
    fun ang(x: Float) = start + sweep * ((x - min) / (max - min))
    val from = if (min < 0f && max > 0f) 0f else min
    fun pt(deg: Float, rr: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + rr * kotlin.math.cos(a).toFloat(), c.y + rr * kotlin.math.sin(a).toFloat())
    }
    // face
    if (!st.minimal) {
        st.glow?.let { drawCircle(Brush.radialGradient(listOf(it.copy(alpha = 0.22f), Color.Transparent), c, r * 1.08f), r * 1.08f, c) }
        drawCircle(Brush.radialGradient(st.face, c, r), r, c)
        if (st.carbon) carbonWeave(c, r)
        drawCircle(st.rim, r - 1.5f, c, style = Stroke(if (st.glow != null) 2.5f else 1.5f))
    }
    val arcR = r * 0.86f
    val tl = Offset(c.x - arcR, c.y - arcR)
    val sz = Size(arcR * 2, arcR * 2)
    val stroke = r * (if (st.minimal) 0.035f else 0.07f)
    drawArc(st.track, start, sweep, false, tl, sz, style = Stroke(stroke))
    if (redFrom != null) {
        drawArc(st.red.copy(alpha = if (st.minimal) 0.5f else 0.6f), ang(redFrom), ang(max) - ang(redFrom), false, tl, sz, style = Stroke(stroke))
    }
    val arc = st.arc
    if (arc != null && v != from) {
        val hot = redFrom != null && v >= redFrom
        val brush = if (hot) Brush.sweepGradient(listOf(C.Orange, C.Red), c) else Brush.sweepGradient(arc, c)
        val a0 = ang(minOf(from, v))
        val a1 = ang(maxOf(from, v))
        st.glow?.let { drawArc(it.copy(alpha = 0.25f), a0, a1 - a0, false, tl, sz, style = Stroke(stroke * 2.4f, cap = StrokeCap.Round)) }
        drawArc(brush, a0, a1 - a0, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
    }
    // ticks and numbers
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = r * 0.13f
        color = st.numbers.toArgb()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        st.glow?.let { setShadowLayer(r * 0.05f, 0f, 0f, it.toArgb()) }
    }
    val minor = step / 2f
    val decimals = step / labelDiv < 1f
    var i = 0
    while (true) {
        val x = min + minor * i++
        if (x > max + minor * 0.01f) break
        val major = i % 2 == 1 // ticks alternate major/minor from [min], which is a multiple of [step]
        val a = ang(x)
        val red = redFrom != null && x >= redFrom
        val col = if (red) st.red else if (major) st.tickMajor else st.tickMinor
        if (col.alpha > 0f) {
            drawLine(col, pt(a, arcR - stroke * 0.9f), pt(a, arcR - stroke * (if (major) 2.6f else 1.7f)), if (major) 2.5f else 1.4f)
        }
        if (major && (step * 2 > (max - min) / 8 || (i / 2) % 2 == 0)) {
            val p = pt(a, arcR - stroke * (if (st.minimal) 5.5f else 3.9f))
            val label = if (decimals) "%.1f".format(x / labelDiv) else kotlin.math.round(x / labelDiv).toInt().toString()
            drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y + paint.textSize * 0.35f, paint)
        }
    }
    // needle
    if (st.needle.alpha > 0f) {
        val na = ang(v)
        drawLine(st.needle.copy(alpha = 0.35f), pt(na, r * 0.10f), pt(na, arcR - stroke * 0.3f), r * 0.05f, cap = StrokeCap.Round)
        drawLine(st.needle, pt(na, r * 0.10f), pt(na, arcR - stroke * 0.3f), r * 0.022f, cap = StrokeCap.Round)
        drawCircle(st.rim.takeIf { it.alpha > 0f } ?: Color(0xFF2A3150), r * 0.075f, c)
    }
}

/** Twill weave hint for the carbon face: small alternating diagonal cells. */
private fun DrawScope.carbonWeave(c: Offset, r: Float) {
    val cell = r / 9f
    val clip = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r - 2f)) }
    drawContext.canvas.save()
    drawContext.canvas.clipPath(clip)
    var y = c.y - r
    var row = 0
    while (y < c.y + r) {
        var x = c.x - r + (row % 2) * cell
        while (x < c.x + r) {
            drawRect(
                Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x05FFFFFF)), Offset(x, y), Offset(x + cell, y + cell)),
                Offset(x, y), Size(cell, cell),
            )
            x += cell * 2
        }
        y += cell
        row++
    }
    drawContext.canvas.restore()
}

/** Pop-up with live previews of every design for the tachometer ([which] = "tach") or the speedometer. */
@Composable
fun DialStylePicker(which: String, modifier: Modifier, onClose: () -> Unit) {
    val s = kg.forester.sg.data.Repo.settings
    StylePicker(
        if (which == "tach") "Дизайн тахометра" else "Дизайн спидометра",
        if (which == "tach") s.tachStyle else s.speedoStyle, plain = null, modifier,
        preview = { st, m ->
            if (which == "tach") Dial(3600f, 7000f, 1000f, 1000f, "об/мин", null, 6000f, m, st)
            else Dial(96f, 200f, 20f, 1f, "км/ч", null, null, m, st)
        },
        onPick = { if (which == "tach") s.tachStyle = it else s.speedoStyle = it },
        onClose = onClose,
    )
}

/**
 * Pop-up with a live [preview] of every gauge design; [plain] (if given) is the name of the card's normal look,
 * offered first and picked as "".
 */
@Composable
fun StylePicker(
    title: String, current: String, plain: String?, modifier: Modifier,
    preview: @Composable (DialStyle, Modifier) -> Unit, onPick: (String) -> Unit, onClose: () -> Unit,
) {
    androidx.compose.ui.window.Popup(onDismissRequest = onClose, alignment = Alignment.Center) {
        Column(
            modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(C.Card).border(1.dp, C.Purple, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            T(title, 16.sp, weight = FontWeight.SemiBold)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            val options: List<DialStyle?> = (if (plain != null) listOf(null) else emptyList<DialStyle?>()) + DialStyles
            options.chunked(if (plain != null) 5 else 4).forEach { row ->
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    row.forEach { st ->
                        val on = (st?.id ?: "") == current
                        Column(
                            Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(if (on) C.Purple.copy(alpha = 0.25f) else Color.Transparent)
                                .border(if (on) 2.dp else 1.dp, if (on) C.Purple else C.Border, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable { onPick(st?.id ?: ""); onClose() }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(112.dp).background(Color(0xFF0B0F1C), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (st != null) preview(st, Modifier.fillMaxSize().padding(4.dp))
                                else T("— — —", 22.sp, C.Violet, FontWeight.Bold)
                            }
                            T(st?.name ?: plain ?: "", 12.sp, if (on) C.Text else C.Sub, maxLines = 1)
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            }
        }
    }
}
