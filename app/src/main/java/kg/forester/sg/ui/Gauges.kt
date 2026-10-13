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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.composables.icons.lucide.ImagePlus
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pencil
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
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

/** How ticks are drawn: lines, round dots (UG2 style) or small triangles pointing inwards. */
enum class TickShape { LINE, DOT, TRIANGLE }

/** Artwork painted on the face under the scale. */
enum class FacePattern { NONE, CARBON, FLAMES, CIRCUIT, SPARKLES, CAT, SAKURA, CHECKER, SYNTH, STARS }

/**
 * Digital read-out instead of the big number in the middle: [segments] uses the 7-segment font with unlit
 * "8"s behind the digits (NFS Most Wanted LCD), otherwise a plain number in a framed box (Hot Pursuit).
 */
data class Readout(val bg: Color, val fg: Color, val ghost: Color, val border: Color, val segments: Boolean = true)

/**
 * Look of a round gauge, NFS Underground style "custom gauges": face, backlight colour of the scale and
 * numbers, needle, glow, plus optional extras (band, outer arcs, read-out, face artwork).
 * Picked per gauge with a long press on it.
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
    val sweep: Float = 270f,           // scale angle; smaller = open "C" gauge
    val ticks: TickShape = TickShape.LINE,
    val italic: Boolean = false,       // slanted numbers
    val band: List<Color>? = null,     // wide ring carrying the numbers (chrome / ultraviolet)
    val bandText: Color = Color.Black,
    val outer: Pair<Color, Color>? = null, // two-tone ring outside the scale, left half / right half (Most Wanted)
    val readout: Readout? = null,
    val pattern: FacePattern = if (carbon) FacePattern.CARBON else FacePattern.NONE,
    val patternColor: Color = Color(0x22FFFFFF),
    val hub: Color? = null,            // needle cap; null = rim colour
    val needleWidth: Float = 0.022f,   // of the radius
    val shortNeedle: Boolean = false,  // only the tip near the scale (Hot Pursuit)
    val image: String? = null,         // user picture under the face (see GaugeImages)
    val tickLow: Color? = null,        // ticks and numbers fade from this colour at the start to tickMajor at the end
    val segments: Boolean = false,     // LED blocks lit up to the value instead of an arc (coloured along [arc])
)

private val WarmLcd = Readout(Color(0xFFB9A46A), Color(0xFF1B1608), Color(0x2A1B1608), Color(0xFF6B5A2A))

/** All gauge designs: the original ones, then the preset collection (GaugePresets.kt). */
val DialStyles: List<DialStyle> by lazy { CoreDialStyles + PresetDialStyles }

private val CoreDialStyles = listOf(
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
    // --- NFS Most Wanted: light textured face, orange/green outer ring, LCD read-out
    DialStyle(
        "lcd", "LCD",
        face = listOf(Color(0xFFE9EAEC), Color(0xFFD5D7DB), Color(0xFFBFC2C8)), rim = Color(0xFF8A8F99), track = Color(0x00000000),
        arc = null, red = Color(0xFFD32222),
        tickMajor = Color(0xFF3A3F48), tickMinor = Color(0xFF6B7280), numbers = Color(0xFF3A3F48),
        value = Color(0xFF1B1608), unit = Color(0xFF4B5160), needle = Color(0xFF2A2D33), hub = Color(0xFF1F2127),
        outer = Color(0xFFF59E0B) to Color(0xFF9BE22B), readout = WarmLcd, needleWidth = 0.035f,
        pattern = FacePattern.SPARKLES, patternColor = Color(0x14FFFFFF),
    ),
    DialStyle(
        "lcd_night", "Ночной LCD",
        face = listOf(Color(0xFF1B2733), Color(0xFF15202B), Color(0xFF0E161E)), rim = Color(0xFF2B3B4A), track = Color(0xFF203040),
        arc = null, red = Color(0xFFE11D2E),
        tickMajor = Color(0xFF5BC8F5), tickMinor = Color(0xFF2E6E8C), numbers = Color(0xFF5BC8F5),
        value = Color(0xFFFFB347), unit = Color(0xFF5BC8F5), needle = Color(0xFFE8B04A), hub = Color(0xFF0B1118),
        outer = Color(0xFFF59E0B) to Color(0xFF9BE22B), needleWidth = 0.03f,
        readout = Readout(Color(0xFF2A1E08), Color(0xFFFFB347), Color(0x26FFB347), Color(0xFF6B4A12)),
    ),
    // --- NFS Hot Pursuit: white line art, framed digital number, short needle, side bars
    DialStyle(
        "pursuit", "Погоня",
        face = listOf(Color(0xCC14161C), Color(0xB314161C), Color(0x6614161C)), rim = Color.Transparent, track = Color(0xFFF1F5F9),
        arc = null, red = Color(0xFFE5232A),
        tickMajor = Color(0xFFF1F5F9), tickMinor = Color(0xFFF1F5F9), numbers = Color(0xFFF1F5F9),
        value = Color.White, unit = Color(0xFFCBD5E1), needle = Color(0xFFE5232A), shortNeedle = true, needleWidth = 0.03f,
        outer = Color(0xFF5BC0EB) to Color(0xFFE5232A), sweep = 240f,
        readout = Readout(Color(0x33000000), Color.White, Color.Transparent, Color(0x99F1F5F9), segments = false),
    ),
    // --- NFS Underground 2 custom gauges
    DialStyle(
        "flames", "Пламя",
        face = listOf(Color(0xFF3A3B3F), Color(0xFF26272B), Color(0xFF16171A)), rim = Color(0xFF6B6E76), track = Color(0x00000000),
        arc = null, red = Color(0xFFE5232A),
        tickMajor = Color.White, tickMinor = Color(0xCCFFFFFF), numbers = Color.White,
        value = Color.White, unit = Color(0xFFD4D4D8), needle = Color(0xFFE5E7EB), hub = Color(0xFF52525B),
        ticks = TickShape.DOT, italic = true, pattern = FacePattern.FLAMES, patternColor = Color(0x30FFFFFF),
    ),
    DialStyle(
        "circuit", "Плата",
        face = listOf(Color(0xFF1A1D21), Color(0xFF121417), Color(0xFF0A0B0D)), rim = Color(0xFFE5E7EB), track = Color(0xFF2A2D33),
        arc = listOf(Color.White, Color.White), red = Color(0xFFB91C1C),
        tickMajor = Color.White, tickMinor = Color(0xFF9CA3AF), numbers = Color(0xFFE5E7EB),
        value = Color.White, unit = Color(0xFF9CA3AF), needle = Color.White, glow = Color(0x66FFFFFF),
        italic = true, pattern = FacePattern.CIRCUIT, patternColor = Color(0x33A3E6B4), sweep = 250f,
    ),
    DialStyle(
        "chrome", "Хром",
        face = listOf(Color(0xFF2A2A2E), Color(0xFF151518), Color(0xFF0B0B0D)), rim = Color(0xFFD9DCE1), track = Color(0x00000000),
        arc = null, red = Color(0xFFF2A0A0),
        tickMajor = Color(0xFF111111), tickMinor = Color(0xFF333333), numbers = Color(0xFF111111),
        value = Color.White, unit = Color(0xFFBFC3C9), needle = Color(0xFFF5F5F5), hub = Color(0xFFE5E7EB),
        band = listOf(Color(0xFFFFFFFF), Color(0xFFC9CDD3), Color(0xFFF4F5F7), Color(0xFF9EA3AA), Color(0xFFFFFFFF)),
        bandText = Color(0xFF111111), italic = true, sweep = 240f, needleWidth = 0.04f,
    ),
    DialStyle(
        "diamond", "Бриллиант",
        face = listOf(Color(0xFF27272A), Color(0xFF111113), Color(0xFF050506)), rim = Color(0xFFD4D4D8), track = Color(0xFF3F3F46),
        arc = listOf(Color(0xFFE4E4E7), Color.White), red = Color(0xFFDC2626),
        tickMajor = Color.White, tickMinor = Color(0xFFA1A1AA), numbers = Color.White,
        value = Color.White, unit = Color(0xFFA1A1AA), needle = Color(0xFFF4F4F5), glow = Color(0x55FFFFFF),
        ticks = TickShape.DOT, italic = true, pattern = FacePattern.SPARKLES, patternColor = Color(0xCCFFFFFF),
    ),
    DialStyle(
        "ultraviolet", "Ультрафиолет",
        face = listOf(Color(0xFF14104A), Color(0xFF0C0A33), Color(0xFF07061F)), rim = Color(0xFF6D5BD0), track = Color(0x00000000),
        arc = null, red = Color(0xFFD946EF),
        tickMajor = Color(0xFFFBBF24), tickMinor = Color(0xFFB45309), numbers = Color(0xFFFBBF24),
        value = Color.White, unit = Color(0xFFC4B5FD), needle = Color(0xFFE11D48), hub = Color(0xFFFBBF24),
        band = listOf(Color(0xFF3B2F9E), Color(0xFF4C3FC2), Color(0xFF2E2480), Color(0xFF3B2F9E)), bandText = Color(0xFFFBBF24),
        italic = true, needleWidth = 0.035f,
    ),
    DialStyle(
        "kitty", "Котик",
        face = listOf(Color(0xFF2A2C31), Color(0xFF1F2125), Color(0xFF141518)), rim = Color(0xFFE5E7EB), track = Color(0x00000000),
        arc = null, red = Color(0xFFB91C1C),
        tickMajor = Color.White, tickMinor = Color(0xCCFFFFFF), numbers = Color.White,
        value = Color.White, unit = Color(0xFFD4D4D8), needle = Color(0xFF111111), hub = Color(0xFF111111),
        ticks = TickShape.TRIANGLE, pattern = FacePattern.CAT, patternColor = Color(0xFFB8BBC2), needleWidth = 0.03f,
    ),
    // --- real-car inspired
    DialStyle(
        "amber", "Янтарь",
        face = listOf(Color(0xFF0A0A0A), Color(0xFF050505), Color(0xFF000000)), rim = Color(0xFF3F2A0A), track = Color(0xFF221605),
        arc = listOf(Color(0xFFFF9F1C), Color(0xFFFFC15E)), red = Color(0xFFFF3B1F),
        tickMajor = Color(0xFFFFB13B), tickMinor = Color(0xFF8A5A12), numbers = Color(0xFFFFB13B),
        value = Color(0xFFFFD08A), unit = Color(0xFFFFB13B), needle = Color.White, hub = Color(0xFFFF9F1C), glow = Color(0xFFFF9F1C),
    ),
    DialStyle(
        "aqua", "Аква",
        face = listOf(Color(0xFF2E5560), Color(0xFF1F3F49), Color(0xFF122A31)), rim = Color(0xFF8FB8C2), track = Color(0x00000000),
        arc = null, red = Color(0xFFD62828),
        tickMajor = Color.White, tickMinor = Color(0xCCE0F2F7), numbers = Color(0xFFE0F2F7),
        value = Color.White, unit = Color(0xFFBFE3EC), needle = Color(0xFFE0A030), hub = Color(0xFF0E1F24),
        outer = Color(0xFFF59E0B) to Color(0xFF9BE22B), needleWidth = 0.04f,
        readout = Readout(Color(0xFF0E2A33), Color(0xFF7DD3FC), Color(0x227DD3FC), Color(0xFF4A8A9A)),
    ),
    DialStyle(
        "cobalt", "Кобальт",
        face = listOf(Color(0xFF0B1020), Color(0xFF070B17), Color(0xFF03050C)), rim = Color(0xFF9CA3AF), track = Color(0xFF111A33),
        arc = listOf(Color(0xFF1E6BFF), Color(0xFF5AA0FF)), red = Color(0xFFFF2D2D),
        tickMajor = Color(0xFF7FB2FF), tickMinor = Color(0xFF2B4C8C), numbers = Color(0xFFBFD6FF),
        value = Color.White, unit = Color(0xFF7FB2FF), needle = Color(0xFFFF5A1F), glow = Color(0xFF1E6BFF),
        ticks = TickShape.TRIANGLE,
    ),
)

/** Look for a user picture: the picture shows through a dark vignette, neon pink/cyan scale for contrast. */
private val PhotoStyle = DialStyle(
    "photo", "Своя картинка",
    face = listOf(Color(0x80000000), Color(0x26000000), Color(0x99000000)), rim = Color(0xFFFF4FD8), track = Color(0x55FFFFFF),
    arc = listOf(Color(0xFFFF4FD8), Color(0xFF22D3EE), Color(0xFFFF4FD8)), red = Color(0xFFFF3B3B),
    tickMajor = Color.White, tickMinor = Color(0xCCFFFFFF), numbers = Color.White,
    value = Color.White, unit = Color(0xFFFFC2F2), needle = Color(0xFF22D3EE), glow = Color(0xFFFF4FD8),
)

/** Style by id; "photo:<file>" is the user-picture look with that picture. */
fun dialStyle(id: String): DialStyle = when {
    id.startsWith("photo:") -> PhotoStyle.copy(id = id, image = id.removePrefix("photo:"))
    id.startsWith("custom:") -> kg.forester.sg.data.CustomGauges.byStyleId(id)?.toStyle() ?: DialStyles.first()
    else -> DialStyles.firstOrNull { it.id == id } ?: DialStyles.first()
}

private val SegmentFont = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(kg.forester.sg.R.font.dseg7_bold_italic))

/**
 * Round needle gauge (tachometer / speedometer): a 270° (or [DialStyle.sweep]) scale with numbered major
 * ticks, an optional red zone, a lit arc up to the value and the number in the middle (or in a read-out
 * below the hub), drawn in [style].
 */
@Composable
fun Dial(
    value: Float?, max: Float, step: Float, labelDiv: Float, unit: String, scaleNote: String?,
    redFrom: Float?, modifier: Modifier, style: DialStyle = DialStyles.first(),
    min: Float = 0f, format: String = "%.0f",
) {
    val v by animateFloatAsState((value ?: min).coerceIn(min, max), tween(240), label = "dial")
    val text = value?.let { format.format(it) } ?: "—"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val photo = remember(style.image) { style.image?.let { kg.forester.sg.data.GaugeImages.bitmap(ctx, it)?.asImageBitmap() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawDial(v, min, max, step, labelDiv, redFrom, style, photo) }
        val ro = style.readout
        if (ro != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val d = minOf(maxWidth, maxHeight)
                Column(
                    Modifier.align(Alignment.Center).padding(top = d * 0.42f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReadoutBox(text, ro, d * 0.44f, style.value)
                    T(unit, (d.value * 0.075f).coerceIn(8f, 13f).sp, style.unit, maxLines = 1)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth(if (style.minimal) 0.62f else 0.5f)) {
                    val d = LocalDensity.current
                    val sp = with(d) { (maxWidth / 3.1f).toSp() }
                    T(text, sp, style.value, FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.Center))
                }
                T(unit, 12.sp, style.unit, maxLines = 1)
                if (scaleNote != null) T(scaleNote, 10.sp, style.unit.copy(alpha = 0.6f), maxLines = 1)
            }
        }
    }
}

/** LCD / framed digital number of [width]; unlit "8"s sit behind a segment read-out. */
@Composable
private fun ReadoutBox(text: String, ro: Readout, width: androidx.compose.ui.unit.Dp, plainColor: Color) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(width * 0.08f)
    Box(
        Modifier.size(width, width * 0.38f).clip(shape).background(ro.bg)
            .border(1.dp, ro.border, shape).padding(horizontal = width * 0.06f),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val d = LocalDensity.current
        val digits = text.length.coerceAtLeast(3)
        val sp = with(d) { minOf(width * 0.26f, (width * 0.86f) / (digits * 0.8f)).toSp() }
        if (ro.segments) {
            // a "1" is as wide as an "8" in DSEG, so the ghost lines up digit for digit
            val ghost = text.map { if (it.isDigit() || it == '—' || it == '-') '8' else it }.joinToString("").padStart(digits, '8')
            androidx.compose.material3.Text(ghost, color = ro.ghost, fontSize = sp, fontFamily = SegmentFont, maxLines = 1)
            androidx.compose.material3.Text(text.replace('—', '-'), color = ro.fg, fontSize = sp, fontFamily = SegmentFont, maxLines = 1)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                T(text, sp * 1.15f, plainColor, FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** The scale runs [min]..[max]; the lit arc starts at 0 when the scale has both signs (trims, timing). */
private fun DrawScope.drawDial(
    v: Float, min: Float, max: Float, step: Float, labelDiv: Float, redFrom: Float?, st: DialStyle,
    photo: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val r = size.minDimension / 2f * (if (st.outer != null) 0.9f else 1f)
    val c = center
    val sweep = st.sweep
    val start = 90f + (360f - sweep) / 2f
    fun ang(x: Float) = start + sweep * ((x - min) / (max - min))
    val from = if (min < 0f && max > 0f) 0f else min
    fun pt(deg: Float, rr: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + rr * kotlin.math.cos(a).toFloat(), c.y + rr * kotlin.math.sin(a).toFloat())
    }
    // outer two-tone ring (Most Wanted: nitrous / speedbreaker bars)
    st.outer?.let { (left, right) ->
        val or = r * 1.06f
        val w = r * 0.07f
        val tl = Offset(c.x - or, c.y - or)
        val sz = Size(or * 2, or * 2)
        val mid = start + sweep / 2f
        drawArc(left, start + 4f, mid - start - 6f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(right, mid + 2f, start + sweep - mid - 6f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
    // face
    if (!st.minimal) {
        st.glow?.let { drawCircle(Brush.radialGradient(listOf(it.copy(alpha = 0.22f), Color.Transparent), c, r * 1.08f), r * 1.08f, c) }
        if (photo != null) {
            val clip = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            drawContext.canvas.save()
            drawContext.canvas.clipPath(clip)
            drawImage(
                photo, dstOffset = androidx.compose.ui.unit.IntOffset((c.x - r).toInt(), (c.y - r).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize((r * 2).toInt(), (r * 2).toInt()),
            )
            drawContext.canvas.restore()
        }
        drawCircle(Brush.radialGradient(st.face, c, r), r, c)
        facePattern(st.pattern, st.patternColor, c, r)
        if (st.rim.alpha > 0f) drawCircle(st.rim, r - 1.5f, c, style = Stroke(if (st.glow != null) 2.5f else 1.5f))
    }
    val band = st.band
    val arcR = if (band != null) r * 0.80f else r * 0.86f
    val tl = Offset(c.x - arcR, c.y - arcR)
    val sz = Size(arcR * 2, arcR * 2)
    val stroke = r * (if (st.minimal) 0.035f else 0.07f)
    if (band != null) {
        val bw = r * 0.26f
        drawArc(Brush.sweepGradient(band, c), start, sweep, false, tl, sz, style = Stroke(bw, cap = StrokeCap.Butt))
        if (redFrom != null) drawArc(st.red, ang(redFrom), ang(max) - ang(redFrom), false,
            Offset(c.x - arcR - bw * 0.33f, c.y - arcR - bw * 0.33f), Size((arcR + bw * 0.33f) * 2, (arcR + bw * 0.33f) * 2),
            style = Stroke(bw * 0.3f))
    } else {
        if (st.track.alpha > 0f) drawArc(st.track, start, sweep, false, tl, sz, style = Stroke(if (st.readout?.segments == false) 2f else stroke))
        if (redFrom != null) {
            drawArc(st.red.copy(alpha = if (st.minimal) 0.5f else 0.6f), ang(redFrom), ang(max) - ang(redFrom), false, tl, sz, style = Stroke(stroke))
        }
    }
    val arc = st.arc
    if (st.segments) {
        // LED bar: 24 blocks, lit up to the value, coloured along the arc colours (green → orange → red etc.)
        val n = 24
        val cols = arc ?: listOf(st.tickMajor, st.tickMajor)
        val gap = sweep / n * 0.28f
        repeat(n) { k ->
            val f = (k + 0.5f) / n
            val lit = min + (max - min) * f <= v + 1e-3f
            val col = lerpColors(cols, f)
            val a0 = start + sweep * k / n + gap / 2
            drawArc(if (lit) col else col.copy(alpha = 0.12f), a0, sweep / n - gap, false, tl, sz, style = Stroke(stroke * 2.2f))
            if (lit) st.glow?.let { drawArc(it.copy(alpha = 0.18f), a0, sweep / n - gap, false, tl, sz, style = Stroke(stroke * 3.4f)) }
        }
    } else if (arc != null && v != from) {
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
        textSize = r * (if (band != null) 0.15f else 0.13f)
        color = (if (band != null) st.bandText else st.numbers).toArgb()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        if (st.italic) textSkewX = -0.2f
        st.glow?.let { if (band == null) setShadowLayer(r * 0.05f, 0f, 0f, it.toArgb()) }
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
        val low = st.tickLow
        val col = if (red && band == null) st.red
            else if (low != null) lerp(low, st.tickMajor, (x - min) / (max - min)).let { if (major) it else it.copy(alpha = it.alpha * 0.7f) }
            else if (major) st.tickMajor else st.tickMinor
        val tickR = if (band != null) arcR - r * 0.13f else arcR
        if (col.alpha > 0f) when (st.ticks) {
            TickShape.LINE -> drawLine(col, pt(a, tickR - stroke * 0.9f), pt(a, tickR - stroke * (if (major) 2.6f else 1.7f)), if (major) 2.5f else 1.4f)
            TickShape.DOT -> drawCircle(col, r * (if (major) 0.038f else 0.02f), pt(a, tickR - stroke * 0.6f))
            TickShape.TRIANGLE -> if (major) {
                val tip = pt(a, tickR - stroke * 2.4f)
                val b1 = pt(a - 3.2f, tickR - stroke * 0.6f)
                val b2 = pt(a + 3.2f, tickR - stroke * 0.6f)
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(tip.x, tip.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() }, col)
            } else drawLine(col, pt(a, tickR - stroke * 0.9f), pt(a, tickR - stroke * 1.5f), 1.4f)
        }
        if (major && (step * 2 > (max - min) / 8 || (i / 2) % 2 == 0)) {
            val p = pt(a, if (band != null) arcR else arcR - stroke * (if (st.minimal) 5.5f else 3.9f))
            val label = if (decimals) "%.1f".format(x / labelDiv) else kotlin.math.round(x / labelDiv).toInt().toString()
            if (low != null && band == null) paint.color = (if (red) st.red else lerp(low, st.numbers, (x - min) / (max - min))).toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y + paint.textSize * 0.35f, paint)
        }
    }
    // needle
    if (st.needle.alpha > 0f && !st.segments) {
        val na = ang(v)
        val w = r * st.needleWidth
        val inner = if (st.shortNeedle) arcR - stroke * 4.5f else r * 0.10f
        val tip = if (band != null) arcR + r * 0.08f else arcR - stroke * 0.3f
        drawLine(st.needle.copy(alpha = 0.35f), pt(na, inner), pt(na, tip), w * 2.2f, cap = StrokeCap.Round)
        drawLine(st.needle, pt(na, inner), pt(na, tip), w, cap = StrokeCap.Round)
        if (st.pattern == FacePattern.SPARKLES) sparkle(pt(na, tip), r * 0.09f, Color.White)
        if (!st.shortNeedle) {
            val hub = st.hub ?: st.rim.takeIf { it.alpha > 0f } ?: Color(0xFF2A3150)
            drawCircle(hub, r * 0.075f, c)
            drawCircle(Color.White.copy(alpha = 0.18f), r * 0.03f, Offset(c.x - r * 0.02f, c.y - r * 0.02f))
        }
    }
    if (st.readout?.segments == false) drawCircle(st.tickMajor, r * 0.5f, c, style = Stroke(2.5f))
}

/** Face artwork, clipped to the face circle. */
private fun DrawScope.facePattern(p: FacePattern, col: Color, c: Offset, r: Float) {
    if (p == FacePattern.NONE) return
    val clip = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r - 2f)) }
    drawContext.canvas.save()
    drawContext.canvas.clipPath(clip)
    val rnd = java.util.Random(7)
    when (p) {
        FacePattern.CARBON -> carbonWeave(c, r)
        FacePattern.FLAMES -> repeat(6) { k ->
            // tongues rising from the lower left towards the centre
            val bx = c.x - r * 0.95f + k * r * 0.28f
            val by = c.y + r
            val h = r * (0.9f + 0.35f * ((k * 37) % 5) / 4f)
            val lean = r * 0.35f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(bx, by)
                cubicTo(bx - r * 0.12f, by - h * 0.35f, bx + lean * 0.2f, by - h * 0.55f, bx + lean, by - h)
                cubicTo(bx + lean * 0.45f, by - h * 0.62f, bx + r * 0.32f, by - h * 0.35f, bx + r * 0.22f, by)
                close()
            }
            drawPath(path, col)
        }
        FacePattern.CIRCUIT -> repeat(22) {
            var x = c.x + (rnd.nextFloat() - 0.5f) * r * 1.8f
            var y = c.y + (rnd.nextFloat() - 0.5f) * r * 1.8f
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(x, y) }
            drawCircle(col, r * 0.022f, Offset(x, y))
            repeat(3) { seg ->
                val len = r * (0.1f + rnd.nextFloat() * 0.25f)
                if (seg % 2 == 0) { if (rnd.nextBoolean()) x += len else x -= len } else { y += if (rnd.nextBoolean()) len * 0.7f else -len * 0.7f; x += len * 0.7f }
                path.lineTo(x, y)
            }
            drawPath(path, col, style = Stroke(r * 0.012f))
            drawCircle(col, r * 0.03f, Offset(x, y), style = Stroke(r * 0.012f))
        }
        FacePattern.SPARKLES -> repeat(14) {
            val a = rnd.nextFloat() * 6.283f
            val d = r * (0.25f + rnd.nextFloat() * 0.7f)
            sparkle(Offset(c.x + d * kotlin.math.cos(a), c.y + d * kotlin.math.sin(a)), r * (0.03f + rnd.nextFloat() * 0.05f), col)
        }
        FacePattern.CAT -> {
            // a friendly cat face filling the left half of the dial, one ear poking up
            drawArc(col, 90f, 180f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
            drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - r * 0.05f, c.y - r * 0.95f); lineTo(c.x + r * 0.45f, c.y - r * 0.55f); lineTo(c.x - r * 0.05f, c.y - r * 0.45f); close()
            }, col)
            val ink = Color(0xFF1A1B1F)
            drawOval(ink, Offset(c.x - r * 0.62f, c.y - r * 0.32f), Size(r * 0.2f, r * 0.26f))
            drawCircle(Color.White, r * 0.04f, Offset(c.x - r * 0.55f, c.y - r * 0.24f))
            drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - r * 0.3f, c.y + r * 0.1f); lineTo(c.x - r * 0.16f, c.y + r * 0.1f); lineTo(c.x - r * 0.23f, c.y + r * 0.19f); close()
            }, ink)
            drawArc(ink, 0f, 180f, false, Offset(c.x - r * 0.43f, c.y + r * 0.12f), Size(r * 0.2f, r * 0.16f), style = Stroke(r * 0.025f))
            drawArc(ink, 0f, 180f, false, Offset(c.x - r * 0.23f, c.y + r * 0.12f), Size(r * 0.2f, r * 0.16f), style = Stroke(r * 0.025f))
            repeat(3) { k ->
                val y = c.y + r * (0.05f + k * 0.08f)
                drawLine(ink.copy(alpha = 0.6f), Offset(c.x - r * 0.95f, y - r * 0.04f * (1 - k)), Offset(c.x - r * 0.62f, y), r * 0.012f)
            }
        }
        FacePattern.SAKURA -> {
            // a branch across the upper face with blossoms, petals drifting below
            val wood = Color(0xFF5B3A29)
            val branch = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - r * 1.05f, c.y - r * 0.15f)
                cubicTo(c.x - r * 0.5f, c.y - r * 0.55f, c.x + r * 0.1f, c.y - r * 0.35f, c.x + r * 1.05f, c.y - r * 0.75f)
            }
            drawPath(branch, wood, style = Stroke(r * 0.05f, cap = StrokeCap.Round))
            drawLine(wood, Offset(c.x - r * 0.25f, c.y - r * 0.42f), Offset(c.x - r * 0.05f, c.y - r * 0.8f), r * 0.03f, cap = StrokeCap.Round)
            drawLine(wood, Offset(c.x + r * 0.35f, c.y - r * 0.5f), Offset(c.x + r * 0.55f, c.y - r * 0.2f), r * 0.03f, cap = StrokeCap.Round)
            repeat(16) {
                val t = rnd.nextFloat()
                val bx = c.x - r * 0.9f + t * r * 1.9f
                val by = c.y - r * (0.2f + 0.45f * t) + (rnd.nextFloat() - 0.5f) * r * 0.35f
                blossom(Offset(bx, by), r * (0.07f + rnd.nextFloat() * 0.05f), col)
            }
            repeat(10) {
                val px = c.x + (rnd.nextFloat() - 0.5f) * r * 1.6f
                val py = c.y + rnd.nextFloat() * r * 0.8f
                drawOval(col.copy(alpha = 0.8f), Offset(px, py), Size(r * 0.06f, r * 0.035f))
            }
        }
        FacePattern.CHECKER -> {
            // EL-backlight checker ring around the hub
            val cell = r * 0.055f
            var y = c.y - r * 0.55f
            while (y < c.y + r * 0.55f) {
                var x = c.x - r * 0.55f
                while (x < c.x + r * 0.55f) {
                    val d = kotlin.math.hypot(x + cell / 2 - c.x, y + cell / 2 - c.y)
                    val k = ((x - c.x) / cell).toInt() + ((y - c.y) / cell).toInt()
                    if (d in r * 0.2f..r * 0.52f && k % 2 == 0) drawRect(col.copy(alpha = col.alpha * (1f - (d - r * 0.2f) / (r * 0.4f))), Offset(x, y), Size(cell, cell))
                    x += cell
                }
                y += cell
            }
        }
        FacePattern.SYNTH -> {
            // synthwave: striped sun on a horizon, perspective grid below
            val hz = c.y + r * 0.15f
            val sunR = r * 0.42f
            val sun = Offset(c.x, hz - sunR * 0.25f)
            drawContext.canvas.save()
            drawContext.canvas.clipRect(c.x - r, c.y - r, c.x + r, hz)
            drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFE066), Color(0xFFFF4FA3)), sun.y - sunR, sun.y + sunR), sunR, sun)
            repeat(5) { k ->
                val yy = sun.y + sunR * (0.1f + k * 0.18f)
                drawRect(Color.Black.copy(alpha = 0.55f), Offset(c.x - sunR, yy), Size(sunR * 2, sunR * 0.05f * (k + 1)))
            }
            drawContext.canvas.restore()
            repeat(7) { k ->
                val f = (k + 1) / 7f
                drawLine(col, Offset(c.x - r, hz + (c.y + r - hz) * f * f), Offset(c.x + r, hz + (c.y + r - hz) * f * f), r * 0.01f)
            }
            repeat(9) { k ->
                val dx = (k - 4) / 4f
                drawLine(col, Offset(c.x + dx * r * 0.15f, hz), Offset(c.x + dx * r * 1.4f, c.y + r), r * 0.01f)
            }
        }
        FacePattern.STARS -> repeat(40) {
            val a = rnd.nextFloat() * 6.283f
            val d = r * kotlin.math.sqrt(rnd.nextFloat())
            val p = Offset(c.x + d * kotlin.math.cos(a), c.y + d * kotlin.math.sin(a))
            if (it % 9 == 0) sparkle(p, r * 0.04f, col) else drawCircle(col.copy(alpha = 0.4f + rnd.nextFloat() * 0.6f), r * (0.006f + rnd.nextFloat() * 0.01f), p)
        }
        FacePattern.NONE -> Unit
    }
    drawContext.canvas.restore()
}

private fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))

/** Colour at [f] (0…1) along a list of evenly spaced colours. */
private fun lerpColors(cols: List<Color>, f: Float): Color {
    if (cols.size == 1) return cols[0]
    val p = f.coerceIn(0f, 1f) * (cols.size - 1)
    val i = p.toInt().coerceAtMost(cols.size - 2)
    return lerp(cols[i], cols[i + 1], p - i)
}

/** Five-petal blossom with a darker centre. */
private fun DrawScope.blossom(p: Offset, s: Float, col: Color) {
    repeat(5) { k ->
        val a = Math.toRadians(k * 72.0 - 90.0)
        drawCircle(col, s * 0.42f, Offset(p.x + s * 0.45f * kotlin.math.cos(a).toFloat(), p.y + s * 0.45f * kotlin.math.sin(a).toFloat()))
    }
    drawCircle(Color(0xFFD94A7A), s * 0.18f, p)
}

/** Four-point star glint. */
private fun DrawScope.sparkle(p: Offset, s: Float, col: Color) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(p.x, p.y - s); quadraticTo(p.x, p.y, p.x + s, p.y); quadraticTo(p.x, p.y, p.x, p.y + s)
        quadraticTo(p.x, p.y, p.x - s, p.y); quadraticTo(p.x, p.y, p.x, p.y - s); close()
    }
    drawPath(path, col)
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
        if (which == "tach") s.tachStyle else s.speedoStyle, plain = null, modifier, target = which,
        preview = { st, m ->
            if (which == "tach") Dial(3600f, 7000f, 1000f, 1000f, "об/мин", null, 6000f, m, st)
            else Dial(96f, 200f, 20f, 1f, "км/ч", null, null, m, st)
        },
        onPick = { if (which == "tach") s.tachStyle = it else s.speedoStyle = it },
        onClose = onClose,
    )
}

private const val PICK = "pick"
private const val CREATE = "create"

/** Opens the gallery for a gauge picture; the result is applied by GaugeImages.onPicked (see MainActivity). */
val LocalPickGaugeImage = androidx.compose.runtime.staticCompositionLocalOf<(target: String) -> Unit> { {} }

/**
 * Pop-up with a live [preview] of every gauge design, plus "own picture"; [plain] (if given) is the name of the card's normal look,
 * offered first and picked as "".
 */
@Composable
fun StylePicker(
    title: String, current: String, plain: String?, modifier: Modifier, target: String,
    preview: @Composable (DialStyle, Modifier) -> Unit, onPick: (String) -> Unit, onClose: () -> Unit,
) {
    val pickPicture = LocalPickGaugeImage.current
    var designing by remember { mutableStateOf<kg.forester.sg.data.CustomGauge?>(null) }
    designing?.let { d ->
        GaugeDesigner(d, onSaved = { onPick(it.styleId); designing = null; onClose() }, onClose = { designing = null })
    }
    // a dialog, not a Popup: a popup anchored near the top of the screen gets shifted on screen while its touch
    // area stays put, so taps landed on the wrong design
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(C.Card).border(1.dp, C.Purple, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.92f).dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(14.dp),
        ) {
            T(title, 16.sp, weight = FontWeight.SemiBold)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            // "" = the card's plain look, then the designs, the user's own ones, a picture already in use,
            // CREATE = new own design, PICK = choose a picture
            val options: List<String> = (if (plain != null) listOf("") else emptyList()) + DialStyles.map { it.id } +
                kg.forester.sg.data.CustomGauges.list.map { it.styleId } +
                listOfNotNull(current.takeIf { it.startsWith("photo:") }) + CREATE + PICK
            options.chunked(5).forEach { row ->
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    row.forEach { id ->
                        val st = if (id.isEmpty() || id == PICK || id == CREATE) null else dialStyle(id)
                        val own = kg.forester.sg.data.CustomGauges.byStyleId(id)
                        val on = id == current
                        Column(
                            Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(if (on) C.Purple.copy(alpha = 0.25f) else Color.Transparent)
                                .border(if (on) 2.dp else 1.dp, if (on) C.Purple else C.Border, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable {
                                    when (id) {
                                        PICK -> { pickPicture(target); onClose() }
                                        CREATE -> designing = kg.forester.sg.data.CustomGauges.newDraft(
                                            current.takeIf { c -> DialStyles.any { it.id == c } } ?: "neon",
                                        )
                                        else -> { onPick(id); onClose() }
                                    }
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(112.dp).background(Color(0xFF0B0F1C), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    st != null -> preview(st, Modifier.fillMaxSize().padding(4.dp))
                                    id == PICK -> androidx.compose.material3.Icon(
                                        com.composables.icons.lucide.Lucide.ImagePlus, null, tint = C.Violet, modifier = Modifier.size(40.dp),
                                    )
                                    id == CREATE -> androidx.compose.material3.Icon(
                                        com.composables.icons.lucide.Lucide.Palette, null, tint = C.Violet, modifier = Modifier.size(40.dp),
                                    )
                                    else -> T("— — —", 22.sp, C.Violet, FontWeight.Bold)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T(
                                    when { id == PICK -> "Своя картинка…"; id == CREATE -> "Свой дизайн…"; st != null -> st.name; else -> plain ?: "" },
                                    12.sp, if (on) C.Text else C.Sub, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp),
                                )
                                if (own != null) androidx.compose.material3.Icon(
                                    com.composables.icons.lucide.Lucide.Pencil, "Изменить", tint = C.Violet,
                                    modifier = Modifier.padding(start = 4.dp).size(16.dp).clickable { designing = own },
                                )
                            }
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            }
        }
    }
}
