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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo

/** Names and icons of the home cards, for the settings list. */
/** The home cards' names, with the SensorBox outputs' widgets set up now. */
val HomeTileInfo: Map<String, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> get() = (HomeTileBase + outWidgetNames())
    // the 3D car is a Subaru: another car has no such card at all
    .let { if (kg.forester.sg.data.CarKind.other.value) it - "car3d" else it }

private val HomeTileBase: Map<String, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> = linkedMapOf(
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
    "tach" to ("Тахометр" to Lucide.Gauge),
    "speedo" to ("Спидометр" to Lucide.Gauge),
    "car3d" to ("3D-машина" to Lucide.Car),
    "minimap" to ("Мини-карта" to Lucide.Map),
) + ExtraCards + kg.forester.sg.data.MORE_VALUES.mapNotNull { id -> Metrics.of(id, emptyList())?.let { id to (it.title to it.icon) } }

@Composable
fun HomeScreen() {
    // the live data goes down as State objects and each card reads only its own number (see [pick]): a new reading
    // recomposes the cards whose number changed, not the whole screen with all its cards on every tick
    val live = Repo.live.collectAsState()
    val gps = Repo.gps.collectAsState()
    val trip = Repo.trip.collectAsState()
    val tcu = Repo.tcu.collectAsState()
    val layout by Repo.homeTiles.collectAsState()
    val clusterSize by Repo.clusterSize.collectAsState()
    val cards = remember(layout) { kg.forester.sg.data.HomeLayout.parse(layout) }
    val rows = remember(layout) { kg.forester.sg.data.HomeLayout.rows(cards) }
    // a tachometer / speedometer added as its own card leaves the 3D block
    val tachCard = cards.any { it.id == "tach" && it.on }
    val speedoCard = cards.any { it.id == "speedo" && it.on }
    val clusterW = kg.forester.sg.data.HomeLayout.clusterWeight(clusterSize)

    // long press on any card (or the pencil on the 3D block) switches to editing right here
    var edit by remember { mutableStateOf(false) }
    val longPress = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { edit = true }) }

    var tip by remember { mutableStateOf(!Repo.settings.homeTipSeen) }
    if (kg.forester.sg.data.HomeLayout.board.isFree(layout)) {
        // free layout: the 3D car, the tachometer and the speedometer are cards like the others, anything can
        // go anywhere and over anything; the area keeps its size with the edit bar or the tip over it
        val header: (@Composable () -> Unit)? = when {
            edit -> ({ HomeEditBar { edit = false } })
            tip -> ({ HomeTip { tip = false; Repo.settings.homeTipSeen = true } })
            else -> null
        }
        FreeFrame(header, Modifier.fillMaxSize(), editing = edit) { m ->
            FreeArea(
                // the 3D car is the backdrop: always under the other cards
                HomeEdit, kg.forester.sg.data.HomeLayout.parse(layout).filter { it.on && it.id in HomeTileInfo }.sortedBy { if (it.id == "car3d") 0 else 1 },
                edit, HomeTileInfo, longPress, m,
            ) { t, mm -> HomeTile(t.id, t.style, live, trip, gps, tcu, mm) }
        }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // a short screen with several rows of cards (1024×600): the 3D block gives up some of its height, down to 0.55
        // of the size picked in the settings, so the cards keep about 88 dp and readable numbers; a roomy screen keeps it
        val rowsRoom = maxHeight - 12.dp * rows.size
        val clusterShare = if (clusterW > 0f && rows.isNotEmpty()) ((rowsRoom / 88.dp - rows.size) / clusterW).coerceIn(0.55f, 1f) else 1f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (edit) HomeEditBar { edit = false }
            else if (tip) HomeTip { tip = false; Repo.settings.homeTipSeen = true }
            if (clusterW > 0f) HomeCluster(live, tachCard, speedoCard, edit, { edit = true }, longPress,
                Modifier.weight(clusterW * clusterShare).fillMaxWidth())
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t ->
                        // keyed by the card: one moved along its row keeps its state (the open edit menu goes with it)
                        androidx.compose.runtime.key(t.id) {
                            Box(Modifier.weight(t.span.toFloat()).fillMaxHeight().then(longPress)) {
                                HomeTile(t.id, t.style, live, trip, gps, tcu, Modifier.fillMaxSize())
                                if (edit) TileEditOverlay(HomeEdit, t, HomeTileInfo[t.id]?.first ?: "")
                            }
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
}

/**
 * [f] of the state's current value, read so that only a change of the result recomposes the caller: a card with the
 * coolant temperature is left alone while the rpm changes several times a second.
 */
@Composable
private fun <S, R> State<S>.pick(f: (S) -> R): R =
    remember(this) { androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) { f(value) } }.value

/** The "cluster": tachometer, the car on the road, speedometer. */
@Composable
private fun HomeCluster(
    live: State<kg.forester.sg.ssm.EcuSnapshot>, tachCard: Boolean, speedoCard: Boolean, edit: Boolean, onEdit: () -> Unit,
    longPress: Modifier, modifier: Modifier,
) {
    var stylePick by remember { mutableStateOf<String?>(null) }
    val styles by Repo.dialStyles.collectAsState()
    Panel(modifier.then(longPress), padding = 0.dp) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // dials as big as the block is tall, but never wider than ~27 % each; the car gets the room between
            val d = minOf(maxHeight - 20.dp, (maxWidth - 28.dp) * 0.27f)
            val carShare = ((maxWidth - (d + 14.dp) * 2) / maxWidth).coerceIn(0.3f, 1f)
            // the Subaru in 3D between the dials; another car has no model, the dials move together to the middle
            val other by kg.forester.sg.data.CarKind.other.collectAsState()
            // in a layer of its own: the car's frames (road, flames) redraw the car, not the dials and the cards
            if (!other) LiveCar(live, carShare, 1f, Modifier.fillMaxSize().graphicsLayer())
            val gap = if (other) ((maxWidth - d * 2) / 2 - 24.dp).coerceAtLeast(14.dp) else 14.dp
            // long press on a gauge: pick its design (NFS Underground style custom gauges)
            if (!tachCard) Box(Modifier.align(Alignment.CenterStart).padding(start = gap).size(d)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { stylePick = "tach" }) }) {
                LiveDial(live, true, styles.first, Modifier.fillMaxSize())
            }
            if (!speedoCard) Box(Modifier.align(Alignment.CenterEnd).padding(end = gap).size(d)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { stylePick = "speedo" }) }) {
                LiveDial(live, false, styles.second, Modifier.fillMaxSize())
            }
            stylePick?.let { which ->
                DialStylePicker(which, Modifier.align(if (which == "tach") Alignment.CenterStart else Alignment.CenterEnd)) { stylePick = null }
            }
            // the pencil stays small in the corner; the area that takes the tap round it is 48 dp
            if (!edit) Box(
                Modifier.align(Alignment.TopEnd).padding(1.dp).size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(C.Card.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Lucide.Pencil, "Настроить главный экран", tint = C.Sub, modifier = Modifier.size(16.dp))
                }
            }
            else Box(Modifier.matchParentSize().border(2.dp, C.Purple, RoundedCornerShape(14.dp)))
        }
    }
}

/** The 3D car; recomposed on its own with each reading. */
@Composable
private fun LiveCar(live: State<kg.forester.sg.ssm.EcuSnapshot>, carShare: Float, carScale: Float, modifier: Modifier) {
    val e = live.value
    CarViewer(e.rpm, e.speedKmh, modifier, throttlePct = e.throttlePct, boostBar = e.boostBar, carShare = carShare, carScale = carScale)
}

/** The tachometer ([tach]) or the speedometer in the design [style], reading only its own value. */
@Composable
private fun LiveDial(live: State<kg.forester.sg.ssm.EcuSnapshot>, tach: Boolean, style: String, modifier: Modifier) {
    // the same design object while the id (and the theme) stays: the dial keeps its drawn face for it
    val st = remember(style, C.theme) { dialStyle(style) }
    if (tach) {
        val rpm = live.pick { it.rpm?.toFloat() }
        Dial(rpm, 7000f, 1000f, 1000f, "об/мин", "x1000", redFrom = 6000f, modifier, st, range = tripRangeOf("rpm", rpm))
    } else {
        val speed = live.pick { it.speedKmh?.toFloat() }
        Dial(speed, 200f, 20f, 1f, "км/ч", null, redFrom = null, modifier, st, range = tripRangeOf("speed", speed), minMark = false)
    }
}

@Composable
private fun HomeTile(
    id: String, st: String, live: State<kg.forester.sg.ssm.EcuSnapshot>, trip: State<kg.forester.sg.data.TripState>,
    gps: State<kg.forester.sg.data.GpsFix?>, tcu: State<kg.forester.sg.ssm.TcuSnapshot?>, m: Modifier,
) {
    if (id.startsWith("outw_")) { OutputWidget(id, m); return }
    val (title, icon) = HomeTileInfo[id] ?: return
    if (id in ExtraCards) { ExtraCard(id, "home.$id", m, st); return }
    when (id) {
        "minimap" -> MiniMapCard(st, m) { HomeEdit.setStyle("minimap", it) }
        "car3d" -> Panel(m, padding = 0.dp) { LiveCar(live, 1f, carScaleOf(st), Modifier.fillMaxSize().graphicsLayer()) }
        "tach", "speedo" -> {
            val styles by Repo.dialStyles.collectAsState()
            androidx.compose.foundation.layout.BoxWithConstraints(m, contentAlignment = Alignment.Center) {
                val d = minOf(maxWidth, maxHeight)
                // "" = the design picked for this gauge in the 3D block
                LiveDial(live, id == "tach", st.ifEmpty { if (id == "tach") styles.first else styles.second }, Modifier.size(d))
            }
        }
        "coolant" -> BarCard(st, "home.$id", icon, title, live.pick { it.coolantC?.toFloat() }, 40f, 110f, "°C", "%.0f", m, warnAbove = 102f)
        "intake" -> BarCard(st, "home.$id", icon, title, live.pick { it.intakeC?.toFloat() }, -20f, 70f, "°C", "%.0f", m, warnAbove = 55f)
        "battery" -> BarCard(st, "home.$id", icon, title, live.pick { it.batteryV }, 10f, 15f, "В", "%.1f", m, warnBelow = 12f)
        "throttle" -> BarCard(st, "home.$id", icon, title, live.pick { it.throttlePct }, 0f, 100f, "%", "%.0f", m)
        "maf" -> BarCard(st, "home.$id", icon, title, live.pick { it.mafGs }, 0f, mafTop, "г/с", "%.1f", m)
        "afr" -> {
            // a constant 14.7 from an ECU with no A/F sensor is no reading; 11.1–11.3 on boost is the stock sensor's floor
            val noWb by kg.forester.sg.service.RulesHost.noWideband.collectAsState()
            val afr = live.pick { it.afr }
            val floor = live.pick { e -> e.afr?.let { it in 11.1f..11.35f } == true && (e.gaugeKpa ?: 0f) > 30f }
            BarCard(st, "home.$id", icon, title, if (noWb) null else afr, 10f, 20f, "", "%.1f", m, empty = if (noWb) "нет ШДК" else "—",
                extra = if (floor) "предел штатного датчика, смесь может быть богаче" else null)
        }
        "afcorr" -> BarCard(st, "home.$id", icon, title, live.pick { it.afCorrPct }, -25f, 25f, "%", "%+.1f", m, warnAbs = 15f)
        "aflearn" -> BarCard(st, "home.$id", icon, title, live.pick { it.afLearnPct }, -25f, 25f, "%", "%+.1f", m, warnAbs = 10f)
        "timing" -> BarCard(st, "home.$id", icon, title, live.pick { it.timingDeg }, -10f, 50f, "°", "%.1f", m)
        "knock" -> BarCard(st, "home.$id", icon, title, live.pick { it.knockDeg }, -10f, 2f, "°", "%.1f", m, warnBelow = -2f)
        "inj" -> BarCard(st, "home.$id", icon, title, live.pick { it.injPwMs }, 0f, 20f, "мс", "%.2f", m)
        "wgdc" -> BarCard(st, "home.$id", icon, title, live.pick { it.wastegatePct }, 0f, 100f, "%", "%.0f", m)
        "fuel" -> BarCard(st, "home.$id", icon, "Расход", trip.pick { it.instLh.toFloat() }, 0f, 40f, "л/ч", "%.1f", m)
        "boost" -> BarCard(st, "home.$id", icon, title, live.pick { it.boostBar }, 0f, boostTop, "бар", "%.2f", m)
        "gps" -> BarCard(st, "home.$id", icon, title, gps.pick { it?.speedKmh }, 0f, 200f, "км/ч", "%.0f", m,
            extra = gps.pick { g -> g?.let { "спутников ${it.satellites}, ±${it.accuracyM.toInt()} м" } }
                ?: Repo.gpsStatus.collectAsState().value.ifEmpty { "нет сигнала" })
        "pedal" -> BarCard(st, "home.$id", icon, title, live.pick { it.pedalPct }, 0f, 100f, "%", "%.0f", m)
        "avcs" -> BarCard(st, "home.$id", icon, title, live.pick { it.avcsDeg }, -10f, 50f, "°", "%.0f", m)
        "map" -> BarCard(st, "home.$id", icon, title, live.pick { it.mapKpa }, 20f, 220f, "кПа", "%.0f", m)
        "atm" -> BarCard(st, "home.$id", icon, title, live.pick { it.atmKpa }, 70f, 105f, "кПа", "%.0f", m)
        "alt" -> BarCard(st, "home.$id", icon, title, live.pick { it.altDutyPct }, 0f, 100f, "%", "%.0f", m)
        "atf" -> BarCard(st, "home.$id", icon, title, tcu.pick { it?.atfC?.toFloat() }, 0f, 130f, "°C", "%.0f", m, warnAbove = 110f)
        "per100" -> BarCard(st, "home.$id", icon, "Расход", trip.pick { it.instPer100?.toFloat() }, 0f, 30f, "л/100", "%.1f", m)
        "range" -> BarCard(st, "home.$id", icon, title, trip.pick { it.rangeKm?.toFloat() }, 0f, 800f, "км", "%.0f", m)
        else -> {
            // any other value of the common catalogue
            val ind = Metrics.of(id, emptyList()) ?: return
            val v = ind.read(rememberMetricCtx())
            BarCard(st, "home.$id", icon, title, v, ind.min, ind.max, ind.unit, ind.format, m,
                warnAbove = ind.warnAbove, warnBelow = ind.warnBelow)
        }
    }
}

@Composable
private fun BigGauge(
    title: String, value: Float?, max: Float, unit: String, format: String, modifier: Modifier, min: Float = 0f,
) {
    Panel(modifier, padding = 10.dp) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            T(title, 14.sp, C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
            InfoButton(title)
        }
        RingGauge(
            ((value ?: min) - min) / (max - min), value?.let { format.format(it) } ?: "—", unit,
            Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BarCard(
    style: String, info: String, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier,
    warnAbove: Float? = null, warnBelow: Float? = null, warnAbs: Float? = null, extra: String? = null, empty: String = "—",
) {
    val warn = value != null && (
        (warnAbove != null && value > warnAbove) ||
            (warnBelow != null && value < warnBelow) ||
            (warnAbs != null && kotlin.math.abs(value) > warnAbs)
        )
    if (style.isNotEmpty()) {
        NeedleCard(CardValue(icon, title, value, min, max, unit, format, info, warnAbove), dialStyle(style), modifier.fillMaxHeight())
        return
    }
    val color: Brush = if (warn) Brush.horizontalGradient(listOf(C.Orange, C.Red)) else C.Accent
    // the widest number of the scale sets the font size, so «99 → 100» does not make the number jump in size
    val widest = remember(format, min, max) { listOf(format.format(min), format.format(max)).maxBy { it.length } }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
        // one continuous scale from a low card (1024×600, four rows) to a tall one, with no step at a threshold:
        // a low card gives its padding, title and bar less room and the number more
        val low = ((maxHeight - 56.dp) / 44.dp).coerceIn(0f, 1f)
        val tall = ((maxHeight - 100.dp) / 60.dp).coerceIn(0f, 1f)
        val pad = 6.dp + 6.dp * low
        val titleH = 16.dp + 4.dp * low
        val gap = 2.dp + 4.dp * low
        val barH = 4.dp + 2.dp * low
        val marks = 4.dp + 2.dp * low
        // the min/max lines: the value row's height is known from the card's (title, gap, bar), with no second
        // measuring pass; a low card gets them smaller and fading out below 9 sp instead of vanishing at once
        val rowH = maxHeight - pad * 2 - titleH - gap - barH - marks
        val rangeSp = minOf(13f + 2f * tall, with(androidx.compose.ui.platform.LocalDensity.current) { (rowH / 2.7f).toSp().value })
        if (maxWidth > maxHeight * 3.4f) {
            // a wide low card (five columns on 1920×720, a card over two cells): the number takes the whole height
            // under the title, the min/max and the bar go beside it, instead of a long thin bar under a small number
            Panel(Modifier.fillMaxSize(), padding = pad) {
                CardTitle(icon, title, info, titleH)
                val range = if (extra == null) tripRangeOf(info.substringAfter('.'), value) else null
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    FitValue(
                        // as tall as the row: the width beside it is plenty
                        value?.let { format.format(it) } ?: empty, if (value == null && empty != "—") "" else unit, 60f,
                        Modifier.weight(1.2f).fillMaxHeight(), if (warn) C.Orange else Color.White, widest = widest,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).padding(bottom = 4.dp), horizontalAlignment = Alignment.End) {
                        if (extra != null) T(extra, 11.sp, C.Sub, maxLines = 1)
                        if (range != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MinMaxLine(Lucide.ArrowUp, format.format(range.second), MaxMarkColor, 13f + 2f * tall)
                            MinMaxLine(Lucide.ArrowDown, format.format(range.first), MinMarkColor, 13f + 2f * tall)
                        }
                        Spacer(Modifier.height(gap))
                        val frac = { x: Float -> ((x - min) / (max - min)).coerceIn(0f, 1f) }
                        RangeBar(value?.let(frac), range?.let { frac(it.first) to frac(it.second) }, color, Modifier.fillMaxWidth(), barH, marks)
                    }
                }
            }
            return@BoxWithConstraints
        }
        Panel(Modifier.fillMaxSize(), padding = pad) {
            CardTitle(icon, title, info, titleH)
            // taller cards (fewer of them on the screen) get bigger numbers; the factory grid keeps 26 sp
            val range = if (extra == null) tripRangeOf(info.substringAfter('.'), value) else null
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FitValue(
                    value?.let { format.format(it) } ?: empty, if (value == null && empty != "—") "" else unit, 26f + 14f * tall, Modifier.weight(1f).fillMaxHeight(),
                    if (warn) C.Orange else Color.White, widest = widest,
                )
                if (range != null) {
                    // the trip's highest over its lowest (as in BtSSM), in the colours of their marks on the bar; as big
                    // as the row lets two lines be: on a 2K screen (Teyes CC3 2K) or with a bigger font they were cut off
                    val sp = rangeSp
                    if (sp >= 7f) Column(
                        Modifier.graphicsLayer { alpha = ((sp - 7f) / 2f).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.End,
                    ) {
                        MinMaxLine(Lucide.ArrowUp, format.format(range.second), MaxMarkColor, sp)
                        MinMaxLine(Lucide.ArrowDown, format.format(range.first), MinMarkColor, sp)
                    }
                }
            }
            if (extra != null) T(extra, 11.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(gap))
            val frac = { x: Float -> ((x - min) / (max - min)).coerceIn(0f, 1f) }
            RangeBar(value?.let(frac), range?.let { frac(it.first) to frac(it.second) }, color, Modifier.fillMaxWidth(), barH, marks)
        }
    }
}

@Composable
private fun MinMaxLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, sp: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size((sp - 1).dp))
        Spacer(Modifier.width(2.dp))
        T(text, sp.sp, color, FontWeight.SemiBold, maxLines = 1)
    }
}

/** The trip's min and max of a home card (null if the feature is off or nothing is counted yet); [value] is included. */
@Composable
fun tripRangeOf(key: String, value: Float?): Pair<Float, Float>? {
    if (!Repo.settings.feature("minmax")) return null
    val (lo, hi) = Repo.tripRange.collectAsState().value[key] ?: return null
    return minOf(lo, value ?: lo) to maxOf(hi, value ?: hi)
}

/**
 * A bar with the trip's range lit up between its min and max marks, like the telltale needles of a gauge.
 * [fraction] null = no reading: an empty track, not the stub that would read as a value at the bottom of the scale.
 */
@Composable
private fun RangeBar(
    fraction: Float?, range: Pair<Float, Float>?, brush: Brush, modifier: Modifier, height: androidx.compose.ui.unit.Dp,
    /** How far the min/max marks reach past the bar, top and bottom together. */
    marks: androidx.compose.ui.unit.Dp = 6.dp,
) {
    val anim by androidx.compose.animation.core.animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), androidx.compose.animation.core.tween(400), label = "bar")
    val known = fraction != null
    androidx.compose.foundation.Canvas(modifier.height(height + marks)) {
        val h = height.toPx()
        val top = (size.height - h) / 2
        val rad = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
        drawRoundRect(C.Border, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), rad)
        if (range != null) {
            val x0 = range.first * size.width
            val x1 = range.second * size.width
            drawRoundRect(Color.White.copy(alpha = 0.16f), androidx.compose.ui.geometry.Offset(x0, top),
                androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(h), h), rad)
        }
        if (known) drawRoundRect(brush, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size((anim * size.width).coerceAtLeast(h * 0.5f), h), rad)
        if (range != null) {
            val w = 3.dp.toPx()
            for ((f, col) in listOf(range.first to MinMarkColor, range.second to MaxMarkColor)) {
                val x = (f * size.width).coerceIn(w / 2, size.width - w / 2)
                drawRoundRect(col, androidx.compose.ui.geometry.Offset(x - w / 2, 0f), androidx.compose.ui.geometry.Size(w, size.height),
                    androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
            }
        }
    }
}

/** Size of the car in the 3D card, kept in its style as "scale:0.6" (1 = as large as fits). */
fun carScaleOf(style: String) = style.removePrefix("scale:").toFloatOrNull()?.coerceIn(0.3f, 1f) ?: 1f

/** Shown once: the screen and the bottom bar are the driver's to arrange, and how. */
@Composable
private fun HomeTip(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Purple.copy(alpha = 0.18f))
            .border(1.dp, C.Purple, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Lucide.Lightbulb, null, tint = C.Violet, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        T("Экран настраивается: нажмите и удерживайте любой виджет — можно убрать лишнее, добавить нужное, сделать " +
            "крупнее или выбрать «Просто». Нижняя панель тоже: удерживайте её, чтобы выбрать показатели.", 13.sp,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Chip("Понятно", true, onClose)
    }
}
