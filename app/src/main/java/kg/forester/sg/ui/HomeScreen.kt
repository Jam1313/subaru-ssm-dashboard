package kg.forester.sg.ui

import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.aspectRatio
import com.composables.icons.lucide.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo

@Composable
fun HomeScreen() {
    val e by Repo.live.collectAsState()
    val gps by Repo.gps.collectAsState()
    val ts by Repo.trip.collectAsState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // the "cluster": tachometer, the car on the road, speedometer
        Panel(Modifier.weight(1.9f).fillMaxWidth(), padding = 0.dp) {
            Box(Modifier.fillMaxSize()) {
                CarViewer(
                    e.rpm, e.speedKmh, Modifier.fillMaxSize(),
                    throttlePct = e.throttlePct, boostBar = e.boostBar,
                )
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Dial(e.rpm?.toFloat(), 7000f, 1000f, 1000f, "об/мин", "x1000", redFrom = 6000f, Modifier.fillMaxHeight().aspectRatio(1f))
                    Spacer(Modifier.weight(1f))
                    Dial(e.speedKmh?.toFloat(), 200f, 20f, 1f, "км/ч", null, redFrom = null, Modifier.fillMaxHeight().aspectRatio(1f))
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarCard(Lucide.Thermometer, "Темп. ОЖ", e.coolantC?.toFloat(), 40f, 110f, "°C", "%.0f", Modifier.weight(1f), warnAbove = 102f)
            BarCard(Lucide.ThermometerSnowflake, "Темп. впуска", e.intakeC?.toFloat(), -20f, 70f, "°C", "%.0f", Modifier.weight(1f), warnAbove = 55f)
            BarCard(Lucide.BatteryCharging, "Напряжение", e.batteryV, 10f, 15f, "В", "%.1f", Modifier.weight(1f), warnBelow = 12f)
            BarCard(Lucide.Disc, "Дроссель", e.throttlePct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            BarCard(Lucide.Wind, "MAF", e.mafGs, 0f, 150f, "г/с", "%.1f", Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarCard(Lucide.Flame, "AFR", e.afr, 10f, 20f, "", "%.1f", Modifier.weight(1f))
            BarCard(Lucide.SlidersHorizontal, "Коррекция A/F", e.afCorrPct, -25f, 25f, "%", "%+.1f", Modifier.weight(1f), warnAbs = 15f)
            BarCard(Lucide.Brain, "Обучение A/F", e.afLearnPct, -25f, 25f, "%", "%+.1f", Modifier.weight(1f), warnAbs = 10f)
            BarCard(Lucide.Zap, "УОЗ", e.timingDeg, -10f, 50f, "°", "%.1f", Modifier.weight(1f))
            BarCard(Lucide.AudioWaveform, "Коррекция детонации", e.knockDeg, -10f, 2f, "°", "%.1f", Modifier.weight(1f), warnBelow = -2f)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarCard(Lucide.Syringe, "Впрыск", e.injPwMs, 0f, 20f, "мс", "%.2f", Modifier.weight(1f))
            BarCard(Lucide.Tornado, "Wastegate", e.wastegatePct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            BarCard(Lucide.Fuel, "Расход", ts.instLh.toFloat(), 0f, 40f, "л/ч", "%.1f", Modifier.weight(1f))
            BarCard(Lucide.Fan, "Наддув", e.boostBar, 0f, 1.2f, "бар", "%.2f", Modifier.weight(1f))
            BarCard(Lucide.Satellite, "GPS", gps?.speedKmh, 0f, 200f, "км/ч", "%.0f", Modifier.weight(1f),
                extra = gps?.let { "спутников ${it.satellites}, ±${it.accuracyM.toInt()} м" } ?: "нет сигнала",
            )
        }
    }
}

@Composable
private fun BigGauge(
    title: String, value: Float?, max: Float, unit: String, format: String, modifier: Modifier, min: Float = 0f,
) {
    Panel(modifier, padding = 10.dp) {
        T(title, 14.sp, C.Sub, maxLines = 1)
        RingGauge(
            ((value ?: min) - min) / (max - min), value?.let { format.format(it) } ?: "—", unit,
            Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BarCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier,
    warnAbove: Float? = null, warnBelow: Float? = null, warnAbs: Float? = null, extra: String? = null,
) {
    val warn = value != null && (
        (warnAbove != null && value > warnAbove) ||
            (warnBelow != null && value < warnBelow) ||
            (warnAbs != null && kotlin.math.abs(value) > warnAbs)
        )
    val color: Brush = if (warn) Brush.horizontalGradient(listOf(C.Orange, C.Red)) else C.Accent
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
        val tight = maxHeight < 84.dp
        Panel(Modifier.fillMaxSize(), padding = if (tight) 9.dp else 12.dp) {
            CardTitle(icon, title)
            FitValue(
                value?.let { format.format(it) } ?: "—", unit, 26f, Modifier.weight(1f).fillMaxWidth(),
                if (warn) C.Orange else Color.White,
            )
            if (extra != null) T(extra, 11.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(if (tight) 4.dp else 6.dp))
            ProgressBar(value?.let { (it - min) / (max - min) } ?: 0f, color, Modifier.fillMaxWidth(), if (tight) 5.dp else 6.dp)
        }
    }
}


/**
 * Round needle gauge (tachometer / speedometer): 270° scale with numbered major ticks, an optional red
 * zone, a lit arc up to the value and the number in the middle.
 */
@Composable
fun Dial(
    value: Float?, max: Float, step: Float, labelDiv: Float, unit: String, scaleNote: String?,
    redFrom: Float?, modifier: Modifier,
) {
    val v by androidx.compose.animation.core.animateFloatAsState(
        (value ?: 0f).coerceIn(0f, max), androidx.compose.animation.core.tween(240), label = "dial",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            val start = 135f
            val sweep = 270f
            fun ang(x: Float) = start + sweep * (x / max)
            fun pt(deg: Float, rr: Float): androidx.compose.ui.geometry.Offset {
                val a = Math.toRadians(deg.toDouble())
                return androidx.compose.ui.geometry.Offset(c.x + rr * kotlin.math.cos(a).toFloat(), c.y + rr * kotlin.math.sin(a).toFloat())
            }
            // glass disc so the dial reads over the road
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Color(0xE60B0F1C), Color(0xCC0B0F1C), Color(0x660B0F1C)), c, r,
                ), r, c,
            )
            drawCircle(C.Border, r - 1f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            val arcR = r * 0.86f
            val tl = androidx.compose.ui.geometry.Offset(c.x - arcR, c.y - arcR)
            val sz = androidx.compose.ui.geometry.Size(arcR * 2, arcR * 2)
            val stroke = r * 0.07f
            drawArc(Color(0xFF1E2438), start, sweep, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (redFrom != null) {
                drawArc(C.Red.copy(alpha = 0.55f), ang(redFrom), ang(max) - ang(redFrom), false, tl, sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            if (v > 0f) {
                val hot = redFrom != null && v >= redFrom
                drawArc(
                    if (hot) androidx.compose.ui.graphics.Brush.sweepGradient(listOf(C.Orange, C.Red), c)
                    else androidx.compose.ui.graphics.Brush.sweepGradient(listOf(C.Blue, C.Violet, C.Purple, C.Blue), c),
                    start, ang(v) - start, false, tl, sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
            // ticks and numbers
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = r * 0.13f
                color = android.graphics.Color.argb(200, 170, 178, 200)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val minor = step / 2f
            var x = 0f
            while (x <= max + 0.01f) {
                val major = (x / step).let { kotlin.math.abs(it - kotlin.math.round(it)) < 0.01f }
                val a = ang(x)
                val red = redFrom != null && x >= redFrom
                drawLine(
                    if (red) C.Red else if (major) Color(0xFFCBD5E1) else Color(0xFF475069),
                    pt(a, arcR - stroke * 0.9f), pt(a, arcR - stroke * (if (major) 2.6f else 1.7f)),
                    if (major) 2.5f else 1.4f,
                )
                if (major && (step * 2 > max / 8 || (x / step).roundToIntSafe() % 2 == 0)) {
                    val p = pt(a, arcR - stroke * 3.9f)
                    drawContext.canvas.nativeCanvas.drawText(
                        (x / labelDiv).toInt().toString(), p.x, p.y + paint.textSize * 0.35f, paint,
                    )
                }
                x += minor
            }
            // needle
            val na = ang(v)
            drawLine(Color(0x55FF4D6D), pt(na, r * 0.10f), pt(na, arcR - stroke * 0.3f), r * 0.05f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color(0xFFFF4D6D), pt(na, r * 0.10f), pt(na, arcR - stroke * 0.3f), r * 0.022f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(Color(0xFF2A3150), r * 0.075f, c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(0.5f)) {
                val d = androidx.compose.ui.platform.LocalDensity.current
                val sp = with(d) { (maxWidth / 3.1f).toSp() }
                T(value?.let { "%.0f".format(it) } ?: "—", sp, weight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.align(Alignment.Center))
            }
            T(unit, 12.sp, C.Sub, maxLines = 1)
            if (scaleNote != null) T(scaleNote, 10.sp, C.Sub.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

private fun Float.roundToIntSafe() = kotlin.math.round(this).toInt()
