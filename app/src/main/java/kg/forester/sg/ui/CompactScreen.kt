package kg.forester.sg.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Shortcuts
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Metric(val title: String, val value: String, val unit: String, val frac: Float?, val warn: Boolean = false)

/**
 * Driver panel used when the window is narrow (split screen next to the navigator):
 * big speed / RPM, the key readings in a grid, and the quick-launch buttons.
 */
@Composable
fun CompactScreen() {
    val ctx = LocalContext.current
    val e by Repo.live.collectAsState()
    val t by Repo.tcu.collectAsState()
    val ts by Repo.trip.collectAsState()
    val link by Repo.link.collectAsState()
    val shortcuts by Shortcuts.list.collectAsState()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5000) } }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(10.dp)
    ) {
        val cols = if (maxWidth > 560.dp) 3 else 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("FORESTER SG5", 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                val ok = link == LinkState.CONNECTED
                Icon(if (ok) Lucide.Usb else Lucide.Unplug, null, tint = if (ok) C.Green else C.Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                T(SimpleDateFormat("HH:mm", Locale.US).format(now), 18.sp, weight = FontWeight.SemiBold)
            }

            // hero: speed + rpm + gear
            Panel(Modifier.fillMaxWidth().weight(1.15f), padding = 12.dp) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            T((e.speedKmh ?: 0).toString(), 58.sp, weight = FontWeight.Bold, maxLines = 1)
                            T(" км/ч", 15.sp, C.Sub, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            T(e.rpm?.toString() ?: "—", 22.sp, weight = FontWeight.SemiBold)
                            T(" об/мин", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        ProgressBar(
                            (e.rpm ?: 0) / 7000f,
                            Brush.horizontalGradient(listOf(C.Blue, C.Violet, C.Orange, C.Red)),
                            Modifier.fillMaxWidth(), 7.dp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val range = t?.range
                        AnimatedContent(range to t?.gear, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "g") { (r, g) ->
                            val txt = when (r) {
                                null -> "—"
                                "D", "3", "2", "1" -> if (g != null) "$r$g" else r
                                else -> r
                            }
                            T(txt, if (txt.length > 2) 26.sp else 40.sp, C.Violet, FontWeight.Bold, maxLines = 1)
                        }
                        T("АКПП", 11.sp, C.Sub)
                    }
                }
            }

            // metrics grid
            val per = ts.instPer100
            val metrics = listOf(
                Metric("Наддув", fmtF(e.boostBar, 2), "бар", e.boostBar?.let { it / 1.2f }),
                Metric("Расход", if (per != null) fmt(per) else fmt(ts.instLh), if (per != null) "л/100" else "л/ч", null),
                Metric("Запас хода", fmt(ts.rangeKm, 0), "км", null),
                Metric("ОЖ", e.coolantC?.toString() ?: "—", "°C", e.coolantC?.let { (it - 40) / 70f }, (e.coolantC ?: 0) > 102),
                Metric("ATF", t?.atfC?.toString() ?: "—", "°C", t?.atfC?.let { it / 130f }, (t?.atfC ?: 0) > 110),
                Metric("Напряжение", fmtF(e.batteryV, 1), "В", e.batteryV?.let { (it - 10f) / 5f }, (e.batteryV ?: 13f) < 12f),
                Metric("AFR", fmtF(e.afr, 1), "", e.afr?.let { (it - 10f) / 10f }),
                Metric("Впуск", e.intakeC?.toString() ?: "—", "°C", e.intakeC?.let { (it + 20) / 90f }, (e.intakeC ?: 0) > 55),
                Metric("Детонация", fmtF(e.knockDeg, 1), "°", null, (e.knockDeg ?: 0f) < 0f),
            ).take(if (cols == 3) 9 else 8)
            metrics.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { MetricCard(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            // quick launch
            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.take(4).forEach { sc ->
                    LaunchButton(Shortcuts.icon(sc.icon), sc.label, Modifier.weight(1f)) { Shortcuts.launch(ctx, sc) }
                }
                LaunchButton(Lucide.Maximize2, "Весь экран", Modifier.weight(1f), highlight = true) { Shortcuts.exitSplit(ctx) }
            }
        }
    }
}

@Composable
private fun MetricCard(m: Metric, modifier: Modifier) {
    Panel(modifier, padding = 10.dp) {
        T(m.title, 12.sp, C.Sub, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            T(m.value, 24.sp, if (m.warn) C.Orange else C.Text, FontWeight.Bold, maxLines = 1)
            if (m.unit.isNotEmpty()) T(" ${m.unit}", 11.sp, C.Sub, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
        }
        if (m.frac != null) {
            Spacer(Modifier.height(4.dp))
            ProgressBar(m.frac, if (m.warn) Brush.horizontalGradient(listOf(C.Orange, C.Red)) else C.Accent, Modifier.fillMaxWidth(), 5.dp)
        }
    }
}

@Composable
private fun LaunchButton(icon: ImageVector, label: String, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(if (highlight) C.Purple.copy(alpha = 0.3f) else C.CardHi)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (highlight) C.Violet else C.Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        T(label, 12.sp, C.Text, maxLines = 1)
    }
}
