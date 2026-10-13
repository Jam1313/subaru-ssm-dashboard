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

private val TcuEdit = BoardEdit(TcuBoard, gaugeable = setOf("atf", "lockup", "line"), rowWeight = ::tcuRowWeight)

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
                CardHeader(Lucide.Cog, "Селектор / передача", C.Violet, info)
                val range = t?.range
                val driving = range == "D" || range == "3" || range == "2" || range == "1"
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = range,
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "range",
                        ) { r -> T(r ?: "—", if ((r?.length ?: 1) > 1) 52.sp else 72.sp, if (r == null) C.Gray else C.Violet, FontWeight.Bold) }
                        if (driving) {
                            Spacer(Modifier.width(16.dp))
                            Column {
                                T("передача", 12.sp, C.Sub)
                                AnimatedContent(
                                    targetState = t?.gear,
                                    transitionSpec = {
                                        val up = (targetState ?: 0) > (initialState ?: 0)
                                        (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                                            (slideOutVertically { if (up) -it else it } + fadeOut())
                                    },
                                    label = "gear",
                                ) { g -> T(g?.toString() ?: "—", 40.sp, weight = FontWeight.Bold) }
                            }
                        }
                    }
                }
                // the selector strip, the current position lit
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("P", "R", "N", "D", "3", "2", "1").forEach { pos ->
                        val on = range == pos
                        Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (on) C.Violet else C.CardHi), contentAlignment = Alignment.Center) {
                            T(pos, 13.sp, if (on) C.Text else C.Sub, if (on) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            "atf" -> ValueCard(
                CardValue(Lucide.ThermometerSun, "Температура ATF", t?.atfC?.toFloat(), 0f, 130f, "°C", "%.0f", info, 110f), cfg.style, m,
            ) { AtfCard(t?.atfC, m) }
            "converter" -> Panel(m) {
                CardHeader(Lucide.Lock, "Гидротрансформатор", C.Blue, info)
                Spacer(Modifier.height(10.dp))
                val known = t?.lockUpPct != null
                val (stText, stColor) = when {
                    !known -> "нет данных" to C.Gray
                    locked -> "заблокирован" to C.Green
                    else -> "разблокирован" to C.Blue
                }
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(stColor.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (locked) Lucide.Lock else Lucide.LockOpen, null, tint = stColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    T(stText, 14.sp, stColor, FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                ConvRow(Lucide.Gauge, "Двигатель", if (rpm > 0) "$rpm" else "—", "об/мин")
                ConvRow(Lucide.Fan, "Турбина", t?.turbineRpm?.toString() ?: "—", "об/мин")
                ConvRow(Lucide.Percent, "Проскальзывание", fmtF(slip, 0), "%", if ((slip ?: 0f) > 15 && locked) C.Orange else C.Text)
                Spacer(Modifier.weight(1f))
                if (status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Info, null, tint = if (t == null) C.Orange else C.Sub, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    T(status, 12.sp, if (t == null) C.Orange else C.Sub, maxLines = 2)
                }
            }
            "lockup" -> Small(Lucide.Lock, "Блокировка (Lock-up)", t?.lockUpPct, "%")
            "line" -> Small(Lucide.ArrowDownUp, "Давление в линии", t?.linePressurePct, "%")
            "awd" -> AwdTile(t?.transferPct, t?.frontWheelKmh, t?.rearWheelKmh, m)
            "wheels" -> Tile(Lucide.CarFront, "Колёса перед / зад", "${t?.frontWheelKmh ?: "—"} / ${t?.rearWheelKmh ?: "—"}", "км/ч", m, info)
            "chartAtf" -> ChartCard(
                "Температура ATF, °C",
                listOf(Series("ATF", th.map { it.atfC?.toFloat() }, C.Orange)),
                0f, 130f, m, "%.0f", info = info, icon = Lucide.ThermometerSun, tint = C.Orange,
            )
            "chartLock" -> ChartCard(
                "Блокировка и полный привод, %",
                listOf(
                    Series("lock-up", th.map { it.lockUpPct }, C.Green),
                    Series("AWD", th.map { it.transferPct }, C.Blue),
                    Series("линия", th.map { it.linePressurePct }, C.Violet),
                ),
                0f, 100f, m, "%.0f", info = info, icon = Lucide.Lock, tint = C.Green,
            )
            "chartTurbine" -> ChartCard(
                "Обороты турбины, об/мин",
                listOf(Series("турбина", th.map { it.turbineRpm?.toFloat() }, C.Violet)),
                0f, 7000f, m, "%.0f", info = info, icon = Lucide.Fan,
            )
            "chartWheels" -> ChartCard(
                "Скорость колёс, км/ч",
                listOf(
                    Series("перед", th.map { it.frontWheelKmh?.toFloat() }, C.Blue),
                    Series("зад", th.map { it.rearWheelKmh?.toFloat() }, C.Orange),
                ),
                0f, 200f, m, "%.0f", info = info, icon = Lucide.CarFront, tint = C.Blue,
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
        CardHeader(Lucide.ThermometerSun, "Температура ATF", C.Orange, "tcu.atf")
        RingGauge((atf ?: 0) / 130f, atf?.let { "$it°" } ?: "—", label, Modifier.weight(1f).fillMaxWidth(), unitColor = color)
        // zones: cold (do not load it) / normal / hot / overheating
        val zones = listOf(40f to C.Blue, 60f to C.Green, 15f to C.Orange, 15f to C.Red)
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            zones.forEach { (w, c) -> Box(Modifier.weight(w).fillMaxHeight().background(c.copy(alpha = 0.7f))) }
        }
        Box(Modifier.fillMaxWidth().height(14.dp)) {
            listOf(0f to "0", 40f to "40", 100f to "100", 115f to "115", 130f to "130").forEach { (v, l) ->
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val x = maxWidth * (v / 130f)
                    T(l, 10.sp, C.Sub, modifier = Modifier.padding(start = (x - if (v == 0f) 0.dp else if (v == 130f) 20.dp else 9.dp).coerceAtLeast(0.dp)))
                }
            }
        }
    }
}

@Composable
private fun ConvRow(icon: ImageVector, label: String, value: String, unit: String, color: androidx.compose.ui.graphics.Color = C.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        T(label, 13.sp, C.Sub, modifier = Modifier.weight(1f))
        T(value, 15.sp, color, FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        T(unit, 11.sp, C.Sub)
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
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
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
    }
    InfoButton("Муфта полного привода", "tcu.awd", Modifier.align(Alignment.TopEnd))
    }
}
