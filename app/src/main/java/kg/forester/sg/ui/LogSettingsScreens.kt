package kg.forester.sg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val e by Repo.live.collectAsState()
    val current by Repo.logFile.collectAsState()
    var enabled by remember { mutableStateOf(Repo.settings.logEnabled) }
    var files by remember { mutableStateOf(listOf<File>()) }
    LaunchedEffect(Unit) {
        while (true) {
            files = File(ctx.getExternalFilesDir(null), "logs").listFiles()?.sortedByDescending { it.name } ?: emptyList()
            delay(5000)
        }
    }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(420.dp).fillMaxHeight()) {
            PanelTitle(Lucide.FileText, "Запись лога")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Писать лог при работе двигателя", 15.sp, modifier = Modifier.weight(1f))
                Switch(enabled, {
                    enabled = it; Repo.settings.logEnabled = it
                }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(8.dp))
            T("Текущий файл: ${current ?: "—"}", 13.sp, C.Sub)
            T("Папка: Android/data/kg.forester.sg/files/logs", 13.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            var hours by remember { mutableStateOf(Repo.settings.logRetentionHours) }
            T("Хранить подробные логи:", 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(24, 72, 168).forEach { h ->
                    Chip(if (h == 168) "7 дн" else "${h / 24} сут", hours == h, { hours = h; Repo.settings.logRetentionHours = h })
                    Spacer(Modifier.width(6.dp))
                }
            }
            T("Старые файлы удаляются автоматически раз в час, папка не больше 200 МБ. Графики расхода хранятся 35 дней, поездки и заправки — всегда.", 12.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            T("Файлы", 15.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(files) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        T(f.name, 13.sp, modifier = Modifier.weight(1f))
                        T("${f.length() / 1024} КБ", 13.sp, C.Sub)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            T("Текущие значения", 16.sp)
            Spacer(Modifier.height(8.dp))
            val rows = listOf(
                "Обороты" to (e.rpm?.toString() ?: "—"),
                "Скорость, км/ч" to (e.speedKmh?.toString() ?: "—"),
                "Темп. ОЖ, °C" to (e.coolantC?.toString() ?: "—"),
                "Темп. впуска, °C" to (e.intakeC?.toString() ?: "—"),
                "MAF, г/с" to fmtF(e.mafGs, 2),
                "Дроссель, %" to fmtF(e.throttlePct),
                "Напряжение, В" to fmtF(e.batteryV, 2),
                "Впрыск, мс" to fmtF(e.injPwMs, 2),
                "УОЗ, °" to fmtF(e.timingDeg),
                "Коррекция детонации, °" to fmtF(e.knockDeg),
                "Атм. давление, кПа" to fmtF(e.atmKpa, 0),
                "Абс. давление впуска, кПа" to fmtF(e.mapKpa, 0),
                "Давление отн. атмосферы, кПа" to fmtF(e.gaugeKpa, 0),
                "Педаль газа, %" to fmtF(e.pedalPct, 0),
                "AVCS, °" to fmtF(e.avcsDeg, 0),
                "Коррекция A/F, %" to fmtF(e.afCorrPct),
                "Обучение A/F, %" to fmtF(e.afLearnPct),
                "AFR" to fmtF(e.afr),
                "Wastegate, %" to fmtF(e.wastegatePct, 0),
            )
            LazyColumn {
                items(rows) { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        T(k, 14.sp, C.Sub, modifier = Modifier.weight(1f))
                        T(v, 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val s = Repo.settings
    val init by Repo.ecuInit.collectAsState()
    val msg by Repo.linkMessage.collectAsState()
    var tank by remember { mutableStateOf(fmt(s.tankL, 0)) }
    var price by remember { mutableStateOf(fmt(s.pricePerL, 1)) }
    var k by remember { mutableStateOf(fmt(s.fuelK, 3)) }
    var left by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(460.dp).fillMaxHeight()) {
            PanelTitle(Lucide.Cog, "Настройки")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Объём бака, л (0 — не учитывать)", tank) { tank = it }
                Field("Цена топлива, KGS/л", price) { price = it }
                Field("Коэффициент расхода (калибровка)", k) { k = it }
                Field("Сейчас в баке, л (если знаете точно)", left) { left = it }
            }
            Spacer(Modifier.height(14.dp))
            ActionButton("Сохранить", true) {
                tank.toD()?.let { s.tankL = it }
                price.toD()?.let { s.pricePerL = it }
                k.toD()?.let { if (it in 0.5..2.0) s.fuelK = it }
                left.toD()?.let { s.fuelAtRefillL = it; s.usedSinceRefillL = 0.0; left = "" }
                Repo.touch()
                saved = "Сохранено"
            }
            Spacer(Modifier.height(8.dp))
            T(saved, 13.sp, C.Green)
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            T("Автомобиль и ЭБУ", 16.sp)
            Spacer(Modifier.height(10.dp))
            InfoRow("Автомобиль", "Subaru Forester SG5 2003, EJ20 turbo")
            InfoRow("ЭБУ", "22611AG572 (Denso, SH7055)")
            InfoRow("SSM ID", init?.ssmId ?: "—")
            InfoRow("ROM ID", init?.romId ?: "—")
            InfoRow("Связь", msg)
            InfoRow("Коррекция спидометра (GPS)", fmt(s.speedK, 3))
            InfoRow("Коэффициент расхода", fmt(s.fuelK, 3))
            Spacer(Modifier.height(14.dp))
            T(
                "Расход считается по MAF с учётом топливных коррекций. Коэффициент уточняется автоматически " +
                    "при заправках до полного бака. Уровень топлива ЭБУ не передаёт — остаток считается от последней заправки.",
                13.sp, C.Sub,
            )
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        T(k, 14.sp, C.Sub, modifier = Modifier.weight(1f))
        T(v, 14.sp)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { T(label, 12.sp, C.Sub) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun String.toD(): Double? = replace(',', '.').trim().toDoubleOrNull()
