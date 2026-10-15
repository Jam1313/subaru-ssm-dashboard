package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** The [Analyzer] in the app: its memory in the preferences, its findings through [Alerts], the last idle sample. */
object RulesHost {
    /** The last half minute of warm idle (for «Проверка»), restored at start. */
    val idle = MutableStateFlow<IdleSample?>(null)
    /** Earlier idle samples, oldest first, one per half hour at most: the trend (a MAF getting dirty, a leak growing). */
    val idleHistory = MutableStateFlow<List<IdleSample>>(emptyList())

    private fun toJson(s: IdleSample): JSONObject {
        val v = JSONObject(); s.values.forEach { (k, x) -> v.put(k, x.toDouble()) }
        return JSONObject().put("t", s.timeMs).put("v", v)
    }

    private fun fromJson(o: JSONObject): IdleSample {
        val v = o.getJSONObject("v")
        return IdleSample(o.getLong("t"), v.keys().asSequence().associateWith { v.getDouble(it).toFloat() })
    }

    /** A new sample: the latest one replaces a sample of the same half hour, the list keeps the last 40. */
    internal fun addToHistory(list: List<IdleSample>, s: IdleSample, sameStopMs: Long = 30 * 60_000L): List<IdleSample> {
        val last = list.lastOrNull()
        val kept = if (last != null && s.timeMs - last.timeMs < sameStopMs) list.dropLast(1) else list
        return (kept + s).takeLast(40)
    }

    private class Store(ctx: Context) : RuleStore {
        private val p = ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE)
        override var lastRunMs: Long
            get() = p.getLong("lastRun", 0L)
            set(v) = p.edit().putLong("lastRun", v).apply()
        override var lastIam: Float
            get() = p.getFloat("lastIam", Float.NaN)
            set(v) = p.edit().putFloat("lastIam", v).apply()
        override var iamResetMs: Long
            get() = p.getLong("iamReset", 0L)
            set(v) = p.edit().putLong("iamReset", v).apply()
        override var boostBase: Float
            get() = p.getFloat("boostBase", 0f)
            set(v) = p.edit().putFloat("boostBase", v).apply()
        override fun lastRefillMs(): Long? = runCatching { Repo.db.refills().maxOfOrNull { it.timeMs } }.getOrNull()
    }

    /** The idle history lives in a file of its own: the rules' small values are saved every minute, this ~16 KB is not. */
    private fun histPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("idle_history", Context.MODE_PRIVATE)

    /** This car's own idle samples from storage, dropping whatever the demo showed. */
    fun restore(ctx: Context) {
        val h = histPrefs(ctx)
        idle.value = runCatching { fromJson(JSONObject(h.getString("idle", null) ?: return@runCatching null)) }.getOrNull()
        idleHistory.value = runCatching {
            val a = org.json.JSONArray(h.getString("idleHist", "[]"))
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Another car on the cable: its own IAM, usual boost and idle history, nothing from the last one. */
    fun forgetCar(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE).edit()
            .remove("lastIam").remove("iamReset").remove("boostBase").remove("lastRun").apply()
        histPrefs(ctx).edit().clear().apply()
        idle.value = null
        idleHistory.value = emptyList()
    }

    fun create(ctx: Context): Analyzer {
        restore(ctx)
        // in demo mode the findings are shown, not spoken; an info is not spoken either
        val sink = RuleSink { id, text, level, cooldown -> Alerts.raise(id, text, level, cooldown, speak = level != Alerts.Level.INFO) }
        // the demo's made-up drives must not become this car's memory (IAM, usual boost, parking time)
        val real = Store(ctx)
        val demo = MemoryRuleStore()
        val store = object : RuleStore {
            private val s get() = if (Repo.settings.demo) demo else real
            override var lastRunMs: Long get() = s.lastRunMs; set(v) { s.lastRunMs = v }
            override var lastIam: Float get() = s.lastIam; set(v) { s.lastIam = v }
            override var iamResetMs: Long get() = s.iamResetMs; set(v) { s.iamResetMs = v }
            override var boostBase: Float get() = s.boostBase; set(v) { s.boostBase = v }
            override fun lastRefillMs() = s.lastRefillMs()
        }
        return Analyzer(store, sink, { Repo.settings.feature(it) }) { sample ->
            idle.value = sample
            // the demo's samples show on the screen (one per stop), they never go into this car's saved history
            if (Repo.settings.demo) idleHistory.value = addToHistory(idleHistory.value, sample, 60_000L)
            else {
                idleHistory.value = addToHistory(idleHistory.value, sample)
                val a = org.json.JSONArray(); idleHistory.value.forEach { a.put(toJson(it)) }
                histPrefs(ctx).edit().putString("idle", toJson(sample).toString()).putString("idleHist", a.toString()).apply()
            }
        }
    }
}
