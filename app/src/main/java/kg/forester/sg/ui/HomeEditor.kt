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
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import kg.forester.sg.data.Board
import kg.forester.sg.data.FreeRect
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Move
import com.composables.icons.lucide.Scaling
import com.composables.icons.lucide.Layers
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kg.forester.sg.data.HomeLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import kg.forester.sg.data.Repo
import kg.forester.sg.data.TileCfg

/**
 * Edits of an editable screen's layout, made right on that screen (long press on a card).
 * [rowWeight] is the relative row height the screen gives a card, used when switching to the free layout.
 */
class BoardEdit(
    val board: Board,
    val gaugeable: Set<String> = emptySet(),
    private val rowWeight: (TileCfg) -> Float = { 1f },
    /** Top and height of the grid's part of the free area (the home grid sits below the 3D block). */
    private val gridBand: () -> Pair<Float, Float> = { 0f to 1f },
    /** Screen-specific changes when switching to the free layout and back (home: split the 3D block). */
    private val toFree: (List<TileCfg>) -> List<TileCfg> = { it },
    private val toGrid: (List<TileCfg>) -> List<TileCfg> = { it },
    private val onReset: () -> Unit = {},
) {
    private val raw get() = Repo.boardFlow(board.key).value
    fun list() = board.parse(raw)
    fun isFree() = board.isFree(raw)
    private fun save(l: List<TileCfg>, free: Boolean = isFree()) = Repo.settings.setBoard(board.key, board.format(l, free))

    /** Free layout on/off; the cards keep the places they had in the grid. */
    fun setFree(on: Boolean) {
        if (on == isFree()) return
        save(if (on) toFree(board.withFreeRects(list(), rowWeight, gridBand())) else toGrid(list()), on)
    }

    /** Puts [id] at [r], on top of the other cards. */
    fun place(id: String, r: FreeRect) {
        val l = list().map { if (it.id == id) it.copy(free = r) else it }.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) l.add(l.removeAt(i))
        save(l)
    }

    /** Draws [id] on top of the others in the free layout. */
    fun toFront(id: String) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        l.add(l.removeAt(i))
        save(l)
    }

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

    fun resize(id: String, delta: Int) =
        save(list().map { if (it.id == id) it.copy(span = (it.span + delta).coerceIn(1, board.maxSpan)) else it })

    fun setStyle(id: String, style: String) = save(list().map { if (it.id == id) it.copy(style = style) else it })

    fun hide(id: String) = save(list().map { if (it.id == id) it.copy(on = false) else it })

    /** Shows a hidden card at the end of the grid. */
    fun add(id: String) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = l.removeAt(i).copy(on = true).let { if (isFree() && it.free == null) it.copy(free = FreeRect(0.35f, 0.3f, 0.3f, 0.4f)) else it }
        l.add(l.indexOfLast { it.on } + 1, t)
        save(l)
    }

    fun reset() { Repo.settings.setBoard(board.key, ""); onReset() }
}

private val ClusterParts = setOf("car3d", "tach", "speedo")

val HomeEdit = BoardEdit(
    HomeLayout.board, HomeLayout.board.defaults.map { it.id }.toSet() - "car3d",
    gridBand = {
        val c = HomeLayout.clusterWeight(Repo.clusterSize.value)
        c / (c + 3f) to 3f / (c + 3f)
    },
    // the 3D block becomes three cards (car, tachometer, speedometer) in the places they had in the band
    toFree = { list ->
        val c = HomeLayout.clusterWeight(Repo.clusterSize.value)
        val band = c / (c + 3f)
        if (band == 0f) list else {
            val dh = (band - 0.04f).coerceAtLeast(0.05f)
            val dw = dh / 2f // the area is about twice as wide as it is tall
            val y = (band - dh) / 2f
            val out = list.map {
                when (it.id) {
                    "car3d" -> it.copy(on = true, free = FreeRect(0f, 0f, 1f, band))
                    "tach" -> if (it.on) it else it.copy(on = true, free = FreeRect(0.012f, y, dw, dh))
                    "speedo" -> if (it.on) it else it.copy(on = true, free = FreeRect(1f - 0.012f - dw, y, dw, dh))
                    else -> it
                }
            }
            out.filter { it.id == "car3d" } + out.filter { it.id != "car3d" } // the car at the bottom
        }
    },
    toGrid = { list -> list.map { if (it.id in ClusterParts) it.copy(on = false) else it } },
) { Repo.settings.clusterSize = 1 }

/** Card names and icons for the "add" menu, by card id. */
typealias CardNames = Map<String, Pair<String, ImageVector>>

/** Toolbar shown above the home grid while editing: the 3D block size, then the common buttons. */
@Composable
fun HomeEditBar(onDone: () -> Unit) {
    val cluster by Repo.clusterSize.collectAsState()
    val free = HomeLayout.board.isFree(Repo.homeTiles.collectAsState().value)
    BoardEditBar(HomeEdit, if (free) HomeTileInfo else HomeTileInfo - "car3d", onDone) {
        if (free) return@BoardEditBar // the 3D block is a card there, sized by dragging
        T("3D:", 14.sp, C.Sub)
        listOf(0 to "Скрыть", 1 to "Обычный", 2 to "Большой", 3 to "Макс.").forEach { (v, l) ->
            Chip(l, cluster == v, { Repo.settings.clusterSize = v })
        }
    }
}

/** Toolbar shown above an editable grid: [leading] screen-specific controls, add, reset, done. */
@Composable
fun BoardEditBar(edit: BoardEdit, names: CardNames, onDone: () -> Unit, leading: @Composable RowScope.() -> Unit = {}) {
    var adding by remember { mutableStateOf(false) }
    val free = edit.board.isFree(Repo.boardFlow(edit.board.key).collectAsState().value)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card)
            .border(1.dp, C.Purple, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        Chip("Сетка", !free, { edit.setFree(false) }, Lucide.LayoutGrid)
        Chip("Свободно", free, { edit.setFree(true) }, Lucide.Move)
        Box {
            Chip("Добавить виджет", false, { adding = true }, Lucide.Plus)
            if (adding) AddMenu(edit, names) { adding = false }
        }
        Chip("Сбросить", false, { edit.reset() })
        Chip("Готово", true, onDone)
    }
}

@Composable
private fun AddMenu(edit: BoardEdit, names: CardNames, onClose: () -> Unit) {
    val hidden = edit.list().filter { !it.on }
    Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, 120), onDismissRequest = onClose) {
        Column(
            Modifier.width(260.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(14.dp)).padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (hidden.isEmpty()) T("Все виджеты уже на экране", 14.sp, C.Sub, modifier = Modifier.padding(16.dp))
            hidden.forEach { t ->
                val (title, icon) = names[t.id] ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clickable { edit.add(t.id); onClose() }.padding(horizontal = 16.dp, vertical = 11.dp),
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

/**
 * Controls drawn over a card while editing: move, narrower/wider, gauge design (cards with one number), remove.
 * In the free layout ([free]) moving and sizing are done by dragging, so only "to front", design and remove stay.
 * Narrow cards get smaller buttons.
 */
@Composable
fun BoxScope.TileEditOverlay(edit: BoardEdit, cfg: TileCfg, title: String = "", free: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    Box(
        Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).background(Color(0x66070A14))
            .border(2.dp, C.Purple, RoundedCornerShape(14.dp)),
    )
    BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
        val gauge = cfg.id in edit.gaugeable
        val count = (if (gauge) 1 else 0) + if (free) 2 else 5
        val narrow = maxWidth < 260.dp || maxHeight < 44.dp
        val gap = if (narrow) 3.dp else 5.dp
        val btn = minOf(32.dp, (maxWidth - 8.dp - gap * (count - 1)) / count, maxHeight - 6.dp)
        Row(
            Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (narrow) 3.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (free) {
                EditButton(Lucide.Layers, btn) { edit.toFront(cfg.id) }
            } else {
                EditButton(Lucide.ChevronLeft, btn) { edit.move(cfg.id, -1) }
                EditButton(Lucide.Minus, btn, cfg.span > 1) { edit.resize(cfg.id, -1) }
                if (!narrow) T("${cfg.span}", 15.sp, weight = FontWeight.Bold)
                EditButton(Lucide.Plus, btn, cfg.span < edit.board.maxSpan) { edit.resize(cfg.id, 1) }
                EditButton(Lucide.ChevronRight, btn) { edit.move(cfg.id, 1) }
            }
            if (gauge) EditButton(Lucide.Gauge, btn, tint = if (cfg.style.isEmpty()) C.Text else C.Violet) { picking = true }
            EditButton(Lucide.X, btn, tint = C.Red) { edit.hide(cfg.id) }
        }
    }
    if (picking) StylePicker(
        "Вид: $title", cfg.style, "Обычный", Modifier, target = "${edit.board.key}/${cfg.id}",
        preview = { st, m -> Dial(62f, 100f, 20f, 1f, "", null, 80f, m, st) },
        onPick = { edit.setStyle(cfg.id, it) },
        onClose = { picking = false },
    )
}

/**
 * An editable screen: rows of cards from [edit]'s layout, each row as tall as [rowWeight] of its tallest card.
 * Long press on any card switches to editing.
 */
@Composable
fun BoardScreen(edit: BoardEdit, names: CardNames, rowWeight: (TileCfg) -> Float, card: @Composable (TileCfg, Modifier) -> Unit) {
    val layout by Repo.boardFlow(edit.board.key).collectAsState()
    val list = remember(layout) { edit.board.parse(layout) }
    val rows = remember(layout) { edit.board.rows(list) }
    var editing by remember { mutableStateOf(false) }
    val longPress = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { editing = true }) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (editing) BoardEditBar(edit, names, { editing = false })
        if (edit.board.isFree(layout)) {
            FreeArea(edit, list.filter { it.on }, editing, names, longPress, Modifier.weight(1f).fillMaxWidth(), card = card)
            return@Column
        }
        rows.forEach { row ->
            Row(Modifier.weight(row.maxOf { rowWeight(it) }).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    Box(Modifier.weight(t.span.toFloat()).fillMaxHeight().then(longPress)) {
                        card(t, Modifier.fillMaxSize())
                        if (editing) TileEditOverlay(edit, t, names[t.id]?.first ?: "")
                    }
                }
                val used = row.sumOf { it.span }
                if (!edit.board.stretch && used < edit.board.columns) Spacer(Modifier.weight((edit.board.columns - used).toFloat()))
            }
        }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().then(longPress), contentAlignment = Alignment.Center) {
                T(if (editing) "Добавьте виджеты кнопкой «Добавить виджет»" else "Виджетов нет: нажмите и удерживайте здесь, чтобы настроить", 16.sp, C.Sub)
            }
        }
    }
}

/**
 * Cards placed anywhere, each at its [TileCfg.free] rect (later cards on top). While [editing], drag a card to
 * move it and the corner handle to resize it; both snap to 0.5 % steps and stay inside the area.
 */
@Composable
fun FreeArea(
    edit: BoardEdit, tiles: List<TileCfg>, editing: Boolean, names: CardNames, longPress: Modifier, modifier: Modifier,
    backgroundPress: Boolean = true,
    card: @Composable (TileCfg, Modifier) -> Unit,
) {
    // without [backgroundPress] the empty space passes touches through to what is drawn below (the 3D block)
    BoxWithConstraints(if (backgroundPress) modifier.then(longPress) else modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val minW = with(density) { 56.dp.toPx() } / w
        val minH = with(density) { 40.dp.toPx() } / h
        fun snap(x: Float) = kotlin.math.round(x * 200f) / 200f
        tiles.forEach { t ->
            key(t.id) {
                var r by remember(t.free) { mutableStateOf(t.free ?: FreeRect(0.35f, 0.3f, 0.3f, 0.4f)) }
                var dragging by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .offset { IntOffset((r.x * w).roundToInt(), (r.y * h).roundToInt()) }
                        .size(with(density) { (r.w * w).toDp() }, with(density) { (r.h * h).toDp() })
                        .then(
                            if (!editing) longPress
                            else Modifier.pointerInput(t.id) {
                                detectDragGestures(
                                    onDragStart = { dragging = true },
                                    onDragEnd = { dragging = false; edit.place(t.id, r.copy(x = snap(r.x), y = snap(r.y))) },
                                    onDragCancel = { dragging = false },
                                ) { change, d ->
                                    change.consume()
                                    r = r.copy(x = (r.x + d.x / w).coerceIn(0f, 1f - r.w), y = (r.y + d.y / h).coerceIn(0f, 1f - r.h))
                                }
                            },
                        ),
                ) {
                    card(t, Modifier.fillMaxSize())
                    if (editing) {
                        TileEditOverlay(edit, t, names[t.id]?.first ?: "", free = true)
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(30.dp)
                                .pointerInput(t.id) {
                                    detectDragGestures(
                                        onDragStart = { dragging = true },
                                        onDragEnd = { dragging = false; edit.place(t.id, r.copy(w = snap(r.w), h = snap(r.h))) },
                                        onDragCancel = { dragging = false },
                                    ) { change, d ->
                                        change.consume()
                                        r = r.copy(w = (r.w + d.x / w).coerceIn(minW, 1f - r.x), h = (r.h + d.y / h).coerceIn(minH, 1f - r.y))
                                    }
                                }
                                .clip(RoundedCornerShape(topStart = 10.dp, bottomEnd = 14.dp)).background(C.Purple),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.Scaling, "Размер", tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
        if (tiles.isEmpty()) T(
            if (editing) "Добавьте виджеты кнопкой «Добавить виджет»" else "Виджетов нет: нажмите и удерживайте здесь, чтобы настроить",
            16.sp, C.Sub, modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun EditButton(icon: ImageVector, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, tint: Color = C.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(C.CardHi)
            .border(1.dp, C.Border, RoundedCornerShape(9.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) tint else C.Border, modifier = Modifier.size(18.dp)) }
}

