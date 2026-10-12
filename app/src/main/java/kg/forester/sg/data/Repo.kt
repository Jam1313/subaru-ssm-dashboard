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

    var tankL: Double
        get() = p.getFloat("tank", 60f).toDouble() // 0 = unknown
        set(v) = p.edit().putFloat("tank", v.toFloat()).apply()

    var pricePerL: Double
        get() = p.getFloat("price", 82f).toDouble()
        set(v) = p.edit().putFloat("price", v.toFloat()).apply()

    /** Fuel calculation correction, learned from full-to-full refills. */
    var fuelK: Double
        get() = p.getFloat("fuelK", 1f).toDouble()
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

    /** Diagnostic adapter: "kkl" (USB KKL cable) or "op2" (Tactrix OpenPort 2.0 / clone). */
    var adapter: String
        get() = p.getString("adapter", "kkl")!!
        set(v) = p.edit().putString("adapter", v).apply()

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
    val knockEvents = MutableStateFlow(0)
    val navPosition = MutableStateFlow("left")
    val uiScale = MutableStateFlow(0)
    val car3d = MutableStateFlow("SG")
    val carPaint = MutableStateFlow("chameleon")
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
            kg.forester.sg.ssm.SsmTrace.enabled = settings.traceEnabled
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
