package kg.forester.sg.ssm

import org.json.JSONObject

/**
 * Descriptions of the standard trouble codes (SAE J2012: P0xxx, P2xxx, P34xx, U0xxx…) in English and Russian,
 * assets/obd_dtc_ru.json (made by tools/obd/build_dtc.py, which names the sources). The make's own codes (P1xxx,
 * P30xx–P33xx, B1/B2, C1/C2, U1/U2) mean something else on every make: no description is made up for them.
 */
class GenericDtc(json: String) {
    private val codes = HashMap<String, Pair<String, String>>()

    init {
        val o = JSONObject(json).optJSONObject("codes")
        o?.keys()?.forEach { k ->
            val a = o.optJSONArray(k) ?: return@forEach
            codes[k.uppercase()] = a.optString(0) to a.optString(1)
        }
    }

    val size get() = codes.size

    /** (English, Russian) of a standard code, or null. */
    operator fun get(code: String): Pair<String, String>? = codes[code.uppercase()]

    /** A text for the screen: Subaru's own table first (for a Subaru), then the standard one, then what kind of code it is. */
    data class Text(val ru: String, val en: String, val known: Boolean)

    fun describe(code: String, subaru: Map<String, Pair<String, String>>? = null, make: String? = null): Text {
        val c = code.uppercase()
        subaru?.get(c)?.let { (en, ru) -> if (ru.isNotBlank() || en.isNotBlank()) return Text(ru.ifBlank { en }, en, true) }
        get(c)?.let { (en, ru) -> return Text(ru.ifBlank { en }, en, true) }
        if (isManufacturer(c)) return Text(manufacturerNote(make), "", false)
        return Text("Стандартный код без описания в базе", "", false)
    }

    companion object {
        /**
         * The make's own range (SAE J2012): the second character 1 (P1, B1, C1, U1), B2/C2/U2, and P3000–P33FF;
         * P34xx–P39xx, P0, P2 and the 0/3 ranges of the other letters are the standard's.
         */
        fun isManufacturer(code: String): Boolean {
            val c = code.uppercase()
            if (c.length != 5) return false
            return when (c[0]) {
                'P' -> c[1] == '1' || (c[1] == '3' && c[2] in '0'..'3')
                'B', 'C', 'U' -> c[1] == '1' || c[1] == '2'
                else -> false
            }
        }

        fun manufacturerNote(make: String?) = "Заводской код ${make?.takeIf { it.isNotBlank() } ?: "производителя"}, описание недоступно"
    }
}
