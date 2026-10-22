package kg.forester.sg.data

import android.content.Context
import org.json.JSONObject

/** A car make: [slug] keys the logos, [country] is where the brand is from (ISO 3166 code), when known. */
data class Brand(val slug: String, val name: String, val country: String?)

/**
 * The car make from the VIN, offline. The tables come from assets/brands (built by tools/brands/build_brands.py
 * from NHTSA vPIC plus a checked supplement for Russian/CIS assembly and Chinese makers): the WMI (VIN chars 1-3,
 * or 1-3 + 12-14 for a small maker whose third char is 9), then a 2-char prefix of a big maker, else nothing.
 * Only the JSON text goes in, so the lookup itself runs anywhere; [get] reads the assets once.
 */
class Brands(wmiJson: String, logosJson: String? = null) {
    private val wmi = HashMap<String, String>()
    private val prefix2 = HashMap<String, String>()
    private val region = HashMap<Char, String>()
    private val makes = HashMap<String, Brand>()
    private val bundled = HashSet<String>()
    private val remote = HashMap<String, String>()

    init {
        val j = JSONObject(wmiJson)
        j.optJSONObject("wmi")?.let { o -> o.keys().forEach { wmi[it] = o.getString(it) } }
        j.optJSONObject("prefix2")?.let { o -> o.keys().forEach { prefix2[it] = o.getString(it) } }
        j.optJSONObject("region")?.let { o -> o.keys().forEach { if (it.length == 1) region[it[0]] = o.getString(it) } }
        j.optJSONObject("makes")?.let { o ->
            o.keys().forEach { slug ->
                val m = o.getJSONObject(slug)
                makes[slug] = Brand(slug, m.optString("name", slug), m.optString("country").ifBlank { null })
            }
        }
        if (logosJson != null) {
            val l = JSONObject(logosJson)
            l.optJSONArray("bundled")?.let { a -> for (i in 0 until a.length()) bundled += a.getString(i) }
            val base = l.optString("remoteBase")
            l.optJSONObject("remote")?.let { o -> o.keys().forEach { remote[it] = base + o.getString(it) } }
        }
    }

    /** The make, or null for an invalid VIN or a maker the tables don't know. */
    fun makeFromVin(vin: String): Brand? {
        val v = normalize(vin) ?: return null
        val slug = (if (v[2] == '9') wmi[v.substring(0, 3) + v.substring(11, 14)] else null)
            ?: wmi[v.substring(0, 3)] ?: prefix2[v.substring(0, 2)] ?: return null
        return brand(slug)
    }

    fun brand(slug: String): Brand? = makes[slug]

    /** Where the maker is registered, by the first VIN char: AF, AS, EU, NA, OC, SA; null if unknown or invalid. */
    fun region(vin: String): String? = normalize(vin)?.let { region[it[0]] }

    fun isSubaru(vin: String) = makeFromVin(vin)?.slug == SUBARU

    /** The bundled logo's asset path (brands/logos/<slug>.webp), or null if there is none in the APK. */
    fun logoAsset(slug: String): String? = if (slug in bundled) "$ASSET_DIR/logos/$slug.webp" else null

    /** Where to download a logo that isn't bundled (a pinned GitHub raw URL, PNG), or null. */
    fun remoteLogoUrl(slug: String): String? = remote[slug]

    val wmiCount get() = wmi.size
    val makeCount get() = makes.size

    companion object {
        const val SUBARU = "subaru"
        const val ASSET_DIR = "brands"
        private val VIN = Regex("[A-HJ-NPR-Z0-9]{17}")
        private const val TRANSLIT = "0123456789.ABCDEFGH..JKLMN.P.R..STUVWXYZ"
        private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

        @Volatile private var cached: Brands? = null

        /** The tables from the assets, read once. */
        fun get(ctx: Context): Brands = cached ?: synchronized(this) {
            cached ?: run {
                val assets = ctx.applicationContext.assets
                fun text(name: String) = assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                Brands(text("wmi.json"), runCatching { text("logos.json") }.getOrNull())
            }.also { cached = it }
        }

        /** The VIN in upper case without spaces, or null unless it is 17 chars with no I, O or Q. */
        fun normalize(vin: String): String? {
            val v = vin.filterNot { it.isWhitespace() || it == '-' }.uppercase()
            return if (VIN.matches(v)) v else null
        }

        /**
         * The check digit (9th char) of a North American VIN, as a hint only: true/false for a VIN made for
         * North America (first char 1-5), null for the rest, where the 9th char is often not a check digit.
         */
        fun checkDigitOk(vin: String): Boolean? {
            val v = normalize(vin) ?: return null
            if (v[0] !in '1'..'5') return null
            var sum = 0
            for (i in v.indices) {
                val c = v[i]
                val n = if (c.isDigit()) c - '0' else TRANSLIT.indexOf(c) % 10
                sum += n * WEIGHTS[i]
            }
            val r = sum % 11
            return v[8] == (if (r == 10) 'X' else '0' + r)
        }
    }
}
