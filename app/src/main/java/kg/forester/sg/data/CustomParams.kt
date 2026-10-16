package kg.forester.sg.data

import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parameter the driver adds by its ECU address, the way BtSSM's "custom field" works: needed for ROMs the
 * RomRaider base doesn't know (e.g. the right-hand-drive "572" ECU keeps FLKC and FBKC at its own RAM addresses).
 * [role] makes the engine guard use it as IAM / FBKC / FLKC / target boost.
 */
data class CustomParam(
    val id: String,
    val name: String,
    val addr: Int,
    /** 1, 2 or 4 bytes, big-endian as the ECU stores them. */
    val len: Int,
    /** "" unsigned, "int8"/"int16"/"int32" signed, "float" IEEE 754. */
    val storage: String,
    /** Conversion over x, the raw number: "x", "x*0.25", "(x-128)/2"… */
    val formula: String,
    val units: String,
    val decimals: Int,
    /** "", "iam", "fbkc", "flkc", "target" or "mapAbs". */
    val role: String,
) {
    fun toParam(): RrParam = RrParam(
        id = id, kind = RrParam.Kind.EXT, target = 1, name = name, nameEn = name, desc = "свой адрес",
        addr = addr, len = len, bit = -1, units = units, format = if (decimals > 0) "0." + "0".repeat(decimals) else "0",
        storage = storage, expr = RrExpr.parse(formula.ifBlank { "x" }), deps = emptyList(), conversions = emptyMap(),
    )

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("addr", addr).put("len", len)
        .put("storage", storage).put("formula", formula).put("units", units).put("dec", decimals).put("role", role)

    companion object {
        val ROLES = listOf(
            "" to "нет", "iam" to "IAM", "fbkc" to "FBKC (откат)", "flkc" to "FLKC (обучение)", "target" to "Целевой наддув",
            // a ROM the RomRaider base doesn't know: its own manifold pressure address lifts the 1.53 bar ceiling of the standard byte
            "mapAbs" to "Давление во впуске, абс. кПа",
        )
        val STORAGES = listOf("" to "без знака", "int8" to "со знаком 8", "int16" to "со знаком 16", "int32" to "со знаком 32", "float" to "float")

        fun fromJson(o: JSONObject) = CustomParam(
            o.getString("id"), o.optString("name"), o.getInt("addr"), o.optInt("len", 1).coerceIn(1, 4),
            o.optString("storage"), o.optString("formula", "x"), o.optString("units"), o.optInt("dec", 1), o.optString("role"),
        )
    }
}

object CustomParams {
    fun list(): List<CustomParam> = runCatching {
        val a = JSONArray(Repo.settings.customParams)
        List(a.length()) { CustomParam.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(l: List<CustomParam>) {
        Repo.settings.customParams = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
        Repo.customVersion.value++
    }

    /** A new id that no saved parameter uses ("U1", "U2"…; RomRaider ids start with P, E and S). */
    fun newId(): String {
        val used = list().map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "U$it" }.first { it !in used }
    }

    fun put(p: CustomParam) {
        val l = list()
        save(if (l.any { it.id == p.id }) l.map { if (it.id == p.id) p else it } else l + p)
        // a new one is watched straight away, so it shows on «Датчики» and in the log without an extra tick
        if (p.id !in Repo.settings.watchIds) Repo.settings.watchIds = Repo.settings.watchIds + p.id
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        Repo.settings.watchIds = Repo.settings.watchIds - id
    }

    /** Role → parameter id, for the engine guard. */
    fun roles(): Map<String, String> = list().filter { it.role.isNotEmpty() }.associate { it.role to it.id }
}
