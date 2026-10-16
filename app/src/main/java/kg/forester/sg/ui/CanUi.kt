package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ListPlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import kg.forester.sg.data.CanChannel
import kg.forester.sg.data.CanChannels
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.CanMonitor
import kg.forester.sg.ssm.LinkDash
import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.Slcan

/**
 * The CANable part of «Авто и адаптер»: the bus speed (as set for the ECU's dash CAN), what goes on the bus, and the
 * driver's own values off it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanablePanel() {
    val s = Repo.settings
    var kbit by remember { mutableStateOf(s.canKbit) }
    var monitor by remember { mutableStateOf(false) }
    var channels by remember { mutableStateOf(false) }
    T("CANable: скорость шины", 15.sp)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Slcan.SPEEDS.forEach { k -> Chip(if (k >= 1000) "1 Мбит" else "$k кбит", kbit == k, { kbit = k; s.canKbit = k }) }
    }
    Spacer(Modifier.height(4.dp))
    T("Та же, что у CAN-шины ЭБУ, к которой подключён адаптер (у Link в PCLink: ECU Controls, CAN Setup). Потоки Link " +
        "Dash2Pro и Generic Dash приложение находит само; другие данные, например ЕГТ в своём потоке, добавляются в «Свои значения»", 11.sp, C.Sub)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Что идёт по CAN", false, { monitor = true }, Lucide.Activity)
        Chip("Свои значения из CAN", false, { channels = true }, Lucide.ListPlus)
    }
    if (monitor) CanMonitorDialog { monitor = false }
    if (channels) CanChannelsDialog(null) { channels = false }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun DialogBox(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(860.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(16.dp),
        ) { content() }
    }
}

/** Every id on the bus with its bytes and rate; a tap starts an own value from that id. */
@Composable
fun CanMonitorDialog(onClose: () -> Unit) {
    val rows by CanMonitor.rows.collectAsState()
    var start by remember { mutableStateOf<CanChannel?>(null) }
    start?.let { c -> CanChannelsDialog(c) { start = null }; return }
    DialogBox(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Что идёт по CAN", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(4.dp))
        val link = LinkDash.id
        T((if (link != null) "Поток Link ${LinkDash.format?.title} найден на ID $link (0x%03X). ".format(link) else "") +
            "Нажмите на строку, чтобы взять из неё своё значение. Где лежит нужное: меняйте его на машине (газ, прогрев) и смотрите, какие байты меняются",
            12.sp, C.Sub)
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) T("Кадров нет. CANable подключён, адаптер в настройках CANable (или Авто), скорость шины верная, зажигание включено?", 14.sp, C.Sub)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                        .clickable {
                            start = CanChannel(CanChannels.newId(), "", r.id, r.ext, r.mux ?: -1, if (r.mux != null) 2 else 0, 2,
                                bigEndian = r.mux == null, signed = false, formula = "x", units = "", decimals = 0, role = "")
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Text(r.idText, Modifier.width(150.dp), color = C.Violet, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    androidx.compose.material3.Text(r.bytesText, Modifier.weight(1f), color = C.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    T("%.0f Гц".format(r.hz), 12.sp, C.Sub, modifier = Modifier.width(70.dp))
                }
            }
        }
    }
}

/** The own values off the bus; [start] opens the form for a new one at once (from the monitor). */
@Composable
fun CanChannelsDialog(start: CanChannel?, onClose: () -> Unit) {
    Repo.customVersion.collectAsState().value
    val list = CanChannels.list()
    var editing by remember { mutableStateOf(start) }
    DialogBox(onClose) {
        val e = editing
        if (e != null) {
            CanChannelForm(e, onDone = { editing = null; if (start != null) onClose() })
            return@DialogBox
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Свои значения из CAN", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Добавить", false, {
                editing = CanChannel(CanChannels.newId(), "", 1004, false, -1, 0, 2, true, false, "x", "", 0, "")
            }, Lucide.Plus)
            Spacer(Modifier.width(8.dp))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(6.dp))
        T("Значение из потока ЭБУ по ID и байтам, как в настройках дашборда: например ЕГТ, которую добавили в свой поток Link. " +
            "Появится на «Датчиках», в логе и в выборе виджетов; с ролью ЕГТ или давление масла по нему работают предупреждения", 12.sp, C.Sub)
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) T("Пока ничего не добавлено.", 14.sp, C.Sub)
        val live = CanChannels.values()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            list.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                        .clickable { editing = c }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T(c.name.ifBlank { c.id }, 15.sp)
                        T(listOfNotNull(c.idText(), c.formula.takeIf { it != "x" },
                            CanChannel.ROLES.firstOrNull { it.first == c.role && c.role.isNotEmpty() }?.let { "роль: ${it.second}" },
                        ).joinToString(" · "), 12.sp, C.Sub, maxLines = 1)
                    }
                    T(live[c.id]?.let { "%.${c.decimals}f %s".format(it, c.units) } ?: "—", 15.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(C.Card).clickable { CanChannels.remove(c.id) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.Trash2, "Удалить", tint = C.Red, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CanChannelForm(start: CanChannel, onDone: () -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var idText by remember { mutableStateOf("%X".format(start.canId)) }
    var ext by remember { mutableStateOf(start.ext) }
    var mux by remember { mutableStateOf(if (start.mux >= 0) start.mux.toString() else "") }
    var byte by remember { mutableStateOf(start.byte) }
    var len by remember { mutableStateOf(start.len) }
    var be by remember { mutableStateOf(start.bigEndian) }
    var signed by remember { mutableStateOf(start.signed) }
    var formula by remember { mutableStateOf(start.formula) }
    var units by remember { mutableStateOf(start.units) }
    var dec by remember { mutableStateOf(start.decimals) }
    var role by remember { mutableStateOf(start.role) }
    val canId = idText.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
    val muxN = mux.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val error = when {
        name.isBlank() -> "Введите название"
        canId == null || canId > (if (ext) 0x1FFFFFFF else 0x7FF) -> "ID в шестнадцатеричном виде: 3E8 для 1000 (у Link ID в PCLink десятичный)"
        mux.isNotBlank() && (muxN == null || muxN !in 0..255) -> "Номер кадра: число 0–255 или пусто"
        byte + len > 8 -> "Значение выходит за 8 байт кадра"
        RrExpr.parse(formula.ifBlank { "x" }) == null -> "Формула: x, числа, + − * / и скобки"
        else -> null
    }
    val draft = if (canId != null) CanChannel(start.id, name, canId, ext, muxN ?: -1, byte, len, be, signed, formula.ifBlank { "x" }, units, dec, role) else null
    val rows by CanMonitor.rows.collectAsState()
    val row = draft?.let { d -> rows.firstOrNull { it.id == d.canId && it.ext == d.ext && (d.mux < 0 || it.mux == d.mux) } }
    val preview = row?.let { r -> draft.raw(r.data)?.let { raw -> raw to draft.decode(r.data) } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(if (start.name.isEmpty()) "Новое значение из CAN" else "Значение ${start.id}", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Отмена", false, onDone)
            Spacer(Modifier.width(8.dp))
            Chip("Сохранить", error == null, {
                if (error == null && draft != null) { CanChannels.put(draft.copy(name = name.trim(), units = units.trim(), formula = formula.trim().ifBlank { "x" })); onDone() }
            })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField("Название", name, Modifier.weight(1.4f)) { name = it }
            FormField("ID (hex)", idText, Modifier.weight(0.8f), KeyboardType.Ascii, KeyboardCapitalization.Characters) { idText = it }
            FormField("Кадр (байт 0 =)", mux, Modifier.weight(0.8f), KeyboardType.Number) { mux = it }
            FormField("Формула", formula, Modifier.weight(1f), KeyboardType.Ascii) { formula = it }
            FormField("Единицы", units, Modifier.weight(0.6f)) { units = it }
        }
        Spacer(Modifier.height(10.dp))
        Labeled("ID") { Chip("11 бит", !ext, { ext = false }); Chip("29 бит", ext, { ext = true }) }
        Labeled("Первый байт") { (0..7).forEach { n -> Chip("$n", byte == n, { byte = n }) } }
        Labeled("Длина") { listOf(1, 2, 4).forEach { n -> Chip("$n байт", len == n, { len = n }) } }
        Labeled("Порядок байт") { Chip("младший первым (Intel)", !be, { be = false }); Chip("старший первым (Motorola)", be, { be = true }) }
        Labeled("Знак") { Chip("без знака", !signed, { signed = false }); Chip("со знаком", signed, { signed = true }) }
        Labeled("Знаков после запятой") { (0..3).forEach { n -> Chip("$n", dec == n, { dec = n }) } }
        Labeled("Роль") { CanChannel.ROLES.forEach { (k, l) -> Chip(l, role == k, { role = k }) } }
        Spacer(Modifier.height(6.dp))
        T(when {
            error != null -> error
            row == null -> "Сейчас этот ID на шине не виден: значение проверится, когда пойдут кадры"
            preview == null -> "Кадр ${row.bytesText}: в нём нет этих байт"
            else -> "Сейчас: кадр ${row.bytesText} → x = ${preview.first} → " + (preview.second?.let { "%.${dec}f %s".format(it, units) } ?: "формула не считается")
        }, 13.sp, if (error != null) C.Orange else C.Text)
        Spacer(Modifier.height(4.dp))
        T("Пример: ЕГТ в своём потоке Link на ID 1004 (3EC), байты 0–1, старший первым, формула x (если в PCLink множитель 1). " +
            "В компаунд-потоке (как Generic Dash) у каждого кадра свой номер в байте 0: впишите его в «Кадр»", 12.sp, C.Sub)
    }
}
