package kg.forester.sg.data

import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parameter the driver adds by its ECU address, the way BtSSM's "custom field" works: needed for ROMs the
 * RomRaider base doesn't know (e.g. the right-hand-drive "572" ECU keeps FLKC and FBKC at its own RAM addresses).
 * [role] makes the engine guard use it as IAM / FBKC / FLKC / target boost, or puts it in place of the ECU's own
 * value ([STAND_IN]). Without an address ([addr] -1) it is only a formula over other values: P8, [P12], U1…
 */
data class CustomParam(
    val id: String,
    val name: String,
    /** -1: no address, the value is the [formula] over other parameters. */
    val addr: Int,
    /** 1, 2 or 4 bytes, big-endian as the ECU stores them. */
    val len: Int,
    /** "" unsigned, "int8"/"int16"/"int32" signed, "float" IEEE 754. */
    val storage: String,
    /** Conversion over x, the raw number: "x", "x*0.25", "(x-128)/2"…; may use other parameters too: "x*P8/1000". */
    val formula: String,
    val units: String,
    val decimals: Int,
    /** One of [ROLES], "" for none. */
    val role: String,
) {
    val calculated: Boolean get() = addr < 0

    fun toParam(): RrParam {
        val expr = RrExpr.parse(formula.ifBlank { "x" })
        return RrParam(
            id = id, kind = RrParam.Kind.EXT, target = 1, name = name, nameEn = name, desc = if (calculated) "своя формула" else "свой адрес",
            addr = if (calculated) -1 else addr, len = if (calculated) 0 else len, bit = -1, units = units,
            format = if (decimals > 0) "0." + "0".repeat(decimals) else "0",
            storage = storage, expr = expr, deps = expr?.refs.orEmpty(), conversions = emptyMap(),
        )
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("addr", addr).put("len", len)
        .put("storage", storage).put("formula", formula).put("units", units).put("dec", decimals).put("role", role)

    companion object {
        val ROLES = listOf(
            "" to "нет", "iam" to "IAM", "fbkc" to "FBKC (откат)", "flkc" to "FLKC (обучение)", "target" to "Целевой наддув",
            // a ROM the RomRaider base doesn't know: its own manifold pressure address lifts the 1.53 bar ceiling of the standard byte
            "mapAbs" to "Давление во впуске, абс. кПа",
            "maf" to "MAF, г/с", "iat" to "Воздух на впуске, °C", "coolant" to "ОЖ, °C", "throttle" to "Дроссель, %",
            "injPw" to "Время впрыска, мс", "afr" to "AFR (свой или виртуальный ШДК)", "timing" to "УОЗ, °",
            "atm" to "Атм. давление, кПа", "oil" to "Масло, °C",
        )
        /**
         * Roles that take the ECU's own value's place everywhere (gauges, fuel count, logs, warnings): for what an
         * old ECU (JECS) doesn't give or gives wrong. "oil" is the guard's own pick, the base's address after this one.
         */
        val STAND_IN = listOf("maf", "iat", "coolant", "throttle", "injPw", "afr", "timing", "atm")
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
