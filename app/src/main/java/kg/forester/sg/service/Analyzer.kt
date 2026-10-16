package kg.forester.sg.service

import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlin.math.abs

/** One sample for the rules: the engine, the transmission and what the guard worked out from them. */
data class RuleInput(
    val e: EcuSnapshot,
    val t: TcuSnapshot? = null,
    val now: Long = e.timeMs,
    /** IAM 0…1, knock corrections in degrees (negative = timing pulled), from the guard's roles. */
    val iam: Float? = null,
    val fbkc: Float? = null,
    val flkc: Float? = null,
    /** Oil temperature, read or estimated by the guard. */
    val oilC: Float? = null,
    val turbo: Boolean = false,
    val targetBoostKpa: Float? = null,
)

/** What the rules keep between drives: in the app's preferences, in memory for the tests. */
interface RuleStore {
    /** The last time the engine was seen running (saved about once a minute): how long the car has been parked. */
    var lastRunMs: Long
    /** IAM at the end of the last drive, NaN = unknown. */
    var lastIam: Float
    /** When an ECU reset (IAM relearning from its initial value) was noticed, 0 = none. */
    var iamResetMs: Long
    /** This car's usual peak boost on a full-throttle pull, kPa, 0 = not learned yet. */
    var boostBase: Float
    /** The last refill, for «after the refill» in the knock warnings; null = unknown. */
    fun lastRefillMs(): Long? = null
}

class MemoryRuleStore : RuleStore {
    override var lastRunMs = 0L
    override var lastIam = Float.NaN
    override var iamResetMs = 0L
    override var boostBase = 0f
    var refillMs: Long? = null
    override fun lastRefillMs() = refillMs
}

/** Where a finding goes: [Alerts] in the app, a list in the tests. */
fun interface RuleSink {
    fun raise(id: String, text: String, level: Alerts.Level, cooldownMs: Long)
}

/** Average values over half a minute of warm idle, for the comparison with the factory table. */
data class IdleSample(val timeMs: Long, val values: Map<String, Float>)

/**
 * The rules from docs/knowledge (engine-ej.md, transmission-awd-electrical.md): knock and IAM with the ECU reset
 * and the low-load noise kept out, mixture trims, boost, idle, cooling, ATF and lock-up, front/rear tyres.
 * Each rule looks only at the situations where its numbers hold (warm engine, closed loop, warm idle, a pull),
 * so a normal car stays quiet. [features] are the switches in Settings → Предупреждения.
 */
class Analyzer(
    private val store: RuleStore,
    private val sink: RuleSink,
    private val features: (String) -> Boolean = { true },
    private val onIdle: (IdleSample) -> Unit = {},
) {
    /** Milliseconds a condition has held; 0 when it doesn't. */
    private class Hold {
        private var since = Long.MIN_VALUE
        private var seen = Long.MIN_VALUE
        fun ms(cond: Boolean, now: Long): Long {
            // not looked at for a while (the engine was off, a rule skipped): the condition starts over
            if (seen != Long.MIN_VALUE && now - seen > 5000L) since = Long.MIN_VALUE
            seen = now
            if (!cond) { since = Long.MIN_VALUE; return 0L }
            if (since == Long.MIN_VALUE) since = now
            return now - since
        }
    }

    private var lastSampleMs = Long.MIN_VALUE
    private var runningSince = 0L
    private var lastRunSaved = 0L
    private var lastIamSeen: Float? = null
    private var transientUntil = 0L
    private var lastThrottle: Pair<Long, Float>? = null
    private var lastGear: Int? = null
    private val speeds = ArrayDeque<Pair<Long, Int>>()
    private var accel = 0f
    private var corrNonZeroMs = Long.MIN_VALUE
    private var corrEverNonZero = false

    // knock
    private var iamStart: Float? = null
    private var tripRefill: Long? = null
    private var fbkcEvents = 0
    private var fbkcInEvent = false
    // mixture
    private val trims = ArrayDeque<Pair<Long, Float>>()
    private var idleTrimS = 0.0; private var idleTrimSum = 0.0
    private var cruiseTrimS = 0.0; private var cruiseTrimSum = 0.0
    private var idleLeanDone = false
    // boost
    private val gauges = ArrayDeque<Pair<Long, Float>>()
    private var pullStart = 0L
    private var pullPeak = 0f
    private var pullMaxRpm = 0
    private var pullMaxWgdc = 0f
    private val pullLows = ArrayDeque<Boolean>()
    // idle
    private val idleRpm = ArrayDeque<Pair<Long, Int>>()
    private val idleSums = HashMap<String, Double>()
    private var idleSumS = 0.0
    // cooling
    private var jamHotPeak = 0
    private var jamMoveStart = 0L
    // ATF
    private var lastAtf: Pair<Long, Int>? = null
    /** Times the ATF reading leapt by more than 20° in a second: one is a misread byte, several are a sensor. */
    private val atfJumps = ArrayDeque<Long>()
    private var atfImplausibleUntil = 0L
    private var g3Start = 0L
    private var g3Min = Int.MAX_VALUE
    private var g3Max = 0
    // tyres
    private var tyreS = 0.0; private var tyreFront = 0.0; private var tyreRear = 0.0
    private var tyresDone = false

    private val idleHold = Hold(); private val cruiseHold = Hold(); private val wotHold = Hold()
    private val belowMaxHold = Hold(); private val leanBoostHold = Hold(); private val learnHold = Hold(); private val railHold = Hold()
    private val frozenHold = Hold(); private val atmHold = Hold(); private val mapHold = Hold(); private val overboostHold = Hold()
    private val cutHold = Hold(); private val wgdcHold = Hold(); private val dutyHold = Hold(); private val idleHighHold = Hold()
    private val idleLowHold = Hold(); private val avcsHold = Hold(); private val warm102Hold = Hold(); private val speedHold = Hold()
    private val atfBadHold = Hold(); private val atf120Hold = Hold(); private val atf110Hold = Hold(); private val atf100Hold = Hold()
    private val atf105Hold = Hold(); private val lampHold = Hold(); private val lockHold = Hold(); private val jamHold = Hold()

    private fun on(f: String) = features(f)

    /** Forget the drive in progress (demo switched on or off): the next sample starts a new drive. */
    fun reset() { lastSampleMs = Long.MIN_VALUE; runningSince = 0L; lastIamSeen = null }
    private fun say(id: String, text: String, level: Alerts.Level, cooldownMs: Long) = sink.raise(id, text, level, cooldownMs)

    fun process(x: RuleInput) {
        val e = x.e
        val now = x.now
        val rpm = e.rpm ?: 0
        val running = rpm > 400
        val speed = e.speedKmh ?: 0
        val thr = e.throttlePct ?: e.pedalPct
        val c = e.coolantC
        val load = e.loadGRev
        val gauge = e.gaugeKpa

        // a gap in the samples = the ignition was off: a new key-on
        val keyOn = lastSampleMs == Long.MIN_VALUE || now - lastSampleMs > 60_000L
        val dt = if (keyOn) 0f else ((now - lastSampleMs) / 1000f).coerceIn(0f, 2f)
        lastSampleMs = now
        if (keyOn) {
            // the last drive may have ended with the ECU just going silent: keep its IAM all the same
            lastIamSeen?.let { store.lastIam = it }
            keyOnChecks(e, now)
            newDrive(e, now)
        }

        if (!running) {
            if (runningSince != 0L) endDrive(now)
            runningSince = 0L
        } else {
            if (runningSince == 0L) runningSince = now
            if (now - lastRunSaved > 60_000L) {
                lastRunSaved = now; store.lastRunMs = now
                lastIamSeen?.let { store.lastIam = it }
            }
        }
        val runMs = if (runningSince == 0L) 0L else now - runningSince

        // --- contexts
        // warm: 75° and the engine running five minutes this time (not since some earlier drive)
        val warm = running && c != null && c >= 75 && runMs >= 5 * 60_000L
        if (thr != null) {
            lastThrottle?.let { (t0, v0) ->
                val s = (now - t0) / 1000f
                if (s in 0.05f..2f && abs(thr - v0) / s > 30f) transientUntil = now + 1500
            }
            lastThrottle = now to thr
        }
        val gear = x.t?.gear
        if (gear != null && lastGear != null && gear != lastGear) transientUntil = now + 1500
        lastGear = gear
        // acceleration over about two seconds: the ECU's whole km/h flicker (79, 80, 79) is not a change of pace
        speeds.addLast(now to speed)
        while (speeds.size > 2 && now - speeds.first().first > 2500L) speeds.removeFirst()
        speeds.first().let { (t0, v0) -> val s = (now - t0) / 1000f; accel = if (s >= 1f) (speed - v0) / s else 0f }
        val transient = now < transientUntil
        val decel = thr != null && thr < 1f && rpm > 1300 && speed > 10
        if (e.afCorrPct != null && e.afCorrPct != 0f) { corrNonZeroMs = now; corrEverNonZero = true }
        val closedLoop = warm && thr != null && thr < 60f && (e.mafGs ?: 0f) < 50f && (e.injPwMs ?: 1f) > 0.5f && !decel &&
            corrNonZeroMs != Long.MIN_VALUE && now - corrNonZeroMs < 5000
        val idleNow = running && speed == 0 && (thr ?: 0f) < 3f && rpm < 1200
        val idleMs = idleHold.ms(idleNow, now)
        val hotIdle = warm && c != null && c >= 80 && idleMs >= 20_000L
        val cruiseMs = cruiseHold.ms(speed >= 40 && abs(accel) < 1f && thr != null && thr in 5f..40f, now)
        val cruise = warm && cruiseMs >= 10_000L
        val wot = (thr ?: 0f) >= 90f
        val wotMs = wotHold.ms(wot, now)
        val pull = wotMs >= 1000L

        if (running) {
            knock(x, now, warm, transient, pull, load, rpm, thr, gauge)
            if (on("mixture")) mixture(e, now, dt, warm, closedLoop, hotIdle, cruise, speed, thr)
            air(e, now, hotIdle)
            if (x.turbo && on("boost")) boost(x, now, rpm, thr, gauge, wot, c)
            if (on("idle")) idle(e, now, hotIdle, rpm)
            if (on("cooling") && c != null) cooling(now, c, speed, runMs)
            if (on("warmup")) cold(x, rpm, load, gauge)
        }
        if (hotIdle) collectIdle(e, x, now, dt) else { idleSums.clear(); idleSumS = 0.0 }
        x.t?.let { t ->
            if (on("atf")) atf(t, now, speed, rpm, running)
            if (on("awd")) tyres(t, now, dt, speed, thr)
        }
        if (runMs > 0) lastIamSeen = x.iam ?: lastIamSeen
    }

    private fun newDrive(e: EcuSnapshot, now: Long) {
        iamStart = null; fbkcEvents = 0; fbkcInEvent = false
        trims.clear(); idleTrimS = 0.0; idleTrimSum = 0.0; cruiseTrimS = 0.0; cruiseTrimSum = 0.0; idleLeanDone = false
        tyreS = 0.0; tyreFront = 0.0; tyreRear = 0.0; tyresDone = false
        jamHotPeak = 0; jamMoveStart = 0L; atfJumps.clear(); lastAtf = null; g3Start = 0L; pullStart = 0L
        tripRefill = store.lastRefillMs()
    }

    private fun endDrive(now: Long) {
        lastIamSeen?.let { store.lastIam = it }
        store.lastRunMs = now
    }

    // ---------------------------------------------------------------- knock and IAM (engine-ej K01…K13)

    /** After an ECU reset IAM starts low (often 0.5) and climbs as the ECU relearns: not a knock problem. */
    private fun afterReset(iam: Float?, now: Long): Boolean {
        val since = store.iamResetMs
        if (since == 0L) return false
        if ((iam != null && iam >= 0.95f) || now - since > 30L * 24 * 3600_000L) { store.iamResetMs = 0L; return false }
        return true
    }

    private fun knock(x: RuleInput, now: Long, warm: Boolean, transient: Boolean, pull: Boolean, load: Float?, rpm: Int, thr: Float?, gauge: Float?) {
        if (!on("knock")) return
        val iam = x.iam
        if (iam != null) {
            if (iamStart == null) {
                iamStart = iam
                // lower than at the end of the last drive: IAM never drops with the engine off, so the ECU was reset
                val before = store.lastIam
                if (iam <= 0.8f && ((!before.isNaN() && before - iam >= 0.2f) || x.e.afLearnPct == 0f)) store.iamResetMs = now
            } else if (iamStart!! <= 0.65f && iam - iamStart!! >= 0.05f && store.iamResetMs == 0L) {
                store.iamResetMs = now // climbing from an initial-looking value during the drive: the ECU relearns after a reset
            } else if (iam < iamStart!! - 0.04f && store.iamResetMs != 0L) {
                store.iamResetMs = 0L // falling, not relearning: knock again, the low-IAM warnings apply
            }
        }
        val reset = afterReset(iam, now)
        if (iam != null && reset) {
            say("iamRelearn", "После сброса ЭБУ заново подбирает угол зажигания, IAM сейчас %.2f. Это нормально. ".format(iam) +
                "Первые 50–100 км поездите спокойно, без полного газа на холодном моторе", Alerts.Level.INFO, 24 * 3600_000L)
        }
        if (iam != null && warm && !reset) {
            val start = iamStart ?: iam
            when {
                iam < 0.75f -> say("iam", "IAM упал до %.2f: ЭБУ сильно урезал угол зажигания из-за детонации. ".format(iam) +
                    "Не давите газ в пол и не грузите мотор, пока не заправитесь хорошим бензином", if (x.turbo) Alerts.Level.DANGER else Alerts.Level.WARN, 30 * 60_000L)
                start - iam >= 0.1f -> {
                    val fuel = tripRefill?.let { now - it < 2 * 24 * 3600_000L } == true
                    say("iamDrop", "IAM снизился с %.2f до %.2f за поездку: была детонация.".format(start, iam) +
                        if (fuel) " Вы недавно заправлялись, скорее всего дело в бензине" else " Проверьте бензин", Alerts.Level.WARN, 30 * 60_000L)
                }
            }
            if (belowMaxHold.ms(iam < 0.95f, now) >= 10 * 60_000L) {
                say("iamBelow", "IAM %.2f, ниже максимума: ЭБУ уменьшил угол на всех режимах, потому что раньше слышал детонацию. ".format(iam) +
                    "Мотор тянет хуже и расходует больше. Чаще всего помогает хороший бензин", Alerts.Level.INFO, 24 * 3600_000L)
            }
        } else belowMaxHold.ms(false, now)

        // knock counts only where it means something: warm, not a light-load tap, not a throttle stab or a shift
        val c = x.e.coolantC ?: 0
        val loaded = if (load != null) !(load < 0.75f && rpm < 3000) else (thr ?: 0f) >= 40f || rpm >= 3000
        val valid = warm && c >= 60 && loaded && !transient
        val heavy = load?.let { it >= 1.0f } ?: ((thr ?: 0f) >= 60f)
        val hot = x.e.intakeC?.takeIf { it >= if (x.turbo) 50 else 55 }
        val hotNote = hot?.let { " Воздух во впуске очень горячий ($it°), от этого мотор склонен к детонации. Дайте машине проехать спокойно, чтобы впуск остыл" } ?: ""
        val flkc = x.flkc
        if (flkc != null && valid) {
            when {
                flkc <= -4f && heavy -> say("flkc", "Мотор устойчиво детонирует под нагрузкой: ЭБУ выучил поправку %.1f°, дальше он сбросит IAM. ".format(flkc) +
                    "Снизьте нагрузку и заправьтесь хорошим бензином. Если повторится, нужна диагностика.$hotNote", Alerts.Level.DANGER, 30 * 60_000L)
                flkc <= -2.8f -> say("flkc", "Выученная коррекция по детонации %.1f°: мотор стабильно детонирует на этом режиме. Проверьте бензин.$hotNote".format(flkc),
                    Alerts.Level.WARN, 30 * 60_000L)
                flkc <= -1.4f && heavy -> say("flkcStep", "ЭБУ уже снимал угол на этом режиме (%.1f°). Не крутите мотор в натяг на этой передаче и присмотритесь к бензину.$hotNote".format(flkc),
                    Alerts.Level.INFO, 6 * 3600_000L)
            }
        }
        val fbkc = x.fbkc
        if (fbkc != null && valid) {
            val boosted = (gauge ?: 0f) > 20f
            if (boosted && fbkc <= -4f) {
                if (!fbkcInEvent) { fbkcInEvent = true; fbkcEvents++ }
                val bad = fbkc <= -6f || fbkcEvents >= 3
                say("fbkc", "Сильный откат зажигания %.1f° под наддувом".format(fbkc) +
                    (if (bad) ", и не первый раз за поездку. Не разгоняйтесь в пол до проверки бензина и смеси" else "") + ".$hotNote",
                    if (bad) Alerts.Level.DANGER else Alerts.Level.WARN, if (bad) 10 * 60_000L else 5 * 60_000L)
            } else if (pull && heavy && fbkc <= -2f) {
                say("fbkcPull", "Во время разгона мотор детонировал, ЭБУ убрал %.1f°. Если повторяется на тех же оборотах, ".format(fbkc) +
                    "не разгоняйтесь в пол до проверки бензина и смеси.$hotNote", Alerts.Level.WARN, 10 * 60_000L)
            }
        }
        if ((fbkc ?: 0f) > -2f) fbkcInEvent = false

        // lean under boost: the stock sensor is poor there and reads rich at worst, so lean is worth a warning
        val lean = x.turbo && (gauge ?: 0f) > 50f && (thr ?: 0f) >= 90f && (x.e.lambda ?: 0f) > 0.85f
        if (leanBoostHold.ms(lean, now) >= 1000L) {
            say("leanBoost", "Под наддувом смесь бедная (AFR %.1f). Это опасно для поршней. Не давите газ в пол до проверки топливной системы".format(x.e.afr ?: 0f),
                Alerts.Level.DANGER, 10 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- cold engine (O01, B07)

    private fun cold(x: RuleInput, rpm: Int, load: Float?, gauge: Float?) {
        // warm at 80° coolant: that is where every EJ ends its warm-up enrichment (the oil is only an estimate)
        val c = x.e.coolantC ?: return
        if (c >= 80) return
        if (x.turbo && (gauge ?: 0f) > 30f) {
            say("coldBoost", "Мотор холодный, а вы уже под наддувом. Холодное масло хуже защищает вкладыши и турбину, подождите прогрева",
                Alerts.Level.WARN, 5 * 60_000L)
        } else if (rpm > 3600 || (load ?: 0f) > 1.0f) {
            say("cold", "Мотор не прогрет: антифриз $c°, прогрет он с 80. Пока не выше 3000 оборотов и без полного газа", Alerts.Level.WARN, 2 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- mixture (F01…F08)

    private fun mixture(e: EcuSnapshot, now: Long, dt: Float, warm: Boolean, closedLoop: Boolean, hotIdle: Boolean, cruise: Boolean, speed: Int, thr: Float?) {
        val corr = e.afCorrPct
        val learn = e.afLearnPct
        val total = if (corr != null && learn != null) corr + learn else null
        if (total != null && closedLoop) {
            trims.addLast(now to total)
            while (trims.isNotEmpty() && now - trims.first().first > 120_000L) trims.removeFirst()
            if (trims.size >= 30 && now - trims.first().first >= 60_000L) {
                val mean = trims.sumOf { it.second.toDouble() } / trims.size
                when {
                    mean >= 25 -> say("lean", "Смесь сильно бедная: ЭБУ добавляет %.0f%% топлива. Проверьте, не слетела ли гофра впуска, ".format(mean) +
                        "шланги и хомуты. Не нагружайте мотор до проверки", Alerts.Level.DANGER, 30 * 60_000L)
                    mean >= 15 -> say("lean", "ЭБУ постоянно добавляет топливо (%.0f%%): в мотор попадает лишний воздух или не хватает бензина. ".format(mean) +
                        "Проверьте гофру между датчиком расхода воздуха и дросселем, шланги вентиляции картера, почистите датчик расхода воздуха", Alerts.Level.WARN, 30 * 60_000L)
                    mean <= -25 -> say("rich", "Смесь сильно богатая: ЭБУ убирает %.0f%% топлива. Растёт расход и страдает катализатор, нужна проверка".format(-mean),
                        Alerts.Level.DANGER, 30 * 60_000L)
                    mean <= -15 -> say("rich", "ЭБУ постоянно убирает топливо (%.0f%%), смесь слишком богатая. ".format(-mean) +
                        "Частые причины: клапан продувки бензобака, подтекающая форсунка, высокое давление топлива", Alerts.Level.WARN, 30 * 60_000L)
                }
            }
        }
        val learnMs = learnHold.ms(warm && learn != null && abs(learn) >= 14.5f, now)
        if (learn != null && learnMs >= 30_000L) {
            if (learn > 0) say("lean", "Обучение смеси упёрлось в +%.0f%%: ЭБУ больше не может компенсировать нехватку топлива. ".format(learn) +
                "Ищите подсос воздуха после датчика расхода или слабый бензонасос", Alerts.Level.WARN, 30 * 60_000L)
            else say("rich", "Обучение смеси упёрлось в %.0f%%: смесь слишком богатая. Проверьте клапан продувки бензобака и форсунки".format(learn),
                Alerts.Level.WARN, 30 * 60_000L)
        }
        // lean only at idle = a vacuum leak (it shows most at small air flow)
        if (total != null && closedLoop) {
            if (hotIdle) { idleTrimS += dt; idleTrimSum += total * dt }
            if (cruise) { cruiseTrimS += dt; cruiseTrimSum += total * dt }
            if (!idleLeanDone && idleTrimS >= 60 && cruiseTrimS >= 60) {
                idleLeanDone = true
                val diff = idleTrimSum / idleTrimS - cruiseTrimSum / cruiseTrimS
                if (diff >= 8) say("idleLean", "На холостых ЭБУ добавляет заметно больше топлива, чем в движении (на %.0f%%). ".format(diff) +
                    "Так обычно проявляется подсос воздуха: трещина в гофре впуска или шланге", Alerts.Level.WARN, 6 * 3600_000L)
            }
        }
        if (corr != null && railHold.ms(closedLoop && abs(corr) >= 24f, now) >= 10_000L) {
            say("afRail", "Смесь резко ушла из нормы прямо сейчас (коррекция %.0f%%). Если мотор троит или глохнет, проверьте, не слетела ли гофра воздушного фильтра".format(corr),
                Alerts.Level.WARN, 15 * 60_000L)
        }
        // the correction stuck at exactly 0 while driving: the ECU ignores the A/F sensor (its heater, usually)
        val frozen = corrEverNonZero && corr == 0f && warm && speed >= 20 && thr != null && thr in 5f..40f
        if (frozenHold.ms(frozen, now) >= 60_000L) {
            say("afFrozen", "ЭБУ не использует датчик состава смеси и работает вслепую. Обычно это неисправность датчика или его подогрева, проверьте ошибки. Расход может вырасти",
                Alerts.Level.WARN, 3 * 3600_000L)
        }
        val duty = e.injDutyPct
        val dutyMs = dutyHold.ms((duty ?: 0f) >= 85f, now)
        if (duty != null && dutyMs >= 1000L) {
            say("injDuty", "Форсунки работают почти без пауз (%.0f%%). Запаса по топливу больше нет, это опасно на полном газу".format(duty),
                if (duty >= 95f) Alerts.Level.DANGER else Alerts.Level.WARN, 10 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- air and sensors (A02, A06, A07)

    private fun air(e: EcuSnapshot, now: Long, hotIdle: Boolean) {
        val atm = e.atmKpa
        // 50 kPa: the highest passes here (Ak-Baital ~57 kPa) are real
        if (atm != null && atmHold.ms(atm < 50f || atm > 108f, now) >= 5000L) {
            say("atm", "Датчик атмосферного давления показывает нереальные %.0f кПа. ЭБУ может неверно считать наддув и смесь".format(atm),
                Alerts.Level.WARN, 3 * 3600_000L)
        }
        val map = e.mapKpa
        // absolute: a healthy engine idles at about the same manifold pressure at any altitude (the factory 24–41 kPa)
        if (on("idle") && map != null && mapHold.ms(hotIdle && map > 43f, now) >= 30_000L) {
            say("idleMap", "На холостых во впуске слабое разрежение (%.0f кПа). Так бывает при большом подсосе воздуха, низкой компрессии или сбитых фазах".format(map),
                Alerts.Level.WARN, 6 * 3600_000L)
        }
    }

    /** Right after a long park the coolant and the intake air are at the same (outside) temperature. */
    private fun keyOnChecks(e: EcuSnapshot, now: Long) {
        val parked = store.lastRunMs
        // ignition on, engine not turned yet (a remote start or a block heater warms the coolant: not a fault)
        if (parked == 0L || now - parked < 9 * 3600_000L || (e.rpm ?: 0) > 0) return
        val c = e.coolantC ?: return
        val i = e.intakeC ?: return
        // the coolant reading colder than the air after a night is the implausible direction (the ECU then over-fuels)
        if (c <= 30 && i <= 30 && i - c > 8) {
            say("tempPlaus", "После долгой стоянки датчики температуры антифриза ($c°) и воздуха ($i°) должны показывать почти одинаково. " +
                "Один из датчиков может врать, из-за этого хуже пуск и расход", Alerts.Level.WARN, 24 * 3600_000L)
        }
    }

    // ---------------------------------------------------------------- boost (B01…B05, turbo only)

    private fun boost(x: RuleInput, now: Long, rpm: Int, thr: Float?, gauge: Float?, wot: Boolean, c: Int?) {
        val e = x.e
        if (gauge != null) {
            gauges.addLast(now to gauge)
            while (gauges.isNotEmpty() && now - gauges.first().first > 1500L) gauges.removeFirst()
        }
        val target = x.targetBoostKpa
        if (target != null && gauge != null && overboostHold.ms(gauge > target + 20f, now) >= 1000L) {
            say("overboost", "Наддув выше нормы: %.2f бар при цели %.2f. Это нагружает поршни. Проверьте шланги к актуатору вестгейта и соленоиду".format(gauge / 100f, target / 100f),
                Alerts.Level.WARN, 10 * 60_000L)
        }
        // fuel cut: full throttle, the injectors suddenly off, right after high boost
        val ref = store.boostBase.takeIf { it > 0f } ?: 70f
        val recentPeak = gauges.filter { now - it.first <= 1000L }.maxOfOrNull { it.second } ?: 0f
        // below the rev limiter (EJ25x cut fuel at ~6400–6700 rpm on purpose)
        val cut = (thr ?: 0f) >= 80f && rpm in 2500..6000 && ((e.injPwMs?.let { it < 0.5f } == true) || (e.lambda?.let { it > 1.5f } == true))
        if (cutHold.ms(cut, now) >= 200L && recentPeak > 0.8f * ref) {
            say("fuelCut", "На разгоне ЭБУ отключил подачу топлива из-за превышения наддува, отсюда рывок. Не разгоняйтесь в пол до проверки управления наддувом",
                Alerts.Level.DANGER, 10 * 60_000L)
        }
        // underboost: each full-throttle pull compared with this car's usual peak
        if (wot) {
            if (pullStart == 0L) { pullStart = now; pullPeak = 0f; pullMaxRpm = 0; pullMaxWgdc = 0f }
            pullPeak = maxOf(pullPeak, gauge ?: 0f)
            pullMaxRpm = maxOf(pullMaxRpm, rpm)
            pullMaxWgdc = maxOf(pullMaxWgdc, e.wastegatePct ?: 0f)
        } else if (pullStart != 0L && (thr ?: 0f) < 80f) {
            val long = now - pullStart >= 1500L
            pullStart = 0L
            if (long && pullMaxRpm >= 4000 && (c ?: 0) >= 70) judgePull(x)
        }
        if (wgdcHold.ms((e.wastegatePct ?: 0f) >= 95f, now) >= 3000L) {
            say("wgdcMax", "Соленоид наддува работает на пределе: турбина или впуск не справляются с запросом ЭБУ", Alerts.Level.INFO, 6 * 3600_000L)
        }
    }

    private fun judgePull(x: RuleInput) {
        val base = store.boostBase
        if (base <= 0f) { if (pullPeak >= 30f) store.boostBase = pullPeak; return }
        val low = pullPeak < 0.6f * base
        if (!low) store.boostBase = base * 0.85f + pullPeak * 0.15f
        pullLows.addLast(low)
        while (pullLows.size > 3) pullLows.removeFirst()
        if (low && base >= 40f && pullLows.count { it } >= 2) {
            val text = when {
                pullMaxWgdc >= 70f -> "Турбина не даёт положенного наддува (%.2f бар вместо обычных %.2f), хотя ЭБУ его просит. ".format(pullPeak / 100f, base / 100f) +
                    "Обычно это утечка во впуске после турбины или заклинивший вестгейт: проверьте хомуты и патрубки интеркулера"
                (x.iam ?: 1f) < 0.75f || pullMaxWgdc < 20f -> "ЭБУ сам ограничивает наддув (%.2f бар вместо %.2f). Чаще всего после детонации или из-за ошибки: ".format(pullPeak / 100f, base / 100f) +
                    "посмотрите коды и IAM, заправьтесь хорошим бензином"
                else -> "Наддув на разгоне ниже обычного: %.2f бар вместо %.2f. Проверьте впуск на утечки и шланги соленоида наддува".format(pullPeak / 100f, base / 100f)
            }
            say("underboost", text, Alerts.Level.WARN, 30 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- idle (I01, I02, V01) and the idle sample

    private fun idle(e: EcuSnapshot, now: Long, hotIdle: Boolean, rpm: Int) {
        if (idleHighHold.ms(hotIdle && (e.batteryV ?: 0f) > 12.8f && rpm > 1050, now) >= 60_000L) {
            say("idleHigh", "Прогретый мотор держит повышенные обороты на холостых ($rpm). Обычно это подсос воздуха или грязный дроссель и клапан холостого хода",
                Alerts.Level.INFO, 6 * 3600_000L)
        }
        if (idleLowHold.ms(hotIdle && rpm < 550, now) >= 5000L) {
            say("idleLow", "Обороты холостого хода падают ($rpm), мотор может заглохнуть. Помогает чистка дросселя и клапана холостого хода",
                Alerts.Level.WARN, 60 * 60_000L)
        }
        if (hotIdle) {
            idleRpm.addLast(now to rpm)
            while (idleRpm.isNotEmpty() && now - idleRpm.first().first > 20_000L) idleRpm.removeFirst()
            if (now - idleRpm.first().first >= 15_000L) {
                val r = idleRpm.map { it.second }
                var turns = 0
                var dir = 0
                for (i in 1 until r.size) {
                    val d = r[i] - r[i - 1]
                    if (abs(d) < 30) continue
                    val s = if (d > 0) 1 else -1
                    if (dir != 0 && s != dir) turns++
                    dir = s
                }
                if (r.max() - r.min() >= 150 && turns >= 4) {
                    say("idleHunt", "Обороты холостого хода плавают (${r.min()}–${r.max()}). Помогает чистка дросселя и клапана холостого хода, проверьте подсос воздуха",
                        Alerts.Level.WARN, 60 * 60_000L)
                }
            }
        } else idleRpm.clear()
        val avcs = e.avcsDeg
        if (avcs != null && avcsHold.ms(hotIdle && abs(avcs) > 5f, now) >= 10_000L) {
            say("avcsIdle", "Фазорегулятор распредвала не возвращается в исходное положение на холостых (%.0f°). Проверьте уровень и состояние масла, сетки клапанов AVCS".format(avcs),
                Alerts.Level.WARN, 6 * 3600_000L)
        }
    }

    private fun collectIdle(e: EcuSnapshot, x: RuleInput, now: Long, dt: Float) {
        if (dt <= 0f) return
        fun add(k: String, v: Float?) { if (v != null && !v.isNaN()) idleSums[k] = (idleSums[k] ?: 0.0) + v * dt }
        add("rpm", e.rpm?.toFloat()); add("coolant", e.coolantC?.toFloat()); add("timing", e.timingDeg); add("maf", e.mafGs)
        add("throttle", e.throttlePct); add("inj", e.injPwMs); add("afcorr", e.afCorrPct); add("aflearn", e.afLearnPct)
        add("map", e.mapKpa); add("battery", e.batteryV); add("avcs", e.avcsDeg); add("intake", e.intakeC?.toFloat())
        add("knock", x.fbkc ?: e.knockDeg)
        idleSumS += dt
        if (idleSumS >= 30.0) {
            onIdle(IdleSample(now, idleSums.mapValues { (it.value / idleSumS).toFloat() }))
            idleSums.clear(); idleSumS = 0.0
        }
    }

    // ---------------------------------------------------------------- cooling (C01…C03)

    private fun cooling(now: Long, c: Int, speed: Int, runMs: Long) {
        // a hot restart: the coolant by the sensor reads 103–108° for a minute or so (heat soak), not overheating
        if (runMs < 90_000L) { warm102Hold.ms(false, now); jamHold.ms(false, now); return }
        when {
            c >= 110 -> say("hot", "Перегрев! ОЖ $c°. Остановитесь в безопасном месте и дайте мотору остыть, проверьте уровень антифриза", Alerts.Level.DANGER, 2 * 60_000L)
            c >= 105 -> say("hot", "Перегрев! ОЖ $c°. Сбросьте нагрузку, следите за стрелкой, при 110° остановитесь", Alerts.Level.DANGER, 2 * 60_000L)
        }
        if (warm102Hold.ms(c >= 102, now) >= 30_000L && c < 105) {
            say("warm102", "Мотор горячее обычного: ОЖ $c°. Снизьте нагрузку и следите за температурой", Alerts.Level.WARN, 10 * 60_000L)
        }
        if (speedHold.ms(speed >= 70, now) >= 60_000L && c >= 100) {
            say("hotSpeed", "На скорости мотор не должен греться выше 100°, а сейчас $c°. Проверьте уровень антифриза. Если он уходит без видимых потёков, нужна проверка прокладки ГБЦ",
                Alerts.Level.WARN, 30 * 60_000L)
        }
        // hot only in traffic and cooling down on the move: the radiator fans don't switch on (they should cut in at 97–99°)
        if (jamHold.ms(c >= 100 && speed < 20, now) >= 60_000L) { jamHotPeak = maxOf(jamHotPeak, c); jamMoveStart = 0L }
        if (jamHotPeak > 0 && speed > 30) {
            if (jamMoveStart == 0L) jamMoveStart = now
            if (now - jamMoveStart > 3 * 60_000L) jamHotPeak = 0
            else if (c <= jamHotPeak - 5) {
                say("fans", "Мотор перегревался в пробке ($jamHotPeak°) и остывает на ходу. Скорее всего, не включаются вентиляторы радиатора: проверьте предохранители и реле",
                    Alerts.Level.WARN, 6 * 3600_000L)
                jamHotPeak = 0
            }
        }
    }

    // ---------------------------------------------------------------- ATF, lock-up, failsafe (transmission A, B, C)

    private fun atf(t: TcuSnapshot, now: Long, speed: Int, rpm: Int, running: Boolean) {
        val a = t.atfC
        if (a != null) {
            // a leap of more than 20° within a second; the way back from a one-sample spike is the same misread, not another
            if (lastAtf?.let { (t0, v0) -> now - t0 <= 1500L && abs(a - v0) > 20 } == true &&
                (atfJumps.isEmpty() || now - atfJumps.last() > 3000L)) atfJumps.addLast(now)
            while (atfJumps.isNotEmpty() && now - atfJumps.first() > 10 * 60_000L) atfJumps.removeFirst()
            lastAtf = now to a
            if (atfBadHold.ms(a <= -45 || a >= 150, now) >= 5000L || atfJumps.size >= 3) {
                atfJumps.clear()
                atfImplausibleUntil = now + 60_000L
                say("atfSensor", "Датчик температуры масла в коробке показывает неправдоподобные значения ($a°). Из-за этого коробка может переключаться жёстко, проверьте ошибки и разъём датчика",
                    Alerts.Level.WARN, 3 * 3600_000L)
            }
            val sane = now >= atfImplausibleUntil
            when {
                sane && (a >= 130 || atf120Hold.ms(a >= 120, now) >= 30_000L) ->
                    say("atfHot", "Коробка опасно перегрета: ATF $a°. Остановитесь в безопасном месте, поставьте P и дайте мотору поработать на холостых несколько минут",
                        Alerts.Level.DANGER, 3 * 60_000L)
                sane && atf110Hold.ms(a >= 110, now) >= 60_000L ->
                    say("atfWarm", "Коробка перегрета: ATF $a°. Сбавьте нагрузку, дайте ей остыть на ходу без рывков, проверьте радиатор и уровень масла",
                        Alerts.Level.WARN, 10 * 60_000L)
                sane && (atf100Hold.ms(a >= 100, now) >= 5 * 60_000L || atf105Hold.ms(a >= 105, now) >= 2 * 60_000L) ->
                    say("atfElevated", "Масло в коробке выше 100°. В пробке на долгих стоянках лучше ставить N или P, а в горку и с прицепом ехать спокойнее",
                        Alerts.Level.INFO, 3 * 3600_000L)
            }
            if (a < 120) atf120Hold.ms(false, now)
            if (a < 110) atf110Hold.ms(false, now)
        }
        if (lampHold.ms(t.atfLamp == true && speed > 10, now) >= 3000L) {
            say("atfLamp", "Горит лампа температуры коробки: блок АКПП сам видит перегрев или неисправность. Снизьте нагрузку и прочитайте ошибки АКПП", Alerts.Level.DANGER, 10 * 60_000L)
        }
        // failsafe: the TCU holds 3rd whatever the speed (cold ATF keeps out 4th on purpose, hence ATF ≥ 20°)
        val g = t.gear
        if (running && g == 3 && t.range == "D" && (a ?: 0) >= 20) {
            if (g3Start == 0L) { g3Start = now; g3Min = speed; g3Max = speed }
            g3Min = minOf(g3Min, speed); g3Max = maxOf(g3Max, speed)
            val held = now - g3Start
            if ((g3Min <= 10 && g3Max >= 80) || (held >= 2 * 60_000L && g3Min <= 20 && g3Max >= 90)) {
                say("limp", "Коробка ушла в аварийный режим и держит третью передачу. Ехать можно спокойно до сервиса, но без нагрузки, и нужно прочитать ошибки АКПП",
                    Alerts.Level.DANGER, 30 * 60_000L)
            }
        } else g3Start = 0L
        // the factory lock-up slip criterion (P0741 logic): commanded on, yet engine and turbine apart
        val tr = t.turbineRpm
        // steady speed only: the turbine speed comes a TCU poll (up to a second) later than the engine's
        val slip = t.lockUpPct != null && t.lockUpPct >= 90f && g != null && g in 3..4 && (a ?: 0) >= 50 && tr != null &&
            abs(accel) < 1.5f && rpm - tr > 40 + speed / 2
        if (lockHold.ms(slip, now) >= 10_000L) {
            say("lockSlipF", "Блокировка гидротрансформатора проскальзывает: мотор крутится на ${rpm - (tr ?: rpm)} об/мин быстрее турбины. " +
                "Начните с проверки уровня и замены масла в коробке, старое масло часто даёт именно это", Alerts.Level.WARN, 30 * 60_000L)
        }
    }

    /** Front and rear axles at different speeds on a steady straight road: different tyres (or pressures). */
    private fun tyres(t: TcuSnapshot, now: Long, dt: Float, speed: Int, thr: Float?) {
        val f = t.frontWheelKmh ?: return
        val r = t.rearWheelKmh ?: return
        if (tyresDone || dt <= 0f) return
        if (speed in 50..110 && abs(accel) < 1f && thr != null && thr in 5f..40f && r > 0) {
            tyreS += dt; tyreFront += f * dt; tyreRear += r * dt
        }
        if (tyreS >= 300) {
            tyresDone = true
            val ratio = (tyreFront - tyreRear) / tyreRear
            if (abs(ratio) >= 0.006) {
                say("tyres", "Передние и задние колёса крутятся с разной скоростью (на %.1f%%), похоже на разный износ или давление в шинах. ".format(abs(ratio) * 100) +
                    "Для полного привода Subaru это вредно: муфта постоянно работает на проскальзывание", Alerts.Level.INFO, 3 * 24 * 3600_000L)
            }
        }
    }
}
