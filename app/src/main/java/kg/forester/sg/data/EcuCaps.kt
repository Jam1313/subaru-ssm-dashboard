package kg.forester.sg.data

import kg.forester.sg.ssm.EcuInit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Capability bits the driver turns on by hand, per ROM. Old ECUs (JECS above all) leave bits off for sensors they
 * do have, so MAF, intake air or throttle stayed empty: the init's list is trusted everywhere else, since a byte the
 * ECU really lacks comes back as junk (an EJ253 showed "knock correction +63.5°"). Bits in RomRaider's numbering
 * (see [EcuInit.supports]).
 */
object EcuCaps {
    private fun all(): JSONObject = runCatching { JSONObject(Repo.settings.capsForced) }.getOrDefault(JSONObject())

    fun forced(romId: String): Set<Pair<Int, Int>> {
        val a = all().optJSONArray(romId.uppercase()) ?: return emptySet()
        return List(a.length()) { a.getString(it) }.mapNotNull { s ->
            val b = s.substringBefore('.').toIntOrNull()
            val bit = s.substringAfter('.', "").toIntOrNull()
            if (b != null && bit != null) b to bit else null
        }.toSet()
    }

    fun set(romId: String, byte: Int, bit: Int, on: Boolean) {
        val rom = romId.uppercase()
        val now = forced(rom).let { if (on) it + (byte to bit) else it - (byte to bit) }
        val o = all()
        if (now.isEmpty()) o.remove(rom) else o.put(rom, JSONArray(now.sortedWith(compareBy({ it.first }, { it.second })).map { "${it.first}.${it.second}" }))
        Repo.settings.capsForced = o.toString()
        Repo.capsVersion.value++
    }

    /** A capability bit the ECU's init leaves off, with the standard parameters behind it. */
    data class Hidden(val byte: Int, val bit: Int, val names: List<String>)

    /** What the connected ECU says it lacks, in its own init (before the hand-set bits). */
    fun hidden(ctx: android.content.Context): List<Hidden> {
        val init = Repo.ecuInitRaw.value ?: return emptyList()
        return kg.forester.sg.ssm.RomRaider.load(ctx)
            .filter { p ->
                p.kind == kg.forester.sg.ssm.RrParam.Kind.STD && (p.target == 1 || p.target == 3) && p.addr >= 0 &&
                    p.capByte - 8 in init.capabilities.indices && !init.supports(p.capByte, p.capBit)
            }
            .groupBy { it.capByte to it.capBit }
            .map { (k, ps) -> Hidden(k.first, k.second, ps.map { it.name }) }
            .sortedWith(compareBy({ it.byte }, { -it.bit }))
    }

    /** [init] with this ROM's hand-set bits on (only in the bytes it sent: past them nothing is ruled out anyway). */
    fun apply(init: EcuInit): EcuInit {
        val bits = forced(init.romId).filter { it.first - 8 in init.capabilities.indices && it.second in 0..7 }
        if (bits.isEmpty()) return init
        val caps = init.capabilities.copyOf()
        for ((b, bit) in bits) caps[b - 8] = (caps[b - 8].toInt() or (1 shl bit)).toByte()
        return init.copy(capabilities = caps)
    }
}
