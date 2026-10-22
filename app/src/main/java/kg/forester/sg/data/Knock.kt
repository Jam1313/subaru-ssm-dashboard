package kg.forester.sg.data

/**
 * One timing pull by the ECU: the knock correction went down to [KnockStats.START_DEG] or lower and stayed
 * negative until [durationMs] later. The engine values are taken at the deepest point of the pull.
 */
data class KnockEvent(
    val startMs: Long,
    val durationMs: Long = 0,
    val maxPullDeg: Float,
    val rpm: Int?,
    val loadGRev: Float?,
    val throttlePct: Float?,
    val speedKmh: Int?,
    val coolantC: Int?,
    val timingDeg: Float?,
    val active: Boolean = true,
)

/** Timing pulls since the current trip started. */
data class KnockStats(val sinceMs: Long = 0, val events: List<KnockEvent> = emptyList()) {
    val count get() = events.size
    /** The deepest pull of the trip, a negative number of degrees. */
    val maxPullDeg get() = events.minOfOrNull { it.maxPullDeg }

    companion object {
        /**
         * Any pull counts, the ECU's smallest steps too (0.35° on the 16-bit ones, 0.5° in the standard byte):
         * with -1° the small pulls BTSSM shows never got here. Flicker is one event, see [MERGE_MS].
         */
        const val START_DEG = -0.3f
        /** A pull starting this soon after the last one ended carries on that event instead of counting again. */
        const val MERGE_MS = 3000L
    }
}

/** Pulls in one cell of the [KnockMap]: real ones, ones at an automatic gearbox shift, the deepest of them. */
data class KnockCell(val count: Int = 0, val shift: Int = 0, val worstDeg: Float = 0f)

/**
 * Timing pulls by where they happened, engine speed × load (g/rev), a count per cell: the knock map owners read in
 * BTSSM. Pulls on a lifted foot, at light load below 3000 rpm and on a cold engine never get here (noise, see
 * engine-ej K07, K08, K13); those within ~0.5 s of an automatic gearbox shift are counted apart in [KnockCell.shift]:
 * mostly the valve body's click or the TCU's own torque cut, not knock (engine-ej K16).
 */
data class KnockMap(val sinceMs: Long = 0, val cells: Map<Int, KnockCell> = emptyMap()) {
    val total get() = cells.values.sumOf { it.count }
    val shiftTotal get() = cells.values.sumOf { it.shift }
    val worstDeg get() = cells.values.minOfOrNull { it.worstDeg }

    fun cell(row: Int, col: Int) = cells[row * COLS + col]

    fun add(rpm: Int, load: Float, deg: Float, atShift: Boolean): KnockMap {
        val i = indexOf(rpm, load) ?: return this
        val c = cells[i] ?: KnockCell()
        val next = if (atShift) c.copy(shift = c.shift + 1) else c.copy(count = c.count + 1, worstDeg = minOf(c.worstDeg, deg))
        return copy(cells = cells + (i to next))
    }

    fun toJson(): String {
        val a = org.json.JSONArray()
        cells.forEach { (i, c) -> a.put(org.json.JSONArray().put(i).put(c.count).put(c.shift).put(c.worstDeg.toDouble())) }
        return org.json.JSONObject().put("since", sinceMs).put("cells", a).toString()
    }

    companion object {
        /** Lower edges of the columns, rpm; the last one is open. */
        val RPM = IntArray(12) { 1000 + it * 500 }
        /** Lower edges of the rows, g/rev, bottom to top; the last one is open. */
        val LOAD = floatArrayOf(0f, 0.6f, 0.8f, 1.0f, 1.2f, 1.4f, 1.7f, 2.0f, 2.4f)
        val COLS = RPM.size
        val ROWS = LOAD.size

        fun indexOf(rpm: Int, load: Float): Int? {
            if (rpm < RPM[0] || load.isNaN()) return null
            val col = RPM.indexOfLast { rpm >= it }
            val row = LOAD.indexOfLast { load >= it }.coerceAtLeast(0)
            return row * COLS + col
        }

        fun fromJson(s: String): KnockMap = runCatching {
            val o = org.json.JSONObject(s)
            val a = o.getJSONArray("cells")
            KnockMap(o.optLong("since"), (0 until a.length()).associate { k ->
                val c = a.getJSONArray(k)
                c.getInt(0) to KnockCell(c.getInt(1), c.getInt(2), c.getDouble(3).toFloat())
            })
        }.getOrDefault(KnockMap())
    }
}
