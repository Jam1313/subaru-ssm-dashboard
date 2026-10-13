package kg.forester.sg.ui

import androidx.compose.foundation.layout.size

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import com.composables.icons.lucide.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.DriveBoard
import kg.forester.sg.data.KnockStats
import kg.forester.sg.data.TileCfg
import androidx.compose.ui.graphics.vector.ImageVector
import kg.forester.sg.data.Repo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cards of the "Динамика" screen, for the "add" menu. */
val DriveCards: CardNames = linkedMapOf(
    "pedal" to ("Педаль газа" to Lucide.ChevronsUp),
    "throttle" to ("Дроссель" to Lucide.Disc),
    "boost" to ("Наддув" to Lucide.Fan),
    "load" to ("Нагрузка" to Lucide.Activity),
    "rpm" to ("Обороты" to Lucide.Gauge),
    "knock" to ("Детонация" to Lucide.AudioWaveform),
    "chartPedal" to ("График: педаль и дроссель" to Lucide.ChartLine),
    "chartBoost" to ("График: наддув" to Lucide.ChartLine),
    "chartAfr" to ("График: смесь (AFR)" to Lucide.ChartLine),
    "chartTiming" to ("График: зажигание и детонация" to Lucide.ChartLine),
    "chartRpm" to ("График: обороты" to Lucide.ChartLine),
    "chartTrims" to ("График: коррекции A/F" to Lucide.ChartLine),
    "chartMaf" to ("График: MAF" to Lucide.ChartLine),
    "avcs" to ("AVCS (впуск)" to Lucide.RefreshCw),
    "injDuty" to ("Загрузка форсунок" to Lucide.Droplets),
    "maf" to ("MAF" to Lucide.Wind),
    "throttleMotor" to ("Привод дросселя" to Lucide.Cog),
    "wgdc" to ("Wastegate" to Lucide.Wind),
    "pressure" to ("Абс. давление" to Lucide.Gauge),
) + ExtraCards

private val DriveEdit = BoardEdit(
    DriveBoard,
    gaugeable = setOf("pedal", "throttle", "boost", "load", "rpm", "avcs", "injDuty", "maf", "throttleMotor", "wgdc"),
    rowWeight = ::driveRowWeight,
)

/** Row heights: gauges 0.9, charts 1.2, small tiles 0.55; a row with a needle gauge gets 1.3 so the dial is readable. */
private fun driveRowWeight(t: TileCfg) = when {
    t.id.startsWith("chart") -> 1.2f
    t.id in kg.forester.sg.data.EXTRA_CARDS -> 0.9f
    t.style.isNotEmpty() -> 1.3f
    t.id in setOf("avcs", "injDuty", "maf", "throttleMotor", "wgdc", "pressure") -> 0.55f
    else -> 0.9f
}

@Composable
fun DriveScreen() {
    val e by Repo.live.collectAsState()
    val h by Repo.history.collectAsState()
    val knock by Repo.knock.collectAsState()

    BoardScreen(DriveEdit, DriveCards, ::driveRowWeight) { t, m ->
        val info = "drive.${t.id}"
        @Composable
        fun Mini(icon: ImageVector, title: String, v: Float?, min: Float, max: Float, unit: String, fmt: String, sub: String? = null, red: Float? = null) =
            ValueCard(CardValue(icon, title, v, min, max, unit, fmt, info, red), t.style, m) {
                MiniGauge(icon, title, v, min, max, unit, fmt, m, sub = sub, info = info)
            }
        @Composable
        fun Small(icon: ImageVector, title: String, v: Float?, min: Float, max: Float, unit: String, fmt: String) =
            ValueCard(CardValue(icon, title, v, min, max, unit, fmt, info), t.style, m) {
                Tile(icon, title, v?.let { fmt.format(it) } ?: "—", unit, m, info)
            }
        @Composable
        fun Chart(title: String, series: List<Series>, min: Float, max: Float, fmt: String = "%.1f", zero: Boolean = false) =
            ChartCard(title, series, min, max, m, fmt, zero, info)
        when (t.id) {
            "pedal" -> Mini(Lucide.ChevronsUp, "Педаль газа", e.pedalPct, 0f, 100f, "%", "%.0f")
            "throttle" -> Mini(Lucide.Disc, "Дроссель", e.throttlePct, 0f, 100f, "%", "%.0f")
            "boost" -> Mini(Lucide.Fan, "Наддув", e.boostBar, 0f, 1.2f, "бар", "%.2f",
                sub = e.vacuumKpa?.takeIf { it > 1f }?.let { "разрежение %.0f кПа".format(it) })
            "load" -> Mini(Lucide.Activity, "Нагрузка", e.loadGRev, 0f, 2.5f, "г/об", "%.2f")
            "rpm" -> Mini(Lucide.Gauge, "Обороты", e.rpm?.toFloat(), 0f, 7000f, "об/мин", "%.0f", red = 6000f)
            "knock" -> KnockCard(knock, e.knockDeg, m)
            "chartPedal" -> Chart(
                "Педаль и дроссель, %",
                listOf(Series("педаль", h.map { it.pedalPct }, C.Violet), Series("дроссель", h.map { it.throttlePct }, C.Blue)),
                0f, 100f, "%.0f",
            )
            "chartBoost" -> Chart("Наддув, бар", listOf(Series("наддув", h.map { it.boostBar }, C.Green)), 0f, 1.2f, "%.2f")
            "chartAfr" -> Chart("Смесь (AFR)", listOf(Series("AFR", h.map { it.afr }, C.Orange)), 10f, 20f)
            "chartTiming" -> Chart(
                "Зажигание и детонация, °",
                listOf(Series("УОЗ", h.map { it.timingDeg }, C.Blue), Series("детонация", h.map { it.knockDeg }, C.Red)),
                -10f, 45f, "%.0f", zero = true,
            )
            "chartRpm" -> Chart("Обороты, об/мин", listOf(Series("обороты", h.map { it.rpm?.toFloat() }, C.Violet)), 0f, 7000f, "%.0f")
            "chartTrims" -> Chart(
                "Коррекции A/F, %",
                listOf(Series("коррекция", h.map { it.afCorrPct }, C.Orange), Series("обучение", h.map { it.afLearnPct }, C.Blue)),
                -25f, 25f, "%.0f", zero = true,
            )
            "chartMaf" -> Chart("MAF, г/с", listOf(Series("MAF", h.map { it.mafGs }, C.Blue)), 0f, 150f, "%.0f")
            "avcs" -> Small(Lucide.RefreshCw, "AVCS (впуск)", e.avcsDeg, -10f, 50f, "°", "%.0f")
            "injDuty" -> Small(Lucide.Droplets, "Загрузка форсунок", e.injDutyPct, 0f, 100f, "%", "%.0f")
            "maf" -> Small(Lucide.Wind, "MAF", e.mafGs, 0f, 150f, "г/с", "%.1f")
            "throttleMotor" -> Small(Lucide.Cog, "Привод дросселя", e.throttleMotorPct, -100f, 100f, "%", "%.0f")
            "wgdc" -> Small(Lucide.Wind, "Wastegate", e.wastegatePct, 0f, 100f, "%", "%.0f")
            "pressure" -> Tile(Lucide.Gauge, "Абс. давление", "${fmtF(e.mapKpa, 0)} / ${fmtF(e.atmKpa, 0)}", "кПа", m, info)
            else -> ExtraCard(t.id, info, m)
        }
    }
}

@Composable
fun MiniGauge(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier, sub: String? = null, big: Boolean = false, info: String = title) {
    // The card height depends on the screen; drop the scale labels and shrink the number when it is short
    // instead of letting the number get clipped.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
        val roomy = maxHeight >= if (big) 150.dp else 135.dp
        val tight = maxHeight < 96.dp
        Panel(Modifier.fillMaxSize(), padding = if (tight) 8.dp else 12.dp) {
            CardTitle(icon, title, info)
            FitValue(
                value?.let { format.format(it) } ?: "—", unit, if (big) 46f else 30f,
                Modifier.weight(1f).fillMaxWidth(),
            )
            if (sub != null || (roomy && !big)) T(sub ?: " ", 11.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(if (tight) 4.dp else 6.dp))
            ProgressBar(value?.let { (it - min) / (max - min) } ?: 0f, C.Accent, Modifier.fillMaxWidth(), if (tight) 5.dp else 7.dp)
            if (roomy) {
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth()) {
                    T(format.format(min), 10.sp, C.Sub, modifier = Modifier.weight(1f))
                    T(format.format(max), 10.sp, C.Sub)
                }
            }
        }
    }
}

/** Card header: a small accent icon and the parameter name. */
@Composable
fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, info: String = title) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = C.Violet.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        T(title, 13.sp, C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
        InfoButton(title, info)
    }
}

/** Big number + unit, bottom-aligned, as large as [maxSp] but never taller or wider than its box. */
@Composable
fun FitValue(text: String, unit: String, maxSp: Float, modifier: Modifier, color: androidx.compose.ui.graphics.Color = C.Text) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.BottomStart) {
        val d = androidx.compose.ui.platform.LocalDensity.current
        val unitW = if (unit.isEmpty()) 0.dp else (unit.length * 8 + 12).dp
        val digits = text.length.coerceAtLeast(2)
        // a bold digit is ~0.6 em wide, the line is ~1.2 em tall
        val byH = with(d) { (maxHeight / 1.2f).toSp().value }
        val byW = with(d) { ((maxWidth - unitW) / (digits * 0.62f)).toSp().value }
        val size = minOf(maxSp, byH, byW).coerceAtLeast(10f)
        Row(verticalAlignment = Alignment.Bottom) {
            T(text, size.sp, color, FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(5.dp))
                T(unit, (size * 0.33f).coerceIn(10f, 15f).sp, C.Sub, maxLines = 1, modifier = Modifier.padding(bottom = (size * 0.2f).dp))
            }
        }
    }
}

/** Timing pulls this trip; pulses red while the ECU is pulling timing, tap for the list. */
@Composable
private fun KnockCard(stats: KnockStats, knockDeg: Float?, modifier: Modifier) {
    val active = (knockDeg ?: 0f) < 0f
    var open by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "knock").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse",
    )
    Panel(modifier.fillMaxHeight().clip(Rounded).clickable { open = true }, padding = 12.dp) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Детонация", 13.sp, C.Sub, modifier = Modifier.weight(1f))
            InfoButton("Детонация", "drive.knock")
        }
        Spacer(Modifier.weight(1f))
        T(
            if (active) "%.1f°".format(knockDeg) else "нет",
            30.sp, if (active) C.Red else C.Green, FontWeight.Bold,
            Modifier.alpha(if (active) pulse else 1f),
        )
        Spacer(Modifier.height(4.dp))
        T(
            stats.maxPullDeg?.let { "За поездку: ${stats.count} · макс %.1f°".format(it) } ?: "За поездку: не было",
            12.sp, C.Sub, maxLines = 1,
        )
    }
    if (open) KnockDialog(stats) { open = false }
}

@Composable
private fun KnockDialog(stats: KnockStats, onClose: () -> Unit) {
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.CardHi,
        title = { T("Откаты зажигания за поездку", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column {
                T(
                    "Это не отдельные стуки, а случаи, когда ЭБУ по сигналу датчика детонации убирал угол зажигания. " +
                        "Считаются откаты от ${"%.0f".format(-KnockStats.START_DEG)}° и глубже; случай длится, пока коррекция не вернётся к нулю.",
                    13.sp, C.Sub,
                )
                Spacer(Modifier.height(10.dp))
                if (stats.events.isEmpty()) T("С начала поездки откатов не было.", 14.sp)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(stats.events.asReversed()) { ev ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(time.format(Date(ev.startMs)), 13.sp, C.Sub, modifier = Modifier.width(70.dp))
                            T("%.1f°".format(ev.maxPullDeg), 15.sp, if (ev.maxPullDeg <= -3f) C.Red else C.Orange, FontWeight.Bold, Modifier.width(60.dp))
                            T(
                                listOfNotNull(
                                    ev.rpm?.let { "$it об/мин" },
                                    ev.loadGRev?.let { "%.2f г/об".format(it) },
                                    ev.throttlePct?.let { "дроссель %.0f%%".format(it) },
                                    ev.speedKmh?.let { "$it км/ч" },
                                    if (ev.active) "идёт сейчас" else "%.1f с".format(ev.durationMs / 1000f),
                                ).joinToString(" · "),
                                13.sp, maxLines = 2, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                T(
                    "Редкие откаты на 1–2° под нагрузкой — обычная работа ЭБУ (плохой бензин, жара, кочки дают ложную детонацию). " +
                        "Стоит разобраться, если откаты глубже 3°, частые или идут на прогретом моторе при ровной езде. " +
                        "Все случаи пишутся в logs/knock.csv.",
                    12.sp, C.Sub,
                )
            }
        },
        confirmButton = { TextButton(onClose) { Text("Закрыть") } },
    )
}
