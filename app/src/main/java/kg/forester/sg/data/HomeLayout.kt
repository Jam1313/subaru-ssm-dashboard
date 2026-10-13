package kg.forester.sg.data

/** Place of a card in the free layout, as fractions of the card area (0…1). */
data class FreeRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun format() = listOf(x, y, w, h).joinToString(",") { "%.4f".format(java.util.Locale.US, it) }

    companion object {
        fun parse(s: String?): FreeRect? {
            val f = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
            if (f.size != 4) return null
            return FreeRect(f[0].coerceIn(0f, 1f), f[1].coerceIn(0f, 1f), f[2].coerceIn(0.02f, 1f), f[3].coerceIn(0.02f, 1f))
        }
    }
}

/**
 * One card on an editable screen: shown or not, how many grid columns it spans, gauge design ("" = normal look)
 * and its place when the screen uses the free layout.
 */
data class TileCfg(val id: String, val on: Boolean, val span: Int = 1, val style: String = "", val free: FreeRect? = null)

/**
 * Which cards a screen shows, in which order and how wide. Saved in the settings under [key] as
 * "[!free;]id:on:span:style:x,y,w,h;…", "" means the factory layout; "!free" switches to cards placed anywhere. Cards added in later versions are appended switched off, so a saved layout never
 * loses them. With [stretch] the cards of a row share the full width, otherwise the rest of the row stays empty.
 */
class Board(
    val key: String,
    val columns: Int,
    val maxSpan: Int,
    val stretch: Boolean,
    val defaults: List<TileCfg>,
    /** Cards that exist only in the free layout (the home 3D block is a fixed band in the grid). */
    val freeOnly: Set<String> = emptySet(),
) {
    fun isFree(s: String) = s.startsWith(FREE)

    fun parse(s: String): List<TileCfg> {
        if (s.isBlank()) return defaults
        val known = defaults.associateBy { it.id }
        val saved = s.removePrefix(FREE).split(';').mapNotNull { e ->
            val f = e.split(':')
            val id = f.getOrNull(0) ?: return@mapNotNull null
            if (id !in known) return@mapNotNull null
            // the style may itself contain ':' ("photo:<file>"); the free rect is always the last field (5+ fields)
            val withRect = f.size >= 5
            TileCfg(
                id, f.getOrNull(1) == "1", (f.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, maxSpan),
                if (withRect) f.subList(3, f.size - 1).joinToString(":") else f.getOrNull(3) ?: "",
                if (withRect) FreeRect.parse(f.last()) else null,
            )
        }.distinctBy { it.id }
        val missing = defaults.filter { d -> saved.none { it.id == d.id } }.map { it.copy(on = false) }
        return saved + missing
    }

    fun format(list: List<TileCfg>, free: Boolean = false) = (if (free) FREE else "") +
        list.joinToString(";") { "${it.id}:${if (it.on) 1 else 0}:${it.span}:${it.style}:${it.free?.format() ?: ""}" }

    /**
     * Free-layout places for the cards that have none yet, taken from where the grid shows them now, so switching
     * to the free layout changes nothing on screen. [rowWeight] gives the relative height of a card's row,
     * [band] the top and height of the grid within the free area.
     */
    fun withFreeRects(list: List<TileCfg>, rowWeight: (TileCfg) -> Float, band: Pair<Float, Float> = 0f to 1f): List<TileCfg> {
        val rows = rows(list)
        val heights = rows.map { r -> r.maxOf(rowWeight) }
        val total = heights.sum().takeIf { it > 0f } ?: return list
        val placed = HashMap<String, FreeRect>()
        var y = band.first
        rows.forEachIndexed { i, row ->
            val h = heights[i] / total * band.second
            val width = if (stretch) row.sumOf { it.span }.toFloat() else columns.toFloat()
            var x = 0f
            row.forEach { t ->
                val w = t.span / width
                placed[t.id] = FreeRect(x + 0.004f, y + 0.006f, w - 0.008f, h - 0.012f)
                x += w
            }
            y += h
        }
        return list.map { t -> if (t.free != null) t else t.copy(free = placed[t.id] ?: FreeRect(0.35f, 0.35f, 0.3f, 0.3f)) }
    }

    private companion object {
        const val FREE = "!free;"
    }

    fun rows(list: List<TileCfg>): List<List<TileCfg>> {
        val rows = mutableListOf<MutableList<TileCfg>>()
        var used = columns
        list.filter { it.on && it.id !in freeOnly }.forEach { t ->
            if (used + t.span > columns) { rows += mutableListOf<TileCfg>(); used = 0 }
            rows.last() += t
            used += t.span
        }
        return rows
    }
}

object HomeLayout {
    const val COLUMNS = 5

    /** The factory layout: the 15 cards of v0.8, then extras that start switched off. */
    val board = Board(
        "homeTiles", COLUMNS, 3, stretch = false,
        defaults = listOf(
            "coolant", "intake", "battery", "throttle", "maf",
            "afr", "afcorr", "aflearn", "timing", "knock",
            "inj", "wgdc", "fuel", "boost", "gps",
        ).map { TileCfg(it, true) } + listOf(
            "pedal", "avcs", "map", "atm", "alt", "atf", "per100", "range", "tach", "speedo", "car3d",
        ).map { TileCfg(it, false) },
        freeOnly = setOf("car3d"),
    )

    fun parse(s: String) = board.parse(s)
    fun rows(list: List<TileCfg>) = board.rows(list)

    fun clusterWeight(size: Int) = when (size) {
        0 -> 0f
        2 -> 3.0f
        3 -> 4.6f
        else -> 1.9f
    }
}

/** "Динамика": 12 columns, so five gauges (span 2) and six tiles (span 2) each fill a row as before. */
val DriveBoard = Board(
    "driveTiles", 12, 12, stretch = true,
    defaults = listOf(
        TileCfg("pedal", true, 2), TileCfg("throttle", true, 2), TileCfg("boost", true, 2),
        TileCfg("load", true, 2), TileCfg("knock", true, 2),
        TileCfg("chartPedal", true, 6), TileCfg("chartBoost", true, 6),
        TileCfg("chartAfr", true, 6), TileCfg("chartTiming", true, 6),
        TileCfg("avcs", true, 2), TileCfg("injDuty", true, 2), TileCfg("maf", true, 2),
        TileCfg("throttleMotor", true, 2), TileCfg("wgdc", true, 2), TileCfg("pressure", true, 2),
        TileCfg("rpm", false, 2), TileCfg("chartRpm", false, 6), TileCfg("chartTrims", false, 6),
        TileCfg("chartMaf", false, 6),
    ),
)

/** "АКПП": selector 3 + ATF 4 + torque converter 5 columns, the proportions of the fixed layout. */
val TcuBoard = Board(
    "tcuTiles", 12, 12, stretch = true,
    defaults = listOf(
        TileCfg("range", true, 3), TileCfg("atf", true, 4), TileCfg("converter", true, 5),
        TileCfg("lockup", true, 3), TileCfg("line", true, 3), TileCfg("awd", true, 3), TileCfg("wheels", true, 3),
        TileCfg("chartAtf", true, 6), TileCfg("chartLock", true, 6),
        TileCfg("chartTurbine", false, 6), TileCfg("chartWheels", false, 6),
    ),
)

/** Editable layouts by their settings key. */
object Boards {
    fun byKey(key: String) = when (key) {
        DriveBoard.key -> DriveBoard
        TcuBoard.key -> TcuBoard
        else -> HomeLayout.board
    }
}
