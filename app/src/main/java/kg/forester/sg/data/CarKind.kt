package kg.forester.sg.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A Subaru (the whole app, as it always was) or «другая машина»: any other make on plain OBD-II. Then the
 * Subaru-only parts step aside: the АКПП screen (the SSM transmission unit), «Обучение», the 3D Subaru car and engine,
 * the RomRaider and CarBerry blocks; the header shows the make by the VIN.
 */
object CarKind {
    /** True: another make, or an ECU that knows no SSM at all. */
    val other = MutableStateFlow(false)

    /** The make by the current VIN, else by the last one (null: no VIN yet or the make is not in the base). */
    val make = MutableStateFlow<Brand?>(null)

    /**
     * The decision. [override] is the setting ("auto", "subaru", "other"); [vinSubaru] the VIN's make: true Subaru,
     * false another make, null no VIN or a make the base doesn't know; [obd] a plain OBD-II session; [ssm] an SSM ECU
     * answered (or the demo runs); [link] a sports ECU on the CAN (those sit in Subarus here); [lastOther] what was
     * decided at the last connection, for the time before the car answers.
     */
    fun decide(override: String, vinSubaru: Boolean?, obd: Boolean, ssm: Boolean, link: Boolean, lastOther: Boolean): Boolean = when {
        override == "subaru" -> false
        override == "other" -> true
        vinSubaru != null -> !vinSubaru
        ssm || link -> false
        obd -> true
        else -> lastOther
    }

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789" // 1980…2009, then the same letters from 2010

    /**
     * The model year from the VIN's 10th character, null when it can't be read. The letters come round every 30
     * years: a car on OBD-II is 1996 or newer, and a digit in place 7 (North American VINs) means before 2010.
     */
    fun modelYear(vin: String, thisYear: Int): Int? {
        if (vin.length != 17) return null
        val i = YEAR_CODES.indexOf(vin[9].uppercaseChar())
        if (i < 0) return null
        val old = 1980 + i
        val new = old + 30
        val soon = thisYear + 1
        return when {
            new <= soon && !(vin[6].isDigit() && old >= 1996) -> new
            old >= 1996 -> old
            new <= soon -> new
            else -> null
        }
    }

    /** The header of another car: the make and the year by the VIN, or «Автомобиль (OBD-II)». */
    fun header(make: String?, vin: String, connected: Boolean, thisYear: Int, title: String = "", custom: Boolean = false): Pair<String, String> {
        val year = modelYear(vin, thisYear)?.toString()
        val t = when {
            custom && title.isNotBlank() -> title
            make != null -> make
            else -> "Автомобиль (OBD-II)"
        }
        val state = if (!connected) "подключите адаптер" else if (make != null || custom) "OBD-II" else "стандартный OBD-II"
        return t to listOfNotNull(year, state).joinToString(" · ")
    }

    /** What the logo box shows; see [chooseLogo]. */
    sealed class LogoPick {
        object Subaru : LogoPick()
        object None : LogoPick()
        /** No make known: a plain car pictogram. */
        object Generic : LogoPick()
        /** The driver's own picture or a logo downloaded before (a file). */
        data class File(val path: String) : LogoPick()
        /** A logo that comes with the app (assets). */
        data class Asset(val path: String) : LogoPick()
        /** The make's name in bold; [download] the logo to fetch meanwhile (once, when online). */
        data class Text(val name: String, val download: String? = null) : LogoPick()
    }

    /**
     * The logo by the setting: [mode] "auto" / "subaru" / "custom" / "none"; [custom] the own picture's file (null:
     * none or gone); [slug], [name] the make; [asset] its logo in the app, [cached] one downloaded before, [remote] where
     * to get it. Each missing step falls to the next: own picture → by the car → bundled → downloaded → the name.
     */
    fun chooseLogo(
        mode: String, other: Boolean, custom: String?, slug: String?, name: String?,
        asset: String?, cached: String?, remote: String?,
    ): LogoPick {
        when (mode) {
            "none" -> return LogoPick.None
            "subaru" -> return LogoPick.Subaru
            "custom" -> if (custom != null) return LogoPick.File(custom)
        }
        if (!other || slug == "subaru") return LogoPick.Subaru
        if (slug == null || name.isNullOrBlank()) return LogoPick.Generic
        asset?.let { return LogoPick.Asset(it) }
        cached?.let { return LogoPick.File(it) }
        return LogoPick.Text(name, remote)
    }

    /** What the start-up screen shows: "route" (the neon road picture), "subaru" (the picture as before), a logo, "name" (the app's name). */
    sealed class SplashPick {
        object Route : SplashPick()
        object Subaru : SplashPick()
        object Name : SplashPick()
        data class Logo(val logo: LogoPick) : SplashPick()
    }

    /** [mode] "route" / "subaru" / "car" / "custom" / "name"; [carLogo] the last car's logo by [chooseLogo] in "auto". */
    fun chooseSplash(mode: String, custom: String?, carLogo: LogoPick): SplashPick = when (mode) {
        // the road picture shows no make: the same for any car
        "route" -> SplashPick.Route
        // no logo of the make at hand: its name in big letters; no make at all: the app's name
        "car" -> if (carLogo == LogoPick.Generic || carLogo == LogoPick.None) SplashPick.Name else SplashPick.Logo(carLogo)
        "custom" -> custom?.let { SplashPick.Logo(LogoPick.File(it)) } ?: SplashPick.Name
        "name" -> SplashPick.Name
        else -> SplashPick.Subaru
    }

    /**
     * The home cards of another car's untouched factory screen: what it actually reports ([seen]: card ids that had a
     * value), in the usual order, then the fuel and GPS ones, which count by themselves. Boost only on a turbo.
     */
    fun obdHomeCards(seen: Set<String>, turbo: Boolean): List<String> {
        val wanted = listOf("coolant", "intake", "battery", "throttle", "maf", "map", "afr", "afcorr", "aflearn", "timing", "pedal", "atm")
        val got = wanted.filter { it in seen }.toMutableList()
        if ("maf" in got && "map" in got) got -= "map" // one of the two is enough for the load
        if (turbo && "boost" in seen) got.add(minOf(got.size, 4), "boost")
        val always = listOf("fuel", "per100", "range", "gps")
        val room = HomeLayout.COLUMNS * 3 - always.size
        val extra = listOf("rpm", "speed").filter { it in seen }
        return (got + extra).take(room) + always
    }

    // -------- the running part: follows the ECU, the VIN and the setting --------

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Once per process (Repo.init): keeps [other] and [make] up to date, remembers the VIN and the decision. */
    fun start(ctx: android.content.Context) {
        if (started) return
        started = true
        val s = Repo.settings
        val app = ctx.applicationContext
        fun makeOf(vin: String) = vin.takeIf { it.isNotEmpty() }?.let { runCatching { Brands.get(app).makeFromVin(it) }.getOrNull() }
        scope.launch {
            combine(Repo.vin, Repo.obdMode, Repo.ecuInit, Repo.demoOn, Repo.carVersion) { vin, obd, init, demo, _ ->
                Triple(vin, obd, (init != null) to demo)
            }.collect { (vin, obd, ssmDemo) ->
                val (ssm, demo) = ssmDemo
                if (vin.isNotEmpty() && vin != s.lastVin) s.lastVin = vin
                // a Subaru on SSM without a VIN (SG and older): the last car's VIN is someone else's now
                if (vin.isEmpty() && ssm && !demo && s.lastVin.isNotEmpty() && makeOf(s.lastVin)?.slug != Brands.SUBARU) s.lastVin = ""
                // before an OBD car gives its VIN (a second or so) the last one stands for it
                val useVin = vin.ifEmpty { if (obd) s.lastVin else "" }
                val brand = makeOf(useVin)
                make.value = brand ?: makeOf(s.lastVin)
                val link = CanEcu.active
                val auto = decide("auto", brand?.let { it.slug == Brands.SUBARU }, obd, ssm || demo, link, s.carKindLast == "other")
                // only what the car itself said is remembered: the demo is the Subaru's
                if (vin.isNotEmpty() || obd || link || (ssm && !demo)) s.carKindLast = if (auto) "other" else "subaru"
                other.value = decide(s.carKind, brand?.let { it.slug == Brands.SUBARU }, obd, ssm || demo, link, s.carKindLast == "other")
            }
        }
        // another car's untouched factory screen gets the cards it fills, as the atmo and the Link screens do
        scope.launch {
            while (true) {
                delay(1000)
                if (!Repo.obdMode.value || !other.value || s.homeTiles.isNotEmpty()) continue
                val seen = HashSet<String>()
                var n = 0
                while (n < 6 && Repo.obdMode.value) {
                    delay(1000)
                    val e = Repo.live.value
                    mapOf(
                        "coolant" to e.coolantC, "intake" to e.intakeC, "battery" to e.batteryV, "throttle" to e.throttlePct,
                        "maf" to e.mafGs, "map" to e.mapKpa, "afr" to e.afr, "afcorr" to e.afCorrPct, "aflearn" to e.afLearnPct,
                        "timing" to e.timingDeg, "pedal" to e.pedalPct, "atm" to e.atmKpa, "boost" to e.boostBar,
                        "rpm" to e.rpm, "speed" to e.speedKmh,
                    ).forEach { (k, v) -> if (v != null) seen += k }
                    n++
                }
                if (n < 6 || seen.isEmpty() || s.homeTiles.isNotEmpty()) continue
                val on = obdHomeCards(seen, Repo.guard.value.turboCar)
                val board = HomeLayout.board
                s.homeTiles = board.format(on.map { TileCfg(it, true) } + board.defaults.filter { it.id !in on }.map { it.copy(on = false) })
            }
        }
    }
}
