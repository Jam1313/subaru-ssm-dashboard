package kg.forester.sg.ssm

/**
 * The dash streams a Link G4+/G4X sends on its CAN by itself, found without asking which one is set in PCLink:
 *
 * - «Dash2Pro» (what RealDash's "LINK: Short CAN" reads): four plain frames on ids base…base+3 (1000…1003 by
 *   default), high byte first. Layout from RealDash's link_dash2pro.xml (janimm/RealDash-extras, public domain).
 * - «Generic Dash»: a compound message, every frame on one id, byte 0 = frame number 0–12, then three 16-bit values
 *   each, low byte first. Layout after jasongaunt's open receiver for Link ECUs (MIT,
 *   github.com/jasongaunt/Adafruit-Feather-M4-CAN-Express-Link-ECU-Generic-Dash).
 *
 * A stream is taken only when its voltage, coolant and engine speed make sense, so another unit's chatter on the
 * same ids is not. No EGT in either: an EGT added to a user stream is read with «Свои значения из CAN».
 */
object LinkDash {
    enum class Format(val title: String) { DASH2PRO("Dash2Pro"), GENERIC("Generic Dash") }

    /** A value of the stream beyond the regular sample: shown on «Датчики», cards and in the log as "LNK_…". */
    class Channel(val key: String, val name: String, val nameEn: String, val units: String, val format: String, val formats: Set<Format>)

    @Volatile var format: Format? = null
        private set
    /** The stream's id: Generic Dash's one id, Dash2Pro's first. */
    @Volatile var id: Int? = null
        private set
    @Volatile var lastMs = 0L
        private set

    // Generic Dash: frames by number; Dash2Pro: frames by id − base
    private val frames = arrayOfNulls<ByteArray>(16)
    private val frameMs = LongArray(16)
    // what was heard before a stream was found
    private val muxSeen = HashMap<Int, Array<ByteArray?>>()
    private val plain = HashMap<Int, ByteArray>()
    private var speedSeen = false

    fun reset() {
        frames.fill(null); frameMs.fill(0); format = null; id = null; lastMs = 0L
        muxSeen.clear(); plain.clear(); speedSeen = false
    }

    /** A frame off the bus (any id). Returns its Generic Dash frame number when it was that stream (for the monitor). */
    fun feed(canId: Int, ext: Boolean, d: ByteArray, now: Long = System.currentTimeMillis()): Int? {
        if (ext || d.size != 8) return null
        if (format == null) detect(canId, d, now)
        val base = id ?: return null
        return when (format) {
            Format.GENERIC -> {
                val idx = d[0].toInt() and 0xFF
                if (canId != base || idx > 15) return null
                frames[idx] = d.copyOf(); frameMs[idx] = now; lastMs = now
                idx
            }
            Format.DASH2PRO -> {
                val k = canId - base
                if (k in 0..3) { frames[k] = d.copyOf(); frameMs[k] = now; lastMs = now }
                null
            }
            null -> null
        }
    }

    private fun detect(canId: Int, d: ByteArray, now: Long) {
        if (plain.size < 256 || canId in plain) plain[canId] = d.copyOf()
        val idx = d[0].toInt() and 0xFF
        if (idx <= 15 && (muxSeen.size < 64 || canId in muxSeen)) muxSeen.getOrPut(canId) { arrayOfNulls(16) }[idx] = d.copyOf()
        // Dash2Pro: this id could be any of the four; try the base it would make
        for (k in 0..3) {
            val b = canId - k
            val f = (0..3).map { plain[b + it] ?: return@map null }
            if (f.all { it != null } && dash2ProSane(f.map { it!! })) {
                take(Format.DASH2PRO, b, f.map { it!! }, now); return
            }
        }
        muxSeen[canId]?.let { m ->
            if (m.count { it != null } >= 6 && m[0] != null && m[2] != null && m[3] != null && genericSane(m)) take(Format.GENERIC, canId, m.toList(), now)
        }
    }

    private fun take(f: Format, base: Int, got: List<ByteArray?>, now: Long) {
        format = f; id = base; lastMs = now
        got.forEachIndexed { i, b -> if (b != null && i < 16) { frames[i] = b; frameMs[i] = now } }
        muxSeen.clear(); plain.clear()
    }

    private fun u8(d: ByteArray, at: Int) = d[at].toInt() and 0xFF
    private fun be16(d: ByteArray, at: Int) = (u8(d, at) shl 8) or u8(d, at + 1)
    /** Generic Dash value [slot] (0–2) of a frame: low byte first, from byte 2. */
    private fun le(d: ByteArray, slot: Int) = (u8(d, 3 + slot * 2) shl 8) or u8(d, 2 + slot * 2)

    private fun dash2ProSane(f: List<ByteArray>): Boolean {
        val rpm = be16(f[0], 0); val clt = u8(f[0], 4) - 50; val volts = u8(f[0], 6) * 0.1f; val baro = be16(f[2], 6) * 0.1f
        return rpm <= 12000 && clt in -40..150 && volts in 6f..18f && baro in 50f..115f
    }

    private fun genericSane(m: Array<ByteArray?>): Boolean {
        val rpm = le(m[0]!!, 0); val ect = le(m[2]!!, 2) - 50; val volts = le(m[3]!!, 1) * 0.01f
        return rpm <= 12000 && ect in -40..150 && volts in 6f..18f
    }

    /** Frame [i] if heard within [maxAge] of [now]. */
    private fun fr(i: Int, now: Long, maxAge: Long): ByteArray? = frames[i]?.takeIf { now - frameMs[i] <= maxAge }

    fun fresh(at: Long = System.currentTimeMillis()) = id != null && at - lastMs < 2000

    private val G = setOf(Format.GENERIC)
    private val D = setOf(Format.DASH2PRO)
    private val BOTH = G + D

    val channels: List<Channel> = listOf(
        Channel("OILT", "Темп. масла", "Oil temperature", "°C", "0", BOTH),
        Channel("OILP", "Давление масла", "Oil pressure", "бар", "0.00", BOTH),
        Channel("FUELP", "Давление топлива", "Fuel pressure", "бар", "0.00", BOTH),
        Channel("LAM2", "Лямбда 2", "Lambda 2", "λ", "0.000", BOTH),
        Channel("GEAR", "Передача", "Gear", "", "0", BOTH),
        Channel("FAULTS", "Ошибки Link", "Fault codes", "", "0", BOTH),
        Channel("KNOCK", "Детонация", "Knock level", "", "0", BOTH),
        Channel("ECUT", "Темп. ЭБУ", "ECU temperature", "°C", "0", D),
        Channel("FUELCUT", "Отсечка топлива", "Fuel cut", "", "0", D),
        Channel("IGNCUT", "Срез зажигания", "Ignition cut", "%", "0", D),
        Channel("MGP", "Наддув (MGP)", "MGP", "кПа", "0", G),
        Channel("IDC", "Загрузка форсунок", "Injector duty", "%", "0.0", G),
        Channel("IDC2", "Форсунки 2-й ступени", "Injector duty 2", "%", "0.0", G),
        Channel("INJT", "Фаза впрыска", "Injection timing", "°", "0", G),
        Channel("CAMIL", "Впускной вал L", "Cam inlet L", "°", "0.0", G),
        Channel("CAMIR", "Впускной вал R", "Cam inlet R", "°", "0.0", G),
        Channel("CAMEL", "Выпускной вал L", "Cam exhaust L", "°", "0.0", G),
        Channel("CAMER", "Выпускной вал R", "Cam exhaust R", "°", "0.0", G),
        Channel("WLF", "Колесо переднее левое", "Wheel LF", "км/ч", "0", G),
        Channel("WRF", "Колесо переднее правое", "Wheel RF", "км/ч", "0", G),
        Channel("WLR", "Колесо заднее левое", "Wheel LR", "км/ч", "0", G),
        Channel("WRR", "Колесо заднее правое", "Wheel RR", "км/ч", "0", G),
        Channel("TRIGERR", "Ошибки синхронизации", "Trigger 1 errors", "", "0", G),
    ) + (1..8).map { c -> Channel("KNOCK$c", "Детонация цил. $c", "Knock level $c", "", "0", G) }

    /** The channels the stream found sends. */
    fun channelsNow(): List<Channel> = format?.let { f -> channels.filter { f in it.formats } } ?: emptyList()

    /**
     * Everything the stream says now, by key: the regular sample's values (RPM, MAP, ECT…) and the channels.
     * Frames older than [maxAgeMs] give nothing.
     */
    fun all(at: Long = System.currentTimeMillis(), maxAgeMs: Long = 2000): Map<String, Float> {
        val m = HashMap<String, Float>()
        when (format) {
            Format.DASH2PRO -> {
                fr(0, at, maxAgeMs)?.let { f ->
                    m["RPM"] = be16(f, 0).toFloat(); m["MAP"] = be16(f, 2).toFloat(); m["ECT"] = u8(f, 4) - 50f
                    m["IAT"] = u8(f, 5) - 50f; m["BATT"] = u8(f, 6) * 0.1f; m["OILT"] = u8(f, 7) - 50f
                }
                fr(1, at, maxAgeMs)?.let { f ->
                    m["TPS"] = be16(f, 0) * 0.1f; m["IGN"] = be16(f, 2) * 0.1f - 100f; m["VSS"] = u8(f, 4).toFloat()
                    m["OILP"] = u8(f, 5) * 0.1f; m["FUELP"] = u8(f, 6) * 0.1f; m["ECUT"] = u8(f, 7) - 50f
                }
                fr(2, at, maxAgeMs)?.let { f ->
                    m["LAM1"] = be16(f, 0) * 0.001f; m["LAM2"] = be16(f, 2) * 0.001f; m["BARO"] = be16(f, 6) * 0.1f
                }
                fr(3, at, maxAgeMs)?.let { f ->
                    m["GEAR"] = u8(f, 0).toFloat(); m["FUELCUT"] = u8(f, 1).toFloat(); m["IGNCUT"] = u8(f, 2).toFloat()
                    m["INJPW"] = be16(f, 3) * 0.001f; m["FAULTS"] = u8(f, 5).toFloat(); m["KNOCK"] = be16(f, 6).toFloat()
                }
            }
            Format.GENERIC -> {
                fun v(frame: Int, slot: Int) = fr(frame, at, maxAgeMs)?.let { le(it, slot) }
                fun put(k: String, x: Float?) { if (x != null) m[k] = x }
                put("RPM", v(0, 0)?.toFloat()); put("MAP", v(0, 1)?.toFloat()); put("MGP", v(0, 2)?.let { it - 100f })
                put("BARO", v(1, 0)?.let { it * 0.1f }); put("TPS", v(1, 1)?.let { it * 0.1f }); put("IDC", v(1, 2)?.let { it * 0.1f })
                put("IDC2", v(2, 0)?.let { it * 0.1f }); put("INJPW", v(2, 1)?.let { it * 0.001f }); put("ECT", v(2, 2)?.let { it - 50f })
                put("IAT", v(3, 0)?.let { it - 50f }); put("BATT", v(3, 1)?.let { it * 0.01f }); put("MAF", v(3, 2)?.let { it * 0.1f })
                put("GEAR", v(4, 0)?.toFloat()); put("INJT", v(4, 1)?.toFloat()); put("IGN", v(4, 2)?.let { it * 0.1f - 100f })
                put("CAMIL", v(5, 0)?.let { it * 0.1f }); put("CAMIR", v(5, 1)?.let { it * 0.1f })
                put("CAMEL", v(5, 2)?.let { it * -0.1f }); put("CAMER", v(6, 0)?.let { it * -0.1f })
                put("LAM1", v(6, 1)?.let { it * 0.001f }); put("LAM2", v(6, 2)?.let { it * 0.001f })
                put("TRIGERR", v(7, 0)?.toFloat()); put("FAULTS", v(7, 1)?.toFloat()); put("FUELP", v(7, 2)?.let { it / 100f })
                put("OILT", v(8, 0)?.let { it - 50f }); put("OILP", v(8, 1)?.let { it / 100f })
                put("WLF", v(8, 2)?.let { it * 0.1f }); put("WLR", v(9, 0)?.let { it * 0.1f })
                put("WRF", v(9, 1)?.let { it * 0.1f }); put("WRR", v(9, 2)?.let { it * 0.1f })
                for (c in 1..8) put("KNOCK$c", v(10 + (c - 1) / 3, (c - 1) % 3)?.let { it * 5f })
                (1..8).mapNotNull { m["KNOCK$it"] }.maxOrNull()?.let { m["KNOCK"] = it }
                put("LIMITS", v(12, 2)?.toFloat())
                val wheels = listOfNotNull(m["WLF"], m["WRF"])
                if (wheels.isNotEmpty()) m["VSS"] = wheels.average().toFloat()
            }
            null -> {}
        }
        return m
    }

    /** The channels' values by key (for «Датчики», cards and the log). */
    fun values(at: Long = System.currentTimeMillis(), maxAgeMs: Long = 2000): Map<String, Float> {
        val keys = channelsNow().map { it.key }.toSet()
        return all(at, maxAgeMs).filterKeys { it in keys }
    }

    /** The regular sample: what the gauges, the fuel count and the guard read. */
    fun snapshot(at: Long = System.currentTimeMillis()): EcuSnapshot {
        val m = all(at)
        val rpm = m["RPM"]?.toInt()?.takeIf { it in 0..15000 }
        val vss = m["VSS"]
        if (vss != null && vss > 1f) speedSeen = true
        return EcuSnapshot(
            timeMs = at,
            rpm = rpm,
            mapKpa = m["MAP"],
            atmKpa = m["BARO"]?.takeIf { it in 50f..115f },
            throttlePct = m["TPS"],
            injPwMs = m["INJPW"],
            coolantC = m["ECT"]?.toInt(),
            intakeC = m["IAT"]?.toInt(),
            batteryV = m["BATT"],
            // a speed-density Link sends 0 g/s: no MAF, not no air
            mafGs = m["MAF"]?.takeIf { it > 0f || (rpm ?: 0) < 300 },
            timingDeg = m["IGN"],
            lambda = m["LAM1"]?.takeIf { it in 0.5f..1.6f },
            // a speed only once the Link has shown it gets one: zeros from an unwired input are no standstill
            speedKmh = if (speedSeen) vss?.toInt() else null,
        )
    }

    /** Limiters active now (Generic Dash frame 12): rev, MAP, launch… for the trace. */
    fun limits(at: Long = System.currentTimeMillis()): List<String> {
        val f = all(at)["LIMITS"]?.toInt() ?: return emptyList()
        val names = listOf("RPM limit", "MAP limit", "Speed limit", "Max ignition", "Anti-lag cut", "High supply voltage", "Overrun",
            "Traction", "Low supply voltage", "Launch RPM limit", "Wakeup", "GP RPM limit 1", "CL stepper limit", "GP RPM limit 2",
            "E-throttle limit", "Cyclic idle")
        return names.filterIndexed { i, _ -> (f shr i) and 1 == 1 }
    }
}
