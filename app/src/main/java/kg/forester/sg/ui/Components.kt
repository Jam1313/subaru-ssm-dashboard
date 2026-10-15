package kg.forester.sg.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interface colour themes, like the dash lighting of the cars: [main] is the highlight colour (C.Violet),
 * [deep] the selection (C.Purple), [second] the other end of the accent gradient. Ids are stored in the settings.
 */
enum class AccentTheme(val id: String, val title: String, val main: Color, val deep: Color, val second: Color) {
    VIOLET("violet", "Фиолетовый", Color(0xFFA855F7), Color(0xFF8B5CF6), Color(0xFF3B82F6)),
    GREEN("green", "Зелёный", Color(0xFF4ADE80), Color(0xFF16A34A), Color(0xFF0D9488)),
    RED("red", "Красный", Color(0xFFFF4D5E), Color(0xFFE11D48), Color(0xFFF97316)),
    BLUE("blue", "Синий", Color(0xFF60A5FA), Color(0xFF2563EB), Color(0xFF06B6D4)),
    ORANGE("orange", "Оранжевый", Color(0xFFFB923C), Color(0xFFEA580C), Color(0xFFE11D48));

    val theme get() = UiTheme(main, deep, second)

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: VIOLET
    }
}

/** The accent colours in use: one of the [AccentTheme]s or made from the user's own colour. */
data class UiTheme(val main: Color, val deep: Color, val second: Color) {
    companion object {
        /** From one picked colour: a deeper shade for the selection and a neighbouring hue for the gradient. */
        fun of(rgb: Int): UiTheme {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(rgb or 0xFF000000.toInt(), hsv)
            fun c(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(
                floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
            val v = hsv[2].coerceAtLeast(0.55f) // a very dark pick would vanish on the dark background
            return UiTheme(c(hsv[0], hsv[1], v), c(hsv[0], hsv[1] * 1.1f + 0.05f, v * 0.8f), c(hsv[0] - 45f, hsv[1], v * 0.95f))
        }

        /** A stored id: "violet", "green", ... or "custom:RRGGBB". */
        fun parse(id: String?): UiTheme =
            id?.takeIf { it.startsWith("custom:") }?.removePrefix("custom:")?.toIntOrNull(16)?.let(::of)
                ?: AccentTheme.byId(id).theme
    }
}

object C {
    /** Stored id of the theme ("violet", ..., "custom:RRGGBB"). */
    var themeId by mutableStateOf("violet")
        private set
    /** The accent colours; Compose state, so everything drawn in them follows a change (also a live preview). */
    var theme by mutableStateOf(AccentTheme.VIOLET.theme)

    fun applyTheme(id: String) {
        themeId = id
        theme = UiTheme.parse(id)
    }

    val Bg = Color(0xFF080A12)
    val Card = Color(0xFF111522)
    val CardHi = Color(0xFF181D2E)
    val Border = Color(0xFF232A40)
    val Text = Color(0xFFEDEFF5)
    val Sub = Color(0xFF8E95AB)
    val Purple: Color get() = theme.deep
    val Violet: Color get() = theme.main
    val Blue = Color(0xFF3B82F6)
    val Green = Color(0xFF22C55E)
    val Orange = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Gray = Color(0xFF64748B)
    val Accent: Brush get() = Brush.horizontalGradient(listOf(theme.second, theme.main))
}

val Rounded = RoundedCornerShape(14.dp)

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(Rounded)
            .background(C.Card)
            .border(BorderStroke(1.dp, C.Border), Rounded)
            .padding(padding),
        content = content,
    )
}

@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = C.Text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) = Text(
    text, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    style = TextStyle(fontSize = size, color = color, fontWeight = weight, textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified),
)

@Composable
fun PanelTitle(icon: ImageVector, title: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(title, 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
        InfoButton(title)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) C.Purple.copy(alpha = 0.28f) else C.CardHi)
            .border(1.dp, if (selected) C.Purple else C.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) C.Violet else C.Sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        T(text, 14.sp, if (selected) C.Text else C.Sub, FontWeight.Medium, maxLines = 1)
    }
}

/** Small stat tile: icon + label + value + unit. */
@Composable
fun Tile(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier = Modifier, info: String = label) {
    // the "i" sits in the corner, so the value and its unit keep the whole width of a narrow tile
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = C.Sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                T(label, 11.sp, C.Sub, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    T(value, 20.sp, weight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    T(unit, 12.sp, C.Sub, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
        InfoButton(label, info, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
fun ProgressBar(fraction: Float, brush: Brush, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "bar")
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(C.Border)
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim.coerceIn(0.001f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height))
                .background(brush)
        )
    }
}

/** 240° arc gauge with ticks and a knob at the current value. */
@Composable
fun ArcGauge(value: Float, max: Float, ticks: List<Int>, modifier: Modifier = Modifier) {
    val animFrac by animateFloatAsState((value / max).coerceIn(0f, 1f), tween(350), label = "arc")
    Canvas(modifier) {
        val stroke = (size.minDimension * 0.075f).coerceIn(4.dp.toPx(), 18.dp.toPx())
        val r = size.minDimension / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2 + r * 0.15f)
        val showTicks = size.minDimension > 180.dp.toPx()
        val start = 150f
        val sweep = 240f
        val tl = Offset(center.x - r, center.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawArc(C.Border, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val frac = animFrac
        drawArc(
            Brush.sweepGradient(listOf(C.Blue, C.Violet, C.Purple, C.Blue), center),
            start, sweep * frac, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // minor ticks
        if (showTicks) for (i in 0..40) {
            val a = Math.toRadians((start + sweep * i / 40.0))
            val r1 = r - stroke * 1.3f
            val r2 = r1 - if (i % 5 == 0) 12.dp.toPx() else 6.dp.toPx()
            drawLine(
                C.Sub.copy(alpha = 0.7f),
                Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
                strokeWidth = 2f,
            )
        }
        val ka = Math.toRadians((start + sweep * frac).toDouble())
        val kp = Offset(center.x + r * cos(ka).toFloat(), center.y + r * sin(ka).toFloat())
        drawCircle(Color.White, stroke * 0.55f, kp)
        drawCircle(C.Purple, stroke * 0.55f, kp, style = Stroke(3f))
    }
}

/** Donut with segments (fraction, color). */
@Composable
fun Donut(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 22.dp.toPx()
        val r = size.minDimension / 2 - stroke / 2
        val tl = Offset(size.width / 2 - r, size.height / 2 - r)
        val s = Size(r * 2, r * 2)
        val total = segments.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) {
            drawArc(C.Border, 0f, 360f, false, tl, s, style = Stroke(stroke))
            return@Canvas
        }
        var a = -90f
        segments.forEach { (f, color) ->
            val sw = 360f * f / total
            drawArc(color, a + 1f, (sw - 2f).coerceAtLeast(0.5f), false, tl, s, style = Stroke(stroke))
            a += sw
        }
    }
}

/** Area line chart with a dashed average line. */
@Composable
fun AreaChart(values: List<Float?>, maxY: Float, avg: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3
            drawLine(C.Border, Offset(0f, y), Offset(w, y), 1f)
        }
        if (values.size < 2) return@Canvas
        val step = w / (values.size - 1)
        // null = no data / standing: the line breaks there instead of dropping to zero
        val path = Path()
        val fill = Path()
        var open = false
        var segStart = 0f
        var lastX = 0f
        values.forEachIndexed { i, v ->
            val x = i * step
            if (v == null) {
                if (open) { fill.lineTo(lastX, h); fill.lineTo(segStart, h); fill.close(); open = false }
                return@forEachIndexed
            }
            val y = h - (v / maxY).coerceIn(0f, 1f) * h
            if (!open) {
                path.moveTo(x, y); fill.moveTo(x, y); segStart = x; open = true
            } else {
                path.lineTo(x, y); fill.lineTo(x, y)
            }
            lastX = x
        }
        if (open) { fill.lineTo(lastX, h); fill.lineTo(segStart, h); fill.close() }
        drawPath(fill, Brush.verticalGradient(listOf(C.Purple.copy(alpha = 0.55f), C.Blue.copy(alpha = 0.05f))))
        drawPath(path, C.Violet, style = Stroke(2.5f))
        if (avg != null) {
            val y = h - (avg / maxY).coerceIn(0f, 1f) * h
            drawLine(Color.White, Offset(0f, y), Offset(w, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}

fun fmt(v: Double?, digits: Int = 1, dash: String = "—"): String =
    if (v == null || v.isNaN() || v.isInfinite()) dash else "%.${digits}f".format(v)

fun fmtF(v: Float?, digits: Int = 1): String = fmt(v?.toDouble(), digits)


data class Series(val name: String, val values: List<Float?>, val color: Color)

/** Rolling multi-line chart for live data; [min]..[max] is the Y range. */
@Composable
fun LiveChart(series: List<Series>, min: Float, max: Float, modifier: Modifier = Modifier, zeroLine: Boolean = false) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..4) {
            val y = h * i / 4
            drawLine(C.Border, Offset(0f, y), Offset(w, y), 1f)
        }
        fun yOf(v: Float) = h - ((v - min) / (max - min)).coerceIn(0f, 1f) * h
        if (zeroLine && min < 0 && max > 0) {
            drawLine(C.Sub, Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        series.forEachIndexed { idx, s ->
            val n = s.values.size
            if (n < 2) return@forEachIndexed
            val step = w / (n - 1)
            val path = Path()
            var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = i * step
                val y = yOf(v)
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            if (idx == 0) {
                val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), Color.Transparent)))
            }
            drawPath(path, s.color, style = Stroke(3f, cap = StrokeCap.Round))
            s.values.lastOrNull()?.let { drawCircle(s.color, 5f, Offset(w, yOf(it))) }
        }
    }
}

@Composable
fun Legend(series: List<Series>, format: String = "%.1f") {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        series.forEach { s ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(s.color))
                Spacer(Modifier.width(6.dp))
                T("${s.name} ${s.values.lastOrNull()?.let { format.format(it) } ?: "—"}", 12.sp, C.Sub)
            }
        }
    }
}

/** Panel with a title row, legend and a live chart filling the rest. */
@Composable
fun ChartCard(title: String, series: List<Series>, min: Float, max: Float, modifier: Modifier, format: String = "%.1f", zeroLine: Boolean = false, info: String = title,
    icon: ImageVector? = null, tint: Color = C.Violet) {
    Panel(modifier, padding = 12.dp) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            T(title, 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Legend(series, format)
            InfoButton(title, info)
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
            Column(Modifier.width(34.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                T(format.format(max), 10.sp, C.Sub)
                T(format.format((max + min) / 2), 10.sp, C.Sub)
                T(format.format(min), 10.sp, C.Sub)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LiveChart(series, min, max, Modifier.fillMaxSize(), zeroLine)
                if (series.all { sr -> sr.values.all { it == null } }) T("Нет данных", 13.sp, C.Sub, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Card header: the icon in a tinted circle, the title, an optional line under it, and the "i". */
@Composable
fun CardHeader(icon: ImageVector, title: String, tint: Color, info: String = title, sub: String? = null) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(title, 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (sub != null) T(sub, 11.sp, C.Sub, maxLines = 1)
        }
        InfoButton(title, info)
    }
}

/**
 * Arc gauge with its value centred inside; text is sized from the space actually available,
 * so it never overlaps the arc no matter how small the cell is.
 */
@Composable
fun RingGauge(
    fraction: Float,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    unitColor: Color = C.Sub,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = if (maxWidth < maxHeight) maxWidth else maxHeight
        Box(Modifier.size(d), contentAlignment = Alignment.Center) {
            ArcGauge(fraction.coerceIn(0f, 1f) * 100f, 100f, emptyList(), Modifier.fillMaxSize())
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = d * 0.08f),
            ) {
                val digits = value.length.coerceAtLeast(2)
                // inner free width is ~0.62·d; a digit is ~0.58 em wide
                val byWidth = d.value * 0.62f / (digits * 0.58f)
                val size = minOf(byWidth, d.value * 0.26f, 60f)
                T(value, size.sp, weight = FontWeight.Bold, maxLines = 1)
                T(unit, maxOf(10f, size * 0.32f).sp, unitColor, maxLines = 1)
            }
        }
    }
}

/**
 * Demo on: a plate in the header that says so plainly (the made-up drive looks like a real one), a tap turns it off
 * and the app goes back to looking for the car.
 */
@Composable
fun DemoPill(short: Boolean = false) {
    val on by kg.forester.sg.data.Repo.demoOn.collectAsState()
    if (!on) return
    Row(
        Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Violet.copy(alpha = 0.22f))
            .border(1.dp, C.Violet, RoundedCornerShape(10.dp)).clickable { kg.forester.sg.data.Repo.settings.demo = false }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.MonitorPlay, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        T(if (short) "Демо" else "Демо: не ваша машина", 13.sp, C.Text, FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.X, "Выключить демо", tint = C.Sub, modifier = Modifier.size(14.dp))
    }
}

/**
 * The connection in the header: how the app talks to the car (USB cable, Wi-Fi or Bluetooth ELM327, demo),
 * green when the ECU answers, orange (struck through) while it does not.
 */
@Composable
fun LinkIcon(size: Dp) {
    val link by kg.forester.sg.data.Repo.link.collectAsState()
    val found by kg.forester.sg.data.Repo.adapterFound.collectAsState()
    val ok = link == kg.forester.sg.data.LinkState.CONNECTED
    val s = kg.forester.sg.data.Repo.settings
    val via = when {
        s.demo -> "demo"
        found.startsWith("Wi-Fi") -> "wifi"
        found.startsWith("Bluetooth") -> "bt"
        found.startsWith("USB") -> "usb"
        s.adapter == "elm_wifi" -> "wifi"
        s.adapter == "elm_bt" -> "bt"
        else -> "usb"
    }
    val icon = when (via) {
        "demo" -> Lucide.MonitorPlay
        "wifi" -> if (ok) Lucide.Wifi else Lucide.WifiOff
        "bt" -> if (ok) Lucide.Bluetooth else Lucide.BluetoothOff
        else -> if (ok) Lucide.Usb else Lucide.Unplug
    }
    Icon(icon, null, tint = if (via == "demo") C.Violet else if (ok) C.Green else C.Orange, modifier = Modifier.size(size))
}
