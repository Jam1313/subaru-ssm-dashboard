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

/** Stock KKL chips plus the Tactrix cables: OpenPort 2.0 (CDC-ACM) and OpenPort 1.3 (FTDI), both with their own ids. */
private val adapterProber = UsbSerialProber(UsbSerialProber.getDefaultProbeTable().apply {
    OpenPort2.PIDS.forEach { addProduct(OpenPort2.VID, it, CdcAcmSerialDriver::class.java) }
    OpenPort13.PIDS.forEach { addProduct(OpenPort13.VID, it, FtdiSerialDriver::class.java) }
})

/** Shared access to the ECU for one-off requests (DTC read/clear) between poll cycles. */
object EcuLink {
    val mutex = Mutex()
    @Volatile var client: SsmClient? = null
    /** Set instead of [client] while the car is read over standard OBD-II. */
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

    /** After ACC sleep: GPS again (it often stalls) and the dashboard on screen if the driver wants it. */
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
        battery // restore the last battery readings for the UI right away
        Voice.start(this)
        Fyt.bind(this)
        pollJob = scope.launch { pollLoop() }
        wakeWatch.start(scope)
        scope.launch(Dispatchers.Main) {
            while (isActive) { delay(30_000); runCatching { gpsWatchdog() } }
        }
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
            ACTION_EXIT -> {
                exitRequested.tryEmit(Unit)
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
            SsmTrace.enabled = s.traceEnabled
            SsmTrace.polling = false
            tracker.tick(System.currentTimeMillis())
            val kind = s.adapter
            when (kind) {
                "elm_bt" -> { bluetoothSession(s.btAddress, s.btName); delay(3000); continue }
                "elm_wifi" -> { wifiSession(explicit = true); delay(3000); continue }
            }
            val drivers = adapterProber.findAllDrivers(usb) + unknownSerial(usb, adapterProber.findAllDrivers(usb))
            logUsbDevices(usb, drivers)
            val driver = pickDriver(kind, drivers)
            if (driver == null) {
                if (!triedHostFix && System.currentTimeMillis() - noCableSince > 8000) {
                    triedHostFix = true
                    forceUsbHostMode()
                }
                if (kind == "auto") {
                    // nothing on USB: a Wi-Fi ELM327 we are connected to, then a paired Bluetooth one
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
            Repo.link.value = LinkState.CONNECTING
            val conn = usb.openDevice(driver.device)
            if (conn == null) {
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
                        // a bare KKL cable or an ELM327 behind the same USB chip: ask, and remember the answer
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
                            // remembered as an ELM but silent now: another cable on the same chip, ask again next time
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
                logger?.close(); logger = null
            }
            delay(1000)
        }
    }

    /**
     * USB devices the driver table does not list but whose chip vendor we know (FTDI, WCH CH340, Prolific,
     * Silicon Labs): KKL clones and rebadged cables (e.g. an OpenPort 1.3 with its own product id) come with ids
     * nobody listed.
     */
    private fun unknownSerial(usb: UsbManager, known: List<UsbSerialDriver>): List<UsbSerialDriver> {
        val names = known.map { it.device.deviceName }.toSet()
        return usb.deviceList.values.filter { it.deviceName !in names }.mapNotNull { d ->
            runCatching<UsbSerialDriver?> {
                when (d.vendorId) {
                    0x0403 -> FtdiSerialDriver(d)
                    0x1A86 -> com.hoho.android.usbserial.driver.Ch34xSerialDriver(d)
                    0x067B -> com.hoho.android.usbserial.driver.ProlificSerialDriver(d)
                    0x10C4 -> com.hoho.android.usbserial.driver.Cp21xxSerialDriver(d)
                    // not a generic CDC-ACM fallback: a head unit's internal 4G modem looks like one
                    else -> null
                }
            }.getOrNull()?.takeIf { it.ports.isNotEmpty() }
        }
    }

    private fun isOp2(d: UsbSerialDriver) = OpenPort2.matches(d.device.vendorId, d.device.productId)
    /**
     * By its ids, or by its name: an FTDI chip whose product id got wiped to 0000 (seen on an OpenPort 1.3,
     * typical of the FTDI driver "bricking" clone chips) still says "Tactrix OpenPort 1.3" and wants its RTS cleared.
     */
    private fun isOp13(d: UsbSerialDriver) = OpenPort13.matches(d.device.vendorId, d.device.productId) ||
        (d.device.vendorId == OpenPort13.VID && runCatching { d.device.productName }.getOrNull()?.contains("OpenPort 1.3", true) == true)

    private fun pickDriver(kind: String, drivers: List<UsbSerialDriver>): UsbSerialDriver? = when (kind) {
        // "OpenPort" picked for an OpenPort 1.3: it is a K-line cable like a KKL, just use it as one
        "op2" -> drivers.firstOrNull { isOp2(it) } ?: drivers.firstOrNull { isOp13(it) }
        "auto" -> drivers.firstOrNull { isOp2(it) } ?: drivers.firstOrNull { isOp13(it) } ?: drivers.firstOrNull()
        else -> drivers.firstOrNull { !isOp2(it) }
    }

    private var lastUsbList = ""

    /** Every USB device, recognised or not, once per change: shows in a sent log which cable someone has. */
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

    /** Demo data instead of a cable; trips, fuel and logs are left alone. */
    private suspend fun demoLoop() {
        Repo.link.value = LinkState.CONNECTED
        Repo.linkMessage.value = "Демо-данные (без машины)"
        knock.reset(System.currentTimeMillis(), record = false)
        // a trip counted in memory only (never saved), so the fuel screen shows something in the demo
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
        Repo.link.value = LinkState.NO_CABLE
    }

    /** Bluetooth OBD adapter (ELM327 and clones) over the serial port profile. Returns false if it did not connect. */
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
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            if (!quiet) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Bluetooth выключен"
            }
            return false
        }
        val name = label.ifEmpty { addr }
        if (!quiet) Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "Подключение к $name…"
        SsmTrace.log("=== Bluetooth $name $addr, шина ${Repo.settings.bus}")
        val spp = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val dev = bt.getRemoteDevice(addr)
        runCatching { bt.cancelDiscovery() }
        val sock = runCatching { dev.createRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .recoverCatching { dev.createInsecureRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .getOrElse { e ->
                SsmTrace.log("bt connect: $e")
                if (!quiet) {
                    Repo.link.value = LinkState.NO_CABLE
                    Repo.linkMessage.value = "Адаптер $name не отвечает (включено зажигание?)"
                }
                return false
            }
        Repo.adapterFound.value = "Bluetooth $name → ELM327"
        elmSession(Elm327(sock.inputStream, sock.outputStream, sock), "Bluetooth")
        return true
    }

    private fun btAllowed() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * "Auto" without anything on USB: the chosen Bluetooth adapter, or else any paired device named like an
     * OBD adapter (the one that answers is remembered).
     */
    @SuppressLint("MissingPermission")
    private suspend fun autoBluetooth(): Boolean {
        if (!btAllowed()) return false
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) return false
        val s = Repo.settings
        if (s.btAddress.isNotEmpty()) return bluetoothSession(s.btAddress, s.btName, quiet = true)
        val candidates = runCatching { bt.bondedDevices.toList() }.getOrDefault(emptyList())
            .filter { d -> OBD_NAMES.any { (d.name ?: "").uppercase().contains(it) } }
        for (d in candidates) {
            if (bluetoothSession(d.address, d.name ?: "", quiet = true)) {
                s.btAddress = d.address; s.btName = d.name ?: d.address
                return true
            }
        }
        return false
    }

    /**
     * Wi-Fi ELM327: the head unit joins the adapter's own network, the adapter listens on TCP (192.168.0.10:35000
     * on nearly all of them). The socket is bound to that Wi-Fi, which has no internet and is otherwise skipped
     * by Android. With [explicit] false ("Auto") it is only tried when we are on the adapter's subnet.
     */
    private suspend fun wifiSession(explicit: Boolean): Boolean {
        val (host, port) = Repo.settings.wifiHost.split(':').let { it[0].trim() to (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 35000) }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi == null) {
            if (explicit) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Нет Wi-Fi: подключитесь к сети адаптера (обычно WiFi_OBDII, V-LINK…)"
            }
            return false
        }
        if (!explicit) {
            val prefix = host.substringBeforeLast('.') + "."
            val sameNet = cm.getLinkProperties(wifi)?.linkAddresses?.any { it.address.hostAddress?.startsWith(prefix) == true } == true
            if (!sameNet) return false
        }
        Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "Подключение к Wi-Fi адаптеру $host:$port…"
        SsmTrace.log("=== Wi-Fi $host:$port, шина ${Repo.settings.bus}")
        val sock = runCatching {
            wifi.socketFactory.createSocket().also {
                it.connect(java.net.InetSocketAddress(host, port), 2000)
                it.tcpNoDelay = true
            }
        }.getOrElse { e ->
            SsmTrace.log("wifi connect: $e")
            if (explicit) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Wi-Fi адаптер $host:$port не отвечает"
            }
            return false
        }
        Repo.adapterFound.value = "Wi-Fi $host:$port → ELM327"
        elmSession(Elm327(sock.getInputStream(), sock.getOutputStream(), sock), "Wi-Fi")
        return true
    }

    /**
     * An ELM327 on any link: SSM on the K-line or CAN first, standard OBD-II when the car does not answer SSM.
     * Returns false if the adapter did not answer as an ELM327 at all.
     */
    private suspend fun elmSession(elm: Elm327, via: String): Boolean {
        try {
            try {
                elm.open()
            } catch (e: Exception) {
                SsmTrace.log("ELM ($via) does not answer: $e")
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "Адаптер не отвечает как ELM327"
                return false
            }
            SsmTrace.log("ELM ($via): ${elm.version}")
            session(obd = { ElmObd(elm).takeIf { it.open() } }) { kline ->
                if (kline) ElmKLineTransport(elm).also { it.open() } else ElmCanTransport(elm).also { it.open() }
            }
            return true
        } catch (e: Exception) {
            SsmTrace.log("error: $e")
            Repo.link.value = LinkState.ERROR
            Repo.linkMessage.value = e.message ?: "Ошибка связи"
            return true
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
     * [open] makes a transport for the K-line (true) or CAN (false). [obd], when the adapter can do it, makes a
     * standard OBD-II link that is tried after a full round of silence on SSM.
     */
    private suspend fun session(
        klineOnly: Boolean = false,
        obd: (() -> ObdLink?)? = null,
        open: (Boolean) -> SsmTransport,
    ) {
        val buses = when {
            klineOnly -> listOf(true) // a KKL cable has no CAN
            Repo.settings.bus == "kline" -> listOf(true)
            Repo.settings.bus == "can" -> listOf(false)
            else -> listOf(true, false)
        }
        var busIdx = 0
        var lineMode = 0
        var silentRounds = 0
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
                // other makes (Nissan Consult, Toyota…) never answer SSM: say so instead of letting them wait
                Repo.linkMessage.value = "ЭБУ не отвечает (${client.transport.name}). Зажигание включено? " +
                    "Приложение работает только с Subaru (протокол SSM)"
                tracker.tick(System.currentTimeMillis())
                // next: other DTR/RTS levels for a cable with a chip inside, else the other bus
                val modes = transport.lineModes
                val roundDone = if (modes > 1) {
                    transport.setLineMode(++lineMode)
                    lineMode % modes == 0
                } else {
                    busIdx++
                    busIdx % buses.size == 0
                }
                if (roundDone) silentRounds++
                if (roundDone && obd != null && (silentRounds == 1 || silentRounds % 3 == 0)) {
                    // the OBD link sets the adapter up its own way; SSM starts afresh after it
                    transport.close()
                    transport = null
                    EcuLink.client = null
                    Repo.linkMessage.value = "SSM молчит, пробую стандартный OBD-II…"
                    val link = runCatching { obd() }.onFailure { SsmTrace.log("obd: $it") }.getOrNull()
                    if (link != null && obdSession(link)) silentRounds = 0
                    continue
                }
                delay(if (roundDone) 3000 else 300)
                continue
            }
            silentRounds = 0
            Repo.obdMode.value = false
            Repo.ecuInit.value = init
            Repo.link.value = LinkState.CONNECTED
            val car = kg.forester.sg.ssm.EcuIds.car(this, init.romId)
            Repo.linkMessage.value = "ROM ${init.romId}" + (car?.let { " ($it)" } ?: "") + " · ${client.transport.name}"
            RomRaider.load(this)
            RomRaider.siblingOf(init.romId)?.let { SsmTrace.log("no extended parameters for ROM ${init.romId}, using sibling $it") }
            SsmTrace.log("ECU init: SSM ${init.ssmId} ROM ${init.romId} caps ${init.capabilities.toHex(" ")}")
            val addrs = choosePollAddresses(client, init)
            if (addrs == null) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "ЭБУ ${init.romId} ответил на init, но не отдаёт параметры"
                delay(3000)
                continue
            }
            Repo.pollAddresses.value = addrs.size
            runCatching { connectChecks(client, init) }.onFailure { SsmTrace.log("connect checks: $it") }
            ecuWatch.setup(init)
            tcuWatch.clear()
            // an engine without a turbo (no wastegate in the init) on the untouched factory screen: no empty boost cards
            if (Repo.settings.homeTiles.isEmpty() && !EcuParams.reported(init, EcuParams.WGDC)) {
                Repo.settings.homeTiles = kg.forester.sg.data.HomeLayout.atmoDefault()
            }
            val afSensor = EcuParams.reported(init, EcuParams.AF_SENSOR)
            val skip = EcuParams.unsupported(init, addrs)
            if (skip.isNotEmpty()) SsmTrace.log("not supported by this ECU, ignored: ${skip.joinToString(" ") { "%02X".format(it) }}")
            var failures = 0
            var cycle = 0
            tcuAddrs = null
            tcuNextTryMs = 0
            SsmTrace.polling = true
            while (scope.isActive && failures < 5) {
                val raw = EcuLink.mutex.withLock { client.read(addrs) }
                val now = System.currentTimeMillis()
                if (raw == null) {
                    failures++
                    tracker.tick(now)
                    continue
                }
                failures = 0
                var snap = EcuParams.decode(addrs, raw, now, skip)
                // SSM answers any address; an A/F byte from an ECU without the wideband sensor would fake
                // "enrichment" in the fuel calculation
                if (!afSensor) snap = snap.copy(lambda = null)
                ecuWatch.poll(client)
                onSnapshot(snap, now)
                if (++cycle % Repo.settings.tcuEvery.coerceAtLeast(1) == 0 && Repo.settings.gearbox != "mt") pollTcu(client, now)
            }
            SsmTrace.polling = false
            SsmTrace.log("ECU silent, polling stopped")
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь с ЭБУ потеряна"
            logger?.close(); logger = null
        }
    }

    /** Everything a fresh sample feeds: live values, charts, battery, trip and fuel, knock, CSV log. */
    private fun onSnapshot(read: EcuSnapshot, now: Long) {
        val snap = read.saneMaf()
        if (snap !== read && !mafDropLogged) {
            mafDropLogged = true
            SsmTrace.log("MAF %.2f g/s at %s rpm is impossible, ignored".format(read.mafGs, read.rpm))
        }
        Repo.live.value = snap
        history.addLast(snap)
        while (history.size > 240) history.removeFirst()
        Repo.history.value = history.toList()
        battery.process(snap)
        tracker.process(snap, Repo.gps.value) // first: a new trip clears the knock events
        kg.forester.sg.data.Scales.process(snap)
        knock.process(snap)
        extras(snap, now)
        if (Repo.settings.logEnabled && (snap.rpm ?: 0) > 0) {
            // the ticked parameters are extra columns: a new file when the choice changes
            val extras = Repo.rrPolled.value
            if (logger != null && (logger?.extras != extras || logger?.columns?.map { it.key } != LogColumns.enabled().map { it.key })) {
                logger?.close(); logger = null
            }
            val l = logger ?: CsvLogger(this, extras).also { logger = it; Repo.logFile.value = it.file.name }
            l.write(snap, Repo.gps.value, Repo.trip.value.instLh, Repo.tcu.value, Repo.rrValues.value)
        } else if (logger != null && (snap.rpm ?: 0) == 0) {
            logger?.close(); logger = null
        }
        updateNotification()
    }

    /** Engine guard, G-meter, acceleration timer: everything that only reads the samples. */
    private fun extras(snap: EcuSnapshot, now: Long) {
        runCatching { guard.process(snap, Repo.tcu.value, now) }.onFailure { SsmTrace.log("guard: $it") }
        val s = Repo.settings
        if (s.feature("motion")) runCatching { Motion.process(snap, Repo.gps.value, now) }
        if (s.feature("perf")) runCatching { Perf.process(snap, Repo.gps.value, now) }.onFailure { SsmTrace.log("perf: $it") }
    }

    /** Standard OBD-II polling until the ECU goes silent. Returns false if the ECU did not answer at all. */
    private suspend fun obdSession(link: ObdLink): Boolean {
        val obd = Obd2(link)
        try {
            if (EcuLink.mutex.withLock { runCatching { obd.init() }.getOrDefault(false) } != true) return false
            EcuLink.obd = obd
            ecuWatch.clear(); tcuWatch.clear()
            Repo.obdMode.value = true
            Repo.ecuInit.value = null
            Repo.tcuStatus.value = "АКПП: недоступна в режиме OBD-II"
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "OBD-II · ${link.name}: основные параметры (SSM не ответил)"
            Repo.pollAddresses.value = obd.polledCount
            var failures = 0
            SsmTrace.polling = true
            while (scope.isActive && failures < 5) {
                val now = System.currentTimeMillis()
                val snap = EcuLink.mutex.withLock { runCatching { obd.poll(now) }.getOrNull() }
                if (snap == null) {
                    failures++
                    tracker.tick(now)
                    continue
                }
                failures = 0
                onSnapshot(snap, now)
                // a fast CAN adapter would go round 100 times a second: 5 a second is plenty
                delay((200 - (System.currentTimeMillis() - now)).coerceAtLeast(0))
            }
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь с ЭБУ потеряна"
            return true
        } finally {
            SsmTrace.polling = false
            EcuLink.obd = null
            Repo.obdMode.value = false
            logger?.close(); logger = null
            runCatching { link.close() }
        }
    }

    private var ecuAvail: List<RrParam> = emptyList()
    private var tcuAvail: List<RrParam> = emptyList()
    private val ecuWatch = Watch(SsmClient.ECU, tcu = false)
    private val tcuWatch = Watch(SsmClient.TCU, tcu = true)

    private fun publishWatch() {
        Repo.rrAvailable.value = ecuAvail + tcuAvail
        Repo.rrPolled.value = ecuWatch.params + tcuWatch.params
    }

    /**
     * The parameters ticked on the «Датчики» screen that one unit has, read in a request of their own after the
     * regular one: an address this ECU does not know can then only cost these, not the gauges.
     */
    private inner class Watch(private val dest: Int, private val tcu: Boolean) {
        var params: List<RrParam> = emptyList()
            private set
        private var avail: List<RrParam> = emptyList()
        private var all: List<RrParam> = emptyList()
        private var addrs = IntArray(0)
        private var version = -1
        private var fails = 0

        fun setup(init: EcuInit) {
            all = RomRaider.load(this@EcuService)
            avail = RomRaider.available(this@EcuService, init, tcu)
            this.init = init
            custom = Repo.customVersion.value
            if (tcu) tcuAvail = avail else ecuAvail = avail
            SsmTrace.log("RomRaider: ${avail.size} parameters for ${if (tcu) "TCU" else "ECU"} ROM ${init.romId}")
            version = -1
            params = emptyList()
            publishWatch()
        }

        fun clear() {
            avail = emptyList(); params = emptyList(); addrs = IntArray(0)
            if (tcu) tcuAvail = emptyList() else ecuAvail = emptyList()
            publishWatch()
        }

        private var internal: Set<String> = emptySet()
        private var init: EcuInit? = null
        private var custom = 0

        suspend fun poll(client: SsmClient) {
            // own addresses added or edited while connected: the list of what this unit has changes
            if (custom != Repo.customVersion.value) {
                custom = Repo.customVersion.value
                init?.let { avail = RomRaider.available(this@EcuService, it, tcu); if (tcu) tcuAvail = avail else ecuAvail = avail }
                version = -1
            }
            if (version != Repo.watchVersion.value || internal != Repo.internalWatch.value) {
                version = Repo.watchVersion.value
                internal = Repo.internalWatch.value
                val ids = Repo.settings.watchIds + internal
                params = avail.filter { it.id in ids }
                addrs = RomRaider.readList(params, all)
                fails = 0
                publishWatch()
            }
            if (addrs.isEmpty()) return
            val raw = EcuLink.mutex.withLock { client.read(addrs, dest) }
            if (raw == null) {
                if (++fails >= 3) prune(client)
                return
            }
            fails = 0
            Repo.rrValues.value = Repo.rrValues.value + RomRaider.decode(params, addrs, raw, all)
        }

        /** The unit keeps refusing the request: keep only the parameters that answer on their own. */
        private suspend fun prune(client: SsmClient) {
            val ok = params.filter { p ->
                val a = RomRaider.readList(listOf(p), all)
                a.isNotEmpty() && EcuLink.mutex.withLock { client.read(a, dest) } != null
            }
            SsmTrace.log("RomRaider: dropped ${(params - ok.toSet()).joinToString(" ") { it.id }}")
            params = ok
            addrs = RomRaider.readList(params, all)
            fails = 0
            publishWatch()
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

    /**
     * Once per connection, only where the init says the ECU has it (FreeSSM's flag bytes, + 8 in our numbering):
     * the VIN (byte 36 bit 0: a 3-byte pointer at 0xDA, 17 ASCII bytes there) and the test mode connectors
     * (byte 11 bit 5: 0x61 bit 5). Joined green connectors make the check engine lamp blink and store false codes.
     */
    private suspend fun connectChecks(client: SsmClient, init: EcuInit) {
        Repo.vin.value = ""
        if (init.supports(44, 0)) {
            val p = EcuLink.mutex.withLock { client.read(intArrayOf(0xDA, 0xDB, 0xDC)) }
            if (p != null && p.size == 3) {
                val at = ((p[0].toInt() and 0xFF) shl 16) or ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
                val raw = EcuLink.mutex.withLock { client.read(IntArray(17) { at + it }) }
                val vin = raw?.map { (it.toInt() and 0xFF).toChar() }?.joinToString("")?.trim()
                if (vin != null && vin.length == 17 && vin.all { it.isLetterOrDigit() }) Repo.vin.value = vin
                SsmTrace.log("VIN: ${vin ?: "—"}")
            }
        }
        if (init.supports(19, 5)) {
            val t = EcuLink.mutex.withLock { client.read(intArrayOf(0x61)) }
            if (t != null && t.isNotEmpty() && (t[0].toInt() shr 5) and 1 == 1) {
                Alerts.raise(
                    "testMode", "Замкнуты разъёмы тест-режима (зелёные, под рулём): «чек» мигает и ЭБУ может записать " +
                        "ложные ошибки. Разъедините их, если не проводите диагностику", Alerts.Level.WARN, 60 * 60_000L,
                )
            }
        }
    }

    /** The transmission unit answers at 0x18 on the same K-line; poll it every few ECU cycles. */
    private suspend fun pollTcu(client: SsmClient, now: Long) {
        var addrs = tcuAddrs
        if (addrs == null) {
            if (now < tcuNextTryMs) return
            val init = EcuLink.mutex.withLock { client.init(SsmClient.TCU) }
            if (init == null) {
                Repo.tcuStatus.value = "Блок АКПП не отвечает по SSM: у механики и некоторых старых Subaru его нет на " +
                    "диагностической линии, а через часть Bluetooth-адаптеров он недоступен (надёжнее KKL-кабель)"
                tcuNextTryMs = now + 60_000
                return
            }
            Repo.tcuInit.value = init
            tcuWatch.setup(init)
            addrs = TcuParams.supported(init)
            tcuAddrs = addrs
            tcuAtfOnly = false
            Repo.tcuStatus.value = "АКПП: ROM ${init.romId}"
        }
        if (addrs.isEmpty()) return
        // "only ATF": the temperature and the selector, a much shorter request
        if (Repo.settings.tcuAtfOnly != tcuAtfOnly) {
            tcuAtfOnly = Repo.settings.tcuAtfOnly
            addrs = TcuParams.supported(Repo.tcuInit.value ?: return).let { all ->
                if (tcuAtfOnly) all.filter { it == TcuParams.ATF_OLD || it == TcuParams.ATF_NEW || it == TcuParams.RANGE || it == TcuParams.GEAR }.toIntArray() else all
            }
            tcuAddrs = addrs
        }
        val raw = EcuLink.mutex.withLock { client.read(addrs, SsmClient.TCU) } ?: return
        tcuWatch.poll(client)
        val t = lockUp(TcuParams.decode(addrs, raw, now), Repo.live.value.rpm, now)
        Repo.tcu.value = t
        tcuHistory.addLast(t)
        while (tcuHistory.size > 120) tcuHistory.removeFirst()
        Repo.tcuHistory.value = tcuHistory.toList()
    }

    private var lockSlipSinceMs = 0L

    /**
     * Converter slip from the engine and turbine speeds, and the lock-up state checked against it: a TCU that
     * keeps commanding lock-up while the clutch still slips for seconds gets a warning (worn clutch, tired ATF).
     */
    private fun lockUp(t: TcuSnapshot, rpm: Int?, now: Long): TcuSnapshot {
        val slip = t.turbineRpm?.let { tr -> if (rpm != null && rpm > 800) (rpm - tr) * 100f / rpm else null }
        val slipping = (t.lockUpPct ?: 0f) > 60f && (slip ?: 0f) > 10f && (rpm ?: 0) > 1200
        if (!slipping) lockSlipSinceMs = 0L else if (lockSlipSinceMs == 0L) lockSlipSinceMs = now
        val lock = LockState.of(t.lockUpPct, slip, if (lockSlipSinceMs == 0L) 0L else now - lockSlipSinceMs)
        if (lock == LockState.SLIPPING && Repo.settings.feature("atf")) {
            Alerts.raise(
                "lockSlip", "Гидротрансформатор не держит блокировку: проскальзывание %.0f %% при команде %.0f %%. Проверьте ATF и ошибки АКПП"
                    .format(slip, t.lockUpPct), Alerts.Level.WARN, 30 * 60_000L,
            )
        }
        return t.copy(slipPct = slip, lock = lock)
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

    @Volatile private var lastGpsFixMs = 0L
    @Volatile private var gpsSats = 0
    @Volatile private var gpsStartedMs = 0L

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastGpsFixMs = System.currentTimeMillis()
            Repo.gpsStatus.value = ""
            Repo.gps.value = GpsFix(
                speedKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f,
                altitudeM = l.altitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy else 99f,
                satellites = (l.extras?.getInt("satellites") ?: 0).takeIf { it > 0 } ?: gpsSats,
                timeMs = System.currentTimeMillis(),
                bearing = if (l.hasBearing()) l.bearing else null,
                lat = l.latitude, lon = l.longitude,
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { gpsCheck() }
        override fun onProviderDisabled(provider: String) { gpsCheck() }
    }

    /** Position from the network while GPS has nothing (indoors, just woken up): for the map only, no speed. */
    private val netListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (System.currentTimeMillis() - lastGpsFixMs < 30_000) return
            Repo.gps.value = GpsFix(
                speedKmh = 0f, altitudeM = l.altitude, accuracyM = if (l.hasAccuracy()) maxOf(l.accuracy, 50f) else 99f,
                satellites = gpsSats, timeMs = System.currentTimeMillis(), bearing = null, lat = l.latitude, lon = l.longitude,
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gnssCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            gpsSats = (0 until status.satelliteCount).count { status.usedInFix(it) }
            if (System.currentTimeMillis() - lastGpsFixMs > 5000) {
                Repo.gpsStatus.value = "ищу спутники: видно ${status.satelliteCount}, в решении $gpsSats"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Repo.gpsStatus.value = "нет разрешения на геолокацию: откройте приложение и разрешите"
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(gpsListener) }
        runCatching { lm.removeUpdates(netListener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
        }.onFailure { SsmTrace.log("gps: $it") }
        runCatching { lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper())) }
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, netListener, Looper.getMainLooper())
        }
        gpsStartedMs = System.currentTimeMillis()
        gpsCheck()
        SsmTrace.log("gps started, providers ${lm.allProviders}, enabled ${lm.getProviders(true)}")
    }

    /** Why there is no position, for the GPS card and the mini-map. */
    private fun gpsCheck() {
        if (System.currentTimeMillis() - lastGpsFixMs < 5000) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Repo.gpsStatus.value = when {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "нет разрешения на геолокацию: откройте приложение и разрешите"
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> "геолокация выключена в настройках Android"
            // keep the satellite count from GnssStatus, but never a stale "no permission" / "switched off"
            else -> Repo.gpsStatus.value.takeIf { it.startsWith("ищу") } ?: "ищу спутники…"
        }
    }

    /**
     * Called every half minute: re-subscribes when GPS has been silent for a minute (after the head unit's sleep
     * the GPS module comes back, but the old subscription often stays dead) and refreshes the status text.
     */
    private fun gpsWatchdog() {
        if (Repo.settings.demo) return
        val now = System.currentTimeMillis()
        if (now - lastGpsFixMs > 60_000 && now - gpsStartedMs > 60_000) {
            SsmTrace.log("gps silent for a minute, re-subscribing")
            startGps()
        } else gpsCheck()
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
            .addAction(Notification.Action.Builder(null, "Выход", PendingIntent.getService(
                this, 1, Intent(this, EcuService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE
            )).build())
            .build()
    }

    companion object {
        const val CHANNEL = "ecu"
        const val ACTION_USB_PERMISSION = "kg.forester.sg.USB_PERMISSION"
        const val ACTION_RESET_TRIP = "kg.forester.sg.RESET_TRIP"
        const val ACTION_START_GPS = "kg.forester.sg.START_GPS"
        /** From the notification: stop everything and close the app. */
        const val ACTION_EXIT = "kg.forester.sg.EXIT"
        val exitRequested = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /** Paired Bluetooth devices worth trying in "Auto". */
        private val OBD_NAMES = listOf("OBD", "ELM", "V-LINK", "VLINK", "KONNWEI", "VGATE", "ICAR", "OBDLINK", "CARISTA", "VEEPEAK")

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
        // allowed from the background only with "display over other apps" (Android 10+)
        WakeWatch.openApp(context)
    }
}

/** One CSV file per engine run in Android/data/kg.forester.sg/files/logs; columns as chosen on the «Лог» screen. */
class CsvLogger(ctx: Context, val extras: List<RrParam> = emptyList()) {
    val file: File
    private val w: FileWriter
    private var lines = 0
    val columns: List<LogColumn> = LogColumns.enabled()

    init {
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
        file = File(dir, name)
        w = FileWriter(file)
        w.write(
            (listOf("time") + columns.map { it.key }).joinToString(",") +
                extras.joinToString("") { p -> "," + "${p.id} ${p.nameEn} ${p.units}".trim().replace(',', ' ') } + "\n"
        )
    }

    fun write(s: kg.forester.sg.ssm.EcuSnapshot, g: GpsFix?, lh: Double, t: TcuSnapshot?, extra: Map<String, Double> = emptyMap()) {
        val row = LogRow(s, g, lh, t, Repo.guard.value)
        w.write(
            (listOf(s.timeMs.toString()) + columns.map { it.get(row) }).joinToString(",") + extras.joinToString("") { p ->
                "," + (extra[p.id]?.let { v ->
                    if (kotlin.math.abs(v) >= 1e7) "%.6g".format(Locale.US, v)
                    else "%.4f".format(Locale.US, v).trimEnd('0').trimEnd('.')
                } ?: "")
            } + "\n"
        )
        if (++lines % 20 == 0) w.flush()
    }

    fun close() {
        runCatching { w.flush(); w.close() }
    }
}
