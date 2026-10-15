package kg.forester.sg.data

import android.content.Context
import kg.forester.sg.ssm.EcuSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil

/**
 * Top of a gauge/chart scale that grows with the car. A stock car keeps [stock]; one that really goes further
 * (a tuned ROM, a bigger turbo or MAF) widens it in [step]s, kept across restarts, so needles, bars and charts
 * never pin at the end.
 */
class LearnedScale(private val key: String, val stock: Float, private val step: Float, private val cap: Float,
                   /** Readings above this are no real value on these cars. */
                   private val sane: Float) {
    val top = MutableStateFlow(stock)
    private var streak = 0

    internal fun load(top: Float) { this.top.value = top.coerceIn(stock, cap) }

    /**
     * One reading under load. Within half a step of the end three samples in a row widens the scale, with some
     * room above: a single junk byte must not.
     */
    internal fun feed(v: Float?): Boolean {
        if (v == null || v <= top.value - step / 2 || v > sane) { streak = 0; return false }
        if (++streak < 3) return false
        streak = 0
        val t = (ceil((v + step * 0.75f) / step - 0.001f) * step).coerceAtMost(cap)
        if (t <= top.value) return false
        top.value = t
        Scales.save(key, t)
        return true
    }

    internal fun reset() { streak = 0; top.value = stock }
}

/** The scales that adapt to the car: boost (bar), MAF (g/s) and load (g/rev). */
object Scales {
    val boost = LearnedScale("boost", 1.2f, 0.2f, 2.4f, sane = 2.5f)
    val maf = LearnedScale("maf", 150f, 50f, 450f, sane = 400f)
    val load = LearnedScale("load", 2.5f, 0.5f, 5f, sane = 5f)
    private val all = mapOf("boost" to boost, "maf" to maf, "load" to load)

    private var appCtx: Context? = null
    private fun prefs() = appCtx?.getSharedPreferences("learned_scales", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val p = prefs() ?: return
        for ((k, s) in all) s.load(p.getFloat(k, s.stock))
    }

    internal fun save(key: String, v: Float) { prefs()?.edit()?.putFloat(key, v)?.apply() }

    /** Boost scale in kPa, for the values shown in kPa. */
    val boostKpa: Float get() = boost.top.value * 100f

    /** One sample; only under load (above 1500 rpm), and never a MAP byte of 0xFF. */
    fun process(e: EcuSnapshot) {
        if ((e.rpm ?: 0) < 1500) return
        if (e.mapKpa != 255f) boost.feed(e.boostBar)
        maf.feed(e.mafGs)
        load.feed(e.loadGRev)
    }

    /** Back to stock scales, e.g. another car was chosen. */
    fun reset() {
        all.values.forEach { it.reset() }
        prefs()?.edit()?.clear()?.apply()
    }
}
