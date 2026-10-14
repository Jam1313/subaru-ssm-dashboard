package kg.forester.sg.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import kg.forester.sg.data.Repo

/** One value on the bottom bar; [bar] = with a progress bar (takes more room). */
data class BottomSlot(val id: String, val bar: Boolean)

object BottomBarCfg {
    const val MAX = 6
    const val DEFAULT = "range:1,coolant:1,battery:1,intake:0,altitude:0"

    fun parse(s: String): List<BottomSlot> = s.split(',').mapNotNull { e ->
        val f = e.trim().split(':')
        f.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { BottomSlot(it, f.getOrNull(1) == "1") }
    }.distinctBy { it.id }.take(MAX)

    fun format(l: List<BottomSlot>) = l.joinToString(",") { "${it.id}:${if (it.bar) 1 else 0}" }
}

/**
 * The strip under every screen. What it shows is up to the driver: long press opens the editor with every value
 * of the app (and the parameters ticked on «Датчики»).
 */
@Composable
fun BottomStatusBar() {
    // a warning takes the bar's place for a few seconds (see AlertInBottomBar), then the values come back
    val alert by kg.forester.sg.service.Alerts.current.collectAsState()
    androidx.compose.animation.AnimatedContent(
        alert,
        transitionSpec = {
            (androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn()) togetherWith
                (androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut())
        },
        label = "bottomAlert",
    ) { a -> if (a != null) AlertInBottomBar(a) else BottomValues() }
}

@Composable
private fun BottomValues() {
    val cfg by Repo.bottomBar.collectAsState()
    val slots = remember(cfg) { BottomBarCfg.parse(cfg) }
    val avail by Repo.rrAvailable.collectAsState()
    val ctx = rememberMetricCtx()
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp)).background(C.Card)
            .border(1.dp, C.Border, RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures(onLongPress = { editing = true }) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slots.isEmpty()) T("Нижняя панель пуста: нажмите и удерживайте, чтобы выбрать показатели", 13.sp, C.Sub)
        slots.forEach { s ->
            val m = Metrics.of(s.id, avail)
            if (m == null) {
                // a «Датчики» parameter while the car is not connected
                BottomItem(Lucide.ListChecks, s.id.removePrefix("rr:"), "—", null, null, Modifier.weight(0.7f))
                return@forEach
            }
            val v = m.read(ctx)
            val frac = if (!s.bar) null else if (m.id == "range") {
                val tank = Repo.settings.tankL
                if (tank > 0) ((ctx.trip.fuelLeftL ?: 0.0) / tank).toFloat() else null
            } else v?.let { ((it - m.min) / (m.max - m.min)).coerceIn(0f, 1f) } ?: 0f
            val brush = when {
                m.warn(v) -> Brush.horizontalGradient(listOf(C.Orange, C.Red))
                m.id == "range" -> C.Accent
                m.id == "battery" -> Brush.horizontalGradient(listOf(C.Green, C.Green))
                else -> Brush.horizontalGradient(listOf(C.Blue, C.Violet))
            }
            val text = m.text(v) + if (m.unit.isEmpty() || v == null) "" else if (m.unit.startsWith("°")) m.unit else " ${m.unit}"
            BottomItem(m.icon, m.short, text, frac, brush, Modifier.weight(if (s.bar) 1f else 0.7f), m.warn(v))
        }
    }
    if (editing) BottomBarEditor { editing = false }
}

@Composable
private fun BottomItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, frac: Float?, brush: Brush?,
    modifier: Modifier, warn: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (warn) C.Orange else C.Sub, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            T(label, 12.sp, C.Sub, maxLines = 1)
            T(value, 16.sp, if (warn) C.Orange else C.Text, FontWeight.SemiBold, maxLines = 1)
        }
        if (frac != null && brush != null && !frac.isNaN()) {
            Spacer(Modifier.width(10.dp))
            ProgressBar(frac, brush, Modifier.width(90.dp), 6.dp)
        }
    }
}

/** Pick what the bottom bar shows: the chosen values in order (move, bar on/off, remove) and everything to add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBarEditor(onClose: () -> Unit) {
    val cfg by Repo.bottomBar.collectAsState()
    val slots = BottomBarCfg.parse(cfg)
    val avail by Repo.rrAvailable.collectAsState()
    Repo.watchVersion.collectAsState().value
    val all = Metrics.all(avail, Repo.settings.watchIds)
    fun save(l: List<BottomSlot>) { Repo.settings.bottomBar = BottomBarCfg.format(l) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(760.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Нижняя панель", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Как было", false, { Repo.settings.bottomBar = BottomBarCfg.DEFAULT })
                Spacer(Modifier.width(8.dp))
                Chip("Готово", true, onClose)
            }
            Spacer(Modifier.height(4.dp))
            T("До ${BottomBarCfg.MAX} показателей. «Полоска» — шкала рядом с числом, она занимает больше места.", 12.sp, C.Sub)
            Spacer(Modifier.height(10.dp))
            slots.forEachIndexed { i, s ->
                val m = Metrics.of(s.id, avail)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(m?.icon ?: Lucide.ListChecks, null, tint = C.Violet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    T(m?.title ?: s.id, 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Chip("Полоска", s.bar, { save(slots.mapIndexed { j, x -> if (j == i) x.copy(bar = !x.bar) else x }) })
                    Spacer(Modifier.width(6.dp))
                    SmallButton(Lucide.ChevronUp, i > 0) {
                        save(slots.toMutableList().also { l -> l[i] = l[i - 1].also { l[i - 1] = l[i] } })
                    }
                    SmallButton(Lucide.ChevronDown, i < slots.size - 1) {
                        save(slots.toMutableList().also { l -> l[i] = l[i + 1].also { l[i + 1] = l[i] } })
                    }
                    SmallButton(Lucide.X, true, C.Red) { save(slots.filterIndexed { j, _ -> j != i }) }
                }
            }
            Spacer(Modifier.height(10.dp))
            T(if (slots.size >= BottomBarCfg.MAX) "Панель заполнена: уберите что-нибудь, чтобы добавить другое" else "Добавить:", 13.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                all.groupBy { it.group }.forEach { (group, list) ->
                    T(group, 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { m ->
                            val on = slots.any { it.id == m.id }
                            Chip(m.title, on, {
                                if (on) save(slots.filter { it.id != m.id })
                                else if (slots.size < BottomBarCfg.MAX) save(slots + BottomSlot(m.id, false))
                            }, m.icon)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, tint: androidx.compose.ui.graphics.Color = C.Text, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).background(C.Card)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) tint else C.Border, modifier = Modifier.size(16.dp)) }
}
