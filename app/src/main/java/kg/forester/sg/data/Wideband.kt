package kg.forester.sg.data

import kg.forester.sg.devices.innovate.InnovateReading
import kg.forester.sg.devices.innovate.InnovateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * A wideband controller with a digital output (Innovate LC-2, LC-1, MTX-L) on a USB-serial adapter of its own.
 * The adapter is remembered by its ids and serial number; a chip without one (CH340) by where it was plugged in,
 * [path], which only helps to pick whom to listen to first: a device is taken for the wideband only by its serial
 * number or after its Innovate packets were heard (see [WidebandPick]), never just for having the same ids as the
 * ECU cable.
 */
data class WidebandConfig(val enabled: Boolean = false, val vendor: Int = -1, val product: Int = -1,
                          val serial: String = "", val port: Int = 0, val path: String = "") {
    val chosen: Boolean get() = enabled && vendor >= 0 && product >= 0
}

enum class WidebandLinkState { DISABLED, WAITING, PERMISSION, AMBIGUOUS, BUSY, CONNECTING, RECEIVING, STALE, ERROR, DEMO }

data class WidebandSample(val device: String, val timeMs: Long, val elapsedMs: Long, val reading: InnovateReading)

data class WidebandState(val link: WidebandLinkState = WidebandLinkState.DISABLED, val message: String = "Выключено",
                         val sample: WidebandSample? = null) {
    fun fresh(now: Long) = link in setOf(WidebandLinkState.RECEIVING, WidebandLinkState.DEMO) &&
        sample?.let { now - it.elapsedMs in 0..STALE_MS } == true
    fun afr(now: Long): Float? = if (fresh(now)) sample?.reading?.afr else null
    fun lambda(now: Long): Float? = if (fresh(now)) sample?.reading?.lambda else null
    /** For the CSV: the controller's status (an error with its code), or why there is none. */
    fun status(now: Long): String = if (fresh(now)) sample!!.reading.let {
        if (it.status == InnovateStatus.ERROR) "ERROR:${it.raw}" else it.status.name
    } else if (sample != null && link == WidebandLinkState.RECEIVING) "STALE" else link.name
    companion object { const val STALE_MS = 1500L }
}

/** A USB device with the chosen adapter's ids, as the wideband link sees it at this moment. */
data class WidebandCandidate(
    val name: String,
    /** Null: not readable yet (no USB permission); empty: the chip has none. */
    val serial: String?,
    val permitted: Boolean,
    /** Null: not listened to yet; true: Innovate packets heard; false: nothing like them, or the ECU answers through it. */
    val probe: Boolean?,
    /** Can be listened to now: nobody has it open, or the ECU loop has it and no ECU answers through it. */
    val free: Boolean,
)

/** Which of the devices with the chosen ids is the wideband's adapter, or what to do to find out. */
sealed class WidebandPick {
    data class Found(val name: String) : WidebandPick()
    /** Listen to it for a moment (see InnovateProbe). */
    data class Probe(val name: String) : WidebandPick()
    /** These need the USB permission before anything can be said about them. */
    data class Permission(val names: List<String>) : WidebandPick()
    /** None of them is it (the chosen adapter is unplugged). */
    data object Missing : WidebandPick()
    /** Listened to all, no packets: the controller is off (ignition) or not connected to the adapter. */
    data object Silent : WidebandPick()
    /** The one device is open by somebody else (the SensorBox asking it, the ECU loop connecting). */
    data object Busy : WidebandPick()
    /** Two or more identical adapters and no way to tell which one: the ECU cable is left alone. */
    data object Ambiguous : WidebandPick()

    companion object {
        fun of(cfg: WidebandConfig, all: List<WidebandCandidate>): WidebandPick {
            if (!cfg.chosen || all.isEmpty()) return Missing
            val pool = if (cfg.serial.isNotEmpty()) {
                val same = all.filter { it.serial == cfg.serial }
                // the one with its serial number, unless the ECU answers through it (the ECU cable picked by mistake)
                if (same.size == 1) return if (same.single().probe == false) Silent else Found(same.single().name)
                if (same.isEmpty()) {
                    val unknown = all.filter { it.serial == null }
                    return if (unknown.isNotEmpty()) Permission(unknown.map { it.name }) else Missing
                }
                // clones sharing one serial number: tell them apart by listening
                same
            } else all.filter { it.serial.isNullOrEmpty() } // one with a serial number is some other adapter
            if (pool.isEmpty()) return Missing
            val heard = pool.filter { it.probe == true }
            if (heard.size == 1) return Found(heard.single().name)
            if (heard.size > 1) return Ambiguous
            val open = pool.filter { it.probe == null }
            open.filter { it.permitted && it.free }.takeIf { it.isNotEmpty() }?.let { c ->
                return Probe((c.firstOrNull { it.name == cfg.path } ?: c.first()).name)
            }
            open.filter { !it.permitted }.takeIf { it.isNotEmpty() }?.let { c -> return Permission(c.map { it.name }) }
            if (open.isNotEmpty()) return if (pool.size > 1) Ambiguous else Busy
            return Silent
        }
    }
}

object Wideband {
    val config = MutableStateFlow(WidebandConfig())
    val state = MutableStateFlow(WidebandState())
    /** The device found to be the wideband's adapter, for as long as it stays plugged in. */
    @Volatile var device: String? = null
    @Volatile var preview = false

    fun load() {
        val j = runCatching { JSONObject(Repo.settings.wideband) }.getOrElse { JSONObject() }
        config.value = WidebandConfig(j.optBoolean("enabled"), j.optInt("vendor", -1), j.optInt("product", -1),
            j.optString("serial", ""), j.optInt("port", 0).coerceAtLeast(0), j.optString("path", ""))
        device = null
        preview = false
        state.value = WidebandState()
    }

    fun save(c: WidebandConfig) {
        if (c.vendor != config.value.vendor || c.product != config.value.product || c.serial != config.value.serial ||
            c.path != config.value.path || !c.enabled) device = null
        preview = false
        config.value = c
        state.value = WidebandState(if (c.enabled) WidebandLinkState.WAITING else WidebandLinkState.DISABLED,
            if (c.enabled) "Ожидание подключения" else "Выключено")
        Repo.settings.wideband = JSONObject().put("enabled", c.enabled).put("vendor", c.vendor)
            .put("product", c.product).put("serial", c.serial).put("port", c.port).put("path", c.path).toString()
    }
}
