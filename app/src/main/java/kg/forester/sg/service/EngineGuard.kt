package kg.forester.sg.service

import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.TcuSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** What the engine guard knows now; shown on the «Мониторинг» screen and in cards. */
data class GuardState(
    /** IAM, 0…1 (1 = full timing advance learned); null when this ECU/ROM has no IAM. */
    val iam: Float? = null,
    /** IAM at the start of this drive. */
    val iamStart: Float? = null,
    /** Feedback knock correction (now) and fine learning knock correction, degrees (negative = pulled). */
    val fbkc: Float? = null,
    val flkc: Float? = null,
    /** Target boost relative to the atmosphere, kPa (from the ROM's extended parameters). */
    val targetBoostKpa: Float? = null,
    /** Oil temperature: read from the ECU, or estimated from the coolant and running time. */
    val oilC: Float? = null,
    val oilEstimated: Boolean = true,
    /** Engine still cold (oil under ~60 °C): keep the revs down. */
    val cold: Boolean = false,
    val turbo: Boolean = false,
    /** A turbo car by what the ECU says (ROM, wastegate, boost seen), whether or not the turbo timer is on. */
    val turboCar: Boolean = false,
    /** Turbo heat 0…100 and, at idle after a hard drive, the seconds left before switching off. */
    val heat: Float = 0f,
    val cooldownSec: Int = 0,
    /** Minutes the last cold start took to reach 80 °C coolant. */
    val warmupMin: Float? = null,
    val thermostat: String = "",
    val coolantMax: Int? = null,
    val atfMax: Int? = null,
)

/** Highest (and lowest) values since the start of the trip or the last reset. */
data class Peaks(
    val boostBar: Float? = null,
    val rpm: Int? = null,
    val mafGs: Float? = null,
    val loadGRev: Float? = null,
    val speedKmh: Int? = null,
    val intakeC: Int? = null,
    val coolantC: Int? = null,
    val atfC: Int? = null,
    val knockDeg: Float? = null,
    val leanAfrUnderBoost: Float? = null,
    val sinceMs: Long = System.currentTimeMillis(),
)

/**
 * Watches the engine between polls: reads IAM and the knock corrections, estimates the oil temperature, keeps
 * the turbo cool-down, the thermostat (warm-up time), sudden coolant rise (EJ25 head gaskets) and the peak values.
 * The other warnings (knock, mixture, boost, idle, overheating, ATF) are the [analyzer]'s rules. Warnings go
 * through [Alerts].
 */
class EngineGuard {
    /** The rules from docs/knowledge; fed with every sample and what the guard worked out. */
    var analyzer: Analyzer? = null
    /** For the ROM base lookup (turbo or not by the ECU's ROM ID). */
    var ctx: android.content.Context? = null
    private var romIdSeen: String? = null
    private var romTurbo = false

    private var avail: List<RrParam>? = null
    private var roles = emptyMap<String, RrParam>()
    private var lastMs = 0L
    private var engineOnSince = 0L
    private var oilEst: Float? = null
    /** Air flow averaged over the last few minutes, g/s: how hard the engine has been working (heats the oil). */
    private var mafAvg = 0f
    private var heat = 0f
    private var cooldownAnnounced = false
    private var turboSeen = false
    private var boostMs = 0L
    private var iamStart: Float? = null
    private var iamLogged = false
    private var coldStartMs = 0L
    private var movingMsSinceCold = 0L
    private var warmupMin: Float? = null
    private var thermostatWarned = false
    private val coolant = ArrayDeque<Pair<Long, Int>>()
    private var warmSince = 0L
    private var maintenanceChecked = 0L
    private var features = -1
    private var custom = -1
    private var pendingHours = 0.0

    /** The RomRaider parameters the guard reads, chosen from what this ROM has (first match wins). */
    private fun chooseRoles(list: List<RrParam>) {
        // the driver's own address for a role comes first: it is there because the base had none (or a wrong one)
        val own = kg.forester.sg.data.CustomParams.roles()
        fun pick(role: String, vararg ids: String) =
            (listOfNotNull(own[role]) + ids).firstNotNullOfOrNull { id -> list.firstOrNull { it.id == id } }
        val knock = Repo.settings.feature("knock")
        roles = listOfNotNull(
            pick("iam", "P90", "E31", "E96", "E111", "E1")?.takeIf { knock }?.let { "iam" to it },
            pick("fbkc", "E39", "E94", "E102", "E10")?.takeIf { knock }?.let { "fbkc" to it },
            pick("flkc", "P91", "E41", "E95", "E101", "E12")?.takeIf { knock }?.let { "flkc" to it },
            pick("target", "E36", "E90", "E120", "E6")?.let { "target" to it },
            pick("oil", "P122")?.let { "oil" to it },
            pick("latG", "P172")?.let { "latG" to it },
            pick("yaw", "P171")?.let { "yaw" to it },
        ).toMap()
        Repo.internalWatch.value = roles.values.map { it.id }.toSet()
    }

    fun role(name: String): RrParam? = roles[name]

    /**
     * Forget everything learned from the samples so far: called when the demo is switched on or off, so its made-up
     * boost doesn't make an NA car a turbo (and its injectors 380 cc), its heat doesn't keep the turbo timer going.
     */
    fun reset() {
        oilEst = null; mafAvg = 0f; heat = 0f; cooldownAnnounced = false; turboSeen = false; boostMs = 0L
        iamStart = null; iamLogged = false; coldStartMs = 0L; movingMsSinceCold = 0L; warmupMin = null
        thermostatWarned = false; coolant.clear(); warmSince = 0L; engineOnSince = 0L; lastMs = 0L; romIdSeen = null
        analyzer?.reset()
    }

    fun newTrip(now: Long) {
        Repo.peaks.value = Peaks(sinceMs = now)
        Repo.tripRange.value = emptyMap()
        iamStart = null
        iamLogged = false
    }

    fun process(e: EcuSnapshot, t: TcuSnapshot?, now: Long) {
        val list = Repo.rrAvailable.value
        if (list !== avail || features != Repo.featureVersion.value || custom != Repo.customVersion.value) {
            avail = list; features = Repo.featureVersion.value; custom = Repo.customVersion.value; chooseRoles(list)
        }
        val s = Repo.settings
        val fKnock = s.feature("knock"); val fTurbo = s.feature("turbo")
        val fCool = s.feature("cooling"); val fService = s.feature("service")
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 2f)
        lastMs = now
        val rpm = e.rpm ?: 0
        val running = rpm > 400
        // engine hours: counted in memory, saved once a minute (flash wear)
        if (running && !s.demo) {
            pendingHours += dt / 3600.0
            if (pendingHours > 1.0 / 60) { s.engineHours += pendingHours; pendingHours = 0.0 }
        }
        if (!running) { engineOnSince = 0L; oilEst = null }
        else if (engineOnSince == 0L) engineOnSince = now
        val rr = Repo.rrValues.value
        fun v(role: String) = roles[role]?.let { rr[it.id] }?.toFloat()

        // --- IAM and knock corrections
        // a role read from a borrowed or user-typed address can be junk: keep only values that make sense for it
        val iam = v("iam")?.let { if (it > 1.5f) it / 16f else it }?.takeIf { it in 0f..1f }
        if (iam != null && running && fKnock) {
            if (iamStart == null) iamStart = iam
            if (!iamLogged) { iamLogged = true; if (!s.demo) logIam(now, iam) }
        }
        val fbkc = v("fbkc")?.takeIf { it in -16f..2f }
        val flkc = v("flkc")?.takeIf { it in -16f..2f }
        val target = targetBoost(e)

        // --- turbo heat and cool-down
        val gauge = e.gaugeKpa ?: 0f
        // turbo only after real boost: above 0.15 bar, below 2.5 bar (a junk 0xFF reads +127 kPa), held for 2 s
        boostMs = if (gauge in 15f..250f && (e.rpm ?: 0) > 1500) boostMs + (dt * 1000).toLong() else 0L
        if (boostMs > 2000) turboSeen = true
        // turbo by what the ECU says (its ROM ID, a wastegate it reports, boost seen), not by the name typed in the settings
        val init = Repo.ecuInit.value
        if (init?.romId != romIdSeen) {
            romIdSeen = init?.romId
            romTurbo = init != null && ((ctx?.let { kg.forester.sg.ssm.EngineId.romTurbo(it, init.romId) } ?: kg.forester.sg.ssm.EngineId.fromRomId(init.romId)?.turbo) == true ||
                (kg.forester.sg.ssm.EcuParams.reported(init, kg.forester.sg.ssm.EcuParams.WGDC) && init.capabilities.size > 4))
        }
        val turboCar = turboSeen || romTurbo || s.demo
        val turbo = fTurbo && turboCar
        val speed = e.speedKmh ?: 0
        val idle = running && rpm < 1300 && speed < 3
        heat = when {
            !running -> (heat - dt * 1.2f).coerceAtLeast(0f)
            turboSeen && gauge in 10f..250f -> heat + dt * (gauge / 40f + if (rpm > 4000) 0.6f else 0.2f) * 2.5f
            idle -> heat - dt * 1.1f
            else -> heat - dt * 0.25f
        }.coerceIn(0f, 100f)
        val cooldown = if (turbo && idle && heat > 12f) heat.toInt() else 0
        if (turbo && idle && heat > 35f && !cooldownAnnounced) {
            cooldownAnnounced = true
            Alerts.raise("turbo", "Турбина горячая: дайте мотору поработать на холостых около ${heat.toInt()} секунд перед выключением", Alerts.Level.INFO, 5 * 60_000L,
                banner = false) // the countdown is on screen already
        }
        // the same moment the countdown ends (heat 12): spoken only, the header pill turns green «можно глушить»
        if (cooldownAnnounced && heat <= 12f) {
            cooldownAnnounced = false
            Alerts.clear("turbo")
            if (idle) Alerts.raise("turboOk", "Турбина остыла, можно глушить", Alerts.Level.INFO, 5 * 60_000L, banner = false)
        }
        if (!idle && heat > 35f) cooldownAnnounced = false

        // --- oil temperature and cold engine
        val c = e.coolantC
        val oilEcu = v("oil")
        e.mafGs?.let { m -> mafAvg += (m - mafAvg) * (dt / 180f).coerceAtMost(1f) }
        val oil = oilEcu ?: if (running && c != null) {
            // Most EJs have no oil temperature sensor: estimated. The oil lags the coolant by several minutes after a
            // cold start, and once warm it runs hotter than the coolant, the more so the harder the engine works
            // (a stock STI cruising at 105 km/h: oil ~106° at 90–95° coolant; the turbo is cooled by the oil too).
            val warmth = ((c - 60) / 20f).coerceIn(0f, 1f) // no gap while the thermostat is still shut
            val gap = (if (turboCar) 4f + 0.33f * mafAvg else 2f + 0.25f * mafAvg).coerceAtMost(30f) * warmth
            val target = c + gap
            val o = oilEst ?: (c - 5f)
            (o + (target - o) * (dt / 420f).coerceAtMost(1f)).also { oilEst = it }
        } else null
        val cold = running && oil != null && oil < 60f

        // --- thermostat: warm-up time from a cold start
        if (running && c != null) {
            if (coldStartMs == 0L && c < 45 && engineOnSince != 0L && now - engineOnSince < 120_000L) {
                coldStartMs = now; movingMsSinceCold = 0L; thermostatWarned = false
            }
            if (coldStartMs != 0L) {
                if (speed > 30) movingMsSinceCold += (dt * 1000).toLong()
                if (c >= 80) {
                    warmupMin = (now - coldStartMs) / 60_000f
                    coldStartMs = 0L
                } else if (fCool && movingMsSinceCold > 20 * 60_000L && c < 72 && !thermostatWarned) {
                    thermostatWarned = true
                    Alerts.raise("thermostat", "Мотор не прогревается: 20 минут в движении, а ОЖ всего $c°. Похоже, термостат открыт", Alerts.Level.WARN, 6 * 3600_000L)
                }
            }
        } else if (!running) coldStartMs = 0L

        // --- coolant: overheating and sudden rise on a warm engine
        if (c != null && running && fCool) {
            coolant.addLast(now to c)
            while (coolant.isNotEmpty() && now - coolant.first().first > 90_000L) coolant.removeFirst()
            // right after reaching 80° the thermostat may still be opening and the coolant overshoots: no spike check then
            if (c < 80) warmSince = 0L else if (warmSince == 0L) warmSince = now
            val old = coolant.firstOrNull { now - it.first in 50_000L..90_000L }?.second
            if (old != null && old in 80..98 && c - old >= 8 && now - warmSince > 5 * 60_000L) {
                Alerts.raise("spike", "ОЖ выросла с $old° до $c° за минуту. Чаще всего это воздух в системе охлаждения, особенно после замены антифриза. " +
                    "Если повторяется после прокачки, проверьте прокладку ГБЦ", Alerts.Level.WARN, 20 * 60_000L)
            }
        } else if (!running) warmSince = 0L
        val atf = t?.atfC
        // the transmission's sample only while fresh: a TCU that stopped answering must not feed a gear frozen at 3
        val tFresh = t?.takeIf { now - it.timeMs in 0L..2500L }
        // a rule failing must not cost the guard's own update below
        runCatching {
            analyzer?.process(RuleInput(e, tFresh, now, iam = if (fKnock) iam else null, fbkc = if (fKnock) fbkc else null, flkc = if (fKnock) flkc else null,
                oilC = oil, turbo = turboCar, targetBoostKpa = target))
        }.onFailure { kg.forester.sg.ssm.SsmTrace.log("analyzer: $it") }

        // --- maintenance reminder once a day when driving off
        if (fService && running && now - maintenanceChecked > 24 * 3600_000L) {
            maintenanceChecked = now
            Maintenance.dueText()?.let { Alerts.raise("service", it, Alerts.Level.INFO, 24 * 3600_000L) }
        }

        // --- shift light
        Repo.shift.value = s.shiftLight && rpm >= s.shiftRpm

        val g = Repo.guard.value
        Repo.guard.value = GuardState(
            iam = iam, iamStart = iamStart, fbkc = fbkc, flkc = flkc, targetBoostKpa = target,
            oilC = oil, oilEstimated = oilEcu == null, cold = cold, turbo = turbo, turboCar = turboCar, heat = heat, cooldownSec = cooldown,
            warmupMin = warmupMin ?: g.warmupMin,
            thermostat = when {
                coldStartMs != 0L -> "прогрев: ${(now - coldStartMs) / 60_000} мин, ОЖ ${c ?: "—"}°"
                warmupMin != null -> "последний прогрев до 80°: %.0f мин".format(warmupMin)
                else -> ""
            },
            coolantMax = maxOf(g.coolantMax ?: Int.MIN_VALUE, c ?: Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            atfMax = maxOf(g.atfMax ?: Int.MIN_VALUE, atf ?: Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
        )
        updatePeaks(e, t)
        updateRange(e, t)
    }

    /** Min / max of every home card value over the trip (like BtSSM), only while the engine runs. */
    private fun updateRange(e: EcuSnapshot, t: TcuSnapshot?) {
        if ((e.rpm ?: 0) < 400) return
        val trip = Repo.trip.value
        val now = mapOf(
            "coolant" to e.coolantC?.toFloat(), "intake" to e.intakeC?.toFloat(), "battery" to e.batteryV,
            "throttle" to e.throttlePct, "maf" to e.mafGs, "afr" to e.afr, "afcorr" to e.afCorrPct, "aflearn" to e.afLearnPct,
            "timing" to e.timingDeg, "knock" to e.knockDeg, "inj" to e.injPwMs, "wgdc" to e.wastegatePct, "boost" to e.boostBar,
            "pedal" to e.pedalPct, "avcs" to e.avcsDeg, "map" to e.mapKpa, "atm" to e.atmKpa, "alt" to e.altDutyPct,
            "atf" to t?.atfC?.toFloat(), "fuel" to trip.instLh.toFloat(), "per100" to trip.instPer100?.toFloat(),
            "gps" to Repo.gps.value?.speedKmh, "load" to e.loadGRev,
            "rpm" to e.rpm?.toFloat(), "speed" to e.speedKmh?.toFloat(),
        )
        val old = Repo.tripRange.value
        val next = HashMap(old)
        for ((k, v) in now) {
            if (v == null || v.isNaN()) continue
            val r = old[k]
            next[k] = if (r == null) v to v else minOf(r.first, v) to maxOf(r.second, v)
        }
        Repo.tripRange.value = next
    }

    /** Target boost relative to the atmosphere, whatever units this ROM's definition uses. */
    private fun targetBoost(e: EcuSnapshot): Float? {
        val p = roles["target"] ?: return null
        val raw = Repo.rrValues.value[p.id]?.toFloat() ?: return null
        val u = p.units.lowercase()
        val k = when { "bar" in u -> 100f; "psi" in u -> 6.895f; "mmhg" in u -> 0.1333f; else -> 1f }
        val atm = e.atmKpa ?: 101.3f
        return when {
            "sea level" in u -> raw * k + 101.3f - atm
            "relative" in u -> raw * k
            else -> raw * k - atm
        }
    }

    private fun updatePeaks(e: EcuSnapshot, t: TcuSnapshot?) {
        val p = Repo.peaks.value
        fun mx(a: Float?, b: Float?) = if (a == null) b else if (b == null) a else maxOf(a, b)
        fun mxi(a: Int?, b: Int?) = if (a == null) b else if (b == null) a else maxOf(a, b)
        val lean = e.afr?.takeIf { (e.gaugeKpa ?: 0f) > 30f }
        Repo.peaks.value = p.copy(
            boostBar = mx(p.boostBar, e.boostBar),
            mafGs = mx(p.mafGs, e.mafGs),
            loadGRev = mx(p.loadGRev, e.loadGRev),
            rpm = mxi(p.rpm, e.rpm),
            speedKmh = mxi(p.speedKmh, e.speedKmh),
            intakeC = mxi(p.intakeC, e.intakeC),
            coolantC = mxi(p.coolantC, e.coolantC),
            atfC = mxi(p.atfC, t?.atfC),
            knockDeg = e.knockDeg?.let { k -> p.knockDeg?.let { minOf(it, k) } ?: k },
            leanAfrUnderBoost = mx(p.leanAfrUnderBoost, lean),
        )
    }

    /** One IAM reading per drive, with the date: shows after which refill it went down. */
    private fun logIam(now: Long, iam: Float) {
        val s = Repo.settings
        val arr = runCatching { JSONArray(s.iamLog) }.getOrDefault(JSONArray())
        val last = if (arr.length() > 0) arr.getJSONObject(arr.length() - 1) else null
        if (last != null && kotlin.math.abs(last.getDouble("v") - iam) < 0.01 && now - last.getLong("t") < 7 * 24 * 3600_000L) return
        arr.put(JSONObject().put("t", now).put("v", iam.toDouble()))
        while (arr.length() > 60) arr.remove(0)
        s.iamLog = arr.toString()
    }
}
