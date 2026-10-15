package kg.forester.sg.data

import android.content.Context
import kg.forester.sg.ssm.EcuSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil

/**
 * Top of every boost scale, bar. A stock turbo keeps 1.2; a car that really pulls more (a tuned ROM, a bigger
 * turbo) widens it in 0.2 bar steps, kept across restarts, so the needle, bars and chart never pin at the end.
 */
object BoostScale {
    const val STOCK = 1.2f
    private const val CAP = 2.4f

    val top = MutableStateFlow(STOCK)
    private var appCtx: Context? = null
    private var streak = 0

    private fun prefs() = appCtx?.getSharedPreferences("boost_scale", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        top.value = (prefs()?.getFloat("top", STOCK) ?: STOCK).coerceIn(STOCK, CAP)
    }

    /** Same scale in kPa, for the values shown in kPa. */
    val topKpa: Float get() = top.value * 100f

    /**
     * One sample. Boost within 0.1 bar of the end, three samples in a row under load, widens the scale: a single
     * junk byte (MAP 0xFF) must not. Above 2.5 bar is no real reading on these cars.
     */
    fun process(e: EcuSnapshot) {
        val bar = e.boostBar
        if (bar == null || bar <= top.value - 0.1f || bar > 2.5f || e.mapKpa == 255f || (e.rpm ?: 0) < 1500) {
            streak = 0
            return
        }
        if (++streak < 3) return
        streak = 0
        val t = (ceil((bar + 0.15f) / 0.2f - 0.001f) * 0.2f).coerceAtMost(CAP)
        if (t <= top.value) return
        top.value = t
        prefs()?.edit()?.putFloat("top", t)?.apply()
    }

    /** Back to the stock scale, e.g. another car was chosen. */
    fun reset() {
        streak = 0
        top.value = STOCK
        prefs()?.edit()?.remove("top")?.apply()
    }
}
