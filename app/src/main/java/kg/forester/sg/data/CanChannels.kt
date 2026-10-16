package kg.forester.sg.data

import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A value the driver takes off the CAN bus by id and bytes, the way dash makers do it: an EGT the tuner put in a
 * user stream of the Link, a sensor of a Haltech or ECUMaster. Found with the CAN monitor, checked by its live value.
 * [role] lets the engine guard use it (EGT, oil pressure, oil temperature) as it uses a SensorBox input.
 */
data class CanChannel(
    val id: String,
    val name: String,
    val canId: Int,
    val ext: Boolean,
    /** Compound stream: byte 0 must be this frame number; -1 = any frame of the id. */
    val mux: Int,
    /** First byte, 0–7. */
    val byte: Int,
    /** 1, 2 or 4 bytes. */
    val len: Int,
    /** Motorola (high byte first) or Intel (low byte first, as the Link's Generic Dash). */
    val bigEndian: Boolean,
    val signed: Boolean,
    /** Over x, the raw number: "x*0.1", "x-50"… */
    val formula: String,
    val units: String,
    val decimals: Int,
    /** "", "egt", "oilP" (bar), "oil" (°C). */
    val role: String,
) {
    private val expr by lazy { RrExpr.parse(formula.ifBlank { "x" }) }

    /** The raw number in [d], or null when the frame is too short (or not the right frame of a compound stream). */
    fun raw(d: ByteArray): Long? {
        if (mux >= 0 && (d.isEmpty() || (d[0].toInt() and 0xFF) != mux)) return null
        if (byte < 0 || byte + len > d.size) return null
        var u = 0L
        for (i in 0 until len) {
            val b = d[byte + if (bigEndian) i else len - 1 - i].toLong() and 0xFF
            u = (u shl 8) or b
        }
        if (signed) { val bits = len * 8; if (u and (1L shl (bits - 1)) != 0L) u -= 1L shl bits }
        return u
    }

    fun decode(d: ByteArray): Double? = raw(d)?.let { x -> expr?.eval(x.toDouble()) { _, _ -> null } ?: x.toDouble() }

    fun matches(id: Int, isExt: Boolean) = id == canId && isExt == ext

    fun toParam(): RrParam = RrParam(
        id = id, kind = RrParam.Kind.EXT, target = 1, name = name, nameEn = name, desc = "CAN ${idText()}",
        addr = -1, len = 0, bit = -1, units = units, format = if (decimals > 0) "0." + "0".repeat(decimals) else "0",
        storage = "", expr = null, deps = emptyList(), conversions = emptyMap(),
    )

    fun idText() = (if (ext) "%08X" else "%03X").format(canId) + (if (mux >= 0) " #$mux" else "") + ", байт $byte" + (if (len > 1) "–${byte + len - 1}" else "")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("can", canId).put("ext", ext).put("mux", mux)
        .put("byte", byte).put("len", len).put("be", bigEndian).put("signed", signed).put("formula", formula)
        .put("units", units).put("dec", decimals).put("role", role)

    companion object {
        val ROLES = listOf("" to "нет", "egt" to "ЕГТ, °C", "oilP" to "Давление масла, бар", "oil" to "Темп. масла, °C")

        fun fromJson(o: JSONObject) = CanChannel(
            o.getString("id"), o.optString("name"), o.getInt("can"), o.optBoolean("ext"), o.optInt("mux", -1),
            o.optInt("byte").coerceIn(0, 7), o.optInt("len", 2).let { if (it in setOf(1, 2, 4)) it else 2 }, o.optBoolean("be"),
            o.optBoolean("signed"), o.optString("formula", "x"), o.optString("units"), o.optInt("dec", 1), o.optString("role"),
        )
    }
}

object CanChannels {
    @Volatile private var cache: List<CanChannel>? = null
    private val values = ConcurrentHashMap<String, Pair<Double, Long>>()

    fun list(): List<CanChannel> = cache ?: runCatching {
        val a = JSONArray(Repo.settings.canChannels)
        List(a.length()) { CanChannel.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList()).also { cache = it }

    fun save(l: List<CanChannel>) {
        Repo.settings.canChannels = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
        cache = l
        Repo.customVersion.value++
    }

    /** "C1", "C2"…: apart from RomRaider's P/E/S and the own addresses' U. */
    fun newId(): String {
        val used = list().map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "C$it" }.first { it !in used }
    }

    fun put(c: CanChannel) {
        val l = list()
        save(if (l.any { it.id == c.id }) l.map { if (it.id == c.id) c else it } else l + c)
        if (c.id !in Repo.settings.watchIds) Repo.settings.watchIds = Repo.settings.watchIds + c.id
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        values.remove(id)
        Repo.settings.watchIds = Repo.settings.watchIds - id
    }

    /** A frame off the bus: every channel it carries takes its value. */
    fun feed(id: Int, ext: Boolean, d: ByteArray, now: Long = System.currentTimeMillis()) {
        for (c in list()) if (c.matches(id, ext)) c.decode(d)?.let { values[c.id] = it to now }
    }

    /** Values up to [maxAgeMs] old, by channel id. */
    fun values(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 3000): Map<String, Double> =
        values.filter { now - it.value.second <= maxAgeMs }.mapValues { it.value.first }

    fun clearValues() = values.clear()
}
