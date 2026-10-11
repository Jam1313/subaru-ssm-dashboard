package kg.forester.sg.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import com.hoho.android.usbserial.driver.UsbSerialProber
import kg.forester.sg.MainActivity
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuParams
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.TcuParams
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared access to the ECU for one-off requests (DTC read/clear) between poll cycles. */
object EcuLink {
    val mutex = Mutex()
    @Volatile var client: SsmClient? = null

    suspend fun <T> use(block: (SsmClient) -> T): T? = mutex.withLock {
        val c = client ?: return@withLock null
        runCatching { block(c) }.getOrNull()
    }
}

class EcuService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val tracker by lazy { TripTracker() }
    private val battery by lazy { BatteryMonitor(this) }
    private val history = ArrayDeque<EcuSnapshot>()
    private val tcuHistory = ArrayDeque<TcuSnapshot>()
    private var tcuAddrs: IntArray? = null
    private var tcuNextTryMs = 0L
    private var lastKnock = 0f
    private var logger: CsvLogger? = null
    private var triedHostFix = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        startInForeground()
        startGps()
        pollJob = scope.launch { pollLoop() }
        scope.launch {
            while (isActive) {
                Housekeeping.run(this@EcuService, Repo.logFile.value)
                delay(3600_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_TRIP -> scope.launch { tracker.resetTrip() }
            ACTION_START_GPS -> startGps()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch { tracker.shutdown() }
        logger?.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        var noCableSince = System.currentTimeMillis()
        while (scope.isActive) {
            tracker.tick(System.currentTimeMillis())
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
            if (driver == null) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Кабель KKL не найден"
                if (!triedHostFix && System.currentTimeMillis() - noCableSince > 8000) {
                    triedHostFix = true
                    forceUsbHostMode()
                }
                delay(2000)
                continue
            }
            noCableSince = System.currentTimeMillis()
            if (!usb.hasPermission(driver.device)) {
                Repo.link.value = LinkState.NO_PERMISSION
                Repo.linkMessage.value = "Нет разрешения на USB"
                requestUsbPermission(usb, driver.device)
                delay(3000)
                continue
            }
            Repo.link.value = LinkState.CONNECTING
            val conn = usb.openDevice(driver.device)
            if (conn == null) {
                delay(2000); continue
            }
            val port = driver.ports[0]
            val client = try {
                port.open(conn)
                SsmClient(port).also { it.open() }
            } catch (e: Exception) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = e.message ?: "Ошибка порта"
                runCatching { port.close() }
                delay(2000); continue
            }
            EcuLink.client = client
            try {
                session(client)
            } catch (e: Exception) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = e.message ?: "Ошибка связи"
            } finally {
                EcuLink.client = null
                client.close()
                logger?.close(); logger = null
            }
            delay(1000)
        }
    }

    /** One cable session: init the ECU, then poll until the cable or ECU goes away. */
    private suspend fun session(client: SsmClient) {
        while (scope.isActive) {
            val init = EcuLink.mutex.withLock { client.init() }
            if (init == null) {
                Repo.link.value = LinkState.NO_ECU
                Repo.linkMessage.value = "ЭБУ не отвечает (зажигание выключено?)"
                tracker.tick(System.currentTimeMillis())
                delay(3000)
                continue
            }
            Repo.ecuInit.value = init
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "ROM ${init.romId}"
            var failures = 0
            var cycle = 0
            tcuAddrs = null
            tcuNextTryMs = 0
            while (scope.isActive && failures < 5) {
                val raw = EcuLink.mutex.withLock { client.read(EcuParams.pollAddresses) }
                val now = System.currentTimeMillis()
                if (raw == null) {
                    failures++
                    tracker.tick(now)
                    continue
                }
                failures = 0
                val snap = EcuParams.decode(raw, now)
                Repo.live.value = snap
                history.addLast(snap)
                while (history.size > 240) history.removeFirst()
                Repo.history.value = history.toList()
                val knock = snap.knockDeg ?: 0f
                if (knock < 0f && lastKnock >= 0f) Repo.knockEvents.value++
                lastKnock = knock
                battery.process(snap)
                tracker.process(snap, Repo.gps.value)
                if (++cycle % 3 == 0) pollTcu(client, now)
                if (Repo.settings.logEnabled && (snap.rpm ?: 0) > 0) {
                    val l = logger ?: CsvLogger(this).also { logger = it; Repo.logFile.value = it.file.name }
                    l.write(snap, Repo.gps.value, Repo.trip.value.instLh, Repo.tcu.value)
                } else if (logger != null && (snap.rpm ?: 0) == 0) {
                    logger?.close(); logger = null
                }
                updateNotification()
            }
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь с ЭБУ потеряна"
            logger?.close(); logger = null
        }
    }

    /** The transmission unit answers at 0x18 on the same K-line; poll it every few ECU cycles. */
    private suspend fun pollTcu(client: SsmClient, now: Long) {
        var addrs = tcuAddrs
        if (addrs == null) {
            if (now < tcuNextTryMs) return
            val init = EcuLink.mutex.withLock { client.init(SsmClient.TCU) }
            if (init == null) {
                Repo.tcuStatus.value = "АКПП не отвечает"
                tcuNextTryMs = now + 60_000
                return
            }
            Repo.tcuInit.value = init
            addrs = TcuParams.supported(init)
            tcuAddrs = addrs
            Repo.tcuStatus.value = "АКПП: ROM ${init.romId}"
        }
        if (addrs.isEmpty()) return
        val raw = EcuLink.mutex.withLock { client.read(addrs, SsmClient.TCU) } ?: return
        val t = TcuParams.decode(addrs, raw, now)
        Repo.tcu.value = t
        tcuHistory.addLast(t)
        while (tcuHistory.size > 120) tcuHistory.removeFirst()
        Repo.tcuHistory.value = tcuHistory.toList()
    }

    private fun forceUsbHostMode() {
        // This head unit's OTG controller can get stuck in device mode (seen after toggling USB debugging).
        val f = "/sys/devices/platform/soc/soc:aon/5fff0000.usb/mode_switch"
        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "[ \"\$(cat $f)\" = device ] && echo host > $f")).waitFor()
        }
    }

    private fun requestUsbPermission(usb: UsbManager, device: android.hardware.usb.UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        usb.requestPermission(device, pi)
    }

    // ---- GPS ----

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            Repo.gps.value = GpsFix(
                speedKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f,
                altitudeM = l.altitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy else 99f,
                satellites = l.extras?.getInt("satellites") ?: 0,
                timeMs = System.currentTimeMillis(),
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(gpsListener) }
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
        }
    }

    // ---- notification ----

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ЭБУ", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Подключение…")
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    private var lastNotifMs = 0L
    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifMs < 5000) return
        lastNotifMs = now
        val t = Repo.trip.value
        val text = t.instPer100?.let { "%.1f л/100 км".format(it) } ?: "%.1f л/ч".format(t.instLh)
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Forester SG")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "ecu"
        const val ACTION_USB_PERMISSION = "kg.forester.sg.USB_PERMISSION"
        const val ACTION_RESET_TRIP = "kg.forester.sg.RESET_TRIP"
        const val ACTION_START_GPS = "kg.forester.sg.START_GPS"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, EcuService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) EcuService.start(context)
    }
}

/** One CSV file per engine run in Android/data/kg.forester.sg/files/logs. */
class CsvLogger(ctx: Context) {
    val file: File
    private val w: FileWriter
    private var lines = 0

    init {
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
        file = File(dir, name)
        w = FileWriter(file)
        w.write(
            "time,rpm,speed_kmh,coolant_c,intake_c,maf_gs,throttle_pct,battery_v,inj_pw_ms,timing_deg," +
                "knock_deg,atm_kpa,boost_kpa,af_corr_pct,af_learn_pct,afr,wgdc_pct,fuel_lh," +
                "gps_speed_kmh,gps_alt_m,pedal_pct,map_kpa,avcs_deg,alt_duty_pct,gear,atf_c,turbine_rpm,lockup_pct\n"
        )
    }

    fun write(s: kg.forester.sg.ssm.EcuSnapshot, g: GpsFix?, lh: Double, t: TcuSnapshot?) {
        fun f(x: Float?) = x?.let { "%.2f".format(Locale.US, it) } ?: ""
        w.write(
            listOf(
                s.timeMs.toString(), s.rpm?.toString() ?: "", s.speedKmh?.toString() ?: "",
                s.coolantC?.toString() ?: "", s.intakeC?.toString() ?: "", f(s.mafGs), f(s.throttlePct),
                f(s.batteryV), f(s.injPwMs), f(s.timingDeg), f(s.knockDeg), f(s.atmKpa), f(s.gaugeKpa),
                f(s.afCorrPct), f(s.afLearnPct), f(s.afr), f(s.wastegatePct),
                "%.3f".format(Locale.US, lh), f(g?.speedKmh), g?.altitudeM?.let { "%.0f".format(Locale.US, it) } ?: "",
                f(s.pedalPct), f(s.mapKpa), f(s.avcsDeg), f(s.altDutyPct),
                t?.gear?.toString() ?: "", t?.atfC?.toString() ?: "", t?.turbineRpm?.toString() ?: "", f(t?.lockUpPct),
            ).joinToString(",") + "\n"
        )
        if (++lines % 20 == 0) w.flush()
    }

    fun close() {
        runCatching { w.flush(); w.close() }
    }
}
