package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kg.forester.sg.service.BatteryMonitor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Verdict(val text: String, val color: Color)

private fun restingVerdict(v: Float?) = when {
    v == null -> Verdict("нет данных — включите зажигание без запуска", C.Sub)
    v >= 12.5f -> Verdict("заряжен", C.Green)
    v >= 12.2f -> Verdict("заряжен частично", C.Orange)
    else -> Verdict("разряжен — нужна зарядка", C.Red)
}

private fun crankVerdict(v: Float?) = when {
    v == null -> Verdict("нет данных — запишется при запуске", C.Sub)
    v >= 10.0f -> Verdict("отлично держит пуск", C.Green)
    v >= 9.6f -> Verdict("норма", C.Orange)
    else -> Verdict("слабый — пора проверить/менять", C.Red)
}

private fun chargeVerdict(v: Float?) = when {
    v == null -> Verdict("нет данных — через 30 с работы двигателя", C.Sub)
    v < 13.2f -> Verdict("недозаряд — проверьте генератор/ремень", C.Red)
    v <= 14.8f -> Verdict("генератор в норме", C.Green)
    else -> Verdict("перезаряд — проверьте регулятор", C.Red)
}

@Composable
fun BatteryScreen() {
    val e by Repo.live.collectAsState()
    val b by Repo.battery.collectAsState()
    val h by Repo.history.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    var week by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val volts = remember(week, version, tick) {
        val now = System.currentTimeMillis()
        if (week) Repo.db.volts(now - 7 * 86_400_000L, 3600_000L) else Repo.db.volts(now - 86_400_000L, 600_000L)
    }
    val events = remember(version) { Repo.db.batteryEvents(60) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(0.9f).fillMaxHeight()) {
                T("Сейчас", 13.sp, C.Sub)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    T(fmtF(e.batteryV, 2), 36.sp, weight = FontWeight.Bold)
                    T(" В", 14.sp, C.Sub, modifier = Modifier.padding(bottom = 6.dp))
                }
                ProgressBar(((e.batteryV ?: 10f) - 10f) / 5f, Brush.horizontalGradient(listOf(C.Red, C.Orange, C.Green, C.Green)), Modifier.fillMaxWidth(), 7.dp)
                Row(Modifier.fillMaxWidth()) {
                    T("10", 10.sp, C.Sub, modifier = Modifier.weight(1f))
                    T("15 В", 10.sp, C.Sub)
                }
                val soc = b.restingV?.let { BatteryMonitor.socPct(it) }
                T("Заряд по напряжению покоя: ${soc?.let { "$it%" } ?: "—"}", 12.sp, C.Sub)
                Spacer(Modifier.weight(1f))
            }
            VoltCard("Покой (зажигание, двигатель заглушен)", b.restingV, restingVerdict(b.restingV), b.restingTimeMs, Modifier.weight(1f))
            VoltCard(
                "Просадка при пуске", b.crankMinV, crankVerdict(b.crankMinV), b.crankTimeMs, Modifier.weight(1f),
                extra = b.crankMs?.let { "запуск %.1f с".format(it / 1000f) },
            )
            VoltCard(
                "Заряд от генератора", b.chargingV, chargeVerdict(b.chargingV), 0, Modifier.weight(1f),
                extra = if (b.chargingMinV != null) {
                    "мин %.2f / макс %.2f В".format(b.chargingMinV, b.chargingMaxV) +
                        (e.altDutyPct?.takeIf { it > 0f }?.let { ", генератор %.0f%%".format(it) } ?: "")
                } else null,
            )
        }
        Row(Modifier.weight(1.3f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChartCard(
                "Напряжение, последняя минута",
                listOf(Series("В", h.map { it.batteryV }, C.Green)),
                9f, 15f, Modifier.weight(1f).fillMaxHeight(), "%.1f",
            )
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("История напряжения", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    listOf(false to "24 часа", true to "Неделя").forEach { (w, label) ->
                        Box(
                            Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (week == w) C.Purple.copy(alpha = 0.3f) else C.CardHi)
                                .clickable { week = w }.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { T(label, 12.sp, if (week == w) C.Text else C.Sub) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                LiveChart(
                    listOf(Series("В", volts.map { it.second.toFloat() }, C.Green)),
                    9f, 15f, Modifier.fillMaxSize(),
                )
            }
        }
        Panel(Modifier.weight(0.9f).fillMaxWidth(), padding = 12.dp) {
            T("Журнал аккумулятора (также пишется в logs/battery.csv)", 14.sp, weight = FontWeight.SemiBold)
            LazyColumn {
                items(events) { ev ->
                    val name = when (ev.type) {
                        "resting" -> "Покой"
                        "crank" -> "Пуск (мин.)"
                        "charging" -> "Заряд"
                        else -> ev.type
                    }
                    val extra = when (ev.type) {
                        "crank" -> "длительность %.1f с".format(ev.extra / 1000)
                        "charging" -> if (ev.extra > 0) "генератор %.0f%%".format(ev.extra) else ""
                        else -> ""
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        T(SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(ev.timeMs)), 13.sp, C.Sub, modifier = Modifier.width(110.dp))
                        T(name, 13.sp, modifier = Modifier.width(120.dp))
                        T("%.2f В".format(ev.value), 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                        T(extra, 13.sp, C.Sub)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoltCard(title: String, v: Float?, verdict: Verdict, timeMs: Long, modifier: Modifier, extra: String? = null) {
    Panel(modifier.fillMaxHeight()) {
        T(title, 13.sp, C.Sub)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            T(fmtF(v, 2), 36.sp, weight = FontWeight.Bold)
            T(" В", 14.sp, C.Sub, modifier = Modifier.padding(bottom = 6.dp))
        }
        T(verdict.text, 14.sp, verdict.color, FontWeight.SemiBold)
        if (extra != null) T(extra, 12.sp, C.Sub)
        if (timeMs > 0) T(SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(timeMs)), 11.sp, C.Sub)
        Spacer(Modifier.weight(1f))
    }
}
