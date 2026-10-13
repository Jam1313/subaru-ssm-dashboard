package kg.forester.sg.data

/** One card on an editable screen: shown or not, how many grid columns it spans, gauge design ("" = normal look). */
data class TileCfg(val id: String, val on: Boolean, val span: Int = 1, val style: String = "")

/**
 * Which cards a screen shows, in which order and how wide. Saved in the settings under [key] as "id:on:span:style;…",
 * "" means the factory layout. Cards added in later versions are appended switched off, so a saved layout never
 * loses them. With [stretch] the cards of a row share the full width, otherwise the rest of the row stays empty.
 */
class Board(
    val key: String,
    val columns: Int,
    val maxSpan: Int,
    val stretch: Boolean,
    val defaults: List<TileCfg>,
) {
    fun parse(s: String): List<TileCfg> {
        if (s.isBlank()) return defaults
        val known = defaults.associateBy { it.id }
        val saved = s.split(';').mapNotNull { e ->
            val f = e.split(':')
            val id = f.getOrNull(0) ?: return@mapNotNull null
            if (id !in known) return@mapNotNull null
            TileCfg(id, f.getOrNull(1) == "1", (f.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, maxSpan), f.getOrNull(3) ?: "")
        }.distinctBy { it.id }
        val missing = defaults.filter { d -> saved.none { it.id == d.id } }.map { it.copy(on = false) }
        return saved + missing
    }

    fun format(list: List<TileCfg>) = list.joinToString(";") { "${it.id}:${if (it.on) 1 else 0}:${it.span}:${it.style}" }

    fun rows(list: List<TileCfg>): List<List<TileCfg>> {
        val rows = mutableListOf<MutableList<TileCfg>>()
        var used = columns
        list.filter { it.on }.forEach { t ->
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
            "pedal", "avcs", "map", "atm", "alt", "atf", "per100", "range",
        ).map { TileCfg(it, false) },
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
