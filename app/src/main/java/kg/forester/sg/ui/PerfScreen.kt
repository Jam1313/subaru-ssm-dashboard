package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChartSpline
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Timer
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Motion
import kg.forester.sg.service.Perf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RESULT_NAMES = listOf(
    "0-60" to "0–60 км/ч", "0-100" to "0–100 км/ч", "0-150" to "0–150 км/ч", "0-200" to "0–200 км/ч",
    "60-100" to "60–100 км/ч", "80-120" to "80–120 км/ч", "100-200" to "100–200 км/ч",
    "201m" to "201 м (1/8 мили)", "402m" to "402 м (1/4 мили)",
)

/** «Замеры»: acceleration timer, power estimate, lap timer, G-meter and tilt. */
@Composable
fun PerfScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Разгон", tab == 0, { tab = 0 }, Lucide.Timer)
            Chip("Диностенд", tab == 1, { tab = 1 }, Lucide.ChartSpline)
            Chip("Круги", tab == 2, { tab = 2 }, Lucide.Flag)
            Chip("G-метр и крен", tab == 3, { tab = 3 }, Lucide.Crosshair)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> RunsTab()
                1 -> DynoTab()
                2 -> LapsTab()
                else -> GTab()
            }
        }
    }
}

@Composable
private fun RunsTab() {
    val p by Repo.perf.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Timer, "Замер разгона")
            Spacer(Modifier.height(8.dp))
            T(
                when {
                    p.running -> "Едем! Замер закончится, когда отпустите газ"
                    p.armed -> "Готов: трогайтесь, время пойдёт с первого движения"
                    else -> "Остановитесь полностью, чтобы замерить разгон с места. 60–100, 80–120 и 100–200 считаются на ходу всегда"
                }, 13.sp, if (p.armed || p.running) C.Green else C.Sub,
            )
            Spacer(Modifier.height(12.dp))
            FitValue(if (p.running || p.elapsedSec > 0) "%.2f".format(p.elapsedSec) else "—", "с", 64f, Modifier.fillMaxWidth().height(90.dp), C.Violet)
            T("%.0f км/ч · %.0f м".format(p.speedKmh, p.distanceM), 18.sp)
            Spacer(Modifier.height(12.dp))
            RESULT_NAMES.forEach { (k, name) ->
                p.live[k]?.let { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        T(name, 14.sp, C.Sub, modifier = Modifier.weight(1f))
                        T("%.2f с".format(v) + (if (k.endsWith("m")) p.live[k.replace("m", "v")]?.let { "  ·  %.0f км/ч".format(it) } ?: "" else ""), 15.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            T("Время считается по скорости ЭБУ (несколько замеров в секунду, моменты пересечения интерполируются), путь — интегралом скорости. " +
                "Скорость поправлена коэффициентом спидометра, который приложение выучило по GPS. Точность около ±0,1–0,2 с.", 10.sp, C.Sub)
        }
        Panel(Modifier.weight(1.2f).fillMaxHeight()) {
            PanelTitle(Lucide.Flag, "Лучшие и последние") {
                T("очистить", 12.sp, C.Sub, modifier = Modifier.padding(end = 6.dp).clickable { Perf.clearRuns() })
            }
            Spacer(Modifier.height(8.dp))
            val best = RESULT_NAMES.mapNotNull { (k, name) -> p.runs.mapNotNull { it.results[k] }.minOrNull()?.let { name to it } }
            if (best.isEmpty()) T("Замеров ещё нет", 13.sp, C.Sub)
            best.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    row.forEach { (n, v) ->
                        Row(Modifier.weight(1f)) {
                            T("$n ", 12.sp, C.Sub)
                            T("%.2f с".format(v), 13.sp, weight = FontWeight.SemiBold)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
            LazyColumn(Modifier.weight(1f)) {
                items(p.runs) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        T(fmt.format(Date(r.timeMs)) + if (r.maxSpeed > 0) "  ·  до %.0f км/ч".format(r.maxSpeed) else "", 11.sp, C.Sub)
                        T(RESULT_NAMES.mapNotNull { (k, n) -> r.results[k]?.let { "${n.substringBefore(" ")} %.2f".format(it) } }.joinToString("   "), 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DynoTab() {
    val p by Repo.perf.collectAsState()
    val s = Repo.settings
    var mass by remember { mutableStateOf(s.massKg.toString()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(2f).fillMaxHeight()) {
            PanelTitle(Lucide.ChartSpline, "Мощность на колёсах (оценка)")
            val maxHp = p.dyno.maxByOrNull { it.hp }
            val maxNm = p.dyno.maxByOrNull { it.nm }
            T(if (maxHp == null) "Нет замера: на ровной дороге на 3-й передаче от ~2000 об/мин нажмите газ в пол до отсечки"
            else "%.0f л.с. при %d об/мин · %.0f Н·м при %d об/мин · на коленвалу ≈ %.0f л.с. (+15 %% потерь трансмиссии)"
                .format(maxHp.hp, maxHp.rpm, maxNm!!.nm, maxNm.rpm, maxHp.hp * 1.15f), 13.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (p.dyno.size >= 2) Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    val r0 = p.dyno.first().rpm.toFloat(); val r1 = p.dyno.last().rpm.toFloat().coerceAtLeast(r0 + 1)
                    val hpMax = (p.dyno.maxOf { it.hp } * 1.15f).coerceAtLeast(1f)
                    val nmMax = (p.dyno.maxOf { it.nm } * 1.15f).coerceAtLeast(1f)
                    fun x(r: Int) = (r - r0) / (r1 - r0) * size.width
                    for (k in 0..4) drawLine(C.Border, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
                    val hp = Path(); val nm = Path()
                    p.dyno.forEachIndexed { i, d ->
                        val yh = size.height - d.hp / hpMax * size.height
                        val yn = size.height - d.nm / nmMax * size.height
                        if (i == 0) { hp.moveTo(x(d.rpm), yh); nm.moveTo(x(d.rpm), yn) } else { hp.lineTo(x(d.rpm), yh); nm.lineTo(x(d.rpm), yn) }
                    }
                    drawPath(hp, C.Violet, style = Stroke(4f))
                    drawPath(nm, C.Blue, style = Stroke(3f))
                }
            }
            Row {
                T("— мощность, л.с.   ", 12.sp, C.Violet)
                T("— момент, Н·м   ", 12.sp, C.Blue)
                if (p.dyno.isNotEmpty()) T("${p.dyno.first().rpm}…${p.dyno.last().rpm} об/мин", 12.sp, C.Sub)
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Timer, "Как считается")
            Spacer(Modifier.height(8.dp))
            NumberField("Масса машины с водителем, кг", mass) { mass = it; it.toIntOrNull()?.takeIf { v -> v in 600..4000 }?.let { v -> s.massKg = v } }
            Spacer(Modifier.height(8.dp))
            T("Мощность = (масса × ускорение + сопротивление воздуха + качения) × скорость. Ускорение — по скорости ЭБУ, " +
                "сглаживание 0,6 с. Это оценка «виртуального диностенда»: на точность влияют уклон, ветер, масса и шины. " +
                "Сравнивайте свои замеры между собой (до и после доработки) в одинаковых условиях.", 12.sp, C.Sub)
        }
    }
}

@Composable
private fun LapsTab() {
    val p by Repo.perf.collectAsState()
    val gps by Repo.gps.collectAsState()
    val lap = p.lap
    fun t(sec: Float) = "%d:%05.2f".format((sec / 60).toInt(), sec % 60)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Flag, "Секундомер кругов")
            Spacer(Modifier.height(8.dp))
            T(if (!lap.lineSet) "Встаньте на линии старта/финиша и нажмите кнопку. Круг засчитывается, когда машина проезжает в 25 м от этой точки после отъезда дальше 60 м."
            else "Линия старта задана. Каждое прохождение рядом с ней — новый круг.", 13.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            FitValue(if (lap.running) t(lap.currentSec) else "—", "", 64f, Modifier.fillMaxWidth().height(90.dp), C.Violet)
            lap.laps.lastOrNull()?.let { T("последний ${t(it)}", 16.sp) }
            lap.best?.let { T("лучший ${t(it)}", 16.sp, C.Green) }
            Spacer(Modifier.weight(1f))
            ActionButton("Старт/финиш здесь", gps?.lat != null) { Perf.setLapLine(gps) }
            Spacer(Modifier.height(8.dp))
            ActionButton("Сбросить круги", lap.lineSet, danger = true) { Perf.clearLaps() }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Timer, "Круги")
            LazyColumn {
                items(lap.laps.withIndex().reversed().toList()) { (i, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        T("Круг ${i + 1}", 14.sp, C.Sub, modifier = Modifier.weight(1f))
                        T(t(v), 15.sp, if (v == lap.best) C.Green else C.Text, FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GTab() {
    val st by Repo.motion.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GMeterCard("perf.gmeter", Modifier.weight(1.3f).fillMaxHeight(), big = true)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InclineCard("perf.incline", Modifier.weight(1f).fillMaxWidth())
            Panel(Modifier.fillMaxWidth()) {
                T(when {
                    !st.hasSensor -> "В этом устройстве нет акселерометра: перегрузки считаются по скорости ЭБУ и повороту по GPS " +
                        "(на SH и новее — по датчику VDC, если блок его отдаёт), крена нет, уклон — по высоте GPS."
                    !st.calibrated -> "Датчик наклона есть. Поставьте машину на ровное место и нажмите «Обнулить»: так приложение узнает, как стоит магнитола."
                    else -> "Датчик откалиброван. Если переставили магнитолу или телефон — обнулите снова на ровном месте."
                }, 12.sp, C.Sub)
                Spacer(Modifier.height(8.dp))
                if (st.hasSensor) { ActionButton("Обнулить (машина стоит ровно)", true) { Motion.calibrate() }; Spacer(Modifier.height(8.dp)) }
                ActionButton("Сбросить максимумы", true) { Motion.resetMax() }
            }
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, singleLine = true,
        label = { T(label, 12.sp, C.Sub) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A text field in the app's dark style (same look as [NumberField]); [digits] keeps only digits and dots. */
@Composable
fun TextFieldDark(label: String, value: String, digits: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(if (digits) v.filter { it.isDigit() || it == '.' } else v) }, singleLine = true,
        label = { T(label, 12.sp, C.Sub) },
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 15.sp)
            if (sub.isNotEmpty()) T(sub, 11.sp, C.Sub)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
    }
}
