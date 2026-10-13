package kg.forester.sg.ui

import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
import kg.forester.sg.data.TcuBoard
import kg.forester.sg.data.TileCfg
import androidx.compose.ui.graphics.vector.ImageVector

/** Cards of the "АКПП" screen, for the "add" menu. */
val TcuCards: CardNames = linkedMapOf(
    "range" to ("Селектор / передача" to Lucide.Cog),
    "atf" to ("Температура ATF" to Lucide.ThermometerSun),
    "converter" to ("Гидротрансформатор" to Lucide.Lock),
    "lockup" to ("Блокировка (Lock-up)" to Lucide.Lock),
    "line" to ("Давление в линии" to Lucide.ArrowDownUp),
    "awd" to ("Муфта полного привода" to Lucide.Waypoints),
    "wheels" to ("Колёса перед / зад" to Lucide.CarFront),
    "chartAtf" to ("График: температура ATF" to Lucide.ChartLine),
    "chartLock" to ("График: блокировка и полный привод" to Lucide.ChartLine),
    "chartTurbine" to ("График: обороты двигателя и турбины" to Lucide.ChartLine),
    "chartWheels" to ("График: скорость колёс" to Lucide.ChartLine),
)

private val TcuEdit = BoardEdit(TcuBoard, gaugeable = setOf("atf", "lockup", "line"))

/** Row heights: big cards 1.1, charts 1.2, small tiles 0.55 (or 1.1 once shown as a needle). */
private fun tcuRowWeight(t: TileCfg) = when {
    t.id.startsWith("chart") -> 1.2f
    t.id in setOf("range", "atf", "converter") -> 1.1f
    t.style.isNotEmpty() -> 1.1f
    else -> 0.55f
}

@Composable
fun TransmissionScreen() {
    val t by Repo.tcu.collectAsState()
    val th by Repo.tcuHistory.collectAsState()
    val e by Repo.live.collectAsState()
    val status by Repo.tcuStatus.collectAsState()

    val rpm = e.rpm ?: 0
    val slip = t?.turbineRpm?.let { tr -> if (rpm > 800) ((rpm - tr) * 100f / rpm) else null }
    val locked = (t?.lockUpPct ?: 0f) > 60f

    BoardScreen(TcuEdit, TcuCards, ::tcuRowWeight) { cfg, m ->
        val info = "tcu.${cfg.id}"
        @Composable
        fun Small(icon: ImageVector, title: String, v: Float?, unit: String) =
            ValueCard(CardValue(icon, title, v, 0f, 100f, unit, "%.0f", info), cfg.style, m) {
                Tile(icon, title, fmtF(v, 0), unit, m, info)
            }
        when (cfg.id) {
            "range" -> Panel(m) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T("Селектор / передача", 14.sp, C.Sub, modifier = Modifier.weight(1f))
                    InfoButton("Селектор / передача", info)
                }
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
            "atf" -> ValueCard(
                CardValue(Lucide.ThermometerSun, "Температура ATF", t?.atfC?.toFloat(), 0f, 130f, "°C", "%.0f", info, 110f), cfg.style, m,
            ) { AtfCard(t?.atfC, m) }
            "converter" -> Panel(m) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T("Гидротрансформатор", 14.sp, C.Sub, modifier = Modifier.weight(1f))
                    InfoButton("Гидротрансформатор", info)
                }
                Spacer(Modifier.height(10.dp))
                T(if (locked) "Заблокирован" else "Разблокирован", 24.sp, if (locked) C.Green else C.Text, FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                T("Обороты двигателя: $rpm", 14.sp)
                T("Обороты турбины: ${t?.turbineRpm ?: "—"}", 14.sp)
                T("Проскальзывание: ${fmtF(slip, 0)} %", 14.sp, if ((slip ?: 0f) > 15 && locked) C.Orange else C.Text)
                Spacer(Modifier.weight(1f))
                T(status, 12.sp, C.Sub)
            }
            "lockup" -> Small(Lucide.Lock, "Блокировка (Lock-up)", t?.lockUpPct, "%")
            "line" -> Small(Lucide.ArrowDownUp, "Давление в линии", t?.linePressurePct, "%")
            "awd" -> AwdTile(t?.transferPct, t?.frontWheelKmh, t?.rearWheelKmh, m)
            "wheels" -> Tile(Lucide.CarFront, "Колёса перед / зад", "${t?.frontWheelKmh ?: "—"} / ${t?.rearWheelKmh ?: "—"}", "км/ч", m, info)
            "chartAtf" -> ChartCard(
                "Температура ATF, °C",
                listOf(Series("ATF", th.map { it.atfC?.toFloat() }, C.Orange)),
                0f, 130f, m, "%.0f", info = info,
            )
            "chartLock" -> ChartCard(
                "Блокировка и полный привод, %",
                listOf(
                    Series("lock-up", th.map { it.lockUpPct }, C.Green),
                    Series("AWD", th.map { it.transferPct }, C.Blue),
                    Series("линия", th.map { it.linePressurePct }, C.Violet),
                ),
                0f, 100f, m, "%.0f", info = info,
            )
            "chartTurbine" -> ChartCard(
                "Обороты турбины, об/мин",
                listOf(Series("турбина", th.map { it.turbineRpm?.toFloat() }, C.Violet)),
                0f, 7000f, m, "%.0f", info = info,
            )
            "chartWheels" -> ChartCard(
                "Скорость колёс, км/ч",
                listOf(
                    Series("перед", th.map { it.frontWheelKmh?.toFloat() }, C.Blue),
                    Series("зад", th.map { it.rearWheelKmh?.toFloat() }, C.Orange),
                ),
                0f, 200f, m, "%.0f", info = info,
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Температура ATF", 14.sp, C.Sub, modifier = Modifier.weight(1f))
            InfoButton("Температура ATF", "tcu.atf")
        }
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

/**
 * Transfer clutch duty plus a rough front:rear split. SSM has no split parameter; this assumes the
 * clutch engages linearly with the duty, for the AWD layout chosen in the settings. Still to be
 * checked against logs from a real car (transfer_pct and the wheel speeds are in the CSV).
 */
@Composable
private fun AwdTile(duty: Float?, front: Int?, rear: Int?, modifier: Modifier) {
    Repo.carVersion.collectAsState().value
    val vtd = Repo.settings.awd == "vtd"
    // MP-T: 0 % = front only, 100 % = locked ≈ 50:50. VTD: centre diff 45:55, the clutch locks it towards 50:50.
    val rearShare = duty?.let { d ->
        val e = d.coerceIn(0f, 100f) / 100f
        (if (vtd) 55f - 5f * e else 50f * e).roundToInt()
    }
    val slip = front != null && rear != null && rear > 5 && front - rear > 3
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Waypoints, null, tint = C.Sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            T("Муфта полного привода", 11.sp, C.Sub, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                T(fmtF(duty, 0), 20.sp, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                T("%", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                if (rearShare != null) {
                    Spacer(Modifier.width(10.dp))
                    T("≈ ${100 - rearShare}:$rearShare", 14.sp, C.Violet, FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
                    T(" β", 11.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            if (slip) T("передние буксуют", 11.sp, C.Orange, maxLines = 1)
        }
        InfoButton("Муфта полного привода", "tcu.awd")
    }
}
