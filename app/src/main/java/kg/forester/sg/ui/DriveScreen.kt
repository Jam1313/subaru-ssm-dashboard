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
import kg.forester.sg.data.KnockStats
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

@Composable
fun DriveScreen() {
    val e by Repo.live.collectAsState()
    val h by Repo.history.collectAsState()
    val knock by Repo.knock.collectAsState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(0.9f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniGauge(Lucide.ChevronsUp, "Педаль газа", e.pedalPct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            MiniGauge(Lucide.Disc, "Дроссель", e.throttlePct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            MiniGauge(Lucide.Fan, "Наддув", e.boostBar, 0f, 1.2f, "бар", "%.2f", Modifier.weight(1f), sub = e.vacuumKpa?.takeIf { it > 1f }?.let { "разрежение %.0f кПа".format(it) })
            MiniGauge(Lucide.Activity, "Нагрузка", e.loadGRev, 0f, 2.5f, "г/об", "%.2f", Modifier.weight(1f))
            KnockCard(knock, e.knockDeg, Modifier.weight(1f))
        }
        Row(Modifier.weight(1.2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChartCard(
                "Педаль и дроссель, %",
                listOf(
                    Series("педаль", h.map { it.pedalPct }, C.Violet),
                    Series("дроссель", h.map { it.throttlePct }, C.Blue),
                ),
                0f, 100f, Modifier.weight(1f).fillMaxHeight(), "%.0f",
            )
            ChartCard(
                "Наддув, бар",
                listOf(Series("наддув", h.map { it.boostBar }, C.Green)),
                0f, 1.2f, Modifier.weight(1f).fillMaxHeight(), "%.2f",
            )
        }
        Row(Modifier.weight(1.2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChartCard(
                "Смесь (AFR)",
                listOf(Series("AFR", h.map { it.afr }, C.Orange)),
                10f, 20f, Modifier.weight(1f).fillMaxHeight(),
            )
            ChartCard(
                "Зажигание и детонация, °",
                listOf(Series("УОЗ", h.map { it.timingDeg }, C.Blue), Series("детонация", h.map { it.knockDeg }, C.Red)),
                -10f, 45f, Modifier.weight(1f).fillMaxHeight(), "%.0f", zeroLine = true,
            )
        }
        Row(Modifier.weight(0.55f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Lucide.RefreshCw, "AVCS (впуск)", fmtF(e.avcsDeg, 0), "°", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.Droplets, "Загрузка форсунок", fmtF(e.injDutyPct, 0), "%", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.Wind, "MAF", fmtF(e.mafGs, 1), "г/с", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.Cog, "Привод дросселя", fmtF(e.throttleMotorPct, 0), "%", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.Wind, "Wastegate", fmtF(e.wastegatePct, 0), "%", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.Gauge, "Абс. давление", "${fmtF(e.mapKpa, 0)} / ${fmtF(e.atmKpa, 0)}", "кПа", Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
fun MiniGauge(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier, sub: String? = null, big: Boolean = false) {
    // The card height depends on the screen; drop the scale labels and shrink the number when it is short
    // instead of letting the number get clipped.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
        val roomy = maxHeight >= if (big) 150.dp else 135.dp
        val tight = maxHeight < 96.dp
        Panel(Modifier.fillMaxSize(), padding = if (tight) 8.dp else 12.dp) {
            CardTitle(icon, title)
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
fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = C.Violet.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        T(title, 13.sp, C.Sub, maxLines = 1)
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
        T("Детонация", 13.sp, C.Sub)
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
