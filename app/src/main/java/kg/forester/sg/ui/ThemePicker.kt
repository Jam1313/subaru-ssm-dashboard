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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import kg.forester.sg.data.Repo

/** Palette icon for the top bar: opens the [ThemeDialog]. */
@Composable
fun ThemeButton(size: Dp) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { Icon(Lucide.Palette, "Цвет интерфейса", tint = C.Violet, modifier = Modifier.size(size)) }
    if (open) ThemeDialog { open = false }
}

@Composable
fun ThemeDialog(onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Цвет интерфейса", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Готово", true, onClose)
            }
            T("Под подсветку приборов вашей машины, или любой свой", 12.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            ThemeChooser()
        }
    }
}

/**
 * The preset themes plus «Свой»: the colour picker, where the whole interface shows the colour while the sliders
 * move. Leaving without «Применить» goes back to the saved theme.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeChooser() {
    val s = Repo.settings
    val id = C.themeId
    var picker by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { C.applyTheme(s.accentTheme) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AccentTheme.entries.forEach { t ->
            Swatch(Brush.horizontalGradient(listOf(t.second, t.main)), t.title, id == t.id && !picker) {
                picker = false
                s.accentTheme = t.id
            }
        }
        Swatch(
            Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
            "Свой", picker || id.startsWith("custom:"),
        ) {
            if (picker) C.applyTheme(s.accentTheme) // closing the picker drops the preview
            picker = !picker
        }
    }
    if (picker) {
        Spacer(Modifier.height(8.dp))
        val start = id.takeIf { it.startsWith("custom:") }?.removePrefix("custom:")?.toIntOrNull(16)
            ?: (C.theme.main.toArgb() and 0xFFFFFF)
        ColourPicker(start, onChange = { C.theme = UiTheme.of(it) }) { rgb ->
            s.accentTheme = "custom:%06X".format(rgb and 0xFFFFFF)
            picker = false
        }
    }
}

@Composable
private fun Swatch(brush: Brush, title: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).background(if (selected) C.Purple.copy(alpha = 0.28f) else C.CardHi)
            .border(1.dp, if (selected) C.Purple else C.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(brush))
        Spacer(Modifier.width(8.dp))
        T(title, 14.sp)
    }
}
