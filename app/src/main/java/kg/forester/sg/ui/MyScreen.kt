package kg.forester.sg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Lucide
import kg.forester.sg.data.MyBoard
import kg.forester.sg.data.Repo
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.TileCfg

/** Every value «Мой экран» can show (gaugeable), the extra cards, the SensorBox inputs set up in the settings. */
@Composable
private fun myCards(): CardNames {
    val slots by SensorBox.slots.collectAsState()
    return Metrics.builtIn.associate { it.id to (it.title to it.icon) } +
        slots.map(Metrics::ofBox).associate { it.id to (it.title to it.icon) } + ExtraCards +
        kg.forester.sg.data.Relays.channels.collectAsState().value.associate { "outw_${it.ch}" to (it.name to outIcon(it.icon)) }
}

private fun myRowWeight(t: TileCfg) = when {
    t.style.isNotEmpty() -> 1.6f
    t.id.startsWith("outw_") -> 1.0f
    t.id in kg.forester.sg.data.EXTRA_CARDS -> 1.1f
    else -> 0.8f
}

private val MyEdit by lazy {
    BoardEdit(MyBoard, gaugeable = MyBoard.defaults.map { it.id }.filter { it !in kg.forester.sg.data.EXTRA_CARDS && !it.startsWith("outw_") }.toSet(), rowWeight = ::myRowWeight)
}

/**
 * «Мой экран»: the driver picks what to see and how (a number, a bar or a dial in any of the designs), the author's
 * screens stay as they are. Long press to edit, as on the home screen.
 */
@Composable
fun MyScreen() {
    val ctx = rememberMetricCtx()
    val avail by Repo.rrAvailable.collectAsState()
    val layout by Repo.myTiles.collectAsState()
    val names = myCards()
    Column(Modifier.fillMaxSize()) {
        if (layout.isEmpty()) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Hand, null, tint = C.Violet, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            T("Это ваш экран: нажмите и удерживайте любую карточку, чтобы добавить свои показания, выбрать стрелочный прибор и расставить их", 12.sp, C.Sub)
        }
        BoardScreen(MyEdit, names, ::myRowWeight) { t, m ->
            if (t.id.startsWith("outw_")) { OutputWidget(t.id, m); return@BoardScreen }
            val info = "home.${t.id}"
            val ind = Metrics.of(t.id, avail)
            if (ind == null) {
                if (!ExtraCard(t.id, info, m)) Panel(m) { T(names[t.id]?.first ?: "Датчик не настроен", 13.sp, C.Sub) }
                return@BoardScreen
            }
            val v = ind.read(ctx)
            ValueCard(CardValue(ind.icon, ind.title, v, ind.min, ind.max, ind.unit, ind.format, info, ind.warnAbove), t.style, m) {
                MiniGauge(ind.icon, ind.title, v, ind.min, ind.max, ind.unit, ind.format, m, info = info)
            }
        }
    }
}
