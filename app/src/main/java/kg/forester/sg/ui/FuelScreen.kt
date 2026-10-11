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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Refill
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Trip
import kg.forester.sg.service.EcuService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class FuelTab(val title: String, val icon: ImageVector) {
    Current("Текущий", Lucide.ChartColumn),
    History("История", Lucide.FileText),
    Refills("Заправки", Lucide.Fuel),
    Style("Стиль вождения", Lucide.Gauge),
    Trips("Поездки", Lucide.Route),
}

@Composable
fun FuelScreen() {
    var tab by remember { mutableStateOf(FuelTab.Current) }
    val version by Repo.dataVersion.collectAsState()
    val totals = remember(version) { Repo.db.totals() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 6.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelTab.entries.forEach {
                        Chip(if (it == FuelTab.Style) "Стиль" else it.title, tab == it, { tab = it }, it.icon, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Panel(Modifier.width(240.dp).fillMaxHeight(), padding = 10.dp) {
                T("Средний расход (весь период)", 12.sp, C.Sub)
                Row(verticalAlignment = Alignment.Bottom) {
                    val avg = if (totals.first > 1) totals.second / totals.first * 100 else null
                    T(fmt(avg), 24.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    T("л/100 км", 13.sp, C.Sub, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                FuelTab.Current -> CurrentTab()
                FuelTab.History -> HistoryTab()
                FuelTab.Refills -> RefillsTab()
                FuelTab.Style -> StyleTab()
                FuelTab.Trips -> TripsTab()
            }
        }
    }
}

@Composable
private fun CurrentTab() {
    val ts by Repo.trip.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    val trip = ts.trip
    val tank = Repo.settings.tankL

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1.25f).fillMaxWidth()) {
            Panel(Modifier.weight(1.05f).fillMaxHeight()) {
                PanelTitle(Lucide.Fuel, "Текущий расход")
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        val per100 = ts.instPer100
                        ArcGauge((per100 ?: 0.0).toFloat(), 20f, listOf(0, 5, 10, 15, 20), Modifier.fillMaxSize())
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            T(if (per100 != null) fmt(per100) else fmt(ts.instLh), 54.sp, weight = FontWeight.Bold)
                            T(if (per100 != null) "л/100 км" else "л/ч (стоянка)", 14.sp, C.Sub)
                            Spacer(Modifier.height(18.dp))
                            T("Мгновенный", 12.sp, C.Sub)
                            T("${fmt(ts.instLh)} л/ч", 16.sp, weight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tile(Lucide.Milestone, "Запас хода", fmt(ts.rangeKm, 0), "км", Modifier.fillMaxWidth())
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Lucide.Fuel, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    T("Осталось топлива", 12.sp, C.Sub)
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        T(fmt(ts.fuelLeftL, 0), 22.sp, weight = FontWeight.Bold)
                                        T(" л", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                                    }
                                }
                            }
                            if (tank > 0) {
                                val frac = ((ts.fuelLeftL ?: 0.0) / tank).toFloat()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProgressBar(frac, C.Accent, Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    T("${(frac * 100).toInt()}%", 12.sp, C.Sub)
                                }
                            }
                            T("с заправки: ${fmt(Repo.settings.usedSinceRefillL)} л", 11.sp, C.Sub)
                        }
                        Tile(Lucide.Gauge, "Средний (поездка)", fmt(trip?.per100), "л/100 км", Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ChartPanel(version, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            TripInfoPanel(trip, Modifier.weight(1.05f).fillMaxHeight())
            Spacer(Modifier.width(12.dp))
            ModesPanel(trip, Modifier.weight(0.75f).fillMaxHeight())
            Spacer(Modifier.width(12.dp))
            RecentTripsPanel(version, Modifier.weight(0.75f).fillMaxHeight())
        }
    }
}

private enum class Range(val title: String, val spanMs: Long, val stepMs: Long) {
    M10("10 мин", 10 * 60_000L, 10_000L),
    H1("1 час", 3600_000L, 60_000L),
    Today("Сегодня", 0, 10 * 60_000L),
    Week("Неделя", 7 * 86_400_000L, 3 * 3600_000L),
}

@Composable
private fun ChartPanel(version: Long, modifier: Modifier) {
    var range by remember { mutableStateOf(Range.H1) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    val data = remember(range, version, tick) {
        val now = System.currentTimeMillis()
        val from = if (range == Range.Today) startOfDay(now) else now - range.spanMs
        val buckets = Repo.db.buckets(from, range.stepMs)
        val byTime = buckets.associateBy { it.timeMs }
        val values = ArrayList<Float?>()
        var t = from / range.stepMs * range.stepMs
        while (t <= now) {
            val b = byTime[t]
            values += if (b != null && b.km > 0.02) (b.fuelL / b.km * 100).toFloat().coerceAtMost(40f) else 0f
            t += range.stepMs
        }
        val km = buckets.sumOf { it.km }
        val l = buckets.sumOf { it.fuelL }
        Triple(values, if (km > 0.2) (l / km * 100).toFloat() else null, from)
    }
    Panel(modifier) {
        PanelTitle(Lucide.ChartColumn, "Динамика расхода") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.forEach { r ->
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (r == range) C.Purple.copy(alpha = 0.3f) else C.CardHi)
                            .clickable { range = r }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { T(r.title, 12.sp, if (r == range) C.Text else C.Sub) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("л/100 км", 12.sp, C.Sub, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(C.Violet))
            T(" Мгновенный   ", 12.sp, C.Sub)
            T("- - Средний ${fmtF(data.second)}", 12.sp, C.Sub)
        }
        Row(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Column(Modifier.fillMaxHeight().width(26.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("30", "20", "10", "0").forEach { T(it, 11.sp, C.Sub) }
            }
            AreaChart(data.first, 30f, data.second, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun TripInfoPanel(trip: Trip?, modifier: Modifier) {
    val ctx = LocalContext.current
    val price = Repo.settings.pricePerL
    Panel(modifier) {
        PanelTitle(Lucide.Timer, "Информация о поездке") {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                    .clickable { EcuService.start(ctx, EcuService.ACTION_RESET_TRIP) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { T("Сбросить", 12.sp, C.Sub) }
        }
        Spacer(Modifier.height(10.dp))
        val dur = trip?.durationS ?: 0
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(Lucide.Clock, "В пути", "%d:%02d".format(dur / 3600, dur / 60 % 60), "ч:мин", Modifier.weight(1f).fillMaxHeight())
                Tile(Lucide.Activity, "Пробег", fmt(trip?.distanceKm), "км", Modifier.weight(1f).fillMaxHeight())
                Tile(Lucide.Gauge, "Ср. скорость", fmt(trip?.avgSpeed, 0), "км/ч", Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(Lucide.Fuel, "Ср. расход", fmt(trip?.per100), "л/100", Modifier.weight(1f).fillMaxHeight())
                Tile(Lucide.Droplet, "Топливо", fmt(trip?.fuelL), "л", Modifier.weight(1f).fillMaxHeight())
                Tile(Lucide.Banknote, "Стоимость", fmt(trip?.fuelL?.times(price), 0), "KGS", Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ModesPanel(trip: Trip?, modifier: Modifier) {
    // Current trip if it has data, otherwise all-time from the trip list.
    val version by Repo.dataVersion.collectAsState()
    val all = remember(version) { Repo.db.trips(500) }
    val src = if (trip != null && trip.distanceKm > 3) listOf(trip) else all
    val city = src.sumOf { it.cityKm } to src.sumOf { it.cityL }
    val hwy = src.sumOf { it.hwyKm } to src.sumOf { it.hwyL }
    val mix = src.sumOf { it.mixKm } to src.sumOf { it.mixL }
    val totalKm = city.first + hwy.first + mix.first
    val totalL = city.second + hwy.second + mix.second
    fun per(p: Pair<Double, Double>) = if (p.first > 0.3) p.second / p.first * 100 else null
    fun pct(p: Pair<Double, Double>) = if (totalKm > 0) (p.first / totalKm * 100).toInt() else 0
    Panel(modifier) {
        T("Расход по режимам", 16.sp, weight = FontWeight.SemiBold)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Donut(
                    listOf(city.first.toFloat() to C.Violet, hwy.first.toFloat() to C.Blue, mix.first.toFloat() to C.Gray),
                    Modifier.fillMaxSize()
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T(fmt(if (totalKm > 0.3) totalL / totalKm * 100 else null), 24.sp, weight = FontWeight.Bold)
                    T("л/100 км", 11.sp, C.Sub)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeRow("Город", C.Violet, per(city), pct(city))
                ModeRow("Трасса", C.Blue, per(hwy), pct(hwy))
                ModeRow("Смешанный", C.Gray, per(mix), pct(mix))
            }
        }
    }
}

@Composable
private fun ModeRow(name: String, color: Color, per100: Double?, pct: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(110.dp)) {
            T(name, 14.sp)
            T("${fmt(per100)} л/100 км", 12.sp, C.Sub)
        }
        T("$pct%", 14.sp, color, FontWeight.SemiBold)
    }
}

@Composable
private fun RecentTripsPanel(version: Long, modifier: Modifier) {
    val trips = remember(version) { Repo.db.trips(4) }
    Panel(modifier, padding = 12.dp) {
        T("Последние поездки", 16.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (trips.isEmpty()) T("Пока нет поездок", 13.sp, C.Sub)
        trips.forEach { TripRow(it); Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun TripRow(t: Trip) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(dayLabel(t.startMs), 13.sp, modifier = Modifier.width(110.dp))
        Column(Modifier.weight(1f)) {
            T("${fmt(t.per100)} л/100 км", 14.sp, weight = FontWeight.SemiBold)
            val d = t.durationS
            T("${fmt(t.distanceKm, 0)} км • %d:%02d".format(d / 3600, d / 60 % 60), 11.sp, C.Sub)
        }
        val icon = when {
            t.hwyKm >= t.cityKm && t.hwyKm >= t.mixKm -> Lucide.Milestone
            t.cityKm >= t.mixKm -> Lucide.Building2
            else -> Lucide.Route
        }
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(C.Blue.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Blue, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HistoryTab() {
    val version by Repo.dataVersion.collectAsState()
    val days = remember(version) {
        val now = System.currentTimeMillis()
        val from = startOfDay(now) - 29 * 86_400_000L
        Repo.db.buckets(from, 86_400_000L).filter { it.km > 0.1 || it.fuelL > 0.05 }.reversed()
    }
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.FileText, "История за 30 дней")
        Spacer(Modifier.height(8.dp))
        HeaderRow(listOf("Дата", "Пробег, км", "Топливо, л", "Расход, л/100", "Стоимость, KGS"))
        LazyColumn {
            items(days) { b ->
                val per = if (b.km > 0.5) b.fuelL / b.km * 100 else null
                DataRow(
                    listOf(
                        SimpleDateFormat("dd.MM.yyyy, EE", Locale("ru")).format(Date(b.timeMs)),
                        fmt(b.km), fmt(b.fuelL), fmt(per), fmt(b.fuelL * Repo.settings.pricePerL, 0)
                    )
                )
            }
        }
        if (days.isEmpty()) T("Данных пока нет", 14.sp, C.Sub)
    }
}

@Composable
private fun RefillsTab() {
    val version by Repo.dataVersion.collectAsState()
    val refills = remember(version) { Repo.db.refills() }
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(fmt(Repo.settings.pricePerL, 1)) }
    var full by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(380.dp).fillMaxHeight()) {
            PanelTitle(Lucide.Fuel, "Новая заправка")
            Spacer(Modifier.height(12.dp))
            NumField("Сколько залил, литров", liters) { liters = it }
            Spacer(Modifier.height(10.dp))
            NumField("Цена за литр, KGS (необязательно)", price) { price = it }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { full = !full }) {
                Checkbox(full, { full = it }, colors = CheckboxDefaults.colors(checkedColor = C.Purple))
                T("Залил до полного (для точной калибровки)", 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Accent)
                    .clickable {
                        val l = liters.replace(',', '.').toDoubleOrNull()
                        val p = price.replace(',', '.').toDoubleOrNull() ?: Repo.settings.pricePerL
                        if (l == null || l <= 0) {
                            msg = "Введите литры"
                        } else {
                            msg = RefillLogic.add(l, p, full)
                            liters = ""
                        }
                    }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) { T("Добавить", 16.sp, weight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            T("Дата и время подставятся автоматически", 12.sp, C.Sub)
            T(msg, 13.sp, C.Sub)
            Spacer(Modifier.weight(1f))
            T("Коэффициент расхода: ${fmt(Repo.settings.fuelK, 3)}", 12.sp, C.Sub)
            T("Уточняется автоматически между двумя заправками до полного бака.", 12.sp, C.Sub)
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.FileText, "Заправки")
            Spacer(Modifier.height(8.dp))
            HeaderRow(listOf("Дата", "Литры", "Цена", "Сумма", "Полный", "Расчёт, л"))
            LazyColumn {
                items(refills) { r: Refill ->
                    DataRow(
                        listOf(
                            SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(Date(r.timeMs)),
                            fmt(r.liters), fmt(r.pricePerL), fmt(r.liters * r.pricePerL, 0),
                            if (r.full) "да" else "нет", fmt(r.odoComputedL)
                        )
                    )
                }
            }
        }
    }
}

object RefillLogic {
    fun add(liters: Double, price: Double, full: Boolean): String {
        val s = Repo.settings
        s.pricePerL = price
        val computed = s.usedSinceFullL
        var note = "Заправка добавлена"
        if (full) {
            if (s.hadFullRefill && computed > 5) {
                // Litres put in since the previous full tank (this one + partial refills in between).
                val partials = Repo.db.refills().takeWhile { !it.full }.sumOf { it.liters }
                val actual = liters + partials
                val ratio = (actual / computed).coerceIn(0.6, 1.6)
                s.fuelK = s.fuelK * ratio
                note = "Калибровка: факт ${fmt(actual)} л, расчёт ${fmt(computed)} л → коэф. ${fmt(s.fuelK, 3)}"
            } else {
                note = "Полный бак отмечен. Калибровка будет после следующего полного бака."
            }
            s.hadFullRefill = true
            s.usedSinceFullL = 0.0
            if (s.tankL > 0) s.fuelAtRefillL = s.tankL
        } else {
            // Unknown previous level counts as empty; the estimate gets exact after a full tank.
            val left = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL).coerceAtLeast(0.0) else 0.0
            val after = left + liters
            s.fuelAtRefillL = if (s.tankL > 0) after.coerceAtMost(s.tankL) else after
            note = "Добавлено ${fmt(liters)} л. В баке ≈ ${fmt(s.fuelAtRefillL, 0)} л"
        }
        s.usedSinceRefillL = 0.0
        Repo.db.addRefill(Refill(timeMs = System.currentTimeMillis(), liters = liters, pricePerL = price, full = full, odoComputedL = computed))
        Repo.touch()
        return note
    }
}

@Composable
private fun StyleTab() {
    val ts by Repo.trip.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    val last = remember(version) { Repo.db.trips(1).firstOrNull() }
    val t = ts.trip?.takeIf { it.durationS > 60 } ?: last
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.Gauge, if (t === ts.trip) "Стиль вождения — текущая поездка" else "Стиль вождения — последняя поездка")
        Spacer(Modifier.height(16.dp))
        if (t == null) {
            T("Нет данных", 14.sp, C.Sub); return@Panel
        }
        val idlePct = if (t.durationS > 0) t.idleS / t.durationS * 100 else 0.0
        val perHour = if (t.durationS > 0) 3600.0 / t.durationS else 0.0
        val score = (100 - (t.hardAccel + t.hardBrake) * perHour * 4 - idlePct * 0.3).coerceIn(0.0, 100.0)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Lucide.Star, "Оценка", fmt(score, 0), "/ 100", Modifier.weight(1f))
            Tile(Lucide.TrendingUp, "Резкие разгоны", t.hardAccel.toString(), "раз", Modifier.weight(1f))
            Tile(Lucide.TrendingDown, "Резкие торможения", t.hardBrake.toString(), "раз", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Lucide.Hourglass, "Холостой ход", fmt(idlePct, 0), "% времени", Modifier.weight(1f))
            Tile(Lucide.Wind, "Макс. наддув", fmt(t.maxBoostKpa / 100, 2), "бар", Modifier.weight(1f))
            Tile(Lucide.Gauge, "Макс. обороты", t.maxRpm.toString(), "об/мин", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        T("Оценка снижается за резкие разгоны/торможения (в пересчёте на час) и долгий холостой ход.", 13.sp, C.Sub)
    }
}

@Composable
private fun TripsTab() {
    val version by Repo.dataVersion.collectAsState()
    val trips = remember(version) { Repo.db.trips(300) }
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.Route, "Все поездки")
        Spacer(Modifier.height(8.dp))
        HeaderRow(listOf("Начало", "Время", "Пробег, км", "Топливо, л", "л/100", "Ср. скорость", "Город/Трасса"))
        LazyColumn {
            items(trips) { t ->
                val d = t.durationS
                val tot = (t.cityKm + t.hwyKm + t.mixKm).coerceAtLeast(0.001)
                DataRow(
                    listOf(
                        dayLabel(t.startMs), "%d:%02d".format(d / 3600, d / 60 % 60), fmt(t.distanceKm), fmt(t.fuelL),
                        fmt(t.per100), fmt(t.avgSpeed, 0), "${(t.cityKm / tot * 100).toInt()}% / ${(t.hwyKm / tot * 100).toInt()}%"
                    )
                )
            }
        }
    }
}

@Composable
fun HeaderRow(cols: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cols.forEach { T(it, 12.sp, C.Sub, modifier = Modifier.weight(1f)) }
    }
}

@Composable
fun DataRow(cols: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        cols.forEach { T(it, 14.sp, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { T(label, 12.sp, C.Sub) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun dayLabel(ms: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    return when {
        ms >= today -> "Сегодня, $time"
        ms >= today - 86_400_000L -> "Вчера, $time"
        else -> SimpleDateFormat("dd.MM, HH:mm", Locale.US).format(Date(ms))
    }
}
