package kg.forester.sg.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
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

/** Names and icons of the home cards, for the settings list. */
val HomeTileInfo: Map<String, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> = linkedMapOf(
    "coolant" to ("Темп. ОЖ" to Lucide.Thermometer),
    "intake" to ("Темп. впуска" to Lucide.ThermometerSnowflake),
    "battery" to ("Напряжение" to Lucide.BatteryCharging),
    "throttle" to ("Дроссель" to Lucide.Disc),
    "maf" to ("MAF" to Lucide.Wind),
    "afr" to ("AFR" to Lucide.Flame),
    "afcorr" to ("Коррекция A/F" to Lucide.SlidersHorizontal),
    "aflearn" to ("Обучение A/F" to Lucide.Brain),
    "timing" to ("УОЗ" to Lucide.Zap),
    "knock" to ("Коррекция детонации" to Lucide.AudioWaveform),
    "inj" to ("Впрыск" to Lucide.Syringe),
    "wgdc" to ("Wastegate" to Lucide.Tornado),
    "fuel" to ("Расход, л/ч" to Lucide.Fuel),
    "boost" to ("Наддув" to Lucide.Fan),
    "gps" to ("GPS" to Lucide.Satellite),
    "pedal" to ("Педаль газа" to Lucide.ChevronsUp),
    "avcs" to ("AVCS" to Lucide.Timer),
    "map" to ("Абс. давление" to Lucide.Gauge),
    "atm" to ("Атм. давление" to Lucide.Cloudy),
    "alt" to ("Генератор" to Lucide.Activity),
    "atf" to ("Темп. ATF" to Lucide.ThermometerSun),
    "per100" to ("Расход, л/100" to Lucide.Fuel),
    "range" to ("Запас хода" to Lucide.Milestone),
)

@Composable
fun HomeScreen() {
    val e by Repo.live.collectAsState()
    val gps by Repo.gps.collectAsState()
    val ts by Repo.trip.collectAsState()
    val tcu by Repo.tcu.collectAsState()
    val layout by Repo.homeTiles.collectAsState()
    val clusterSize by Repo.clusterSize.collectAsState()
    val rows = remember(layout) { kg.forester.sg.data.HomeLayout.rows(kg.forester.sg.data.HomeLayout.parse(layout)) }
    val clusterW = kg.forester.sg.data.HomeLayout.clusterWeight(clusterSize)

    // long press on any card (or the pencil on the 3D block) switches to editing right here
    var edit by remember { mutableStateOf(false) }
    var stylePick by remember { mutableStateOf<String?>(null) }
    val styles by Repo.dialStyles.collectAsState()
    val longPress = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { edit = true }) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (edit) HomeEditBar { edit = false }
        // the "cluster": tachometer, the car on the road, speedometer
        if (clusterW > 0f) Panel(Modifier.weight(clusterW).fillMaxWidth().then(longPress), padding = 0.dp) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                // dials as big as the block is tall, but never wider than ~27 % each; the car gets the room between
                val d = minOf(maxHeight - 20.dp, (maxWidth - 28.dp) * 0.27f)
                val carShare = ((maxWidth - (d + 14.dp) * 2) / maxWidth).coerceIn(0.3f, 1f)
                CarViewer(
                    e.rpm, e.speedKmh, Modifier.fillMaxSize(),
                    throttlePct = e.throttlePct, boostBar = e.boostBar, carShare = carShare,
                )
                // long press on a gauge: pick its design (NFS Underground style custom gauges)
                Box(Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(d)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { stylePick = "tach" }) }) {
                    Dial(e.rpm?.toFloat(), 7000f, 1000f, 1000f, "об/мин", "x1000", redFrom = 6000f, Modifier.fillMaxSize(), dialStyle(styles.first))
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(d)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { stylePick = "speedo" }) }) {
                    Dial(e.speedKmh?.toFloat(), 200f, 20f, 1f, "км/ч", null, redFrom = null, Modifier.fillMaxSize(), dialStyle(styles.second))
                }
                stylePick?.let { which ->
                    DialStylePicker(which, Modifier.align(if (which == "tach") Alignment.CenterStart else Alignment.CenterEnd)) { stylePick = null }
                }
                if (!edit) Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(RoundedCornerShape(9.dp))
                        .background(C.Card.copy(alpha = 0.6f)).clickable { edit = true },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(Lucide.Pencil, "Настроить главный экран", tint = C.Sub, modifier = Modifier.size(16.dp)) }
                else Box(Modifier.matchParentSize().border(2.dp, C.Purple, RoundedCornerShape(14.dp)))
            }
        }
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    Box(Modifier.weight(t.span.toFloat()).fillMaxHeight().then(longPress)) {
                        HomeTile(t.id, e, ts, gps, tcu, Modifier.fillMaxSize())
                        if (edit) TileEditOverlay(t)
                    }
                }
                val used = row.sumOf { it.span }
                if (used < kg.forester.sg.data.HomeLayout.COLUMNS) {
                    Spacer(Modifier.weight((kg.forester.sg.data.HomeLayout.COLUMNS - used).toFloat()))
                }
            }
        }
        if (clusterW == 0f && rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                T(if (edit) "Добавьте виджеты кнопкой «Добавить виджет»" else "Виджетов нет: нажмите и удерживайте здесь, чтобы настроить", 16.sp, C.Sub,
                    modifier = longPress)
            }
        }
    }
}

@Composable
private fun HomeTile(
    id: String, e: kg.forester.sg.ssm.EcuSnapshot, ts: kg.forester.sg.data.TripState,
    gps: kg.forester.sg.data.GpsFix?, tcu: kg.forester.sg.ssm.TcuSnapshot?, m: Modifier,
) {
    val (title, icon) = HomeTileInfo[id] ?: return
    when (id) {
        "coolant" -> BarCard(icon, title, e.coolantC?.toFloat(), 40f, 110f, "°C", "%.0f", m, warnAbove = 102f)
        "intake" -> BarCard(icon, title, e.intakeC?.toFloat(), -20f, 70f, "°C", "%.0f", m, warnAbove = 55f)
        "battery" -> BarCard(icon, title, e.batteryV, 10f, 15f, "В", "%.1f", m, warnBelow = 12f)
        "throttle" -> BarCard(icon, title, e.throttlePct, 0f, 100f, "%", "%.0f", m)
        "maf" -> BarCard(icon, title, e.mafGs, 0f, 150f, "г/с", "%.1f", m)
        "afr" -> BarCard(icon, title, e.afr, 10f, 20f, "", "%.1f", m)
        "afcorr" -> BarCard(icon, title, e.afCorrPct, -25f, 25f, "%", "%+.1f", m, warnAbs = 15f)
        "aflearn" -> BarCard(icon, title, e.afLearnPct, -25f, 25f, "%", "%+.1f", m, warnAbs = 10f)
        "timing" -> BarCard(icon, title, e.timingDeg, -10f, 50f, "°", "%.1f", m)
        "knock" -> BarCard(icon, title, e.knockDeg, -10f, 2f, "°", "%.1f", m, warnBelow = -2f)
        "inj" -> BarCard(icon, title, e.injPwMs, 0f, 20f, "мс", "%.2f", m)
        "wgdc" -> BarCard(icon, title, e.wastegatePct, 0f, 100f, "%", "%.0f", m)
        "fuel" -> BarCard(icon, "Расход", ts.instLh.toFloat(), 0f, 40f, "л/ч", "%.1f", m)
        "boost" -> BarCard(icon, title, e.boostBar, 0f, 1.2f, "бар", "%.2f", m)
        "gps" -> BarCard(icon, title, gps?.speedKmh, 0f, 200f, "км/ч", "%.0f", m,
            extra = gps?.let { "спутников ${it.satellites}, ±${it.accuracyM.toInt()} м" } ?: "нет сигнала")
        "pedal" -> BarCard(icon, title, e.pedalPct, 0f, 100f, "%", "%.0f", m)
        "avcs" -> BarCard(icon, title, e.avcsDeg, -10f, 50f, "°", "%.0f", m)
        "map" -> BarCard(icon, title, e.mapKpa, 20f, 220f, "кПа", "%.0f", m)
        "atm" -> BarCard(icon, title, e.atmKpa, 70f, 105f, "кПа", "%.0f", m)
        "alt" -> BarCard(icon, title, e.altDutyPct, 0f, 100f, "%", "%.0f", m)
        "atf" -> BarCard(icon, title, tcu?.atfC?.toFloat(), 0f, 130f, "°C", "%.0f", m, warnAbove = 110f)
        "per100" -> BarCard(icon, "Расход", ts.instPer100?.toFloat(), 0f, 30f, "л/100", "%.1f", m)
        "range" -> BarCard(icon, title, ts.rangeKm?.toFloat(), 0f, 800f, "км", "%.0f", m)
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
        val tall = maxHeight > 140.dp
        Panel(Modifier.fillMaxSize(), padding = if (tight) 9.dp else 12.dp) {
            CardTitle(icon, title)
            // taller cards (fewer of them on the screen) get bigger numbers; the factory grid keeps 26 sp
            FitValue(
                value?.let { format.format(it) } ?: "—", unit, if (tall) 40f else 26f, Modifier.weight(1f).fillMaxWidth(),
                if (warn) C.Orange else Color.White,
            )
            if (extra != null) T(extra, 11.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(if (tight) 4.dp else 6.dp))
            ProgressBar(value?.let { (it - min) / (max - min) } ?: 0f, color, Modifier.fillMaxWidth(), if (tight) 5.dp else 6.dp)
        }
    }
}
