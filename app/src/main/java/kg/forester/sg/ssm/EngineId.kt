package kg.forester.sg.ssm

import android.content.Context
import kg.forester.sg.data.Repo

/**
 * Which engine the car has: worked out from the ECU's ROM ID (the car it was made for, assets/ecu_ids.json:
 * "JDM Forester XT 2003 AT" → EJ20 turbo), else from the car set up in the app, and confirmed by what the ECU
 * reports (a wastegate = turbo). The family is sure; the exact code (EJ205, EJ257…) is the likely one for that
 * market and year.
 */
data class EngineInfo(
    /** "EJ20", "EJ22", "EJ25", "FA20", "FB", "EZ30", "EZ36", "EE20". */
    val family: String,
    val turbo: Boolean,
    /** The likely code, e.g. "EJ205"; may be just the family. */
    val code: String,
    /** How it was found: "ЭБУ", "настройки", "пример". */
    val source: String,
    val romId: String? = null,
    val car: String? = null,
) {
    val cylinders get() = if (family.startsWith("EZ")) 6 else 4
    val litres get() = when (family) { "EJ25", "FB" -> "2.5"; "EJ22" -> "2.2"; "EZ30" -> "3.0"; "EZ36" -> "3.6"; else -> "2.0" }
    val diesel get() = family == "EE20"
    val title: String get() = "$code · $litres ${if (diesel) "дизель" else if (turbo) "турбо" else "атмо"}" +
        if (cylinders == 6) " · шесть цилиндров" else ""
}

object EngineId {
    private val TURBO_WORDS = listOf("XT", "WRX", "STI", "GT", "TURBO", "SPEC.B", "SGT", "B4", "2.0GT", "DIT")

    /** The engine from a car description of the ECU id base ("USDM Impreza STI 2007 MT"). */
    fun fromCar(car: String, source: String = "ЭБУ", romId: String? = null): EngineInfo {
        val u = car.uppercase()
        val words = u.split(' ', '/').filter { it.isNotBlank() }
        val year = words.mapNotNull { it.toIntOrNull() }.firstOrNull { it in 1990..2030 }
        val market = words.firstOrNull().orEmpty()
        val turbo = TURBO_WORDS.any { t -> words.any { it == t || it.startsWith(t) } } && !u.contains(" NA ")
        fun info(f: String, t: Boolean, code: String) = EngineInfo(f, t, code, source, romId, car)
        return when {
            u.contains("2.0D") -> info("EE20", true, "EE20")
            u.contains("3.6") -> info("EZ36", false, "EZ36")
            u.contains("3.0R") || u.contains(" H6") || u.contains("TRIBECA") || u.contains("B9") -> info("EZ30", false, "EZ30")
            u.contains("FA20") || u.contains("BRZ") || (u.contains("WRX") && !u.contains("STI") && (year ?: 0) >= 2015) ->
                info("FA20", u.contains("WRX"), if (u.contains("WRX")) "FA20DIT" else "FA20")
            u.contains("FB25") || (u.contains(" FB") ) || u.contains(" XV") || (u.contains("FORESTER") && !turbo && (year ?: 0) >= 2011) ->
                info("FB", false, if (u.contains("FB20") || u.contains("2.0")) "FB20" else "FB25")
            turbo -> {
                // the 2.0 turbos stayed in Japan (and early WRX elsewhere); the rest of the world got the 2.5
                val sti = u.contains("STI")
                val two = market == "JDM" || u.contains("2.0") ||
                    (u.contains("WRX") && (year ?: 2005) <= 2005 && market != "JDM") || u.contains("EXIGA")
                when {
                    two && sti -> info("EJ20", true, "EJ207")
                    two && (u.contains("LEGACY") || u.contains("B4") || u.contains("GT")) && (year ?: 2004) >= 2003 -> info("EJ20", true, "EJ20X/EJ20Y")
                    two -> info("EJ20", true, "EJ205")
                    sti -> info("EJ25", true, "EJ257")
                    else -> info("EJ25", true, "EJ255")
                }
            }
            u.contains("2.0") || market == "JDM" || u.contains("2.0I") || u.contains("2.0X") -> info("EJ20", false, "EJ20 (EJ201/EJ202/EJ204)")
            u.contains("2.2") -> info("EJ22", false, "EJ22")
            else -> info("EJ25", false, if ((year ?: 2005) >= 2006) "EJ253" else "EJ251")
        }
    }

    /** From the app's car setup ("2003 · EJ20 turbo"), when no ECU has answered. */
    fun fromSettings(sub: String): EngineInfo? {
        val u = sub.uppercase()
        val fam = listOf("EJ20", "EJ22", "EJ25", "FA20", "FB25", "FB20", "EZ30", "EZ36").firstOrNull { u.contains(it) } ?: return null
        val f = if (fam.startsWith("FB")) "FB" else fam
        return EngineInfo(f, u.contains("TURBO") || u.contains("ТУРБО"), fam, "настройки", car = sub)
    }

    /** The best guess now: the ECU's ROM first, then the settings; a wastegate or boost seen makes it a turbo. */
    fun detect(ctx: Context): EngineInfo {
        val init = Repo.ecuInit.value
        val car = init?.romId?.let { EcuIds.car(ctx, it) }
        val base = when {
            car != null -> fromCar(car, "ЭБУ", init.romId)
            else -> fromSettings(Repo.settings.carSub) ?: EngineInfo("EJ20", Repo.guard.value.turbo, "EJ20", "пример")
        }
        val turboSeen = Repo.guard.value.turbo || (init != null && EcuParams.reported(init, EcuParams.WGDC) && init.capabilities.size > 4)
        return if (turboSeen && !base.turbo && base.family.startsWith("EJ")) base.copy(turbo = true) else base
    }
}
