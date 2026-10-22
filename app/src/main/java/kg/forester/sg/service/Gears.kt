package kg.forester.sg.service

import kotlin.math.abs
import kotlin.math.ln

/**
 * The gear from the engine speed against the road speed, one place for every screen and rule that guesses it: the
 * ratios this car taught [GearSense] when there are enough of them, else the usual Subaru gearboxes. All ratios are
 * rpm per km/h of the ECU's speed (what the car itself shows), as [GearSense] keeps them.
 */
object Gears {
    /** rpm per km/h for an overall ratio (gearbox × final drive) on a tyre rolling [tyreM] metres a turn. */
    fun perKmh(overall: Double, tyreM: Double) = overall * 1000.0 / 60.0 / tyreM

    /** A gearbox as rpm per km/h by gear, 1st first. */
    class Box(val name: String, val perKmh: List<Double>)

    /**
     * 4EAT (2.785 / 1.545 / 1.000 / 0.694, factory-manuals.md) with the converter locked, between the 4.111 and
     * 4.444 final drives (±4 %), on a 205/70R15 or 215/60R16 rolling ~2.04 m.
     */
    val EAT4 = Box("4EAT", listOf(11.9, 6.6, 4.25, 2.95).map { perKmh(it, 2.04) })

    /** 5MT of the EJ Foresters, Legacys and non-WRX Imprezas: the 4.111 and 4.444 final drives in one table (±4 %). */
    val MT5 = Box("5MT EJ", listOf(14.8, 8.6, 6.0, 4.4, 3.33).map { perKmh(it, 2.04) })

    /** 5MT of the WRX (GD/GG): 3.454 / 1.947 / 1.296 / 0.972 / 0.738, final drive 3.9, 205/55R16 or 225/45R17 (~1.95 m). */
    val MT5_WRX = Box("5MT WRX", listOf(3.454, 1.947, 1.296, 0.972, 0.738).map { perKmh(it * 3.9, 1.95) })

    /** How far a ratio may sit from a table's gear (ln): one table known for the car, or several to agree. */
    private const val TOL_ONE = 0.12
    private const val TOL_MANY = 0.09

    /**
     * The car by its ECU (the ECU id base's "USDM Impreza WRX 2004 MT"), null = not known. Set on connecting
     * ([EcuService]); picks the table when nothing is learned yet.
     */
    @Volatile var car: String? = null

    /**
     * The tables to try for this car: by the car's name when the ECU says it, else every one of the kind. A 6MT STI
     * has none (its 3.9 final drive and six close ratios overlap the 5MT ones): it waits for its own learned ratios.
     */
    fun boxes(automatic: Boolean, car: String?): List<Box> {
        if (automatic) return listOf(EAT4)
        val u = car?.uppercase() ?: return listOf(MT5, MT5_WRX)
        return when {
            "STI" in u -> emptyList()
            "WRX" in u -> listOf(MT5_WRX)
            else -> listOf(MT5)
        }
    }

    /** The gear of [ratio] (rpm per km/h) in one table: the nearest one within [tol], else null. */
    fun inBox(ratio: Double, box: Box, tol: Double): Int? {
        val best = box.perKmh.withIndex().minByOrNull { abs(ln(ratio / it.value)) } ?: return null
        return if (abs(ln(ratio / best.value)) < tol) best.index + 1 else null
    }

    /**
     * The gear at [rpm] and [kmh], null with the clutch down, between gears or when it can't be told. [learned] are
     * this car's own ratios, 1st first (a manual's clusters from [GearSense.gears]); with two or more they decide, the
     * way [GearSense.gearOf] does (a slipping clutch reads up to 25 % above its gear). Without them the tables of
     * [boxes]: several tables must agree on the gear number, so a WRX's 4th never shows as another car's 5th.
     * [table] false = learned ratios only (the rules, where a guess must not raise an alarm).
     */
    fun estimate(rpm: Int, kmh: Int, automatic: Boolean, learned: List<Float> = emptyList(), car: String? = this.car, table: Boolean = true): Int? {
        if (kmh < 6 || rpm < 700) return null
        val ratio = rpm / kmh.toDouble()
        if (!automatic && learned.size >= 2) return byLearned(ratio, learned)
        if (!table) return null
        val bs = boxes(automatic, car)
        if (bs.isEmpty()) return null
        val tol = if (bs.size == 1) TOL_ONE else TOL_MANY
        val gears = bs.mapNotNull { inBox(ratio, it, tol) }.distinct()
        return gears.singleOrNull()
    }

    /**
     * The gear of [ratio] by this car's learned ratios, 1st first; null with under two of them or between gears. The
     * highest gear the ratio may belong to: at or a little above that gear's line.
     */
    fun byLearned(ratio: Double, learned: List<Float>): Int? {
        if (learned.size < 2) return null
        val i = learned.indexOfLast { (ratio / it) in 0.97..1.25 }
        return if (i >= 0) i + 1 else null
    }

    /** What a screen shows: the gear, "N" standing or idling, "—" when it can't be told. */
    fun text(rpm: Int?, kmh: Int?, automatic: Boolean, learned: List<Float> = emptyList(), car: String? = this.car): String {
        if (rpm == null || kmh == null) return "—"
        if (kmh < 6 || rpm < 700) return "N"
        return estimate(rpm, kmh, automatic, learned, car)?.toString() ?: "—"
    }
}
