package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.Repo
import kg.forester.sg.data.TileCfg

/** Edits of the home layout, made right on the home screen (long press on a card). */
object HomeEdit {
    fun list() = HomeLayout.parse(Repo.homeTiles.value)
    private fun save(l: List<TileCfg>) { Repo.settings.homeTiles = HomeLayout.format(l) }

    /** Swap with the nearest shown card before (-1) or after (+1). */
    fun move(id: String, dir: Int) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        var j = i + dir
        while (j in l.indices && !l[j].on) j += dir
        if (i < 0 || j !in l.indices) return
        val t = l[i]; l[i] = l[j]; l[j] = t
        save(l)
    }

    fun resize(id: String, delta: Int) = save(list().map { if (it.id == id) it.copy(span = (it.span + delta).coerceIn(1, 3)) else it })

    fun hide(id: String) = save(list().map { if (it.id == id) it.copy(on = false) else it })

    /** Shows a hidden card at the end of the grid. */
    fun add(id: String) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = l.removeAt(i).copy(on = true)
        l.add(l.indexOfLast { it.on } + 1, t)
        save(l)
    }

    fun reset() { Repo.settings.homeTiles = ""; Repo.settings.clusterSize = 1 }
}

/** Toolbar shown above the grid while editing. */
@Composable
fun HomeEditBar(onDone: () -> Unit) {
    val cluster by Repo.clusterSize.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card)
            .border(1.dp, C.Purple, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T("3D:", 14.sp, C.Sub)
        listOf(0 to "Скрыть", 1 to "Обычный", 2 to "Большой", 3 to "Макс.").forEach { (v, l) ->
            Chip(l, cluster == v, { Repo.settings.clusterSize = v })
        }
        Spacer(Modifier.weight(1f))
        Box {
            Chip("Добавить виджет", false, { adding = true }, Lucide.Plus)
            if (adding) AddMenu { adding = false }
        }
        Chip("Сбросить", false, { HomeEdit.reset() })
        Chip("Готово", true, onDone)
    }
}

@Composable
private fun AddMenu(onClose: () -> Unit) {
    val hidden = HomeEdit.list().filter { !it.on }
    Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, 120), onDismissRequest = onClose) {
        Column(
            Modifier.width(260.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(14.dp)).padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (hidden.isEmpty()) T("Все виджеты уже на экране", 14.sp, C.Sub, modifier = Modifier.padding(16.dp))
            hidden.forEach { t ->
                val (title, icon) = HomeTileInfo[t.id] ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clickable { HomeEdit.add(t.id); onClose() }.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = C.Violet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    T(title, 15.sp)
                }
            }
        }
    }
}

/** Controls drawn over a card while editing: move, narrower/wider, remove. */
@Composable
fun BoxScope.TileEditOverlay(cfg: TileCfg) {
    Box(
        Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).background(Color(0x66070A14))
            .border(2.dp, C.Purple, RoundedCornerShape(14.dp)),
    )
    Row(
        Modifier.align(Alignment.Center).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditButton(Lucide.ChevronLeft) { HomeEdit.move(cfg.id, -1) }
        EditButton(Lucide.Minus, cfg.span > 1) { HomeEdit.resize(cfg.id, -1) }
        T("${cfg.span}", 15.sp, weight = FontWeight.Bold)
        EditButton(Lucide.Plus, cfg.span < 3) { HomeEdit.resize(cfg.id, 1) }
        EditButton(Lucide.ChevronRight) { HomeEdit.move(cfg.id, 1) }
        EditButton(Lucide.X, tint = C.Red) { HomeEdit.hide(cfg.id) }
    }
}

@Composable
private fun EditButton(icon: ImageVector, enabled: Boolean = true, tint: Color = C.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.CardHi)
            .border(1.dp, C.Border, RoundedCornerShape(9.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) tint else C.Border, modifier = Modifier.size(18.dp)) }
}

