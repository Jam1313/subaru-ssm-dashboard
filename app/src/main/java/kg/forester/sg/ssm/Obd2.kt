package kg.forester.sg.ssm

/**
 * Standard OBD-II (SAE J1979), the fallback when the ECU does not answer Subaru's SSM: any car since the
 * 2000s speaks it, but it only has the basic parameters (no knock, no boost control, no transmission).
 */
interface ObdLink {
    val name: String
    /** CAN: up to 6 PIDs per mode 01 request, and DTC answers carry a count byte. */
    val isCan: Boolean
    /** Sends [req] (e.g. 01 0C) and returns the answer frames, each starting with the response code (41 0C …). */
    fun request(req: ByteArray): List<ByteArray>?
    fun close()
}

/** OBD through an ELM327 with automatic protocol search: CAN, KWP2000 or ISO 9141, whatever the car has. */
class ElmObd(private val elm: Elm327) : ObdLink {
    private var protocol = ""
    override val name get() = "ELM327, OBD-II" + (PROTOCOLS[protocol]?.let { " ($it)" } ?: "")
    override val isCan get() = protocol in setOf("6", "7", "8", "9")

    /** Resets the SSM settings and lets the ELM find the car's protocol (up to ~10 s on the first request). */
    fun open(): Boolean {
        elm.cmd("ATD"); elm.cmd("ATE0"); elm.cmd("ATL0"); elm.cmd("ATS0"); elm.cmd("ATH0")
        elm.cmd("ATAT1"); elm.cmd("ATSP0")
        val r = runCatching { elm.cmd("0100", 15000) }.getOrNull() ?: return false
        if (Elm327.isError(r) || !r.filter { it.isLetterOrDigit() }.contains("4100")) return false
        protocol = runCatching { elm.cmd("ATDPN") }.getOrDefault("").trim().removePrefix("A")
        SsmTrace.log("OBD protocol $protocol")
        return true
    }

    override fun request(req: ByteArray): List<ByteArray>? {
        val r = runCatching { elm.cmd(Elm327.hex(req), 4000) }.getOrNull() ?: return null
        if (Elm327.isError(r)) return null
        val lines = r.split('\r', '\n').map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) }
        val frames = if (lines.any { it.length > 2 && it[1] == ':' }) {
            // CAN multi-frame with ATCAF1: a length line, then "0:…", "1:…"
            val len = lines.first().takeIf { !it.contains(':') }?.toIntOrNull(16) ?: return null
            val b = Elm327.bytes(lines.filter { it.contains(':') }.joinToString("") { it.substringAfter(':') })
            if (b.size < len) return null
            listOf(b.copyOf(len))
        } else {
            lines.map { Elm327.bytes(it) }
        }
        return frames.filter { it.isNotEmpty() && it[0] == (req[0] + 0x40).toByte() }.ifEmpty { null }
    }

    override fun close() {}

    companion object {
        private val PROTOCOLS = mapOf(
            "1" to "J1850 PWM", "2" to "J1850 VPW", "3" to "ISO 9141", "4" to "KWP 5 бод", "5" to "KWP быстрый",
            "6" to "CAN 11/500", "7" to "CAN 29/500", "8" to "CAN 11/250", "9" to "CAN 29/250",
        )
    }
}

/** OBD over a CAN transport that already does ISO-TP to the engine unit (7E0/7E8), e.g. the OpenPort 2.0. */
class CanObd(private val t: SsmTransport, adapter: String) : ObdLink {
    override val name = "$adapter, OBD-II (CAN)"
    override val isCan = true
    override fun request(req: ByteArray): List<ByteArray>? =
        t.exchange(SsmClient.ECU, req)?.takeIf { it.isNotEmpty() && it[0] == (req[0] + 0x40).toByte() }?.let { listOf(it) }

    override fun close() = t.close()
}

class Obd2(val link: ObdLink) {
    /** Mode 01 PIDs the ECU says it has. */
    var supported: Set<Int> = emptySet()
        private set
    private val values = HashMap<Int, ByteArray>()
    private var multi = link.isCan
    private var slowIdx = 0

    /** Reads the "PIDs supported" bitmaps (01 00, 01 20, 01 40). */
    fun init(): Boolean {
        val out = HashSet<Int>()
        var base = 0
        while (base <= 0x40) {
            val a = link.request(byteArrayOf(0x01, base.toByte()))?.firstOrNull() ?: break
            if (a.size < 6) break
            val bits = ((a[2].toLong() and 0xFF) shl 24) or ((a[3].toLong() and 0xFF) shl 16) or
                ((a[4].toLong() and 0xFF) shl 8) or (a[5].toLong() and 0xFF)
            for (i in 0 until 32) if (bits shr (31 - i) and 1L == 1L) out += base + i + 1
            if (base + 0x20 !in out) break
            base += 0x20
        }
        supported = out
        SsmTrace.log("OBD PIDs: ${out.sorted().joinToString(" ") { "%02X".format(it) }}")
        return out.isNotEmpty()
    }

    /** How many of the parameters this app shows the car has. */
    val polledCount get() = (FAST + SLOW).count { it in supported }

    /** One poll: the fast parameters every time, one of the slow ones in turn. Null when the ECU went silent. */
    fun poll(now: Long): EcuSnapshot? {
        val fast = FAST.filter { it in supported }
        val slow = SLOW.filter { it in supported }
        val ask = fast + listOfNotNull(slow.getOrNull(slowIdx++ % slow.size.coerceAtLeast(1)))
        var got = 0
        if (multi) {
            for (group in ask.chunked(6)) got += readMany(group)
            if (got == 0 && ask.isNotEmpty()) multi = false // ECU that only takes one PID per request
        }
        if (!multi) for (pid in ask) got += readMany(listOf(pid))
        if (got == 0) return null
        return decode(now)
    }

    private fun readMany(pids: List<Int>): Int {
        val frames = link.request(byteArrayOf(0x01) + pids.map { it.toByte() }) ?: return 0
        var n = 0
        for (f in frames) {
            var i = 1
            while (i < f.size) {
                val pid = f[i].toInt() and 0xFF
                val len = LEN[pid] ?: break
                if (i + 1 + len > f.size) break
                values[pid] = f.copyOfRange(i + 1, i + 1 + len)
                n++
                i += 1 + len
            }
        }
        return n
    }

    private fun decode(now: Long): EcuSnapshot {
        fun a(pid: Int) = values[pid]?.get(0)?.toInt()?.and(0xFF)
        fun ab(pid: Int) = values[pid]?.takeIf { it.size >= 2 }?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        return EcuSnapshot(
            timeMs = now,
            coolantC = a(0x05)?.let { it - 40 },
            rpm = ab(0x0C)?.let { it / 4 },
            speedKmh = a(0x0D),
            timingDeg = a(0x0E)?.let { it / 2f - 64f },
            intakeC = a(0x0F)?.let { it - 40 },
            mafGs = ab(0x10)?.let { it / 100f },
            throttlePct = a(0x11)?.let { it * 100f / 255f },
            batteryV = ab(0x42)?.let { it / 1000f },
            atmKpa = a(0x33)?.toFloat(),
            mapKpa = a(0x0B)?.toFloat(),
            afCorrPct = a(0x06)?.let { (it - 128) * 100f / 128f },
            afLearnPct = a(0x07)?.let { (it - 128) * 100f / 128f },
            lambda = (ab(0x34) ?: ab(0x24))?.takeIf { it != 0 }?.let { it * 2f / 65536f },
            pedalPct = a(0x49)?.let { it * 100f / 255f },
            fuelLevelPct = a(0x2F)?.let { it * 100f / 255f },
        )
    }

    /** Stored (mode 03) and pending (mode 07) trouble codes; null when the ECU did not answer. */
    fun readDtcs(): Pair<List<String>, List<String>>? {
        val stored = codes(0x03) ?: return null
        return stored to (codes(0x07) ?: emptyList())
    }

    private fun codes(mode: Int): List<String>? {
        val frames = link.request(byteArrayOf(mode.toByte())) ?: return null
        val out = ArrayList<String>()
        for (f in frames) {
            // CAN: 43 <count> <pairs>; K-line/KWP: 43 <3 pairs, padded with 00 00> per frame
            val start = if (link.isCan) 2 else 1
            var i = start
            while (i + 1 < f.size) {
                val hi = f[i].toInt() and 0xFF
                val lo = f[i + 1].toInt() and 0xFF
                if (hi != 0 || lo != 0) out += "PCBU"[hi shr 6] + "%X%X%02X".format((hi shr 4) and 3, hi and 0xF, lo)
                i += 2
            }
        }
        return out.distinct()
    }

    /** Mode 04: clears the codes and the check engine light (also resets the readiness monitors). */
    fun clearDtcs(): Boolean = link.request(byteArrayOf(0x04)) != null

    companion object {
        /** Every cycle: what the gauges and the fuel calculation need. */
        private val FAST = listOf(0x0C, 0x0D, 0x10, 0x11, 0x0B, 0x0E, 0x06)
        /** One per cycle in turn. */
        private val SLOW = listOf(0x05, 0x0F, 0x42, 0x33, 0x07, 0x2F, 0x49, 0x34, 0x24)
        private val LEN = mapOf(
            0x04 to 1, 0x05 to 1, 0x06 to 1, 0x07 to 1, 0x0B to 1, 0x0C to 2, 0x0D to 1, 0x0E to 1, 0x0F to 1,
            0x10 to 2, 0x11 to 1, 0x24 to 4, 0x2F to 1, 0x33 to 1, 0x34 to 4, 0x42 to 2, 0x49 to 1,
            0x00 to 4, 0x20 to 4, 0x40 to 4,
        )
    }
}
