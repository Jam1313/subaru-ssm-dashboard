package kg.forester.sg.service

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Gears by the engine to road speed ratio (rpm per km/h), learned on the move: every gear of a manual gearbox
 * keeps its own ratio, so steady driving falls into a few clusters, the highest ratio being 1st. With an automatic
 * the TCU says the gear; there the ratio each gear keeps on full throttle is learned (the converter slips a little
 * then, and that is normal). Both are the reference for clutch / converter slip (transmission `gear_ratio_slip_wot`).
 */
class GearSense {
    /** One gear's ratio, rpm per km/h, and how many samples made it. */
    class Ratio(var ratio: Float, var n: Int)

    /** Manual: the clusters, any order (see [gears] for 1st, 2nd…). */
    val clusters = ArrayList<Ratio>()
    /** Automatic: the full-throttle ratio by the TCU's gear. */
    val wot = HashMap<Int, Ratio>()

    /** The learned gears, 1st first: rpm per km/h. */
    fun gears(): List<Float> = clusters.filter { it.n >= CONFIRMED }.map { it.ratio }.sortedDescending()

    /** Steady driving with the clutch in: one more sample for the clusters. */
    fun learn(ratio: Float) {
        val c = clusters.firstOrNull { abs(ratio / it.ratio - 1f) < 0.05f }
        if (c != null) {
            c.ratio += (ratio - c.ratio) * 0.02f
            c.n = minOf(c.n + 1, 5000)
            return
        }
        // a ratio between gears (the clutch slipping on a start, a half-pressed pedal) never gathers samples:
        // the clusters are capped, the weakest one makes room
        if (clusters.size >= 8) clusters.remove(clusters.minByOrNull { it.n })
        clusters += Ratio(ratio, 1)
    }

    /** The gear of [ratio] by the clusters (a slipping clutch reads up to 25 % above its gear), null = not learned. */
    fun gearOf(ratio: Float): Int? = Gears.byLearned(ratio.toDouble(), gears())

    /** The automatic's full-throttle ratio in [gear]: [ratio] taken in only near what it was (or to start it). */
    fun learnWot(gear: Int, ratio: Float) {
        val r = wot[gear]
        when {
            r == null -> wot[gear] = Ratio(ratio, 1)
            r.n < 20 -> { r.ratio += (ratio - r.ratio) / (r.n + 1); r.n++ }
            abs(ratio / r.ratio - 1f) < 0.03f -> { r.ratio += (ratio - r.ratio) * 0.05f; r.n = minOf(r.n + 1, 5000) }
        }
    }

    fun toJson(): String = JSONObject()
        .put("c", JSONArray().also { a -> clusters.forEach { a.put(JSONArray().put(it.ratio.toDouble()).put(it.n)) } })
        .put("w", JSONObject().also { o -> wot.forEach { (g, r) -> o.put(g.toString(), JSONArray().put(r.ratio.toDouble()).put(r.n)) } })
        .toString()

    companion object {
        /** Samples before a cluster counts as a gear. */
        const val CONFIRMED = 30

        fun fromJson(s: String): GearSense = GearSense().also { g ->
            runCatching {
                val o = JSONObject(s)
                val c = o.getJSONArray("c")
                for (i in 0 until c.length()) c.getJSONArray(i).let { g.clusters += Ratio(it.getDouble(0).toFloat(), it.getInt(1)) }
                val w = o.getJSONObject("w")
                w.keys().forEach { k -> w.getJSONArray(k).let { g.wot[k.toInt()] = Ratio(it.getDouble(0).toFloat(), it.getInt(1)) } }
            }
        }
    }
}

/** One full-throttle pull's spool point: the rpm where the boost reached [SpoolTrend.levelKpa]. */
data class SpoolPoint(val timeMs: Long, val gear: Int, val rpm: Int, val intakeC: Int? = null)

/**
 * Where the turbo comes on boost, per gear: the rpm of reaching [levelKpa] on a full-throttle pull, against this
 * car's own usual one ([base], from its first pulls). The point creeping up 300 rpm or more is the sign owners find
 * a worn wastegate bushing or a leak before the turbo by (engine-ej B11).
 */
data class SpoolTrend(
    /** The boost the spool point is taken at, kPa over the atmosphere; 0 = not chosen yet (no usual boost yet). */
    val levelKpa: Float = 0f,
    val base: Map<Int, Int> = emptyMap(),
    /** The last pulls, oldest first. */
    val recent: List<SpoolPoint> = emptyList(),
) {
    /** The last few points in [gear], oldest first. */
    fun inGear(gear: Int) = recent.filter { it.gear == gear }

    /** [p] added; the second value is true when the spool point now sits late in two of the last three pulls. */
    fun add(p: SpoolPoint): Pair<SpoolTrend, Boolean> {
        val all = (recent + p).takeLast(30)
        val mine = all.filter { it.gear == p.gear }
        val b = base[p.gear]
        val nextBase = when {
            // the first three pulls in this gear make its usual point
            b == null -> if (mine.size >= 3) mine.takeLast(3).map { it.rpm }.sorted()[1] else null
            // then it follows slow changes (the season), never a jump
            abs(p.rpm - b) < 150 -> (b + (p.rpm - b) * 0.1f).toInt()
            else -> b
        }
        val t = copy(base = if (nextBase != null) base + (p.gear to nextBase) else base, recent = all)
        val late = b != null && mine.takeLast(3).count { it.rpm - b >= LATE_RPM } >= 2 && p.rpm - b >= LATE_RPM
        return t to late
    }

    fun toJson(): String = JSONObject().put("level", levelKpa.toDouble())
        .put("base", JSONObject().also { o -> base.forEach { (g, r) -> o.put(g.toString(), r) } })
        .put("recent", JSONArray().also { a ->
            recent.forEach { a.put(JSONArray().put(it.timeMs).put(it.gear).put(it.rpm).put(it.intakeC ?: JSONObject.NULL)) }
        }).toString()

    companion object {
        /** How much later than usual counts as late (owners: «точка выхода на буст уехала на 300 об»). */
        const val LATE_RPM = 300

        fun fromJson(s: String): SpoolTrend = runCatching {
            val o = JSONObject(s)
            val b = o.getJSONObject("base")
            val r = o.getJSONArray("recent")
            SpoolTrend(
                o.getDouble("level").toFloat(),
                b.keys().asSequence().associate { it.toInt() to b.getInt(it) },
                (0 until r.length()).map { i ->
                    val a = r.getJSONArray(i)
                    SpoolPoint(a.getLong(0), a.getInt(1), a.getInt(2), if (a.isNull(3)) null else a.getInt(3))
                },
            )
        }.getOrDefault(SpoolTrend())
    }
}

/** The last full-throttle pull of a turbo car, for the «Наддув» card. */
data class PullSummary(
    val timeMs: Long,
    val gear: Int?,
    val peakKpa: Float,
    /** The target at the peak, kPa; null = this ROM has no target boost. */
    val targetKpa: Float?,
    /** The largest shortfall against the target once the pull was on boost (target − actual), kPa. */
    val maxErrKpa: Float?,
    val wgdcMax: Float?,
    val spoolRpm: Int?,
)

/** What the analyzer has gathered for the «Откаты и наддув» tab. */
data class DiagState(
    val tripKnock: kg.forester.sg.data.KnockMap = kg.forester.sg.data.KnockMap(),
    val allKnock: kg.forester.sg.data.KnockMap = kg.forester.sg.data.KnockMap(),
    /** Knock Sum counts per cylinder this drive (ROMs with Knock Sum 1–4); null = not read. */
    val cylKnock: List<Int>? = null,
    val spool: SpoolTrend = SpoolTrend(),
    val lastPull: PullSummary? = null,
    /** Learned gears, rpm per km/h, 1st first (manual by the clusters, automatic by the TCU on full throttle). */
    val gears: List<Float> = emptyList(),
    /** Outside air by the intake after a long park; null = not known today. */
    val ambientC: Float? = null,
    /** The ECU's A/F reading never leaves λ 1.0 (14.7) through a varied drive: it has no A/F sensor to read. */
    val noWideband: Boolean = false,
)
