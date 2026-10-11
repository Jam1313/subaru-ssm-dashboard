package kg.forester.sg.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.composables.icons.lucide.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo

@Composable
fun TransmissionScreen() {
    val t by Repo.tcu.collectAsState()
    val th by Repo.tcuHistory.collectAsState()
    val e by Repo.live.collectAsState()
    val status by Repo.tcuStatus.collectAsState()

    val rpm = e.rpm ?: 0
    val slip = t?.turbineRpm?.let { tr -> if (rpm > 800) ((rpm - tr) * 100f / rpm) else null }
    val locked = (t?.lockUpPct ?: 0f) > 60f

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1.1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(0.8f).fillMaxHeight()) {
                T("Селектор / передача", 14.sp, C.Sub)
                val range = t?.range
                val driving = range == "D" || range == "3" || range == "2" || range == "1"
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = range,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "range",
                    ) { r ->
                        T(r ?: "—", if ((r?.length ?: 1) > 1) 64.sp else 96.sp, C.Violet, FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Передача: ", 16.sp, C.Sub)
                    AnimatedContent(
                        targetState = if (driving) t?.gear else null,
                        transitionSpec = {
                            val up = (targetState ?: 0) > (initialState ?: 0)
                            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                                (slideOutVertically { if (up) -it else it } + fadeOut())
                        },
                        label = "gear",
                    ) { g -> T(g?.toString() ?: "—", 28.sp, weight = FontWeight.Bold) }
                }
            }
            AtfCard(t?.atfC, Modifier.weight(1f).fillMaxHeight())
            Panel(Modifier.weight(1.2f).fillMaxHeight()) {
                T("Гидротрансформатор", 14.sp, C.Sub)
                Spacer(Modifier.height(10.dp))
                T(if (locked) "Заблокирован" else "Разблокирован", 24.sp, if (locked) C.Green else C.Text, FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                T("Обороты двигателя: $rpm", 14.sp)
                T("Обороты турбины: ${t?.turbineRpm ?: "—"}", 14.sp)
                T("Проскальзывание: ${fmtF(slip, 0)} %", 14.sp, if ((slip ?: 0f) > 15 && locked) C.Orange else C.Text)
                Spacer(Modifier.weight(1f))
                T(status, 12.sp, C.Sub)
            }
        }
        Row(Modifier.weight(0.55f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Lucide.Lock, "Блокировка (Lock-up)", fmtF(t?.lockUpPct, 0), "%", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.ArrowDownUp, "Давление в линии", fmtF(t?.linePressurePct, 0), "%", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.Waypoints, "Муфта полного привода", fmtF(t?.transferPct, 0), "%", Modifier.weight(1f).fillMaxHeight())
            Tile(Lucide.CarFront, "Колёса перед / зад", "${t?.frontWheelKmh ?: "—"} / ${t?.rearWheelKmh ?: "—"}", "км/ч", Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.weight(1.2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChartCard(
                "Температура ATF, °C",
                listOf(Series("ATF", th.map { it.atfC?.toFloat() }, C.Orange)),
                0f, 130f, Modifier.weight(1f).fillMaxHeight(), "%.0f",
            )
            ChartCard(
                "Блокировка и полный привод, %",
                listOf(
                    Series("lock-up", th.map { it.lockUpPct }, C.Green),
                    Series("AWD", th.map { it.transferPct }, C.Blue),
                    Series("линия", th.map { it.linePressurePct }, C.Violet),
                ),
                0f, 100f, Modifier.weight(1f).fillMaxHeight(), "%.0f",
            )
        }
    }
}

@Composable
private fun AtfCard(atf: Int?, modifier: Modifier) {
    val (label, color) = when {
        atf == null -> "нет данных" to C.Sub
        atf < 40 -> "холодная, не нагружайте" to C.Blue
        atf <= 100 -> "норма" to C.Green
        atf <= 115 -> "горячая" to C.Orange
        else -> "перегрев!" to C.Red
    }
    Panel(modifier) {
        T("Температура ATF", 14.sp, C.Sub)
        RingGauge((atf ?: 0) / 130f, atf?.let { "$it°" } ?: "—", label, Modifier.weight(1f).fillMaxWidth(), unitColor = color)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("0", 11.sp, C.Sub)
            Spacer(Modifier.width(6.dp))
            ProgressBar(
                (atf ?: 0) / 130f,
                Brush.horizontalGradient(listOf(C.Blue, C.Green, C.Orange, C.Red)),
                Modifier.weight(1f), 6.dp,
            )
            Spacer(Modifier.width(6.dp))
            T("130", 11.sp, C.Sub)
        }
    }
}
