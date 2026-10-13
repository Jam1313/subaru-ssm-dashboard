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
import com.composables.icons.lucide.TriangleAlert
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Alerts
import kotlinx.coroutines.delay

/** Over every screen: the shift light, the latest warning, the turbo cool-down countdown. */
@Composable
fun BoxScope.DriverOverlays() {
    ShiftLight()
    // top centre, under the header: the latest warning, then the turbo cool-down
    androidx.compose.foundation.layout.Column(
        Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        AlertBanner(Modifier)
        TurboChip(Modifier)
    }
}

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
        if (alert.level != Alerts.Level.DANGER) { delay(9000); if (Alerts.current.value == alert) Alerts.dismiss() }
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
