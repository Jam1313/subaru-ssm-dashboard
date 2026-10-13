package kg.forester.sg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T("Текущие значения", 16.sp, modifier = Modifier.weight(1f))
                InfoButton("Текущие значения")
            }
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
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Основные", tab == 0, { tab = 0 }, Lucide.SlidersHorizontal)
            Chip("Авто и адаптер", tab == 3, { tab = 3 }, Lucide.Car)
            Chip("Кнопки быстрого запуска", tab == 1, { tab = 1 }, Lucide.LayoutGrid)
            Chip("Голос", tab == 2, { tab = 2 }, Lucide.Mic)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> GeneralSettings()
                1 -> ShortcutSettings()
                3 -> CarAdapterSettings()
                else -> VoiceSettings()
            }
        }
    }
}

@Composable
private fun GeneralSettings() {
    val s = Repo.settings
    val init by Repo.ecuInit.collectAsState()
    val msg by Repo.linkMessage.collectAsState()
    var tank by remember { mutableStateOf(fmt(s.tankL, 0)) }
    var price by remember { mutableStateOf(fmt(s.pricePerL, 1)) }
    var currency by remember { mutableStateOf(s.currency) }
    var k by remember { mutableStateOf(fmt(s.fuelK, 3)) }
    var left by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(460.dp).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelTitle(Lucide.Cog, "Настройки")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Объём бака, л (0 — не учитывать)", tank) { tank = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Цена топлива за литр", price) { price = it } }
                    Box(Modifier.width(120.dp)) {
                        OutlinedTextField(
                            value = currency, onValueChange = { currency = it.take(6) }, singleLine = true,
                            label = { T("Валюта", 12.sp, C.Sub) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                                focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                            ),
                        )
                    }
                }
                Field("Коэффициент расхода (калибровка)", k) { k = it }
                Field("Сейчас в баке, л (если знаете точно)", left) { left = it }
            }
            Spacer(Modifier.height(14.dp))
            ActionButton("Сохранить", true) {
                tank.toD()?.let { s.tankL = it }
                price.toD()?.let { s.pricePerL = it }
                s.currency = currency
                k.toD()?.let { if (it in 0.7..1.4) s.fuelK = it }
                left.toD()?.let { s.fuelAtRefillL = it; s.usedSinceRefillL = 0.0; left = "" }
                Repo.touch()
                saved = "Сохранено"
            }
            Spacer(Modifier.height(8.dp))
            T(saved, 13.sp, C.Green)
            Spacer(Modifier.height(14.dp))
            PlateSettings()
            Spacer(Modifier.height(14.dp))
            val navPos by Repo.navPosition.collectAsState()
            T("Панель навигации", 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("left" to "Слева", "right" to "Справа", "top" to "Сверху", "bottom" to "Снизу").forEach { (k, l) ->
                    Chip(l, navPos == k, { Repo.settings.navPosition = k })
                }
            }
            Spacer(Modifier.height(14.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var home by remember { mutableStateOf(isDefaultHome(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Главный экран магнитолы", 15.sp)
                    T("Открывать Subaru Dashboard кнопкой «Домой» вместо штатного лаунчера", 11.sp, C.Sub)
                }
                Switch(home, {
                    setDefaultHome(ctx, it); home = isDefaultHome(ctx)
                }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            T("Автомобиль и ЭБУ", 16.sp)
            Spacer(Modifier.height(10.dp))
            InfoRow("Автомобиль", "${s.carTitle} ${s.carSub}".replace(Regex("\\s+"), " ").trim())
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

/** Licence plate shown on the 3D car: text or an uploaded picture (e.g. with your country's flag). */
@Composable
private fun PlateSettings() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val plate by kg.forester.sg.data.Plate.bitmap.collectAsState()
    LaunchedEffect(Unit) { if (plate == null) kg.forester.sg.data.Plate.load(ctx) }
    var text by remember { mutableStateOf(kg.forester.sg.data.Plate.text(ctx)) }
    var msg by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) msg = if (kg.forester.sg.data.Plate.setImage(ctx, uri)) "Картинка номера загружена" else "Не удалось открыть картинку" }

    T("Номер на 3D-модели", 15.sp)
    Spacer(Modifier.height(6.dp))
    plate?.let {
        androidx.compose.foundation.Image(
            it.asImageBitmap(), "номер",
            Modifier.fillMaxWidth().height(70.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
    Spacer(Modifier.height(6.dp))
    Field("Текст номера", text) { text = it }
    T("Номер с флагом или регионом можно загрузить картинкой (фото или скан своего номера).", 11.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { ActionButton("Применить текст", text.isNotBlank()) { kg.forester.sg.data.Plate.setText(ctx, text); msg = "Номер обновлён" } }
        Box(Modifier.weight(1f)) { ActionButton("Загрузить картинку", true) { picker.launch("image/*") } }
    }
    if (msg.isNotEmpty()) T(msg, 12.sp, C.Green)
}
