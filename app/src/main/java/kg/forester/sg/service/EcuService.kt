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
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
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
import kg.forester.sg.ssm.CanObd
import kg.forester.sg.ssm.ElmObd
import kg.forester.sg.ssm.KklTransport
import kg.forester.sg.ssm.Obd2
import kg.forester.sg.ssm.ObdLink
import kg.forester.sg.ssm.RomRaider
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.UsbStreams
import kg.forester.sg.ssm.OpenPort13
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
import kg.forester.sg.ssm.LockState
import kg.forester.sg.ssm.KLineTransport
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val adapterProber = UsbSerialProber(UsbSerialProber.getDefaultProbeTable().apply {
    OpenPort2.PIDS.forEach { addProduct(OpenPort2.VID, it, CdcAcmSerialDriver::class.java) }
    OpenPort13.PIDS.forEach { addProduct(OpenPort13.VID, it, FtdiSerialDriver::class.java) }
    addProduct(0xAD50, 0x60C4, CdcAcmSerialDriver::class.java)
    addProduct(0x16D0, 0x117E, CdcAcmSerialDriver::class.java)
})

object EcuLink {
    val mutex = Mutex()
    @Volatile var client: SsmClient? = null
    @Volatile var obd: Obd2? = null

    suspend fun <T> use(block: (SsmClient) -> T): T? = mutex.withLock {
        val c = client ?: return@withLock null
        runCatching { block(c) }.getOrNull()
    }
}

class EcuService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val knock by lazy { KnockWatch(this) }
    private val guard = EngineGuard()
    private val tracker by lazy { TripTracker(this) { knock.reset(it); guard.newTrip(it) } }
    private val battery by lazy { BatteryMonitor(this) }
    private val history = ArrayDeque<EcuSnapshot>()
    private var mafDropLogged = false
    private val tcuHistory = ArrayDeque<TcuSnapshot>()
    private var tcuAddrs: IntArray? = null
    private var tcuNextTryMs = 0L
    private var tcuAtfOnly = false
    private var logger: CsvLogger? = null
    private var triedHostFix = false

    override fun onBind(intent: Intent?): IBinder? = null

    private val wakeWatch by lazy {
        WakeWatch(this) {
            scope.launch(Dispatchers.Main) {
                runCatching { startGps() }
                WakeWatch.openApp(this@EcuService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        SsmTrace.init(this)
        Alerts.init(this)
        Motion.start(this)
        Tpms.start(this)
        startInForeground()
        startGps()
        battery
        guard.analyzer = RulesHost.create(this)
        guard.ctx = applicationContext
        Voice.start(this)
        Fyt.bind(this)
        pollJob = scope.launch { pollLoop() }
        (getSystemService(Context.USB_SERVICE) as UsbManager).let { usb ->
            RelayEngine.start(scope)
            SensorBoxLink.start(scope, usb, { SensorBoxLink.drivers(usb).let { it + unknownSerial(usb, it) } },
                { isOp2(it) || isOp13(it) || isCanable(it) }) { requestUsbPermission(usb, it) }
            ScreenLink.start(scope, this, usb) { requestUsbPermission(usb, it) }
        }
        wakeWatch.start(scope)
        scope.launch { headUnitWatch() }
        scope.launch(Dispatchers.Main) {
            while (isActive) { delay(30_000); runCatching { gpsWatchdog() }; runCatching { Voice.ensure(this@EcuService) } }
        }
        scope.launch {
            while (isActive) {
                Housekeeping.run(this@EcuService, Repo.logFile.value)
                runCatching { kg.forester.sg.data.Updater.checkDaily(this@EcuService) }
                delay(3600_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_TRIP -> scope.launch { tracker.resetTrip() }
            ACTION_START_GPS -> { startGps(); Voice.start(this) }
            ACTION_EXIT -> {
                exitRequested.tryEmit(Unit)
                stopService(Intent(this, PlayerService::class.java))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeWatch.stop()
        scope.launch { tracker.shutdown() }
        logger?.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        var noCableSince = System.currentTimeMillis()
        while (scope.isActive) {
            val s = Repo.settings
            if (s.demo) { demoLoop(); continue }
            if (Repo.linkDemo.value) {
                Repo.adapterFound.value = "Демо Link: CANable не нужен"
                kg.forester.sg.ssm.LinkDemo().use { canSession(it, 500) { Repo.linkDemo.value } }
                continue
            }
            SsmTrace.enabled = s.traceEnabled
            SsmTrace.polling = false
            tracker.tick(System.currentTimeMillis())
            val kind = s.adapter
            when (kind) {
                "elm_bt" -> { bluetoothSession(s.btAddress, s.btName); delay(3000); continue }
                "elm_wifi" -> { wifiSession(explicit = true); delay(3000); continue }
            }
            val all = adapterProber.findAllDrivers(usb) + unknownSerial(usb, adapterProber.findAllDrivers(usb))
            logUsbDevices(usb, all)
            val drivers = SensorBoxLink.forEcu(all)
            val driver = pickDriver(kind, drivers)
            if (driver == null) {
                if (!triedHostFix && System.currentTimeMillis() - noCableSince > 8000) {
                    triedHostFix = true
                    forceUsbHostMode()
                }
                if (kind == "auto") {
                    if (wifiSession(explicit = false) || autoBluetooth()) { delay(1000); continue }
                }
                Repo.link.value = LinkState.NO_CABLE
                Repo.adapterFound.value = ""
                val other = drivers.firstOrNull()
                Repo.linkMessage.value = when {
                    kind == "auto" -> "Адаптер не найден: подключите USB-кабель или Wi-Fi/Bluetooth-адаптер " +
                        "(Bluetooth сначала сопрягите в настройках Android)"
                    other != null && kind == "op2" -> "Найден KKL-кабель, а в настройках выбран OpenPort 2.0"
                    other != null -> "Найден OpenPort 2.0: выберите его в Настройках → Адаптер (или «Авто»)"
                    kind == "op2" -> "OpenPort 2.0 не найден"
                    kind == "canable" && usb.deviceList.values.any { kg.forester.sg.ssm.Slcan.isCandleLight(it.vendorId, it.productId) } ->
                        "CANable с прошивкой candleLight: приложение работает с прошивкой slcan (как RealDash). Перепрошейте адаптер на slcan"
                    kind == "canable" -> "CANable не найден: подключите его к USB магнитолы"
                    kind == "elm_usb" -> "USB-адаптер ELM327 не найден"
                    else -> "Кабель KKL не найден (если он подключён — «Отправить журнал»: в журнале коды всех USB-устройств)"
                }
                delay(2000)
                continue
            }
            noCableSince = System.currentTimeMillis()
            if (!usb.hasPermission(driver.device)) {
                Repo.link.value = LinkState.NO_PERMISSION
                Repo.linkMessage.value = "Нет разрешения на USB: нажмите «OK» в окне Android (лучше с галочкой «по умолчанию»). " +
                    "Окна нет — отключите и снова подключите кабель"
                requestUsbPermission(usb, driver.device)
                delay(3000)
                continue
            }
            if (!SensorBoxLink.claimForEcu(driver)) { delay(500); continue }
            Repo.link.value = LinkState.CONNECTING
            val conn = usb.openDevice(driver.device)
            if (conn == null) {
                SensorBoxLink.releaseEcu()
                delay(2000); continue
            }
            val port = driver.ports[0]
            SsmTrace.log("=== ${driver.javaClass.simpleName} %04X:%04X, адаптер $kind, шина ${s.bus}"
                .format(driver.device.vendorId, driver.device.productId))
            var op2: OpenPort2? = null
            var streams: UsbStreams? = null
            try {
                port.open(conn)
                val id = "%04X:%04X".format(driver.device.vendorId, driver.device.productId)
                when {
                    isCanable(driver) -> {
                        Repo.adapterFound.value = "USB $id → CANable (slcan)"
                        canableSession(port)
                    }
                    isOp2(driver) -> {
                        Repo.adapterFound.value = "USB $id → OpenPort 2.0"
                        op2 = OpenPort2(port).also { it.open() }
                        SsmTrace.log("OpenPort firmware ${op2.firmware}")
                        session(obd = { CanObd(openOp2(op2, false), "OpenPort 2.0") }) { kline -> openOp2(op2, kline) }
                    }
                    isOp13(driver) -> {
                        Repo.adapterFound.value = "USB $id → OpenPort 1.3"
                        session(klineOnly = true) { _ -> KklTransport(port, openPort13 = true).also { it.open() } }
                    }
                    else -> {
                        val baud = when (kind) {
                            "kkl" -> 0
                            "elm_usb" -> UsbStreams.probeElm(port) ?: 0
                            else -> when (val known = s.usbKind(id)) {
                                null -> (UsbStreams.probeElm(port) ?: 0).also { s.setUsbKind(id, it) }
                                0 -> UsbStreams.probeElm(port, quick = true)?.also { s.setUsbKind(id, it) } ?: 0
                                else -> known
                            }
                        }
                        if (baud > 0) {
                            Repo.adapterFound.value = "USB $id → ELM327 ($baud бод)"
                            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                            streams = UsbStreams(port)
                            if (!elmSession(Elm327(streams.input, streams.output, streams), "USB") && kind == "auto") s.setUsbKind(id, 0)
                        } else if (kind == "elm_usb") {
                            Repo.link.value = LinkState.ERROR
                            Repo.linkMessage.value = "На USB не ELM327 (не отвечает на AT-команды). Это KKL-кабель? Выберите «Авто» или «KKL»"
                            delay(3000)
                        } else {
                            Repo.adapterFound.value = "USB $id → KKL"
                            session(klineOnly = true) { _ -> KklTransport(port).also { it.open() } }
                        }
                    }
                }
            } catch (e: Exception) {
                SsmTrace.log("error: $e")
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = e.message ?: "Ошибка связи"
            } finally {
                EcuLink.client?.close()
                EcuLink.client = null
                streams?.close()
                if (op2 != null) op2.close() else runCatching { port.close() }
                SensorBoxLink.releaseEcu()
                logger?.close(); logger = null
            }
            delay(1000)
        }
    }

    private fun unknownSerial(usb: UsbManager, known: List<UsbSerialDriver>): List<UsbSerialDriver> {
        val names = known.map { it.device.deviceName }.toSet()
        return usb.deviceList.values.filter { it.deviceName !in names }.mapNotNull { d ->
            runCatching<UsbSerialDriver?> {
                when (d.vendorId) {
                    0x0403 -> FtdiSerialDriver(d)
                    0x1A86 -> com.hoho.android.usbserial.driver.Ch34xSerialDriver(d)
                    0x067B -> com.hoho.android.usbserial.driver.ProlificSerialDriver(d)
                    0x10C4 -> com.hoho.android.usbserial.driver.Cp21xxSerialDriver(d)
                    else -> if (kg.forester.sg.ssm.Slcan.matches(d.vendorId, d.productId, runCatching { d.productName }.getOrNull())) CdcAcmSerialDriver(d) else null
                }
            }.getOrNull()?.takeIf { it.ports.isNotEmpty() }
        }
    }

    private fun isOp2(d: UsbSerialDriver) = OpenPort2.matches(d.device.vendorId, d.device.productId)
    private fun isOp13(d: UsbSerialDriver) = OpenPort13.matches(d.device.vendorId, d.device.productId) ||
        (d.device.vendorId == OpenPort13.VID && runCatching { d.device.productName }.getOrNull()?.contains("OpenPort 1.3", true) == true)

    private fun isCanable(d: UsbSerialDriver) = kg.forester.sg.ssm.Slcan.matches(
        d.device.vendorId, d.device.productId, runCatching { d.device.productName }.getOrNull())

    private fun pickDriver(kind: String, drivers: List<UsbSerialDriver>): UsbSerialDriver? = when (kind) {
        "op2" -> drivers.firstOrNull { isOp2(it) } ?: drivers.firstOrNull { isOp13(it) }
        "canable" -> drivers.firstOrNull { isCanable(it) }
        "auto" -> drivers.firstOrNull { isOp2(it) } ?: drivers.firstOrNull { isOp13(it) } ?: drivers.firstOrNull { isCanable(it) } ?: drivers.firstOrNull()
        else -> drivers.firstOrNull { !isOp2(it) && !isCanable(it) }
    }

    private var lastUsbList = ""

    private fun logUsbDevices(usb: UsbManager, drivers: List<UsbSerialDriver>) {
        val known = drivers.map { it.device.deviceName }.toSet()
        val list = usb.deviceList.values.joinToString("; ") { d ->
            "%04X:%04X %s %s%s".format(
                d.vendorId, d.productId, d.manufacturerName ?: "", d.productName ?: "",
                if (d.deviceName in known) " [serial]" else "",
            ).trim()
        }
        if (list != lastUsbList) {
            lastUsbList = list
            SsmTrace.log("USB: ${list.ifEmpty { "нет устройств" }}")
        }
    }

    private suspend fun demoLoop() {
        guard.reset()
        Repo.link.value = LinkState.CONNECTED
        Repo.linkMessage.value = "Демо-данные (без машины)"
        knock.reset(System.currentTimeMillis(), record = false)
        val demoStart = System.currentTimeMillis()
        var demoTrip = kg.forester.sg.data.Trip(startMs = demoStart, endMs = demoStart, distanceKm = 0.0, fuelL = 0.0)
        var lastDemo = demoStart
        while (scope.isActive && Repo.settings.demo) {
            val now = System.currentTimeMillis()
            val (snap, tcu) = Demo.ecu(now)
            Repo.live.value = snap
            history.addLast(snap)
            while (history.size > 240) history.removeFirst()
            Repo.history.value = history.toList()
            knock.process(snap, record = false)
            extras(snap, now)
            if (Repo.settings.gearbox != "mt") {
                Repo.tcu.value = lockUp(tcu, snap.rpm, now)
                Repo.tcuStatus.value = "АКПП: демо"
                tcuHistory.addLast(tcu)
                while (tcuHistory.size > 120) tcuHistory.removeFirst()
                Repo.tcuHistory.value = tcuHistory.toList()
            }
            val bearing = if ((snap.speedKmh ?: 0) > 3) Demo.bearing(now) else null
            val (lat, lon) = Demo.position(now, (snap.speedKmh ?: 0).toFloat(), bearing)
            Repo.gps.value = GpsFix(
                speedKmh = (snap.speedKmh ?: 0).toFloat(), altitudeM = 195.0, accuracyM = 5f, satellites = 9,
                timeMs = now, bearing = bearing, lat = lat, lon = lon,
            )
            val lh = Demo.litersPerHour(snap)
            val v = snap.speedKmh ?: 0
            val tank = Repo.settings.tankL.takeIf { it > 0 } ?: 60.0
            val dtH = (now - lastDemo) / 3_600_000.0
            lastDemo = now
            val km = v * dtH; val l = lh * dtH
            demoTrip = demoTrip.copy(
                endMs = now, distanceKm = demoTrip.distanceKm + km, fuelL = demoTrip.fuelL + l,
                cityKm = demoTrip.cityKm + if (v in 3..59) km else 0.0, cityL = demoTrip.cityL + if (v in 3..59) l else 0.0,
                hwyKm = demoTrip.hwyKm + if (v >= 60) km else 0.0, hwyL = demoTrip.hwyL + if (v >= 60) l else 0.0,
                idleS = demoTrip.idleS + if (v < 3) dtH * 3600 else 0.0, idleL = demoTrip.idleL + if (v < 3) l else 0.0,
                maxRpm = maxOf(demoTrip.maxRpm, snap.rpm ?: 0),
            )
            val left = tank * 0.62
            Repo.trip.value = Repo.trip.value.copy(
                trip = demoTrip, instLh = lh, instPer100 = if (v >= 5) lh / v * 100 else null, fuelLeftL = left,
                rangeKm = (demoTrip.per100 ?: 11.0).let { left / it * 100 },
            )
            delay(250)
        }
        Repo.live.value = EcuSnapshot()
        Repo.tcu.value = null
        history.clear(); Repo.history.value = emptyList()
        tcuHistory.clear(); Repo.tcuHistory.value = emptyList()
        Repo.gps.value = null
        Repo.trip.value = kg.forester.sg.data.TripState()
        Repo.guard.value = GuardState()
        guard.reset()
        RulesHost.restore(this)
        Repo.link.value = LinkState.NO_CABLE
    }

    @SuppressLint("MissingPermission")
    private suspend fun bluetoothSession(addr: String, label: String, quiet: Boolean = false): Boolean {
        if (addr.isEmpty()) {
            Repo.link.value = LinkState.NO_CABLE
            Repo.linkMessage.value = "Выберите Bluetooth-адаптер: Настройки → «Авто и адаптер»"
            return false
        }
        if (!btAllowed()) {
            Repo.link.value = LinkState.NO_PERMISSION
            Repo.linkMessage.value = "Нет разрешения на Bluetooth (Настройки → «Авто и адаптер»)"
            return false
        }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as 
