package kg.forester.sg.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Fan
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Snowflake
import com.composables.icons.lucide.TriangleAlert
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Alerts
import kotlinx.coroutines.delay

/**
 * Over every screen: the shift light, and in the compact layout (split screen, phone) the latest warning and the
 * turbo cool-down. The full layout shows those in the header and the bottom bar instead ([floating] = false),
 * so nothing covers the tabs and the content.
 */
@Composable
fun BoxScope.DriverOverlays(floating: Boolean = true) {
    ShiftLight()
    if (!floating) return
    androidx.compose.foundation.layout.Column(
        Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        AlertBanner(Modifier)
        TurboChip(Modifier)
    }
}

/** How long a warning stays: car notifications are glanceable and brief; danger stays until tapped. */
const val ALERT_SHOW_MS = 6000L

@Composable
private fun ShiftLight() {
    val on by Repo.shift.collectAsState()
    if (!on) return
    val t = rememberInfiniteTransition(label = "shift")
    val a by t.animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "shiftA")
    Box(
        Modifier.fillMaxSize().border(
            14.dp, Brush.horizontalGradient(listOf(C.Red.copy(alpha = a), C.Blue.copy(alpha = a), C.Red.copy(alpha = a))),
            RoundedCornerShape(18.dp),
        ),
    )
}

@Composable
private fun AlertBanner(m: Modifier) {
    val a by Alerts.current.collectAsState()
    val alert = a ?: return
    // information and warnings go away on their own, danger stays until tapped
    LaunchedEffect(alert) {
        if (alert.level != Alerts.Level.DANGER) { delay(ALERT_SHOW_MS); if (Alerts.current.value == alert) Alerts.dismiss() }
    }
    val col = when (alert.level) { Alerts.Level.DANGER -> C.Red; Alerts.Level.WARN -> C.Orange; else -> C.Violet }
    Row(
        m.widthIn(max = 760.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF0101422))
            .border(2.dp, col, RoundedCornerShape(14.dp)).clickable { Alerts.dismiss() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.TriangleAlert, null, tint = col, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(alert.text, 15.sp, C.Text, FontWeight.Medium, maxLines = 3)
    }
}

@Composable
private fun TurboChip(m: Modifier) {
    val g by Repo.guard.collectAsState()
    if (g.cooldownSec <= 0) return
    val col = if (g.cooldownSec > 45) C.Orange else C.Green
    Row(
        m.clip(RoundedCornerShape(18.dp)).background(Color(0xF0101422)).border(2.dp, col, RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Fan, null, tint = col, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column {
            T("Турбина остывает", 15.sp, C.Sub)
            T("не глушите ещё ${g.cooldownSec} с", 24.sp, C.Text, FontWeight.Bold)
            Spacer(Modifier.size(4.dp))
            Box(Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.CardHi)) {
                Box(Modifier.fillMaxSize().padding(end = (220 * (1f - (g.heat / 100f).coerceIn(0f, 1f))).dp).background(col))
            }
        }
    }
}

/**
 * The bottom bar's place, taken by the latest warning while it lasts (like a heads-up card: one glance, two lines
 * at most), then the values come back. Tap = hide.
 */
@Composable
fun AlertInBottomBar(alert: Alerts.Alert) {
    LaunchedEffect(alert) {
        if (alert.level != Alerts.Level.DANGER) { delay(ALERT_SHOW_MS); if (Alerts.current.value == alert) Alerts.dismiss() }
    }
    val col = when (alert.level) { Alerts.Level.DANGER -> C.Red; Alerts.Level.WARN -> C.Orange; else -> C.Violet }
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.16f))
            .border(2.dp, col, RoundedCornerShape(14.dp)).clickable { Alerts.dismiss() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.TriangleAlert, null, tint = col, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        T(alert.text, 15.sp, C.Text, FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        T(if (alert.level == Alerts.Level.DANGER) "нажмите, чтобы скрыть" else "скрыть", 12.sp, C.Sub)
    }
}

/**
 * States that last (turbo cool-down, cold engine) as a pill in the header next to the clock: always in the same
 * place, never over the content. Two short lines say what it is and what to do; a tap explains why.
 */
@Composable
fun StatusPill() {
    val g by Repo.guard.collectAsState()
    var info by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // the countdown never hides early: orange while it runs, then green «можно глушить» for a while
    var cooledAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    var wasCounting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(g.cooldownSec > 0) {
        if (g.cooldownSec > 0) { wasCounting = true; cooledAt = 0L }
        else if (wasCounting) {
            wasCounting = false
            cooledAt = System.currentTimeMillis()
            delay(15_000)
            cooledAt = 0L
        }
    }
    val cooled = cooledAt > 0L && g.cooldownSec <= 0
    val turbo = g.cooldownSec > 0 || cooled
    if (!turbo && !g.cold) return
    val col = if (cooled) C.Green else if (turbo) C.Orange else C.Blue
    Row(
        Modifier.padding(end = 22.dp).clip(RoundedCornerShape(18.dp)).background(col.copy(alpha = 0.14f))
            .border(1.5.dp, col, RoundedCornerShape(18.dp)).clickable { info = true }
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (turbo) Lucide.Fan else Lucide.Snowflake, null, tint = col, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column {
            T(if (cooled) "Турбина остыла" else if (turbo) "Турбина остывает" else "Мотор холодный", 11.sp, C.Sub, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(if (cooled) "можно глушить" else if (turbo) "не глушите ещё ${g.cooldownSec} с" else "не выше 3000 об/мин",
                    15.sp, C.Text, FontWeight.SemiBold, maxLines = 1)
                if (turbo && !cooled) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(60.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.CardHi)) {
                        Box(Modifier.fillMaxSize().padding(end = (60 * (1f - (g.heat / 100f).coerceIn(0f, 1f))).dp).background(col))
                    }
                }
            }
        }
    }
    if (info) InfoDialog(
        if (turbo) "Турбина остывает" else "Мотор холодный",
        if (turbo) InfoText(
            what = "Турботаймер: сколько ещё дать мотору поработать на холостых, прежде чем глушить.",
            how = "Приложение копит «жар» турбины по наддуву и оборотам за поездку и считает, сколько ему остывать на " +
                "холостых. Полоса — сколько тепла осталось.",
            norm = "Если заглушить горячую турбину, масло в её подшипнике закипает и коксуется — главная причина " +
                "износа турбин. После спокойной езды хватает 20–30 с, после трассы или активной езды — 1–2 минуты. " +
                "Выключить подсказку: Настройки → «Функции» → «Турботаймер».",
        ) else InfoText(
            what = "Мотор ещё не прогрелся: двигатель лучше не нагружать.",
            how = "По температуре антифриза из ЭБУ: любой EJ заканчивает прогревное обогащение смеси на 80°. Подсказка " +
                "пропадает, когда антифриз дойдёт до 80°.",
            norm = "Холодное масло густое и хуже смазывает: до прогрева держите обороты ниже 3000 и не давите в пол.",
        ),
    ) { info = false }
}
