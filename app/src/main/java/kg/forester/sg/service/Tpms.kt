package kg.forester.sg.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ssm.toHex
import org.json.JSONObject

/** One tyre: pressure (bar), temperature (°C), sensor battery (%), when it was heard. */
data class TyreReading(val sensor: String, val bar: Float, val tempC: Float, val battery: Int, val timeMs: Long, val leak: Boolean = false)

/**
 * Bluetooth LE tyre pressure sensors, the common cheap valve-cap / in-wheel kind sold as "BLE TPMS" (they show up as
 * "TPMS1_xxxxxx"…"TPMS4_xxxxxx" and send manufacturer data 00 01 | 80…83 + 5-byte id | pressure Pa (int32 LE) |
 * temperature 0.01 °C (int32 LE) | battery % | alarm). The first byte after the company id gives the wheel
 * (80 = front left … 83 = rear right) unless the user moved a sensor. Other sensors are logged for later support.
 */
object Tpms {
    val WHEELS = listOf("FL", "FR", "RL", "RR")
    val NAMES = mapOf("FL" to "Переднее левое", "FR" to "Переднее правое", "RL" to "Заднее левое", "RR" to "Заднее правое")
    private var cb: ScanCallback? = null
    private val unknown = HashSet<String>()
    private val history = HashMap<String, ArrayDeque<Pair<Long, Float>>>()

    fun permitted(ctx: Context) = if (Build.VERSION.SDK_INT >= 31)
        ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (cb != null || !Repo.settings.tpms || !permitted(ctx)) return
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
            override fun onScanFailed(errorCode: Int) { SsmTrace.log("TPMS scan failed $errorCode") }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(null, settings, c); cb = c }.onFailure { SsmTrace.log("TPMS: $it") }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        val c = cb ?: return
        cb = null
        runCatching { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner.stopScan(c) }
    }

    @SuppressLint("MissingPermission")
    private fun handle(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val name = runCatching { rec.deviceName ?: r.device.name }.getOrNull() ?: ""
        val md = rec.manufacturerSpecificData
        for (i in 0 until md.size()) {
            val company = md.keyAt(i)
            val d = md.valueAt(i) ?: continue
            if (company == 0x0100 && d.size >= 16 && (d[0].toInt() and 0xF0) == 0x80) {
                parse(d)
                return
            }
        }
        if (name.contains("TPMS", true) && unknown.add(r.device.address)) {
            val raw = (0 until md.size()).joinToString(" ") { "%04X:%s".format(md.keyAt(it), md.valueAt(it).toHex()) }
            SsmTrace.log("TPMS unknown $name ${r.device.address}: $raw")
        }
    }

    private fun le32(d: ByteArray, at: Int) =
        (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8) or ((d[at + 2].toInt() and 0xFF) shl 16) or (d[at + 3].toInt() shl 24)

    private fun parse(d: ByteArray) {
        val id = d.copyOfRange(0, 6).toHex()
        val bar = le32(d, 6) / 100_000f
        val temp = le32(d, 10) / 100f
        val battery = d[14].toInt() and 0xFF
        val now = System.currentTimeMillis()
        val wheel = wheelOf(id, d[0].toInt() and 0x0F)
        // slow leak: more than 0.15 bar lost over 10 minutes at a steady temperature
        val h = history.getOrPut(id) { ArrayDeque() }
        h.addLast(now to bar)
        while (h.isNotEmpty() && now - h.first().first > 10 * 60_000L) h.removeFirst()
        val leak = h.size > 5 && h.first().second - bar > 0.15f && now - h.first().first > 5 * 60_000L
        Repo.tyres.value = Repo.tyres.value + (wheel to TyreReading(id, bar, temp, battery, now, leak))
        val min = Repo.settings.tpmsMinBar
        val w = NAMES[wheel]?.lowercase() ?: wheel
        if (bar in 0.3f..min) Alerts.raise("tyre$wheel", "Низкое давление: $w колесо %.1f бар".format(bar), Alerts.Level.DANGER, 10 * 60_000L)
        else if (leak) Alerts.raise("leak$wheel", "$w колесо теряет давление: %.1f бар".format(bar), Alerts.Level.WARN, 15 * 60_000L)
    }

    private fun wheelOf(id: String, index: Int): String {
        val map = runCatching { JSONObject(Repo.settings.tpmsWheels) }.getOrDefault(JSONObject())
        return map.optString(id).takeIf { it in WHEELS } ?: WHEELS.getOrElse(index) { "FL" }
    }

    /** Puts the sensor now shown at [from] on wheel [to] (and the one at [to] where [from] was). */
    fun swap(from: String, to: String) {
        val t = Repo.tyres.value
        val a = t[from]; val b = t[to]
        val map = runCatching { JSONObject(Repo.settings.tpmsWheels) }.getOrDefault(JSONObject())
        a?.let { map.put(it.sensor, to) }
        b?.let { map.put(it.sensor, from) }
        Repo.settings.tpmsWheels = map.toString()
        Repo.tyres.value = t - from - to + listOfNotNull(a?.let { to to it }, b?.let { from to it })
    }
}
