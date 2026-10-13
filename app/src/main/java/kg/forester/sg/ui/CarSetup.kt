package kg.forester.sg.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Car
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Usb
import kg.forester.sg.data.CarModels
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.SsmTrace

private val adapters = listOf("kkl" to "KKL-кабель", "op2" to "OpenPort 2.0", "elm_bt" to "Bluetooth OBD")
private val gearboxes = listOf("at" to "Автомат (АКПП)", "mt" to "Механика")
private val buses = listOf("auto" to "Авто", "kline" to "K-Line", "can" to "CAN")

/** Settings tab: car model, header text, diagnostic adapter and bus, UI scale, exchange log. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarAdapterSettings() {
    val s = Repo.settings
    Repo.carVersion.collectAsState().value // re-read the settings below when they change
    val msg by Repo.linkMessage.collectAsState()
    val init by Repo.ecuInit.collectAsState()
    val polled by Repo.pollAddresses.collectAsState()
    val scale by Repo.uiScale.collectAsState()
    var adapter by remember { mutableStateOf(s.adapter) }
    var bus by remember { mutableStateOf(s.bus) }
    var title by remember { mutableStateOf(s.carTitle) }
    var sub by remember { mutableStateOf(s.carSub) }
    var trace by remember { mutableStateOf(s.traceEnabled) }
    var gearbox by remember { mutableStateOf(s.gearbox) }
    var demo by remember { mutableStateOf(s.demo) }
    var awd by remember { mutableStateOf(s.awd) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Car, "Автомобиль")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CarModels.all.forEach { m ->
                    Chip(m.name, s.carModel == m.id, {
                        s.applyModel(m)
                        title = m.title; sub = m.sub; bus = m.bus
                    })
                }
            }
            Spacer(Modifier.height(6.dp))
            T(CarModels.byId(s.carModel).note, 12.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            TextBox("Название в шапке", title) { title = it; s.carTitle = it }
            Spacer(Modifier.height(8.dp))
            TextBox("Подпись (год, двигатель)", sub) { sub = it; s.carSub = it }
            Spacer(Modifier.height(14.dp))
            T("Коробка", 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gearboxes.forEach { (k, l) -> Chip(l, gearbox == k, { gearbox = k; s.gearbox = k }) }
            }
            if (gearbox == "at") {
                Spacer(Modifier.height(10.dp))
                T("Полный привод АКПП (для оценки перед:зад)", 13.sp, C.Sub)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("mpt" to "MP-T (перед, до 50:50)", "vtd" to "VTD (45:55)").forEach { (k, l) ->
                        Chip(l, awd == k, { awd = k; s.awd = k })
                    }
                }
                T("MP-T: большинство 4EAT (Forester SG, Impreza). VTD: 5EAT турбо Legacy/Outback и др.", 11.sp, C.Sub)
            }
            Spacer(Modifier.height(16.dp))
            T("3D-модель на главном экране", 15.sp)
            Spacer(Modifier.height(6.dp))
            val car3d by Repo.car3d.collectAsState()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CarModels3d.forEach { (k, l) -> Chip(l, car3d == k, { s.car3d = k }) }
            }
            Spacer(Modifier.height(10.dp))
            T("Цвет кузова", 13.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            val paintSel by Repo.carPaint.collectAsState()
            val anyColour = car3dAnyColour(ctx, car3d)
            var picker by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CarPaints.forEach { cp ->
                    val on = paintSel == cp.id
                    val usable = anyColour || !cp.needsWhite
                    Box(
                        Modifier.size(34.dp).alpha(if (usable) 1f else 0.3f).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(cp.swatch)
                            .border(if (on) 3.dp else 1.dp, if (on) C.Violet else C.Border, androidx.compose.foundation.shape.CircleShape)
                            .clickable(enabled = usable) { s.carPaint = cp.id },
                    )
                }
                // any colour: rainbow swatch opens the picker
                val custom = paintSel.startsWith("custom:")
                Box(
                    Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.sweepGradient(
                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                        .border(if (custom) 3.dp else 1.dp, if (custom) C.Violet else C.Border, androidx.compose.foundation.shape.CircleShape)
                        .clickable { picker = !picker },
                )
            }
            T(carPaint(paintSel).name + if (!anyColour) " · белый и серебро есть у модели SH" else "", 11.sp, C.Sub)
            if (picker) {
                Spacer(Modifier.height(8.dp))
                ColourPicker(paintSel.removePrefix("custom:").toIntOrNull(16) ?: 0x2563EB) { rgb ->
                    s.carPaint = "custom:%06X".format(rgb and 0xFFFFFF); picker = false
                }
            }
            if (car3dDir(ctx, car3d) == null) {
                Spacer(Modifier.height(4.dp))
                T("Модель $car3d ещё готовится, пока показывается SG5.", 11.sp, C.Orange)
            }
            Spacer(Modifier.height(16.dp))
            T("Масштаб интерфейса", 15.sp)
            T("Если на вашей магнитоле цифры обрезаются или всё слишком крупное", 11.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Авто", 80 to "80%", 90 to "90%", 100 to "100%", 110 to "110%", 120 to "120%").forEach { (v, l) ->
                    Chip(l, scale == v, { s.uiScale = v })
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Usb, "Адаптер")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                adapters.forEach { (k, l) -> Chip(l, adapter == k, { adapter = k; s.adapter = k }) }
            }
            Spacer(Modifier.height(6.dp))
            T(
                when (adapter) {
                    "op2" -> "Tactrix OpenPort 2.0 или китайский клон. Умеет K-Line и CAN."
                    "elm_bt" -> "Bluetooth-свисток ELM327 (β). CAN работает (SH 2009+ и новее). K-Line только у оригинальных " +
                        "ELM327 v1.4+, многие клоны не умеют. Данные обновляются примерно раз в секунду."
                    else -> "Простой USB KKL (VAG-COM 409.1, CH340/FTDI) или Tactrix OpenPort 1.3. Только K-Line."
                },
                12.sp, C.Sub,
            )
            if (adapter == "elm_bt") {
                Spacer(Modifier.height(10.dp))
                BluetoothPicker()
            }
            Spacer(Modifier.height(14.dp))
            val multiBus = adapter != "kkl"
            T("Шина", 15.sp, if (multiBus) C.Text else C.Sub)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                buses.forEach { (k, l) ->
                    Chip(l, multiBus && bus == k, { if (multiBus) { bus = k; s.bus = k } })
                }
            }
            Spacer(Modifier.height(6.dp))
            T(
                if (multiBus) "«Авто»: сначала K-Line, если ЭБУ молчит, то CAN (Forester SH 2009+)."
                else "KKL-кабель работает только по K-Line.",
                12.sp, C.Sub,
            )
            Spacer(Modifier.height(14.dp))
            InfoLine("Связь", msg)
            InfoLine("SSM ID / ROM ID", init?.let { "${it.ssmId} / ${it.romId}" } ?: "—")
            InfoLine("Параметров опрашивается", if (polled > 0) "$polled из 23" else "—")
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Журнал обмена (для тестов)", 15.sp)
                    T("Пишет всё, что идёт через адаптер, в ${SsmTrace.path ?: "logs/ssm_trace.txt"}. Пришлите файл, если не работает.", 11.sp, C.Sub)
                }
                Switch(trace, { trace = it; s.traceEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(8.dp))
            ActionButton("Очистить журнал", true) { SsmTrace.clear() }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Демо-данные", 15.sp)
                    T("Показывает придуманную поездку без машины, чтобы посмотреть экраны. Поездки и расход не записываются.", 11.sp, C.Sub)
                }
                Switch(demo, { demo = it; s.demo = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(8.dp))
            T("Адаптер и шина применяются при следующем подключении (переподключите кабель или подождите).", 11.sp, C.Sub)
        }
    }
}

/** First start: ask which car and which adapter, everything else stays at the defaults. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstRunSetup(onDone: () -> Unit) {
    val s = Repo.settings
    var model by remember { mutableStateOf(s.carModel) }
    var adapter by remember { mutableStateOf(s.adapter) }
    var gearbox by remember { mutableStateOf(s.gearbox) }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6070A14))
            .clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 640.dp).padding(16.dp).verticalScroll(rememberScrollState()), padding = 22.dp) {
            T("Добро пожаловать", 24.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            T("Выберите машину и адаптер. Потом это можно поменять в Настройках → «Авто и адаптер».", 13.sp, C.Sub)
            Spacer(Modifier.height(16.dp))
            T("Машина", 15.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CarModels.all.forEach { m -> Chip(m.name, model == m.id, { model = m.id }) }
            }
            Spacer(Modifier.height(4.dp))
            T(CarModels.byId(model).note, 12.sp, C.Sub)
            Spacer(Modifier.height(14.dp))
            T("Коробка", 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gearboxes.forEach { (k, l) -> Chip(l, gearbox == k, { gearbox = k }) }
            }
            Spacer(Modifier.height(14.dp))
            T("Адаптер", 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                adapters.forEach { (k, l) -> Chip(l, adapter == k, { adapter = k }) }
            }
            Spacer(Modifier.height(18.dp))
            ActionButton("Готово", true) {
                s.applyModel(CarModels.byId(model))
                s.adapter = adapter
                s.gearbox = gearbox
                s.setupDone = true
                onDone()
            }
        }
    }
}

/** Paired Bluetooth devices to pick the OBD adapter from (pairing itself is done in the phone's settings). */
@OptIn(ExperimentalLayoutApi::class)
@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun BluetoothPicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s = Repo.settings
    fun granted() = android.os.Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    var chosen by remember { mutableStateOf(s.btAddress) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok = it }
    if (!ok) {
        ActionButton("Разрешить Bluetooth", true) { ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }
        return
    }
    val bt = (ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    val devices = runCatching { bt?.bondedDevices?.toList() }.getOrNull().orEmpty()
        .sortedByDescending { d -> listOf("OBD", "ELM", "V-LINK", "VLINK", "KONNWEI", "VGATE").any { (d.name ?: "").uppercase().contains(it) } }
    if (bt == null) { T("На устройстве нет Bluetooth", 12.sp, C.Orange); return }
    if (!bt.isEnabled) T("Bluetooth выключен", 12.sp, C.Orange)
    if (devices.isEmpty()) {
        T("Нет сопряжённых устройств. Сопрягите адаптер в настройках Bluetooth телефона (PIN обычно 1234 или 0000).", 12.sp, C.Sub)
        return
    }
    T("Адаптер", 13.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        devices.forEach { d ->
            Chip(d.name ?: d.address, chosen == d.address, {
                chosen = d.address; s.btAddress = d.address; s.btName = d.name ?: d.address
            })
        }
    }
}

/** Hue / saturation / brightness sliders with a preview; [onApply] gets 0xRRGGBB. */
@Composable
private fun ColourPicker(start: Int, onApply: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start or 0xFF000000.toInt(), it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))
    val sliderColors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
    )
    @Composable
    fun Track(brush: androidx.compose.ui.graphics.Brush, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(brush))
            androidx.compose.material3.Slider(value, set, valueRange = range, colors = sliderColors)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        T("Оттенок", 12.sp, C.Sub)
        Track(androidx.compose.ui.graphics.Brush.horizontalGradient(
            (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }), h, 0f..360f) { h = it }
        T("Насыщенность", 12.sp, C.Sub)
        Track(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(
            Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0f, v))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v))))),
            sat, 0f..1f) { sat = it }
        T("Яркость", 12.sp, C.Sub)
        Track(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(
            Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, 1f))))), v, 0f..1f) { v = it }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(rgb)).border(1.dp, C.Border, RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            T("#%06X".format(rgb and 0xFFFFFF), 14.sp, modifier = Modifier.weight(1f))
            ActionButton("Применить", true) { onApply(rgb) }
        }
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        T(k, 13.sp, C.Sub, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        T(v, 13.sp)
    }
}

@Composable
private fun TextBox(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { T(label, 12.sp, C.Sub) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
