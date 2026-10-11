package kg.forester.sg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.service.Fyt

/** Music (stock player / Bluetooth) and FM radio, replacing the Teyes launcher widgets. */
@Composable
fun MediaScreen() {
    val m by Fyt.music.collectAsState()
    val r by Fyt.radio.collectAsState()
    val app by Fyt.appId.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // music
        Panel(Modifier.weight(1f).fillMaxHeight(), padding = 20.dp) {
            PanelTitle(if (m.source == "bt") Lucide.Bluetooth else Lucide.Music, if (m.source == "bt") "Bluetooth-музыка" else "Музыка") {
                SmallButton("Открыть") { Fyt.openMusic() }
            }
            Spacer(Modifier.weight(1f))
            T(m.title.ifBlank { "Нет трека" }, 28.sp, weight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            T(m.artist.ifBlank { " " }, 17.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(18.dp))
            val frac = if (m.totalMs > 0) m.posMs.toFloat() / m.totalMs else 0f
            ProgressBar(frac, C.Accent, Modifier.fillMaxWidth(), 6.dp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                T(time(m.posMs), 12.sp, C.Sub, modifier = Modifier.weight(1f))
                T(time(m.totalMs), 12.sp, C.Sub)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Lucide.SkipBack, 64.dp) { Fyt.prev() }
                RoundButton(if (m.playing) Lucide.Pause else Lucide.Play, 84.dp, accent = true) { Fyt.playPause() }
                RoundButton(Lucide.SkipForward, 64.dp) { Fyt.next() }
            }
        }
        // radio
        Panel(Modifier.weight(1f).fillMaxHeight(), padding = 20.dp) {
            PanelTitle(Lucide.Radio, "Радио") {
                SmallButton("Открыть") { Fyt.openRadio() }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(110.dp).clip(RoundedCornerShape(16.dp)).background(C.CardHi), contentAlignment = Alignment.Center) {
                    val logo = r.logo
                    if (logo != null) Image(logo.asImageBitmap(), r.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Lucide.Radio, null, tint = C.Sub, modifier = Modifier.size(42.dp))
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        T(r.freqText, 46.sp, weight = FontWeight.Bold)
                        T(if (r.isFm || r.band == -1) " МГц" else " кГц", 15.sp, C.Sub, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    T(r.name.ifBlank { "—" }, 18.sp, maxLines = 1)
                    T("${r.bandText}${if (r.preset > 0) " · пресет ${r.preset}" else ""}${if (app == 1) " · играет" else ""}", 12.sp, C.Sub)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Lucide.ChevronsLeft, 58.dp) { Fyt.radioSeek(false) }
                RoundButton(Lucide.SkipBack, 58.dp) { Fyt.radioPreset(false) }
                RoundButton(Lucide.Radio, 72.dp, accent = app != 1) { Fyt.ensureRadio() }
                RoundButton(Lucide.SkipForward, 58.dp) { Fyt.radioPreset(true) }
                RoundButton(Lucide.ChevronsRight, 58.dp) { Fyt.radioSeek(true) }
            }
            Spacer(Modifier.height(8.dp))
            T("«<<» и «>>» — поиск станции, «|<» и «>|» — пресеты", 11.sp, C.Sub)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, size: Dp, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (accent) C.Accent else androidx.compose.ui.graphics.SolidColor(C.CardHi))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(size * 0.42f)) }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) { T(text, 12.sp, C.Sub) }
}

private fun time(ms: Long): String {
    val s = (if (ms > 100_000_000) ms / 1000 else ms) / 1000 // tolerate µs/ms
    return "%d:%02d".format(s / 60, s % 60)
}
