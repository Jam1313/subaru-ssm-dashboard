package kg.forester.sg.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Disc3
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.*
import com.composables.icons.lucide.Thermometer
import com.composables.icons.lucide.Wrench
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Alerts
import kg.forester.sg.service.Maintenance
import kg.forester.sg.service.ServiceItem
import kg.forester.sg.service.Tpms
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** «Здоровье»: engine guard (IAM, knock, turbo, oil, thermostat), maintenance planner, tyres, warnings log. */
@Composable
fun HealthScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Мотор", tab == 0, { tab = 0 }, Lucide.HeartPulse)
            Chip("ТО", tab == 1, { tab = 1 }, Lucide.Wrench)
            Chip("Шины", tab == 2, { tab = 2 }, Lucide.Disc3)
            Chip("Предупреждения", tab == 3, { tab = 3 }, Lucide.Bell)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> EngineTab()
                1 -> ServiceTab()
                2 -> TyresTab()
                else -> AlertsTab()
            }
        }
    }
}

@Composable
private fun EngineTab() {
    val g by Repo.guard.collectAsState()
    var voice by remember { mutableStateOf(Repo.settings.voiceAlerts) }
    val fmt = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }
    val log = remember(g.iam) {
        runCatching { JSONArray(Repo.settings.iamLog) }.getOrNull()?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty().reversed()
    }
    val refills = remember { runCatching { Repo.db.refills() }.getOrDefault(emptyList()) }
    fun iamColor(v: Double) = if (v >= 0.95) C.Green else if (v >= 0.75) C.Orange else C.Red
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // knock: IAM now, the corrections, IAM at the start of each drive
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Flame, "IAM и детонация")
            Spacer(Modifier.height(12.dp))
            val iam = g.iam
            Row(verticalAlignment = Alignment.Bottom) {
                T(iam?.let { "%.2f".format(it) } ?: "—", 44.sp, iam?.let { iamColor(it.toDouble()) } ?: C.Gray, FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 8.dp)) {
                    T("IAM", 13.sp, C.Sub)
                    T(when { iam == null -> "нет данных"; iam >= 0.95f -> "норма"; iam >= 0.75f -> "снижен"; else -> "низкий" }, 14.sp,
                        iam?.let { iamColor(it.toDouble()) } ?: C.Sub, FontWeight.Medium)
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
                if (iam != null) Box(Modifier.fillMaxHeight().fillMaxWidth(iam.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(iamColor(iam.toDouble())))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Lucide.Activity, "FBKC (откат)", g.fbkc?.let { "%.1f°".format(it) } ?: "—", "сейчас", Modifier.weight(1f),
                    if ((g.fbkc ?: 0f) < -1f) C.Orange else C.Text)
                StatTile(Lucide.Waves, "FLKC (обучение)", g.flkc?.let { "%.1f°".format(it) } ?: "—", "выученная", Modifier.weight(1f),
                    if ((g.flkc ?: 0f) <= -2.8f) C.Red else if ((g.flkc ?: 0f) < -1f) C.Orange else C.Text)
            }
            if (iam == null) {
                Spacer(Modifier.height(10.dp))
                NoteBox("В определениях этой прошивки нет IAM — приложение следит за коррекцией детонации.")
            }
            Spacer(Modifier.height(14.dp))
            SectionLine(Lucide.History, "IAM по поездкам")
            T("На старте каждой поездки. Падение после заправки — признак плохого бензина на этой АЗС.", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            if (log.isEmpty()) T("Пока нет данных.", 13.sp, C.Sub)
            LazyColumn(Modifier.weight(1f)) {
                items(log.size) { i ->
                    val o = log[i]
                    val t = o.getLong("t")
                    val v = o.getDouble("v")
                    val refill = refills.filter { it.timeMs < t }.maxByOrNull { it.timeMs }
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(iamColor(v)))
                        Spacer(Modifier.width(8.dp))
                        T(fmt.format(Date(t)), 13.sp, C.Sub, modifier = Modifier.width(72.dp))
                        T("%.2f".format(v), 15.sp, iamColor(v), FontWeight.SemiBold, Modifier.width(52.dp))
                        T(refill?.let { "после заправки ${fmt.format(Date(it.timeMs))}" } ?: "", 12.sp, C.Sub, maxLines = 1)
                    }
                }
            }
        }
        // turbo, oil, cooling: tiles
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Thermometer, "Масло, турбина, охлаждение")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Lucide.Droplet, "Масло", g.oilC?.let { "${it.roundToInt()}°" } ?: "—",
                    if (g.oilC == null) "" else if (g.oilEstimated) "оценка по ОЖ и времени" else "датчик ЭБУ", Modifier.weight(1f))
                StatTile(Lucide.Gauge, "Прогрев", if (g.cold) "холодный" else if (g.oilC != null) "прогрет" else "—",
                    if (g.cold) "не выше 3000 об/мин" else g.warmupMin?.let { "до 80° за %.0f мин".format(it) } ?: if (g.oilC != null) "можно нагружать" else "", Modifier.weight(1f),
                    if (g.cold) C.Orange else if (g.oilC != null) C.Green else C.Text)
            }
            if (g.turbo) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Fan, null, tint = C.Sub, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        T("Турботаймер", 13.sp, C.Sub, modifier = Modifier.weight(1f))
                        T("жар ${g.heat.roundToInt()}", 12.sp, C.Sub)
                    }
                    Spacer(Modifier.height(6.dp))
                    val hot = g.heat > 12f
                    T(when {
                        g.cooldownSec > 0 -> "подождите ${g.cooldownSec} с"
                        hot -> "~${g.heat.toInt()} с на холостых"
                        else -> "можно глушить"
                    }, 20.sp, if (hot || g.cooldownSec > 0) C.Orange else C.Green, FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((g.heat / 100f).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp))
                            .background(if (g.heat > 60) C.Red else if (g.heat > 30) C.Orange else C.Green))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SectionLine(Lucide.Thermometer, "Охлаждение и коробка")
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Lucide.Thermometer, "Макс. ОЖ", g.coolantMax?.let { "$it°" } ?: "—", "за сеанс", Modifier.weight(1f),
                    if ((g.coolantMax ?: 0) >= 105) C.Red else C.Text)
                StatTile(Lucide.Cog, "Макс. ATF", g.atfMax?.let { "$it°" } ?: "—", "за сеанс", Modifier.weight(1f),
                    if ((g.atfMax ?: 0) >= 110) C.Orange else C.Text)
            }
            Spacer(Modifier.height(8.dp))
            StatTile(Lucide.Repeat, "Термостат", g.thermostat.ifEmpty { "—" },
                if (g.thermostat.isEmpty()) "оценится после холодного пуска" else "", Modifier.fillMaxWidth(), small = true)
        }
        // what is watched, and the voice
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Bell, "Что отслеживается")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                listOf(
                    Triple(Lucide.Flame, "Детонация", "IAM ниже 0,75 или −0,1 за поездку, FLKC до −2,8°, откат FBKC под наддувом"),
                    Triple(Lucide.Thermometer, "Перегрев", "ОЖ от 105°; рост на 8° за минуту на прогретом моторе (EJ25 — прокладка ГБЦ, воздух)"),
                    Triple(Lucide.Gauge, "Холодный мотор", "обороты выше 3600 на непрогретом масле"),
                    Triple(Lucide.Repeat, "Термостат", "20 минут езды, а ОЖ ниже 72°"),
                    Triple(Lucide.Cog, "АКПП", "ATF выше 110° и 120°"),
                    Triple(Lucide.BatteryCharging, "АКБ и генератор", "разряд на стоянке, слабый пуск, недозаряд ниже 13 В, перезаряд выше 15 В"),
                    Triple(Lucide.Fan, "Турбина", "сколько подождать на холостых после нагрузки"),
                    Triple(Lucide.Wrench, "ТО и шины", "сроки по пробегу, времени, моточасам; давление в шинах"),
                ).forEachIndexed { i, (icon, title, sub) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(C.Violet.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = C.Violet, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            T(title, 14.sp, weight = FontWeight.Medium)
                            T(sub, 12.sp, C.Sub)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(8.dp))
            val ctx = LocalContext.current
            val speech by kg.forester.sg.service.Speech.status.collectAsState()
            SwitchRow("Голосовые предупреждения", "Встроенный русский голос (~21 МБ, один раз)" +
                if (speech.isNotEmpty()) " · $speech" else "", voice) {
                voice = it; Repo.settings.voiceAlerts = it
                if (it) kg.forester.sg.service.Speech.prepare(ctx)
            }
        }
    }
}

/** A value in a rounded tile: icon and label on top, the value large, a note under it. */
@Composable
private fun StatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, note: String,
    modifier: Modifier, color: androidx.compose.ui.graphics.Color = C.Text, small: Boolean = false,
) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.Sub, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            T(label, 12.sp, C.Sub, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        T(value, if (small) 15.sp else 22.sp, color, FontWeight.Bold, maxLines = 2)
        if (note.isNotEmpty()) T(note, 11.sp, C.Sub, maxLines = 2)
    }
}

@Composable
private fun SectionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        T(title, 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NoteBox(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Violet.copy(alpha = 0.10f)).padding(10.dp)) {
        Icon(Lucide.Info, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        T(text, 12.sp, C.Text)
    }
}

@Composable
private fun Line(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        T(k, 13.sp, C.Sub, modifier = Modifier.width(150.dp))
        T(v, 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ServiceTab() {
    Repo.maintenanceVersion.collectAsState().value
    val odo = Maintenance.odometer()
    val now = System.currentTimeMillis()
    val items = Maintenance.items()
    var odoText by remember { mutableStateOf(odo?.roundToInt()?.toString() ?: "") }
    var editing by remember { mutableStateOf<ServiceItem?>(null) }
    val fmt = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Gauge, "Пробег")
            Spacer(Modifier.height(8.dp))
            T(if (odo == null) "Введите пробег с приборки один раз — дальше приложение прибавляет пройденное по поездкам."
            else "Сейчас ≈ ${odo.roundToInt()} км (введённый пробег + поездки)", 12.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            NumberField("Пробег по одометру, км", odoText) { odoText = it }
            Spacer(Modifier.height(8.dp))
            ActionButton("Сохранить пробег", odoText.toDoubleOrNull() != null) { odoText.toDoubleOrNull()?.let { Maintenance.setOdometer(it) } }
            Spacer(Modifier.height(14.dp))
            var hoursText by remember { mutableStateOf("%.1f".format(java.util.Locale.US, Maintenance.hours())) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Clock, null, tint = C.Violet, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                T("Моточасы", 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("%.1f ч".format(Maintenance.hours()), 16.sp, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            T("Приложение считает их само, пока работает двигатель. Если знаете точнее (с прошлого ТО), поправьте.", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            NumberField("Моточасы", hoursText) { hoursText = it }
            Spacer(Modifier.height(6.dp))
            val hoursValue = hoursText.replace(',', '.').trim().toDoubleOrNull()
            ActionButton("Сохранить моточасы", hoursValue != null) { hoursValue?.let { Maintenance.setHours(it) } }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(12.dp))
            T("Нажмите на пункт, чтобы указать, когда его делали, и поменять интервал. «Сделано» — сегодня на текущем пробеге.", 11.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            ActionButton("Добавить свой пункт", true) {
                editing = ServiceItem("c" + System.currentTimeMillis(), "", 10_000, 12, custom = true)
            }
        }
        Panel(Modifier.weight(2f).fillMaxHeight()) {
            PanelTitle(Lucide.Wrench, "Обслуживание")
            Spacer(Modifier.height(8.dp))
            // how many jobs are in each state, at a glance
            val states = items.filter { it.on }.map { serviceState(it, odo, now) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(ServiceState.OVERDUE, "просрочено", Lucide.CircleAlert),
                    Triple(ServiceState.DUE, "скоро", Lucide.Clock),
                    Triple(ServiceState.OK, "в норме", Lucide.CircleCheck),
                    Triple(ServiceState.UNKNOWN, "не отмечено", Lucide.CircleDot),
                ).forEach { (st, label, icon) ->
                    val n = states.count { it == st }
                    if (n > 0) StatusPill(icon, "$label: $n", st.color)
                }
            }
            Spacer(Modifier.height(8.dp))
            val groups = items.groupBy { serviceGroup(it) }.toList().sortedBy { SERVICE_GROUPS.indexOf(it.first) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (group, list) ->
                    item(key = "g$group") {
                        Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(group.uppercase(), 11.sp, C.Violet, weight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f).height(1.dp).background(C.Border))
                        }
                    }
                    items(list, key = { it.id }) { ServiceRow(it, odo, now, fmt) { editing = it } }
                }
            }
        }
    }
    editing?.let { item -> ServiceDialog(item) { editing = null } }
}

private enum class ServiceState(val color: androidx.compose.ui.graphics.Color) {
    OVERDUE(C.Red), DUE(C.Orange), OK(C.Green), UNKNOWN(C.Gray), OFF(C.Gray)
}

private fun serviceState(it: ServiceItem, odo: Double?, now: Long): ServiceState {
    if (!it.on) return ServiceState.OFF
    val used = it.used(odo, now) ?: return ServiceState.UNKNOWN
    return when {
        used >= 1f -> ServiceState.OVERDUE
        it.due(odo, now) -> ServiceState.DUE
        else -> ServiceState.OK
    }
}

private val SERVICE_GROUPS = listOf("Двигатель", "Трансмиссия", "Охлаждение и тормоза", "Салон", "Свои пункты")

private fun serviceGroup(it: ServiceItem) = when (it.id) {
    "oil", "belt", "plugs", "air", "belts" -> "Двигатель"
    "gear", "diff" -> "Трансмиссия"
    "coolant", "brake", "pads" -> "Охлаждение и тормоза"
    "cabin" -> "Салон"
    else -> "Свои пункты"
}

private fun serviceIcon(it: ServiceItem) = when (it.id) {
    "oil" -> Lucide.Droplet
    "belt" -> Lucide.Timer
    "plugs" -> Lucide.Zap
    "air" -> Lucide.Wind
    "belts" -> Lucide.RefreshCw
    "gear" -> Lucide.Cog
    "diff" -> Lucide.Settings2
    "coolant" -> Lucide.Thermometer
    "brake" -> Lucide.Droplets
    "pads" -> Lucide.Disc3
    "cabin" -> Lucide.Fan
    else -> Lucide.Wrench
}

private fun spaced(n: Int) = "%,d".format(n).replace(',', ' ')

@Composable
private fun StatusPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        T(text, 12.sp, color)
    }
}

/** One job: its icon, the interval, how much of it is used, what is left, and «Сделано». */
@Composable
private fun ServiceRow(it: ServiceItem, odo: Double?, now: Long, fmt: SimpleDateFormat, onEdit: () -> Unit) {
    val st = serviceState(it, odo, now)
    val used = it.used(odo, now)
    val km = it.kmLeft(odo)?.roundToInt()
    val days = it.daysLeft(now)
    val hours = it.hoursLeft(Maintenance.hours())?.roundToInt()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .border(1.dp, if (st == ServiceState.OVERDUE || st == ServiceState.DUE) st.color.copy(alpha = 0.5f) else C.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (st == ServiceState.OFF) C.Gray else if (st == ServiceState.UNKNOWN) C.Violet else st.color
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(serviceIcon(it), null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(it.name, 14.sp, if (it.on) C.Text else C.Sub, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                when (st) {
                    ServiceState.OVERDUE -> StatusPill(Lucide.CircleAlert, "просрочено", st.color)
                    ServiceState.DUE -> StatusPill(Lucide.Clock, "скоро", st.color)
                    ServiceState.OFF -> StatusPill(Lucide.CircleDot, "выключено", st.color)
                    else -> {}
                }
            }
            T(listOfNotNull("каждые ${spaced(it.km)} км", "${it.months} мес.", if (it.hours > 0) "${it.hours} м·ч" else null).joinToString(" · ") +
                (if (it.lastMs > 0) "   ·   было ${fmt.format(Date(it.lastMs))}" + (if (it.lastKm >= 0) " на ${spaced(it.lastKm.roundToInt())} км" else "") else ""),
                11.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
                if (used != null) Box(Modifier.fillMaxHeight().fillMaxWidth(used.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(st.color))
            }
            Spacer(Modifier.height(4.dp))
            if (used == null) T("нажмите, чтобы указать, когда делали, или «Сделано», если сегодня", 11.sp, C.Sub)
            else {
                val left = listOfNotNull(
                    km?.let { k -> if (k < 0) "−${spaced(-k)} км" else "${spaced(k)} км" },
                    days?.let { d -> if (d < 0) "−${-d} дн." else "$d дн." },
                    hours?.let { h -> if (h < 0) "−${-h} м·ч" else "$h м·ч" },
                ).joinToString(" · ")
                Row {
                    T(if (st == ServiceState.OVERDUE) "просрочено: " else "осталось: ", 11.sp, C.Sub)
                    T(left, 11.sp, if (st == ServiceState.OK) C.Text else st.color, weight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(C.Purple.copy(alpha = 0.3f))
                .clickable { Maintenance.markDone(it) }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Check, null, tint = C.Text, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            T("Сделано", 13.sp)
        }
    }
}

@Composable
private fun DialogSection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        T(title, 13.sp, C.Violet, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ServiceDialog(item: ServiceItem, onClose: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    var name by remember { mutableStateOf(item.name) }
    var km by remember { mutableStateOf(item.km.toString()) }
    var months by remember { mutableStateOf(item.months.toString()) }
    var lastKm by remember { mutableStateOf(if (item.lastKm >= 0) item.lastKm.roundToInt().toString() else "") }
    // not entered yet: today and now, the usual case is "just did it"
    var lastDate by remember { mutableStateOf(fmt.format(Date(if (item.lastMs > 0) item.lastMs else System.currentTimeMillis()))) }
    var hours by remember { mutableStateOf(if (item.hours > 0) item.hours.toString() else "") }
    var lastHours by remember { mutableStateOf(if (item.lastHours >= 0) "%.0f".format(item.lastHours) else "") }
    var on by remember { mutableStateOf(item.on) }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(C.Violet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(serviceIcon(item), null, tint = C.Violet, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                T(if (item.custom && item.name.isEmpty()) "Новый пункт" else item.name, 20.sp, weight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.note.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Violet.copy(alpha = 0.10f)).padding(10.dp),
                ) {
                    Icon(Lucide.Info, null, tint = C.Violet, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    T(item.note, 12.sp, C.Text)
                }
                if (item.custom) TextFieldDark("Название", name) { name = it }
                DialogSection(Lucide.Repeat, "Интервал — что наступит раньше")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField("км", km) { km = it } }
                    Box(Modifier.weight(1f)) { NumberField("месяцев", months) { months = it } }
                    Box(Modifier.weight(1f)) { NumberField("моточасов", hours) { hours = it } }
                }
                T("Моточасы можно оставить пустыми — тогда только по пробегу и времени.", 11.sp, C.Sub)
                DialogSection(Lucide.History, "Когда делали в последний раз")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField("пробег, км", lastKm) { lastKm = it } }
                    Box(Modifier.weight(1f)) { NumberField("моточасы", lastHours) { lastHours = it } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TextFieldDark("дата и время (дд.мм.гггг чч:мм)", lastDate) { lastDate = it } }
                    Spacer(Modifier.width(8.dp))
                    Chip("Сегодня", false, {
                        lastDate = fmt.format(Date())
                        Maintenance.odometer()?.let { lastKm = it.roundToInt().toString() }
                        lastHours = "%.0f".format(Maintenance.hours())
                    }, Lucide.CalendarCheck)
                }
                if (item.lastMs == 0L) T("Подставлены сегодня и сейчас — поменяйте, если делали раньше. Пустая дата — «не отмечено».", 11.sp, C.Sub)
                Spacer(Modifier.height(4.dp))
                SwitchRow("Следить за этим пунктом", "Выключенный пункт не напоминает о себе", on) { on = it }
            }
        },
        confirmButton = {
            TextButton({
                val ms = runCatching { fmt.parse(lastDate.trim())?.time }.getOrNull()
                    ?: runCatching { dayFmt.parse(lastDate.trim())?.time }.getOrNull() ?: item.lastMs
                Maintenance.update(item.copy(
                    name = name.ifBlank { item.name.ifBlank { "Свой пункт" } },
                    km = km.toIntOrNull() ?: item.km, months = months.toIntOrNull() ?: item.months,
                    lastKm = lastKm.toDoubleOrNull() ?: -1.0, lastMs = if (lastDate.isBlank()) 0L else ms, on = on,
                    hours = hours.toIntOrNull() ?: 0, lastHours = lastHours.toDoubleOrNull() ?: -1.0,
                ))
                onClose()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (item.custom) TextButton({ Maintenance.remove(item.id); onClose() }) { Text("Удалить") }
                TextButton(onClose) { Text("Отмена") }
            }
        },
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
    )
}

@Composable
private fun TyresTab() {
    val ctx = LocalContext.current
    val tyres by Repo.tyres.collectAsState()
    val s = Repo.settings
    var on by remember { mutableStateOf(s.tpms) }
    var min by remember { mutableStateOf("%.1f".format(Locale.US, s.tpmsMinBar)) }
    var picked by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Tpms.permitted(ctx)) { s.tpms = true; on = true; Tpms.start(ctx) }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Disc3, "Датчики давления (TPMS)")
            Spacer(Modifier.height(8.dp))
            SwitchRow("Слушать Bluetooth-датчики", "Недорогие BLE-датчики на ниппель или внутрь колеса (видны как TPMS1…TPMS4)", on) { v ->
                if (v && !Tpms.permitted(ctx)) {
                    ask.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                } else {
                    on = v; s.tpms = v
                    if (v) Tpms.start(ctx) else Tpms.stop(ctx)
                }
            }
            Spacer(Modifier.height(10.dp))
            NumberField("Предупреждать ниже, бар", min) { min = it; it.toFloatOrNull()?.takeIf { v -> v in 0.5f..4f }?.let { v -> s.tpmsMinBar = v } }
            Spacer(Modifier.height(10.dp))
            T("Датчик 1 — переднее левое, 2 — переднее правое, 3 — заднее левое, 4 — заднее правое. Если перепутали — нажмите " +
                "на одно колесо, потом на другое: датчики поменяются местами. Предупреждение придёт и при медленной утечке " +
                "(минус 0,15 бар за 5–10 минут). Неизвестные датчики с «TPMS» в имени пишутся в журнал обмена — пришлите его, добавлю поддержку.", 11.sp, C.Sub)
        }
        Panel(Modifier.weight(1.2f).fillMaxHeight()) {
            PanelTitle(Lucide.Disc3, "Колёса")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("FL" to "FR", "RL" to "RR").forEach { (a, b) ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (w in listOf(a, b)) {
                            val r = tyres[w]
                            val bad = r != null && (r.bar < s.tpmsMinBar || r.leak)
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.CardHi)
                                    .border(2.dp, if (picked == w) C.Purple else if (bad) C.Red else C.Border, RoundedCornerShape(12.dp))
                                    .clickable {
                                        val p = picked
                                        picked = if (p == null) w else { if (p != w) Tpms.swap(p, w); null }
                                    }.padding(12.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                T(Tpms.NAMES[w] ?: w, 12.sp, C.Sub)
                                T(r?.let { "%.2f".format(it.bar) } ?: "—", 30.sp, if (bad) C.Red else C.Text, FontWeight.Bold)
                                T("бар", 12.sp, C.Sub)
                                if (r != null) T("%.0f° · батарея %d%%".format(r.tempC, r.battery) +
                                    " · ${((System.currentTimeMillis() - r.timeMs) / 60_000)} мин назад", 11.sp, C.Sub)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsTab() {
    val list by Alerts.history.collectAsState()
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.Bell, "Последние предупреждения")
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) T("Предупреждений не было", 13.sp, C.Sub)
        LazyColumn {
            items(list) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    T(fmt.format(Date(a.timeMs)), 13.sp, C.Sub, modifier = Modifier.width(100.dp))
                    T(a.text, 14.sp, when (a.level) { Alerts.Level.DANGER -> C.Red; Alerts.Level.WARN -> C.Orange; else -> C.Text })
                }
            }
        }
    }
}
