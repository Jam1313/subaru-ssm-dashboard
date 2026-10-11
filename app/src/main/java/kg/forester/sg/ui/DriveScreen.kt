package kg.forester.sg.ui

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
import kg.forester.sg.data.Repo

@Composable
fun DriveScreen() {
    val e by Repo.live.collectAsState()
    val h by Repo.history.collectAsState()
    val knocks by Repo.knockEvents.collectAsState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(0.9f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniGauge("Педаль газа", e.pedalPct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            MiniGauge("Дроссель", e.throttlePct, 0f, 100f, "%", "%.0f", Modifier.weight(1f))
            MiniGauge("Наддув", e.boostBar, 0f, 1.2f, "бар", "%.2f", Modifier.weight(1f), sub = e.vacuumKpa?.takeIf { it > 1f }?.let { "разрежение %.0f кПа".format(it) })
            MiniGauge("Нагрузка", e.loadGRev, 0f, 2.5f, "г/об", "%.2f", Modifier.weight(1f))
            KnockCard(knocks, e.knockDeg, Modifier.weight(1f))
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
fun MiniGauge(title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier, sub: String? = null, big: Boolean = false) {
    Panel(modifier.fillMaxHeight(), padding = 12.dp) {
        T(title, 13.sp, C.Sub, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            T(value?.let { format.format(it) } ?: "—", if (big) 46.sp else 30.sp, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            T(unit, if (big) 15.sp else 13.sp, C.Sub, modifier = Modifier.padding(bottom = if (big) 9.dp else 5.dp))
        }
        T(sub ?: " ", 11.sp, C.Sub, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        ProgressBar(value?.let { (it - min) / (max - min) } ?: 0f, C.Accent, Modifier.fillMaxWidth(), 7.dp)
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            T(format.format(min), 10.sp, C.Sub, modifier = Modifier.weight(1f))
            T(format.format(max), 10.sp, C.Sub)
        }
    }
}

/** Knock counter; pulses red while the ECU is pulling timing. */
@Composable
private fun KnockCard(count: Int, knockDeg: Float?, modifier: Modifier) {
    val active = (knockDeg ?: 0f) < 0f
    val pulse by rememberInfiniteTransition(label = "knock").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse",
    )
    Panel(modifier.fillMaxHeight(), padding = 12.dp) {
        T("Детонация", 13.sp, C.Sub)
        Spacer(Modifier.weight(1f))
        T(
            if (active) "%.1f°".format(knockDeg) else "нет",
            30.sp, if (active) C.Red else C.Green, FontWeight.Bold,
            Modifier.alpha(if (active) pulse else 1f),
        )
        Spacer(Modifier.height(4.dp))
        T("Случаев за сеанс: $count", 12.sp, C.Sub)
    }
}
