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

    /** Diagnostic adapter: "kkl" (USB KKL cable), "op2" (Tactrix OpenPort 2.0 / clone), "elm_bt" (Bluetooth ELM327). */
    var adapter: String
        get() = p.getString("adapter", "kkl")!!
        set(v) = p.edit().putString("adapter", v).apply()

    /** Paired Bluetooth OBD adapter (ELM327) used when [adapter] is "elm_bt". */
    var btAddress: String
        get() = p.getString("btAddress", "")!!
        set(v) = p.edit().putString("btAddress", v).apply()

    var btName: String
        get() = p.getString("btName", "")!!
        set(v) = p.edit().putString("btName", v).apply()

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
        if (m.id == "SG" || m.id == "SH") car3d = m.id
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
