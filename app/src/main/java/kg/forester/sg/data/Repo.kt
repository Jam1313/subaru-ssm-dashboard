package kg.forester.sg.data

import android.content.Context
import android.content.SharedPreferences
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

enum class LinkState { NO_CABLE, NO_PERMISSION, CONNECTING, CONNECTED, NO_ECU, ERROR }

data class GpsFix(
    val speedKmh: Float,
    val altitudeM: Double,
    val accuracyM: Float,
    val satellites: Int,
    val timeMs: Long,
    /** Course over ground, degrees clockwise from north; null when GPS has none (standing still). */
    val bearing: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

data class BatteryState(
    val restingV: Float? = null,
    val restingTimeMs: Long = 0,
    val crankMinV: Float? = null,
    val crankTimeMs: Long = 0,
    val crankMs: Long? = null,
    val chargingV: Float? = null,
    val chargingMinV: Float? = null,
    val chargingMaxV: Float? = null,
)

/** Live trip accumulators, updated by the service on every sample. */
data class TripState(
    val trip: Trip? = null,
    val instLh: Double = 0.0,
    val instPer100: Double? = null,
    val fuelLeftL: Double? = null,
    /** [fuelLeftL] comes from the ECU's fuel sender, not from counting since the refill. */
    val fuelFromSensor: Boolean = false,
    val rangeKm: Double? = null,
)

class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // installs from before the currency setting priced fuel in KGS: keep that for them
        if (!p.contains("currency") && p.contains("price")) p.edit().putString("currency", "KGS").apply()
    }

    var tankL: Double
        get() = p.getFloat("tank", 60f).toDouble() // 0 = unknown
        set(v) = p.edit().putFloat("tank", v.toFloat()).apply()

    var pricePerL: Double
        get() = p.getFloat("price", 82f).toDouble()
        set(v) = p.edit().putFloat("price", v.toFloat()).apply()

    /** Currency shown next to prices (₽, ₸, $, KGS…). */
    var currency: String
        get() = p.getString("currency", null) ?: localCurrency()
        set(v) = p.edit().putString("currency", v.trim().ifEmpty { localCurrency() }).apply()

    /** Currency of the device's country, as people write it on fuel prices. */
    private fun localCurrency(): String {
        val country = java.util.Locale.getDefault().country
        return when (country) {
            "RU" -> "₽"
            "KZ" -> "₸"
            "KG" -> "KGS"
            "BY" -> "BYN"
            "UZ" -> "сум"
            "UA" -> "₴"
            "AM" -> "֏"
            "GE" -> "₾"
            "AZ" -> "₼"
            "TJ" -> "TJS"
            else -> runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).symbol }.getOrDefault("")
        }
    }

    /** Fuel calculation correction, learned from full-to-full refills. */
    var fuelK: Double
        get() {
            val v = p.getFloat("fuelK", 1f).toDouble()
            // the calibration of older versions could run far off: start over instead of sitting at a limit
            if (v !in 0.7..1.4) { p.edit().putFloat("fuelK", 1f).apply(); return 1.0 }
            return v
        }
        set(v) = p.edit().putFloat("fuelK", v.toFloat()).apply()

    /** Real speed / ECU speed, learned from GPS. */
    var speedK: Double
        get() = p.getFloat("speedK", 1f).toDouble()
        set(v) = p.edit().putFloat("speedK", v.toFloat()).apply()

    /** Litres in the tank at the moment of the last refill. */
    var fuelAtRefillL: Double
        get() = p.getFloat("fuelAtRefill", -1f).toDouble()
        set(v) = p.edit().putFloat("fuelAtRefill", v.toFloat()).apply()

    /** Computed fuel used since the last refill. */
    var usedSinceRefillL: Double
        get() = p.getFloat("usedSinceRefill", 0f).toDouble()
        set(v) = p.edit().putFloat("usedSinceRefill", v.toFloat()).apply()

    /** Computed fuel used since the last FULL refill (for calibration). */
    var usedSinceFullL: Double
        get() = p.getFloat("usedSinceFull", 0f).toDouble()
        set(v) = p.edit().putFloat("usedSinceFull", v.toFloat()).apply()

    var hadFullRefill: Boolean
        get() = p.getBoolean("hadFull", false)
        set(v) = p.edit().putBoolean("hadFull", v).apply()

    var logEnabled: Boolean
        get() = p.getBoolean("log", true)
        set(v) = p.edit().putBoolean("log", v).apply()

    /** How long detailed CSV logs are kept. */
    var logRetentionHours: Int
        get() = p.getInt("logHours", 24)
        set(v) = p.edit().putInt("logHours", v).apply()

    /** Navigation panel position: left, right, top, bottom. */
    var navPosition: String
        get() = p.getString("navPos", "left")!!
        set(v) { p.edit().putString("navPos", v).apply(); Repo.navPosition.value = v }

    /**
     * Diagnostic adapter: "auto" (find it), "kkl" (USB KKL cable or OpenPort 1.3), "op2" (Tactrix OpenPort 2.0 /
     * clone), "elm_usb", "elm_bt", "elm_wifi" (ELM327 by USB, Bluetooth or Wi-Fi).
     */
    var adapter: String
        get() = p.getString("adapter", "auto")!!
        set(v) = p.edit().putString("adapter", v).apply()

    /** Paired Bluetooth OBD adapter (ELM327) used when [adapter] is "elm_bt". */
    var btAddress: String
        get() = p.getString("btAddress", "")!!
        set(v) = p.edit().putString("btAddress", v).apply()

    var btName: String
        get() = p.getString("btName", "")!!
        set(v) = p.edit().putString("btName", v).apply()

    /** Parameters from the RomRaider list the user ticked on the «Датчики» screen, comma-separated ids. */
    var watchIds: Set<String>
        get() = p.getString("watch", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        set(v) { p.edit().putString("watch", v.joinToString(",")).apply(); Repo.watchVersion.value++ }

    private fun str(k: String, d: String) = p.getString(k, d)!!
    private fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()

    /**
     * Optional features, all on by default and switched in Настройки → «Функции»: "knock" (IAM/knock warnings),
     * "turbo" (turbo timer), "warmup", "cooling" (overheating, thermostat, coolant spike), "atf", "service"
     * (maintenance reminders), "perf" (acceleration/laps, «Замеры» screen), "motion" (G-meter, tilt),
     * "sensors" and "health" (those screens in the menu).
     */
    fun feature(id: String) = p.getBoolean("f_$id", true)
    fun setFeature(id: String, on: Boolean) { p.edit().putBoolean("f_$id", on).apply(); Repo.featureVersion.value++ }

    /** Spoken warnings (IAM, overheating, maintenance…); without a Russian voice on the unit it is a beep. */
    var voiceAlerts: Boolean
        get() = p.getBoolean("voiceAlerts", true)
        set(v) = p.edit().putBoolean("voiceAlerts", v).apply()

    /** Shift light: the screen edges flash from [shiftRpm]. */
    var shiftLight: Boolean
        get() = p.getBoolean("shiftLight", false)
        set(v) = p.edit().putBoolean("shiftLight", v).apply()
    var shiftRpm: Int
        get() = p.getInt("shiftRpm", 6300)
        set(v) = p.edit().putInt("shiftRpm", v).apply()

    /** Car with driver and fuel, kg: for the "dyno" power estimate. */
    var massKg: Int
        get() = p.getInt("massKg", 1550)
        set(v) = p.edit().putInt("massKg", v).apply()

    /** Odometer the user typed in, and the app's own trip total at that moment (km). */
    var odoBaseKm: Double
        get() = p.getFloat("odoBase", -1f).toDouble()
        set(v) = p.edit().putFloat("odoBase", v.toFloat()).apply()
    var odoBaseTripsKm: Double
        get() = p.getFloat("odoBaseTrips", 0f).toDouble()
        set(v) = p.edit().putFloat("odoBaseTrips", v.toFloat()).apply()

    /** Maintenance items as JSON (see Maintenance). */
    var maintenance: String
        get() = str("maintenance", "")
        set(v) = putStr("maintenance", v)

    /** IAM readings worth keeping (start of each drive), JSON list. */
    var iamLog: String
        get() = str("iamLog", "[]")
        set(v) = putStr("iamLog", v)

    /** Acceleration runs, JSON list (newest first). */
    var perfRuns: String
        get() = str("perfRuns", "[]")
        set(v) = putStr("perfRuns", v)

    /** Lap timer start/finish line "lat,lon", "" = not set. */
    var lapLine: String
        get() = str("lapLine", "")
        set(v) = putStr("lapLine", v)

    /** Bluetooth tyre pressure sensors. */
    var tpms: Boolean
        get() = p.getBoolean("tpms", false)
        set(v) = p.edit().putBoolean("tpms", v).apply()
    var tpmsMinBar: Float
        get() = p.getFloat("tpmsMin", 1.8f)
        set(v) = p.edit().putFloat("tpmsMin", v).apply()
    /** Sensor address → wheel (FL, FR, RL, RR) where the user moved one, JSON object. */
    var tpmsWheels: String
        get() = str("tpmsWheels", "{}")
        set(v) = putStr("tpmsWheels", v)

    /** Head unit tilt sensor zero: gravity at rest "x,y,z", "" = not calibrated. */
    var tiltZero: String
        get() = str("tiltZero", "")
        set(v) = putStr("tiltZero", v)

    /** What a USB adapter turned out to be in "Auto": ELM327 speed, 0 = KKL, null = not asked yet. */
    fun usbKind(id: String): Int? = if (p.contains("usb:$id")) p.getInt("usb:$id", 0) else null
    fun setUsbKind(id: String, baud: Int) = p.edit().putInt("usb:$id", baud).apply()

    /** Wi-Fi ELM327 address, "host:port"; almost all of them are 192.168.0.10:35000. */
    var wifiHost: String
        get() = p.getString("wifiHost", "192.168.0.10:35000")!!
        set(v) = p.edit().putString("wifiHost", v).apply()

    /** Bus the ECU is on: "kline", "can" or "auto" (K-line first, then CAN). CAN needs the OpenPort. */
    var bus: String
        get() = p.getString("bus", "auto")!!
        set(v) = p.edit().putString("bus", v).apply()

    /** Car model id from [CarModels], plus the two lines shown in the top bar. */
    var carModel: String
        get() = p.getString("carModel", "SG")!!
        set(v) { p.edit().putString("carModel", v).apply(); Repo.carVersion.value++ }

    var carTitle: String
        get() = p.getString("carTitle", "FORESTER SG5")!!
        set(v) { p.edit().putString("carTitle", v).apply(); Repo.carVersion.value++ }

    var carSub: String
        get() = p.getString("carSub", "2003   EJ20 turbo")!!
        set(v) { p.edit().putString("carSub", v).apply(); Repo.carVersion.value++ }

    /** "at" (automatic, TCU at 0x18) or "mt" (manual: no transmission unit, no АКПП screen). */
    var gearbox: String
        get() = p.getString("gearbox", "at")!!
        set(v) { p.edit().putString("gearbox", v).apply(); Repo.carVersion.value++ }

    /** Automatic AWD layout for the split estimate: "mpt" (front-biased clutch) or "vtd" (45:55 centre diff). */
    var awd: String
        get() = p.getString("awd", "mpt")!!
        set(v) { p.edit().putString("awd", v).apply(); Repo.carVersion.value++ }

    /** 3D car on the home screen: "SG" or "SH" (see CarModels3d). */
    var car3d: String
        get() = p.getString("car3d", "SG")!!
        set(v) { p.edit().putString("car3d", v).apply(); Repo.car3d.value = v }

    /** Body colour of the 3D car (see CarPaints). */
    var carPaint: String
        get() = p.getString("carPaint", "chameleon")!!
        set(v) { p.edit().putString("carPaint", v).apply(); Repo.carPaint.value = v }

    /** Home screen cards (see HomeLayout), "" = factory layout. */
    var homeTiles: String
        get() = p.getString("homeTiles", "")!!
        set(v) { p.edit().putString("homeTiles", v).apply(); Repo.homeTiles.value = v }

    /** Keep polling, fuel counting and logging when the app is not on screen (the service outlives the UI). */
    var backgroundWork: Boolean
        get() = p.getBoolean("backgroundWork", true)
        set(v) { p.edit().putBoolean("backgroundWork", v).apply() }

    /** Start the service when the head unit / phone starts or wakes up (ACC on). */
    var autostartService: Boolean
        get() = p.getBoolean("autostartService", true)
        set(v) { p.edit().putBoolean("autostartService", v).apply() }

    /** Also bring the app on screen then (needs "display over other apps" on Android 10+). */
    var autostartApp: Boolean
        get() = p.getBoolean("autostartApp", false)
        set(v) { p.edit().putBoolean("autostartApp", v).apply() }

    /** App the mini-map opens on a tap: a package, or "" for the "Навигатор" shortcut. */
    var mapApp: String
        get() = p.getString("mapApp", "")!!
        set(v) { p.edit().putString("mapApp", v).apply() }

    /** Daily backup into Download/SubaruDashboard (see Backup). */
    var autoBackup: Boolean
        get() = p.getBoolean("autoBackup", true)
        set(v) { p.edit().putBoolean("autoBackup", v).apply() }

    var lastAutoBackupMs: Long
        get() = p.getLong("lastAutoBackupMs", 0L)
        set(v) { p.edit().putLong("lastAutoBackupMs", v).apply() }

    /** Layout of any editable screen (see Board), by its settings key. */
    fun board(key: String) = p.getString(key, "")!!
    fun setBoard(key: String, v: String) { p.edit().putString(key, v).apply(); Repo.boardFlow(key).value = v }

    /** 3D car + dials block: 0 hidden, 1 normal, 2 large, 3 huge. */
    var clusterSize: Int
        get() = p.getInt("clusterSize", 1)
        set(v) { p.edit().putInt("clusterSize", v).apply(); Repo.clusterSize.value = v }

    /** Gauge designs (see DialStyles), separately for the tachometer and the speedometer. */
    var tachStyle: String
        get() = p.getString("tachStyle", "neon")!!
        set(v) { p.edit().putString("tachStyle", v).apply(); Repo.dialStyles.value = v to speedoStyle }

    var speedoStyle: String
        get() = p.getString("speedoStyle", "neon")!!
        set(v) { p.edit().putString("speedoStyle", v).apply(); Repo.dialStyles.value = tachStyle to v }

    /** Generated data instead of the car, to look at the screens. */
    var demo: Boolean
        get() = p.getBoolean("demo", false)
        set(v) = p.edit().putBoolean("demo", v).apply()

    /** The first-start question (model and adapter) was answered. */
    var setupDone: Boolean
        get() = p.getBoolean("setupDone", false)
        set(v) = p.edit().putBoolean("setupDone", v).apply()

    fun applyModel(m: CarModel) {
        carModel = m.id
        carTitle = m.title
        carSub = m.sub
        bus = m.bus
        // the 3D car closest to the chosen model (the SH default is the atmo EJ25: no bonnet scoop)
        car3d = when (m.id) {
            "SG" -> "SG"
            "SH" -> "SHNA"
            "GD" -> "GG"
            "BL", "BH" -> "BM"
            else -> "SK"
        }
    }

    /** Raw adapter exchange log for testers (logs/ssm_trace.txt). */
    var traceEnabled: Boolean
        get() = p.getBoolean("trace", false)
        set(v) { p.edit().putBoolean("trace", v).apply(); kg.forester.sg.ssm.SsmTrace.enabled = v }

    /** Interface scale in percent, 0 = automatic (fit a 1280×720-like layout). */
    var uiScale: Int
        get() = p.getInt("uiScale", 0)
        set(v) { p.edit().putInt("uiScale", v).apply(); Repo.uiScale.value = v }

    var currentTripId: Long
        get() = p.getLong("tripId", 0)
        set(v) = p.edit().putLong("tripId", v).apply()
}

/** Process-wide state shared between the service and the UI. */
object Repo {
    lateinit var db: Db
    lateinit var settings: Settings

    val link = MutableStateFlow(LinkState.NO_CABLE)
    val linkMessage = MutableStateFlow("")
    val ecuInit = MutableStateFlow<EcuInit?>(null)
    val live = MutableStateFlow(EcuSnapshot())
    val gps = MutableStateFlow<GpsFix?>(null)
    val trip = MutableStateFlow(TripState())
    val dataVersion = MutableStateFlow(0L) // bump to make screens re-query the DB
    val logFile = MutableStateFlow<String?>(null)

    /** Last ~60 s of samples for the live charts. */
    val history = MutableStateFlow<List<EcuSnapshot>>(emptyList())
    val tcuInit = MutableStateFlow<EcuInit?>(null)
    val tcu = MutableStateFlow<TcuSnapshot?>(null)
    val tcuHistory = MutableStateFlow<List<TcuSnapshot>>(emptyList())
    val tcuStatus = MutableStateFlow("АКПП: ожидание")
    val battery = MutableStateFlow(BatteryState())
    val knock = MutableStateFlow(KnockStats())
    val navPosition = MutableStateFlow("left")
    val uiScale = MutableStateFlow(0)
    val car3d = MutableStateFlow("SG")
    val carPaint = MutableStateFlow("chameleon")
    val homeTiles = MutableStateFlow("")
    val driveTiles = MutableStateFlow("")
    val tcuTiles = MutableStateFlow("")
    fun boardFlow(key: String) = when (key) {
        "driveTiles" -> driveTiles
        "tcuTiles" -> tcuTiles
        else -> homeTiles
    }
    /** tachometer style id to speedometer style id */
    val dialStyles = MutableStateFlow("neon" to "neon")
    val clusterSize = MutableStateFlow(1)
    val carVersion = MutableStateFlow(0)
    /** How many parameters the ECU is polled for (fewer than all = some are not supported). */
    val pollAddresses = MutableStateFlow(0)
    /** What the connected engine and transmission units can give from the RomRaider list. */
    val rrAvailable = MutableStateFlow<List<kg.forester.sg.ssm.RrParam>>(emptyList())
    /** Latest values of the ticked parameters, by id. */
    val rrValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** Ticked parameters that are being read now (the rest are not supported or answered nothing). */
    val rrPolled = MutableStateFlow<List<kg.forester.sg.ssm.RrParam>>(emptyList())
    val watchVersion = MutableStateFlow(0)
    /** RomRaider parameters the app itself needs (IAM, knock corrections, target boost, VDC G sensor…). */
    val internalWatch = MutableStateFlow<Set<String>>(emptySet())
    val guard = MutableStateFlow(kg.forester.sg.service.GuardState())
    val peaks = MutableStateFlow(kg.forester.sg.service.Peaks())
    val motion = MutableStateFlow(kg.forester.sg.service.MotionState())
    val perf = MutableStateFlow(kg.forester.sg.service.PerfState())
    val tyres = MutableStateFlow<Map<String, kg.forester.sg.service.TyreReading>>(emptyMap())
    val shift = MutableStateFlow(false)
    val maintenanceVersion = MutableStateFlow(0)
    val featureVersion = MutableStateFlow(0)
    /** The ECU answers only standard OBD-II (no SSM): basic parameters, no transmission data. */
    val obdMode = MutableStateFlow(false)
    /** What the automatic adapter search found, e.g. "USB 1A86:7523 → KKL". */
    val adapterFound = MutableStateFlow("")

    fun init(ctx: Context) {
        if (!::db.isInitialized) {
            db = Db(ctx.applicationContext)
            settings = Settings(ctx.applicationContext)
            navPosition.value = settings.navPosition
            uiScale.value = settings.uiScale
            car3d.value = settings.car3d
            carPaint.value = settings.carPaint
            homeTiles.value = settings.homeTiles
            driveTiles.value = settings.board("driveTiles")
            tcuTiles.value = settings.board("tcuTiles")
            dialStyles.value = settings.tachStyle to settings.speedoStyle
            clusterSize.value = settings.clusterSize
            kg.forester.sg.ssm.SsmTrace.enabled = settings.traceEnabled
            CustomGauges.init(ctx)
        }
    }

    fun touch() {
        dataVersion.value = System.currentTimeMillis()
    }
}

data class CarModel(val id: String, val name: String, val title: String, val sub: String, val bus: String, val note: String)

object CarModels {
    val all = listOf(
        CarModel("SG", "Forester SG", "FORESTER SG5", "2003   EJ20 turbo", "kline", "2002–2007, K-Line, KKL-кабель"),
        CarModel("SH", "Forester SH", "FORESTER SH", "2008   EJ25", "auto", "2008–2012: ранние евро на K-Line, позже CAN (нужен OpenPort 2.0)"),
        CarModel("GD", "Impreza GD/GG", "IMPREZA", "EJ20", "kline", "2000–2007, K-Line"),
        CarModel("BL", "Legacy BL/BP", "LEGACY", "EJ20", "kline", "2003–2009, K-Line"),
        CarModel("BH", "Legacy BE/BH", "LEGACY", "EJ20", "kline", "1998–2003, K-Line"),
        CarModel("other", "Другая Subaru", "SUBARU", "", "auto", "SSM2: K-Line или CAN"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}
