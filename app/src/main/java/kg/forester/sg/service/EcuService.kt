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
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber
import kg.forester.sg.MainActivity
import kg.forester.sg.R
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.EcuParams
import kg.forester.sg.ssm.Elm327
import kg.forester.sg.ssm.ElmCanTransport
import kg.forester.sg.ssm.ElmKLineTransport
import kg.forester.sg.ssm.KklTransport
import kg.forester.sg.ssm.Op2CanTransport
import kg.forester.sg.ssm.Op2KLineTransport
import kg.forester.sg.ssm.OpenPort2
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ssm.SsmTransport
import kg.forester.sg.ssm.toHex
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

/** Stock KKL chips plus the OpenPort 2.0 (a CDC-ACM device with Tactrix ids). */
private val adapterProber = UsbSerialProber(UsbSerialProber.getDefaultProbeTable().apply {
    OpenPort2.PIDS.forEach { addProduct(OpenPort2.VID, it, CdcAcmSerialDriver::class.java) }
})

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
    private val knock by lazy { KnockWatch(this) }
    private val tracker by lazy { TripTracker(this) { knock.reset(it) } }
    private val battery by lazy { BatteryMonitor(this) }
    private val history = ArrayDeque<EcuSnapshot>()
    private val tcuHistory = ArrayDeque<TcuSnapshot>()
    private var tcuAddrs: IntArray? = null
    private var tcuNextTryMs = 0L
    private var logger: CsvLogger? = null
    private var triedHostFix = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        SsmTrace.init(this)
        startInForeground()
        startGps()
        battery // restore the last battery readings for the UI right away
        Voice.start(this)
        Fyt.bind(this)
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
            ACTION_START_GPS -> { startGps(); Voice.start(this) }
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
            if (Repo.settings.demo) { demoLoop(); continue }
            if (Repo.settings.adapter == "elm_bt") { bluetoothSession(); delay(3000); continue }
            tracker.tick(System.currentTimeMillis())
            val wantOp2 = Repo.settings.adapter == "op2"
            val drivers = adapterProber.findAllDrivers(usb)
            val driver = drivers.firstOrNull { OpenPort2.matches(it.device.vendorId, it.device.productId) == wantOp2 }
            if (driver == null) {
                Repo.link.value = LinkState.NO_CABLE
                val other = drivers.firstOrNull()
                Repo.linkMessage.value = when {
                    other != null && wantOp2 -> "Найден KKL-кабель, а в настройках выбран OpenPort 2.0"
                    other != null -> "Найден OpenPort 2.0: выберите его в Настройках → Адаптер"
                    wantOp2 -> "OpenPort 2.0 не найден"
                    else -> "Кабель KKL не найден"
                }
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
            SsmTrace.enabled = Repo.settings.traceEnabled
            SsmTrace.log("=== ${driver.javaClass.simpleName} %04X:%04X, адаптер ${Repo.settings.adapter}, шина ${Repo.settings.bus}"
                .format(driver.device.vendorId, driver.device.productId))
            var op2: OpenPort2? = null
            try {
                port.open(conn)
                if (wantOp2) {
                    op2 = OpenPort2(port).also { it.open() }
                    SsmTrace.log("OpenPort firmware ${op2.firmware}")
                    session { kline -> openOp2(op2, kline) }
                } else {
                    session { _ -> KklTransport(port).also { it.open() } }
                }
            } catch (e: Exception) {
                SsmTrace.log("error: $e")
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = e.message ?: "Ошибка связи"
            } finally {
                EcuLink.client?.close()
                EcuLink.client = null
                if (op2 != null) op2.close() else runCatching { port.close() }
                logger?.close(); logger = null
            }
            delay(1000)
        }
    }

    /** Demo data instead of a cable; trips, fuel and logs are left alone. */
    private suspend fun demoLoop() {
        Repo.link.value = LinkState.CONNECTED
        Repo.linkMessage.value = "Демо-данные (без машины)"
        knock.reset(System.currentTimeMillis(), record = false)
        while (scope.isActive && Repo.settings.demo) {
            val now = System.currentTimeMillis()
            val (snap, tcu) = Demo.ecu(now)
            Repo.live.value = snap
            history.addLast(snap)
            while (history.size > 240) history.removeFirst()
            Repo.history.value = history.toList()
            knock.process(snap, record = false)
            if (Repo.settings.gearbox != "mt") {
                Repo.tcu.value = tcu
                Repo.tcuStatus.value = "АКПП: демо"
                tcuHistory.addLast(tcu)
                while (tcuHistory.size > 120) tcuHistory.removeFirst()
                Repo.tcuHistory.value = tcuHistory.toList()
            }
            Repo.gps.value = GpsFix(
                speedKmh = (snap.speedKmh ?: 0).toFloat(), altitudeM = 195.0, accuracyM = 5f, satellites = 9,
                timeMs = now, bearing = if ((snap.speedKmh ?: 0) > 3) Demo.bearing(now) else null,
            )
            val lh = Demo.litersPerHour(snap)
            val v = snap.speedKmh ?: 0
            Repo.trip.value = Repo.trip.value.copy(instLh = lh, instPer100 = if (v >= 5) lh / v * 100 else null)
            delay(250)
        }
        Repo.link.value = LinkState.NO_CABLE
    }

    /** Bluetooth OBD adapter (ELM327 and clones) over the serial port profile. */
    @SuppressLint("MissingPermission")
    private suspend fun bluetoothSession() {
        val addr = Repo.settings.btAddress
        if (addr.isEmpty()) {
            Repo.link.value = LinkState.NO_CABLE
            Repo.linkMessage.value = "Выберите Bluetooth-адаптер: Настройки → «Авто и адаптер»"
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Repo.link.value = LinkState.NO_PERMISSION
            Repo.linkMessage.value = "Нет разрешения на Bluetooth (Настройки → «Авто и адаптер»)"
            return
        }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            Repo.link.value = LinkState.NO_CABLE
            Repo.linkMessage.value = "Bluetooth выключен"
            return
        }
        Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "Подключение к ${Repo.settings.btName.ifEmpty { addr }}…"
        SsmTrace.enabled = Repo.settings.traceEnabled
        SsmTrace.log("=== Bluetooth $addr, шина ${Repo.settings.bus}")
        val spp = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val dev = bt.getRemoteDevice(addr)
        runCatching { bt.cancelDiscovery() }
        val sock = runCatching { dev.createRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .recoverCatching { dev.createInsecureRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .getOrElse { e ->
                SsmTrace.log("bt connect: $e")
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Адаптер ${Repo.settings.btName.ifEmpty { addr }} не отвечает (включено зажигание?)"
                return
            }
        val elm = Elm327(sock.inputStream, sock.outputStream, sock)
        try {
            elm.open()
            SsmTrace.log("ELM: ${elm.version}")
            session { kline -> if (kline) ElmKLineTransport(elm).also { it.open() } else ElmCanTransport(elm).also { it.open() } }
        } catch (e: Exception) {
            SsmTrace.log("error: $e")
            Repo.link.value = LinkState.ERROR
            Repo.linkMessage.value = e.message ?: "Ошибка связи"
        } finally {
            EcuLink.client?.close()
            EcuLink.client = null
            elm.close()
            logger?.close(); logger = null
        }
    }

    private fun openOp2(op2: OpenPort2, kline: Boolean): SsmTransport =
        if (kline) Op2KLineTransport(op2).also { it.open() } else Op2CanTransport(op2).also { it.open() }

    /**
     * One adapter session: find the bus the ECU answers on, init it, then poll until the cable or ECU goes away.
     * [open] makes a transport for the K-line (true) or CAN (false).
     */
    private suspend fun session(open: (Boolean) -> SsmTransport) {
        val buses = when {
            Repo.settings.adapter == "kkl" -> listOf(true) // a KKL cable has no CAN
            Repo.settings.bus == "kline" -> listOf(true)
            Repo.settings.bus == "can" -> listOf(false)
            else -> listOf(true, false)
        }
        var busIdx = 0
        var transport: SsmTransport? = null
        var transportKline = true
        while (scope.isActive) {
            val kline = buses[busIdx % buses.size]
            if (transport == null || transportKline != kline) {
                transport?.close()
                transport = null
                transport = open(kline)
                transportKline = kline
            }
            val client = SsmClient(transport)
            EcuLink.client = client
            val init = EcuLink.mutex.withLock { client.init() }
            if (init == null) {
                Repo.link.value = LinkState.NO_ECU
                Repo.linkMessage.value = "ЭБУ не отвечает (${client.transport.name}). Зажигание включено?"
                busIdx++
                tracker.tick(System.currentTimeMillis())
                delay(if (buses.size > 1 && busIdx % buses.size != 0) 300 else 3000)
                continue
            }
            Repo.ecuInit.value = init
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "ROM ${init.romId} · ${client.transport.name}"
            SsmTrace.log("ECU init: SSM ${init.ssmId} ROM ${init.romId} caps ${init.capabilities.toHex(" ")}")
            val addrs = choosePollAddresses(client, init)
            if (addrs == null) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "ЭБУ ${init.romId} ответил на init, но не отдаёт параметры"
                delay(3000)
                continue
            }
            Repo.pollAddresses.value = addrs.size
            val afSensor = EcuParams.reported(init, EcuParams.AF_SENSOR)
            var failures = 0
            var cycle = 0
            tcuAddrs = null
            tcuNextTryMs = 0
            while (scope.isActive && failures < 5) {
                val raw = EcuLink.mutex.withLock { client.read(addrs) }
                val now = System.currentTimeMillis()
                if (raw == null) {
                    failures++
                    tracker.tick(now)
                    continue
                }
                failures = 0
                var snap = EcuParams.decode(addrs, raw, now)
                // SSM answers any address; an A/F byte from an ECU without the wideband sensor would fake
                // "enrichment" in the fuel calculation
                if (!afSensor) snap = snap.copy(lambda = null)
                Repo.live.value = snap
                history.addLast(snap)
                while (history.size > 240) history.removeFirst()
                Repo.history.value = history.toList()
                battery.process(snap)
                tracker.process(snap, Repo.gps.value) // first: a new trip clears the knock events
                knock.process(snap)
                if (++cycle % 3 == 0 && Repo.settings.gearbox != "mt") pollTcu(client, now)
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

    /**
     * The SG answers the whole list at once. Other ECUs stay silent when one address in the request is
     * unknown to them, so fall back to the addresses the init marks as supported, then probe one by one.
     */
    private suspend fun choosePollAddresses(client: SsmClient, init: EcuInit): IntArray? {
        suspend fun works(a: IntArray) = a.isNotEmpty() &&
            (1..2).any { EcuLink.mutex.withLock { client.read(a) } != null }

        val base = chooseBaseAddresses(client, init, ::works) ?: return null
        val extra = EcuParams.optionalSupported(init)
        if (extra.isNotEmpty() && works(base + extra)) return base + extra
        return base
    }

    private suspend fun chooseBaseAddresses(
        client: SsmClient, init: EcuInit, works: suspend (IntArray) -> Boolean,
    ): IntArray? {
        val full = EcuParams.pollAddresses
        if (works(full)) return full
        val caps = EcuParams.supported(init)
        SsmTrace.log("full list failed, capability list: ${caps.joinToString(" ") { "%02X".format(it) }}")
        if (caps.size < full.size && works(caps)) return caps
        Repo.linkMessage.value = "Подбираю параметры ЭБУ ${init.romId}…"
        val ok = full.filter { a -> EcuLink.mutex.withLock { client.read(intArrayOf(a)) } != null }.toIntArray()
        SsmTrace.log("probed: ${ok.joinToString(" ") { "%02X".format(it) }}")
        return ok.takeIf { it.isNotEmpty() && works(it) }
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
                bearing = if (l.hasBearing()) l.bearing else null,
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
            if (Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Android 14+ refuses some types (e.g. microphone when started at boot): keep the USB part at least.
            try {
                startForeground(1, n, type)
            } catch (e: Exception) {
                runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            }
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
            .setContentTitle(getString(R.string.app_name))
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

/**
 * Autostart: a real boot, a "quick boot", or ACC on after the FYT/Teyes head unit slept (those units mostly
 * sleep instead of shutting down, so BOOT_COMPLETED alone is not enough). Which ACC broadcast a given firmware
 * sends varies, so several known ones are listed in the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        val s = Repo.settings
        if (s.autostartService || s.autostartApp) EcuService.start(context)
        if (s.autostartApp) {
            // allowed from the background only with "display over other apps" (Android 10+); without it this is a no-op
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
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
                "gps_speed_kmh,gps_alt_m,pedal_pct,map_kpa,avcs_deg,alt_duty_pct,gear,atf_c,turbine_rpm,lockup_pct," +
                "transfer_pct,front_wheel_kmh,rear_wheel_kmh,fuel_sensor_v,fuel_sensor_ohm\n"
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
                f(t?.transferPct), t?.frontWheelKmh?.toString() ?: "", t?.rearWheelKmh?.toString() ?: "",
                f(s.fuelSensorV), f(s.fuelSensorOhm),
            ).joinToString(",") + "\n"
        )
        if (++lines % 20 == 0) w.flush()
    }

    fun close() {
        runCatching { w.flush(); w.close() }
    }
}
