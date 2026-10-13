package kg.forester.sg.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.composables.icons.lucide.Lucide
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
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IamCard("health.iam", Modifier.weight(1f).fillMaxWidth())
            TurboCard("health.turbo", Modifier.weight(1f).fillMaxWidth())
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Flame, "IAM по поездкам")
            Spacer(Modifier.height(6.dp))
            T("IAM на старте каждой поездки. Падение после заправки — признак плохого бензина на этой АЗС.", 11.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            if (log.isEmpty()) T("Пока нет данных (IAM есть не у всех прошивок)", 13.sp, C.Sub)
            LazyColumn(Modifier.weight(1f)) {
                items(log) { o ->
                    val t = o.getLong("t")
                    val v = o.getDouble("v")
                    val refill = refills.filter { it.timeMs < t }.maxByOrNull { it.timeMs }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        T(fmt.format(Date(t)), 13.sp, C.Sub, modifier = Modifier.width(80.dp))
                        T("%.2f".format(v), 14.sp, if (v >= 0.95) C.Green else if (v >= 0.75) C.Orange else C.Red, FontWeight.SemiBold,
                            Modifier.width(56.dp))
                        T(refill?.let { "после заправки ${fmt.format(Date(it.timeMs))}" } ?: "", 11.sp, C.Sub, maxLines = 1)
                    }
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Thermometer, "Охлаждение и коробка")
            Spacer(Modifier.height(8.dp))
            Line("Термостат", g.thermostat.ifEmpty { "нужен холодный пуск, чтобы оценить" })
            Line("Макс. ОЖ за сеанс", g.coolantMax?.let { "$it°" } ?: "—")
            Line("Макс. ATF за сеанс", g.atfMax?.let { "$it°" } ?: "—")
            Line("Масло", g.oilC?.let { "${it.roundToInt()}°" + if (g.oilEstimated) " (оценка по ОЖ и времени)" else " (датчик ЭБУ)" } ?: "—")
            Spacer(Modifier.height(10.dp))
            T("Приложение следит и предупреждает:", 13.sp)
            T("• IAM ниже 0,75 или падение на 0,1 за поездку, выученная коррекция FLKC до −2,8°, откат FBKC под наддувом;\n" +
                "• перегрев ОЖ от 105°, рост ОЖ на 8° за минуту на прогретом моторе (на EJ25 — признак прокладки ГБЦ или воздуха);\n" +
                "• «не прогрет»: обороты выше 3600 при холодном масле;\n" +
                "• термостат: 20 минут езды, а ОЖ ниже 72°;\n" +
                "• ATF выше 110° / 120°; турботаймер после нагрузки; ТО по пробегу и сроку; давление в шинах.", 11.sp, C.Sub)
            Spacer(Modifier.height(10.dp))
            val ctx = LocalContext.current
            val speech by kg.forester.sg.service.Speech.status.collectAsState()
            SwitchRow("Голосовые предупреждения", "Встроенный русский голос (скачивается один раз, ~21 МБ)" +
                if (speech.isNotEmpty()) ". Голос: $speech" else "", voice) {
                voice = it; Repo.settings.voiceAlerts = it
                if (it) kg.forester.sg.service.Speech.prepare(ctx)
            }
        }
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
            PanelTitle(Lucide.Wrench, "Пробег")
            Spacer(Modifier.height(8.dp))
            T(if (odo == null) "Введите пробег с приборки один раз — дальше приложение прибавляет пройденное по поездкам."
            else "Сейчас ≈ ${odo.roundToInt()} км (введённый пробег + поездки)", 12.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            NumberField("Пробег по одометру, км", odoText) { odoText = it }
            Spacer(Modifier.height(8.dp))
            ActionButton("Сохранить пробег", odoText.toDoubleOrNull() != null) { odoText.toDoubleOrNull()?.let { Maintenance.setOdometer(it) } }
            Spacer(Modifier.height(14.dp))
            var hoursText by remember { mutableStateOf("%.0f".format(Maintenance.hours())) }
            T("Моточасы: ${"%.1f".format(Maintenance.hours())} ч — приложение считает их само, пока работает двигатель. " +
                "Если знаете точнее (с прошлого ТО), поправьте.", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            NumberField("Моточасы", hoursText) { hoursText = it }
            Spacer(Modifier.height(6.dp))
            ActionButton("Сохранить моточасы", hoursText.toDoubleOrNull() != null) { hoursText.toDoubleOrNull()?.let { Maintenance.setHours(it) } }
            Spacer(Modifier.height(16.dp))
            T("Нажмите на пункт, чтобы указать, когда его делали, и поменять интервал. «Сделано» — сегодня на текущем пробеге.", 11.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            ActionButton("Добавить свой пункт", true) {
                editing = ServiceItem("c" + System.currentTimeMillis(), "", 10_000, 12, custom = true)
            }
        }
        Panel(Modifier.weight(2f).fillMaxHeight()) {
            PanelTitle(Lucide.Wrench, "Обслуживание")
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.id }) { it ->
                    val used = it.used(odo, now)
                    val km = it.kmLeft(odo)?.roundToInt()
                    val days = it.daysLeft(now)
                    val due = it.due(odo, now)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { editing = it }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(it.name + if (!it.on) " (выключено)" else "", 14.sp, if (it.on) C.Text else C.Sub)
                            T("каждые ${"%,d".format(it.km).replace(',', ' ')} км / ${it.months} мес." +
                                (if (it.hours > 0) " / ${it.hours} м·ч" else "") +
                                (if (it.lastMs > 0) " · последний раз ${fmt.format(Date(it.lastMs))}" else "") +
                                (if (it.lastKm >= 0) " на ${it.lastKm.roundToInt()} км" else ""), 11.sp, C.Sub, maxLines = 1)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.CardHi)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((used ?: 0f).coerceIn(0f, 1f))
                                    .background(if (due) C.Orange else if ((used ?: 0f) > 0.8f) C.Violet else C.Green))
                            }
                            T(when {
                                used == null -> "отметьте, когда делали"
                                else -> listOfNotNull(
                                    km?.let { k -> if (k < 0) "просрочено на ${-k} км" else "осталось $k км" },
                                    days?.let { d -> if (d < 0) "просрочено на ${-d} дн." else "$d дн." },
                                    it.hoursLeft(Maintenance.hours())?.roundToInt()?.let { h -> if (h < 0) "просрочено на ${-h} м·ч" else "$h м·ч" },
                                ).joinToString(" · ")
                            }, 11.sp, if (due) C.Orange else C.Sub)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(C.Purple.copy(alpha = 0.3f))
                                .clickable { Maintenance.markDone(it) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { T("Сделано", 13.sp) }
                    }
                }
            }
        }
    }
    editing?.let { item -> ServiceDialog(item) { editing = null } }
}

@Composable
private fun ServiceDialog(item: ServiceItem, onClose: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    var name by remember { mutableStateOf(item.name) }
    var km by remember { mutableStateOf(item.km.toString()) }
    var months by remember { mutableStateOf(item.months.toString()) }
    var lastKm by remember { mutableStateOf(if (item.lastKm >= 0) item.lastKm.roundToInt().toString() else "") }
    var lastDate by remember { mutableStateOf(if (item.lastMs > 0) fmt.format(Date(item.lastMs)) else "") }
    var hours by remember { mutableStateOf(if (item.hours > 0) item.hours.toString() else "") }
    var lastHours by remember { mutableStateOf(if (item.lastHours >= 0) "%.0f".format(item.lastHours) else "") }
    var on by remember { mutableStateOf(item.on) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (item.custom && item.name.isEmpty()) "Новый пункт" else item.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.note.isNotEmpty()) Text(item.note, fontSize = 12.sp)
                if (item.custom) androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                NumberField("Интервал, км", km) { km = it }
                NumberField("Интервал, месяцев", months) { months = it }
                NumberField("Интервал, моточасы (пусто — не считать)", hours) { hours = it }
                NumberField("Когда делали: пробег, км", lastKm) { lastKm = it }
                NumberField("Когда делали: моточасы", lastHours) { lastHours = it }
                androidx.compose.material3.OutlinedTextField(lastDate, { lastDate = it }, label = { Text("Когда делали: дата (дд.мм.гггг)") }, singleLine = true)
                SwitchRow("Следить за этим пунктом", "", on) { on = it }
            }
        },
        confirmButton = {
            TextButton({
                val ms = runCatching { fmt.parse(lastDate.trim())?.time }.getOrNull() ?: item.lastMs
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
