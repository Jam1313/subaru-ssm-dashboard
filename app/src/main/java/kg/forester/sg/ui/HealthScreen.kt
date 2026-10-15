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
import androidx.compose.foundation.horizontalScroll
import com.composables.icons.lucide.Wind
import com.composables.icons.lucide.Box
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
import androidx.compose.ui.graphics.Color
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

/**
 * «Мониторинг»: engine guard (IAM, knock, turbo, oil, thermostat), the ECU's learned values, maintenance planner,
 * tyres, warnings log.
 */
@Composable
fun HealthScreen() {
    var tab by remember { mutableStateOf(0) }
    var engine3d by remember { mutableStateOf(Repo.settings.engine3d) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Мотор", tab == 0, { tab = 0 }, Lucide.HeartPulse)
            Chip("Обучение", tab == 4, { tab = 4 }, Lucide.Wind)
            Chip("Проверка", tab == 5, { tab = 5 }, Lucide.Stethoscope)
            Chip("ТО", tab == 1, { tab = 1 }, Lucide.Wrench)
            Chip("Шины", tab == 2, { tab = 2 }, Lucide.Disc3)
            Chip("Предупреждения", tab == 3, { tab = 3 }, Lucide.Bell)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> {
                    // the engine as a 3D model or as a table of panels, whichever one likes
                    val switch: @Composable () -> Unit = { EngineViewSwitch(engine3d) { engine3d = it; Repo.settings.engine3d = it } }
                    if (engine3d) Engine3DTab(switch) else EngineTable(switch)
                }
                1 -> ServiceTab()
                2 -> TyresTab()
                4 -> LearningTab()
                5 -> CheckTab()
                else -> AlertsTab()
            }
        }
    }
}

/**
 * «Проверка»: half a minute of warm idle against the factory Select Monitor table for this engine, and what the
 * analyzer watches while driving.
 */
@Composable
private fun CheckTab() {
    val ctx = LocalContext.current
    val init by Repo.ecuInit.collectAsState()
    val demo by Repo.demoOn.collectAsState()
    val turbo = Repo.guard.collectAsState().value.turboCar
    val engine = remember(init, demo, turbo) { kg.forester.sg.ssm.EngineId.detect(ctx) }
    val table = remember(engine) { kg.forester.sg.service.IdleRef.forEngine(engine) }
    val latest by kg.forester.sg.service.RulesHost.idle.collectAsState()
    val history by kg.forester.sg.service.RulesHost.idleHistory.collectAsState()
    // a sample picked in the history below, else the latest one
    var picked by remember { mutableStateOf<kg.forester.sg.service.IdleSample?>(null) }
    val sample = picked ?: latest
    // a second sample to compare with: its values in a column of their own, with the difference
    var compare by remember { mutableStateOf<kg.forester.sg.service.IdleSample?>(null) }
    val cmp = compare?.takeIf { it.timeMs != sample?.timeMs }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Stethoscope, "Холостой ход против заводских значений")
            Spacer(Modifier.height(6.dp))
            T("${engine.title} · ${engineSource(engine)}", 12.sp, C.Sub)
            T(table?.source ?: if (engine.source == "нет") "Таблица выберется по мотору, который определит ЭБУ" else "Заводской таблицы для этого мотора нет", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            NoteBox(sample?.let { (if (picked != null) "Замер из истории, ${fmt.format(Date(it.timeMs))}: " else "Замер ${fmt.format(Date(it.timeMs))}: ") +
                "среднее за полминуты прогретого холостого хода. Обновляется сам, когда мотор прогрет до 80° и стоит на холостых." }
                ?: "Прогрейте мотор до 80°, выключите кондиционер и обогревы и оставьте машину на холостых в N или P на минуту. Приложение само снимет средние значения и сравнит с заводскими.")
            if (table != null) {
                val lines = kg.forester.sg.service.IdleRef.judge(table, sample?.values.orEmpty())
                // what the colours mean, above the table so it's seen without scrolling
                Spacer(Modifier.height(8.dp))
                ColorLegend(listOf(
                    C.Green to "норма",
                    Color(0xFFD9C24A) to "чуть за границей (у исправных бывает)",
                    C.Red to "вне нормы",
                    C.Gray to "нет данных",
                ))
                Spacer(Modifier.height(4.dp))
                val cmpLines = cmp?.let { c -> kg.forester.sg.service.IdleRef.judge(table, c.values).associateBy { it.row.key } }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Параметр", 12.sp, C.Sub, modifier = Modifier.weight(1.3f))
                    T(if (cmp != null && sample != null) fmt.format(Date(sample.timeMs)) else "У вас", 12.sp, C.Sub, modifier = Modifier.weight(0.8f), maxLines = 1)
                    if (cmp != null) Row(Modifier.weight(0.9f), verticalAlignment = Alignment.CenterVertically) {
                        T(fmt.format(Date(cmp.timeMs)), 12.sp, C.Violet, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Lucide.X, "Убрать сравнение", tint = C.Sub, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { compare = null })
                    }
                    T("Завод", 12.sp, C.Sub, modifier = Modifier.weight(if (cmp != null) 1.0f else 1.4f))
                }
                lines.forEachIndexed { i, l ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
                    val col = when (l.verdict) {
                        kg.forester.sg.service.IdleRef.Verdict.OK -> C.Green
                        kg.forester.sg.service.IdleRef.Verdict.NEAR -> Color(0xFFD9C24A)
                        kg.forester.sg.service.IdleRef.Verdict.NONE -> C.Gray
                        else -> C.Red
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                            Spacer(Modifier.width(8.dp))
                            T(l.row.title, 14.sp, maxLines = 1)
                        }
                        T(l.value?.let { "%.${l.row.decimals}f".format(it) + (if (l.row.unit.startsWith("°")) "" else " ") + l.row.unit } ?: "—", 15.sp, if (l.verdict == kg.forester.sg.service.IdleRef.Verdict.NONE) C.Sub else col,
                            FontWeight.SemiBold, Modifier.weight(0.8f), maxLines = 1)
                        if (cmpLines != null) {
                            // the other sample, and how much this one differs from it
                            val o = cmpLines[l.row.key]
                            val ov = o?.value
                            val d = l.row.decimals
                            Column(Modifier.weight(0.9f)) {
                                T(ov?.let { "%.${d}f".format(it) } ?: "—", 14.sp, when (o?.verdict) {
                                    kg.forester.sg.service.IdleRef.Verdict.OK -> C.Green
                                    kg.forester.sg.service.IdleRef.Verdict.NEAR -> Color(0xFFD9C24A)
                                    kg.forester.sg.service.IdleRef.Verdict.NONE, null -> C.Sub
                                    else -> C.Red
                                }, FontWeight.Medium, maxLines = 1)
                                val v = l.value
                                if (v != null && ov != null && Math.abs(v - ov) * Math.pow(10.0, d.toDouble()) >= 0.5) {
                                    T("разница %+.${d}f".format(v - ov), 11.sp, C.Sub, maxLines = 1)
                                }
                            }
                        }
                        T(l.row.norm, 12.sp, C.Sub, modifier = Modifier.weight(if (cmpLines != null) 1.0f else 1.4f), maxLines = 2)
                    }
                }
                val hints = lines.mapNotNull { kg.forester.sg.service.IdleRef.hint(it) }.distinct()
                if (hints.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionLine(Lucide.Lightbulb, "Что это может значить")
                    hints.forEach { T("• $it", 13.sp, C.Text.copy(alpha = 0.9f)) }
                }
                if (history.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    SectionLine(Lucide.History, "История замеров")
                    T("Нажмите на замер, чтобы открыть его в таблице, или на ⇄, чтобы сравнить с ним. В скобках сдвиг от первого замера: видно, куда идёт, например грязнеет датчик расхода воздуха или растёт подсос.", 12.sp, C.Sub)
                    Spacer(Modifier.height(6.dp))
                    val first = history.first()
                    fun cell(s: kg.forester.sg.service.IdleSample, k: String, d: Int): String {
                        val v = s.values[k] ?: return "—"
                        val base = first.values[k]
                        // only a real shift, not "+0.00" or "-0.0" from rounding
                        val dv = if (s !== first && base != null) v - base else 0f
                        val delta = if (Math.abs(dv) * Math.pow(10.0, d.toDouble()) >= 0.5) " (%+.${d}f)".format(dv) else ""
                        return "%.${d}f".format(v) + delta
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        listOf("Дата" to 1.1f, "Обороты" to 1f, "MAF, г/с" to 1f, "Впрыск, мс" to 1f, "Обучение, %" to 1f).forEach { (h, w) ->
                            T(h, 11.sp, C.Sub, modifier = Modifier.weight(w))
                        }
                        Spacer(Modifier.width(36.dp))
                    }
                    history.reversed().forEach { s ->
                        val on = sample?.timeMs == s.timeMs
                        val vs = cmp?.timeMs == s.timeMs
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) C.Violet.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { picked = if (s.timeMs == latest?.timeMs) null else s }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(fmt.format(Date(s.timeMs)), 12.sp, modifier = Modifier.weight(1.1f), maxLines = 1)
                            T(cell(s, "rpm", 0), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(cell(s, "maf", 1), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(cell(s, "inj", 2), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(cell(s, "aflearn", 1), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            // compare with this one (not with itself)
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (vs) C.Violet.copy(alpha = 0.3f) else Color.Transparent)
                                    .clickable(enabled = !on) { compare = if (vs) null else s },
                                contentAlignment = Alignment.Center,
                            ) { if (!on) Icon(Lucide.ArrowLeftRight, "Сравнить", tint = if (vs) C.Text else C.Violet, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.ListChecks, "Что проверяется на ходу")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { WatchedList() }
        }
    }
}

/** The colour legend under the idle table: a dot and a few words each, wrapping on narrow screens. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorLegend(items: List<Pair<Color, String>>) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (c, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(5.dp))
                T(text, 11.sp, C.Sub, maxLines = 1)
            }
        }
    }
}

/** 3D or table: two segments in one pill. */
@Composable
private fun EngineViewSwitch(engine3d: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card.copy(alpha = 0.92f)).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(3.dp)) {
        listOf(Triple(true, "3D", Lucide.Box), Triple(false, "Таблица", Lucide.Table)).forEach { (v, text, icon) ->
            val on = engine3d == v
            Row(
                Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) C.Purple.copy(alpha = 0.28f) else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (on) C.Text else C.Sub, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                T(text, 13.sp, if (on) C.Text else C.Sub, FontWeight.Medium)
            }
        }
    }
}

/** «Мотор» as it was before the 3D engine: knock, oil/turbo/cooling, what is watched, side by side. */
@Composable
private fun EngineTable(switch: @Composable () -> Unit) {
    val g by Repo.guard.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Flame, "IAM и детонация")
            Spacer(Modifier.height(12.dp))
            KnockDetails()
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Thermometer, "Масло, турбина, охлаждение")
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                OilDetails()
                if (g.turbo) {
                    Spacer(Modifier.height(8.dp))
                    TurboDetails()
                }
                Spacer(Modifier.height(14.dp))
                SectionLine(Lucide.Thermometer, "Охлаждение и коробка")
                CoolingDetails(wide = false)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { switch() }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Bell, "Что отслеживается")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { WatchedList() }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(8.dp))
            VoiceSwitch()
        }
    }
}

/** IAM colour: full, lowered, low. */
internal fun iamColor(v: Double) = if (v >= 0.95) C.Green else if (v >= 0.75) C.Orange else C.Red

/** The knock sensor's card on the engine: IAM now, the corrections, IAM at the start of each drive. */
@Composable
internal fun KnockDetails() {
    val g by Repo.guard.collectAsState()
    val fmt = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }
    val log = remember(g.iam) {
        runCatching { JSONArray(Repo.settings.iamLog) }.getOrNull()?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty().reversed()
    }
    val refills = remember { runCatching { Repo.db.refills() }.getOrDefault(emptyList()) }
    val iam = g.iam
    Row(verticalAlignment = Alignment.Bottom) {
        T(iam?.let { "%.2f".format(it) } ?: "—", 34.sp, iam?.let { iamColor(it.toDouble()) } ?: C.Gray, FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            T("IAM", 13.sp, C.Sub)
            T(when { iam == null -> "нет данных"; iam >= 0.95f -> "норма"; iam >= 0.75f -> "снижен"; else -> "низкий" }, 14.sp,
                iam?.let { iamColor(it.toDouble()) } ?: C.Sub, FontWeight.Medium)
        }
    }
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
        if (iam != null) Box(Modifier.fillMaxHeight().fillMaxWidth(iam.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(iamColor(iam.toDouble())))
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Activity, "FBKC (откат)", g.fbkc?.let { "%.1f°".format(it) } ?: "—", "сейчас", Modifier.weight(1f),
            if ((g.fbkc ?: 0f) < -1f) C.Orange else C.Text)
        StatTile(Lucide.Waves, "FLKC (обучение)", g.flkc?.let { "%.1f°".format(it) } ?: "—", "выученная", Modifier.weight(1f),
            if ((g.flkc ?: 0f) <= -2.8f) C.Red else if ((g.flkc ?: 0f) < -1f) C.Orange else C.Text)
    }
    if (iam == null) {
        Spacer(Modifier.height(8.dp))
        NoteBox("В определениях этой прошивки нет IAM — приложение следит за коррекцией детонации.")
    }
    Spacer(Modifier.height(12.dp))
    SectionLine(Lucide.History, "IAM по поездкам")
    T("На старте каждой поездки. Падение после заправки — признак плохого бензина на этой АЗС.", 12.sp, C.Sub)
    Spacer(Modifier.height(4.dp))
    if (log.isEmpty()) T("Пока нет данных.", 13.sp, C.Sub)
    log.take(8).forEachIndexed { i, o ->
        val t = o.getLong("t")
        val v = o.getDouble("v")
        val refill = refills.filter { it.timeMs < t }.maxByOrNull { it.timeMs }
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(iamColor(v)))
            Spacer(Modifier.width(8.dp))
            T(fmt.format(Date(t)), 13.sp, C.Sub, modifier = Modifier.width(72.dp))
            T("%.2f".format(v), 15.sp, iamColor(v), FontWeight.SemiBold, Modifier.width(52.dp))
            T(refill?.let { "после заправки ${fmt.format(Date(it.timeMs))}" } ?: "", 12.sp, C.Sub, maxLines = 1)
        }
    }
}

/** The oil's card: the temperature (read or estimated) and whether the engine may be loaded. */
@Composable
internal fun OilDetails() {
    val g by Repo.guard.collectAsState()
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Droplet, "Масло", g.oilC?.let { "${it.roundToInt()}°" } ?: "—",
            if (g.oilC == null) "" else if (g.oilEstimated) "оценка по ОЖ и нагрузке" else "датчик ЭБУ", Modifier.weight(1f))
        StatTile(Lucide.Gauge, "Прогрев", if (g.cold) "холодный" else if (g.oilC != null) "прогрет" else "—",
            if (g.cold) "не выше 3000 об/мин" else g.warmupMin?.let { "до 80° за %.0f мин".format(it) } ?: if (g.oilC != null) "можно нагружать" else "", Modifier.weight(1f),
            if (g.cold) C.Orange else if (g.oilC != null) C.Green else C.Text)
    }
}

/** The turbo's card: the turbo timer. */
@Composable
internal fun TurboDetails() {
    val g by Repo.guard.collectAsState()
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

/** The coolant's card: the highest coolant and ATF this session, and the thermostat. */
@Composable
internal fun CoolingDetails(wide: Boolean = true) {
    val g by Repo.guard.collectAsState()
    // wide: three in a row; narrow: the thermostat under the two maximums
    @Composable fun thermostat(m: Modifier) = StatTile(Lucide.Repeat, "Термостат", g.thermostat.ifEmpty { "—" },
        if (g.thermostat.isEmpty()) "оценится после холодного пуска" else "", m, small = true)
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Thermometer, "Макс. ОЖ", g.coolantMax?.let { "$it°" } ?: "—", "за сеанс", Modifier.weight(1f),
            if ((g.coolantMax ?: 0) >= 105) C.Red else C.Text)
        StatTile(Lucide.Cog, "Макс. ATF", g.atfMax?.let { "$it°" } ?: "—", "за сеанс", Modifier.weight(1f),
            if ((g.atfMax ?: 0) >= 110) C.Orange else C.Text)
        if (wide) thermostat(Modifier.weight(1f))
    }
    if (!wide) {
        Spacer(Modifier.height(8.dp))
        thermostat(Modifier.fillMaxWidth())
    }
}

/** What the guard watches, and the voice warnings switch. */
@Composable
internal fun WatchedDetails() {
    WatchedList()
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
    Spacer(Modifier.height(8.dp))
    VoiceSwitch()
}

@Composable
private fun WatchedList() {
    listOf(
        Triple(Lucide.Flame, "Детонация", "IAM ниже 0,75 или −0,1 за поездку, FLKC −1,4 / −2,8 / −4°, откат FBKC под наддувом и на разгоне. После сброса ЭБУ, на малой нагрузке и холодном моторе не пугает"),
        Triple(Lucide.Droplets, "Смесь", "ЭБУ долго добавляет или убирает больше 15 % топлива, подсос только на холостых, датчик состава смеси не работает, форсунки на пределе"),
        Triple(Lucide.Fan, "Наддув", "перенаддув, отсечка топлива, недодув против обычного для вашей машины, бедная смесь под наддувом, турботаймер"),
        Triple(Lucide.Gauge, "Холостой ход", "плавающие, высокие или низкие обороты, AVCS не возвращается, слабое разрежение"),
        Triple(Lucide.Thermometer, "Охлаждение", "горячо от 102°, перегрев от 105°, выше 100° на скорости, перегрев только в пробке (вентиляторы), рост на 8° за минуту"),
        Triple(Lucide.Snowflake, "Холодный мотор", "высокие обороты, полный газ или наддув на непрогретом масле"),
        Triple(Lucide.Repeat, "Термостат", "20 минут езды, а ОЖ ниже 72°"),
        Triple(Lucide.Cog, "АКПП", "ATF от 100°, 110° и 120°, странный датчик, проскальзывание блокировки, аварийный режим на третьей"),
        Triple(Lucide.Disc3, "Полный привод", "передние и задние колёса крутятся с разной скоростью: разный износ или давление"),
        Triple(Lucide.BatteryCharging, "АКБ и генератор", "разряд на стоянке, слабый пуск, не заряжает ниже 12,6 В, перезаряд выше 15 В, магнитола не выключилась. 12,5–13 В на ходу у Subaru 2001–2008 норма"),
        Triple(Lucide.ThermometerSnowflake, "Датчики", "после долгой стоянки ОЖ и воздух показывают по-разному, нереальное атмосферное давление"),
        Triple(Lucide.Wrench, "ТО и шины", "сроки по пробегу, времени, моточасам; давление в шинах"),
    ).forEachIndexed { i, (icon, title, sub) ->
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun VoiceSwitch() {
    var voice by remember { mutableStateOf(Repo.settings.voiceAlerts) }
    val ctx = LocalContext.current
    val speech by kg.forester.sg.service.Speech.status.collectAsState()
    SwitchRow("Голосовые предупреждения", "Встроенный русский голос (~21 МБ, один раз)" +
        if (speech.isNotEmpty()) " · $speech" else "", voice) {
        voice = it; Repo.settings.voiceAlerts = it
        if (it) kg.forester.sg.service.Speech.prepare(ctx)
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
internal fun NoteBox(text: String) {
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
