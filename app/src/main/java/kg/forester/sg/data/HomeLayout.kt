package kg.forester.sg.data

/** One card on the home screen: shown or not, and how many of the 5 grid columns it spans. */
data class TileCfg(val id: String, val on: Boolean, val span: Int = 1)

/**
 * Which cards the home screen shows, in which order and how wide. Stored as "id:on:span;…".
 * Cards added in later versions are appended switched off, so a saved layout never loses them.
 */
object HomeLayout {
    const val COLUMNS = 5

    /** The factory layout: the 15 cards of v0.8, then extras that start switched off. */
    val defaults = listOf(
        "coolant", "intake", "battery", "throttle", "maf",
        "afr", "afcorr", "aflearn", "timing", "knock",
        "inj", "wgdc", "fuel", "boost", "gps",
    ).map { TileCfg(it, true) } + listOf(
        "pedal", "avcs", "map", "atm", "alt", "atf", "per100", "range",
    ).map { TileCfg(it, false) }

    fun parse(s: String): List<TileCfg> {
        if (s.isBlank()) return defaults
        val known = defaults.associateBy { it.id }
        val saved = s.split(';').mapNotNull { e ->
            val f = e.split(':')
            val id = f.getOrNull(0) ?: return@mapNotNull null
            if (id !in known) return@mapNotNull null
            TileCfg(id, f.getOrNull(1) == "1", (f.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, 3))
        }.distinctBy { it.id }
        val missing = defaults.filter { d -> saved.none { it.id == d.id } }.map { it.copy(on = false) }
        return saved + missing
    }

    fun format(list: List<TileCfg>) = list.joinToString(";") { "${it.id}:${if (it.on) 1 else 0}:${it.span}" }

    /** Cards that are on, packed into rows of [COLUMNS] grid columns. */
    fun rows(list: List<TileCfg>): List<List<TileCfg>> {
        val rows = mutableListOf<MutableList<TileCfg>>()
        var used = COLUMNS
        list.filter { it.on }.forEach { t ->
            if (used + t.span > COLUMNS) { rows += mutableListOf<TileCfg>(); used = 0 }
            rows.last() += t
            used += t.span
        }
        return rows
    }

    /** Height of the 3D car + dials block relative to one row of cards; 0 = hidden. */
    fun clusterWeight(size: Int) = when (size) {
        0 -> 0f
        2 -> 3.0f
        3 -> 4.6f
        else -> 1.9f
    }
}
