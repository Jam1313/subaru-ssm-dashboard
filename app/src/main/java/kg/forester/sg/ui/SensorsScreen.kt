package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Activity
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.RrParam

private val filters = listOf("all" to "Все", "ecu" to "Двигатель", "tcu" to "АКПП", "sw" to "Переключатели", "ext" to "Расширенные")

/**
 * Every parameter of the RomRaider logger definitions this car's units have, like a laptop logger:
 * tick what to watch on the left, see current / min / max on the right (also written to the CSV log).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorsScreen() {
    val s = Repo.settings
    val avail by Repo.rrAvailable.collectAsState()
    val values by Repo.rrValues.collectAsState()
    val polled by Repo.rrPolled.collectAsState()
    val init by Repo.ecuInit.collectAsState()
    val obd by Repo.obdMode.collectAsState()
    Repo.watchVersion.collectAsState().value
    val watch = s.watchIds
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val mins = remember { mutableStateMapOf<String, Double>() }
    val maxs = remember { mutableStateMapOf<String, Double>() }
    LaunchedEffect(values) {
        values.forEach { (id, v) ->
            if (!v.isNaN()) {
                mins[id] = minOf(mins[id] ?: v, v)
                maxs[id] = maxOf(maxs[id] ?: v, v)
            }
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.ListChecks, "Все параметры ЭБУ")
            Spacer(Modifier.height(4.dp))
            T(
                when {
                    obd -> "Машина отвечает только по стандартному OBD-II: расширенных параметров Subaru нет."
                    init == null -> "Список зависит от ЭБУ и его прошивки: подключитесь к машине (в демо-режиме его нет)."
                    else -> "ROM ${init?.romId}: доступно ${avail.size} (база RomRaider). Отметьте, что смотреть."
                },
                12.sp, C.Sub,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.forEach { (k, l) -> Chip(l, filter == k, { filter = k }) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { T("Поиск", 12.sp, C.Sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val q = query.trim().lowercase()
            val shown = avail.filter { p ->
                when (filter) {
                    "ecu" -> p.target != 2 && p.kind != RrParam.Kind.SWITCH
                    "tcu" -> p.target == 2
                    "sw" -> p.kind == RrParam.Kind.SWITCH
                    "ext" -> p.kind == RrParam.Kind.EXT
                    else -> true
                } && (q.isEmpty() || p.name.lowercase().contains(q) || p.nameEn.lowercase().contains(q) || p.id.lowercase() == q)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id + it.target }) { p ->
                    val on = p.id in watch
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { s.watchIds = if (on) watch - p.id else watch + p.id }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (on) C.Purple else C.CardHi),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Lucide.Check, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            T(p.name, 14.sp, maxLines = 2)
                            T(listOfNotNull(
                                p.id, p.nameEn.takeIf { it != p.name }, p.unitsRu.ifEmpty { null },
                                if (p.target == 2) "АКПП" else null,
                            ).joinToString(" · "), 11.sp, C.Sub, maxLines = 1)
                        }
                    }
                }
            }
        }
        Panel(Modifier.weight(1.25f).fillMaxHeight()) {
            PanelTitle(Lucide.Activity, "Выбранные параметры") {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                        .clickable { mins.clear(); maxs.clear() }.padding(horizontal = 10.dp, vertical = 6.dp),
                ) { T("Сбросить мин/макс", 12.sp, C.Sub) }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                T("Параметр", 12.sp, C.Sub, modifier = Modifier.weight(2.4f))
                T("Мин", 12.sp, C.Sub, modifier = Modifier.weight(0.8f))
                T("Сейчас", 12.sp, C.Sub, modifier = Modifier.weight(0.9f))
                T("Макс", 12.sp, C.Sub, modifier = Modifier.weight(0.8f))
                T("Ед.", 12.sp, C.Sub, modifier = Modifier.weight(0.8f))
            }
            val ticked = avail.filter { it.id in watch }
            if (ticked.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                T("Отметьте параметры слева. Они опрашиваются отдельным запросом и попадают в CSV-лог. " +
                    "На K-Line каждый параметр добавляет к циклу опроса примерно 15 мс, так что выбирайте нужное.", 13.sp, C.Sub)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(ticked, key = { "w" + it.id + it.target }) { p ->
                    val reading = polled.any { it.id == p.id }
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(2.4f)) {
                            T(p.name, 14.sp, maxLines = 2)
                            if (!reading && init != null) T("ЭБУ не отдаёт этот параметр", 11.sp, C.Orange)
                        }
                        T(p.text(mins[p.id]), 13.sp, C.Sub, modifier = Modifier.weight(0.8f), maxLines = 1)
                        T(p.text(values[p.id]), 15.sp, C.Text, FontWeight.SemiBold, Modifier.weight(0.9f), maxLines = 1)
                        T(p.text(maxs[p.id]), 13.sp, C.Sub, modifier = Modifier.weight(0.8f), maxLines = 1)
                        T(p.unitsRu, 12.sp, C.Sub, modifier = Modifier.weight(0.8f), maxLines = 1)
                    }
                }
            }
            T("Определения параметров: RomRaider (romraider.com), версия 370.", 10.sp, C.Sub)
        }
    }
}
