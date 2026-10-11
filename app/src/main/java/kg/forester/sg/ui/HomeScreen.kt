package kg.forester.sg.ui

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
        Row(Modifier.weight(1.6f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1.9f).fillMaxHeight(), padding = 0.dp) {
                CarViewer(e.rpm, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniGauge("Обороты", e.rpm?.toFloat(), 0f, 7000f, "об/мин", "%.0f", Modifier.weight(1f).fillMaxWidth(), big = true)
                MiniGauge("Скорость", e.speedKmh?.toFloat(), 0f, 200f, "км/ч", "%.0f", Modifier.weight(1f).fillMaxWidth(), big = true)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarCard("Темп. ОЖ", e.coolantC?.toFloat(), 40f, 110f, "°C", "%.0f", Modifier.weight(1f), warnAbove = 102f)
            BarCard("Темп. впуска", e.intakeC?.toFloat(), -20f, 70f, "°C", "%.0f", Modifier.weight(1f), warnAbove = 55f)
            BarCard("Напряжение", e.batteryV, 10f, 15f, "В", "%.1f", Modifier.weight(1f), warnBelow = 12f)
            BarCard("Дроссель", e.throttlePct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            BarCard("MAF", e.mafGs, 0f, 150f, "г/с", "%.1f", Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarCard("AFR", e.afr, 10f, 20f, "", "%.1f", Modifier.weight(1f))
            BarCard("Коррекция A/F", e.afCorrPct, -25f, 25f, "%", "%+.1f", Modifier.weight(1f), warnAbs = 15f)
            BarCard("Обучение A/F", e.afLearnPct, -25f, 25f, "%", "%+.1f", Modifier.weight(1f), warnAbs = 10f)
            BarCard("УОЗ", e.timingDeg, -10f, 50f, "°", "%.1f", Modifier.weight(1f))
            BarCard("Коррекция детонации", e.knockDeg, -10f, 2f, "°", "%.1f", Modifier.weight(1f), warnBelow = -2f)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarCard("Впрыск", e.injPwMs, 0f, 20f, "мс", "%.2f", Modifier.weight(1f))
            BarCard("Wastegate", e.wastegatePct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            BarCard("Расход", ts.instLh.toFloat(), 0f, 40f, "л/ч", "%.1f", Modifier.weight(1f))
            BarCard("Наддув", e.boostBar, 0f, 1.2f, "бар", "%.2f", Modifier.weight(1f))
            BarCard(
                "GPS", gps?.speedKmh, 0f, 200f, "км/ч", "%.0f", Modifier.weight(1f),
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
    title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier,
    warnAbove: Float? = null, warnBelow: Float? = null, warnAbs: Float? = null, extra: String? = null,
) {
    val warn = value != null && (
        (warnAbove != null && value > warnAbove) ||
            (warnBelow != null && value < warnBelow) ||
            (warnAbs != null && kotlin.math.abs(value) > warnAbs)
        )
    val color: Brush = if (warn) Brush.horizontalGradient(listOf(C.Orange, C.Red)) else C.Accent
    Panel(modifier.fillMaxHeight(), padding = 12.dp) {
        T(title, 13.sp, C.Sub)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            T(value?.let { format.format(it) } ?: "—", 26.sp, if (warn) C.Orange else Color.White, FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            T(unit, 12.sp, C.Sub, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (extra != null) T(extra, 11.sp, C.Sub)
        Spacer(Modifier.height(6.dp))
        ProgressBar(value?.let { (it - min) / (max - min) } ?: 0f, color, Modifier.fillMaxWidth(), 6.dp)
    }
}
