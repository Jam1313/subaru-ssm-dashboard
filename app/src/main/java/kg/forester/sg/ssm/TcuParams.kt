package kg.forester.sg.ssm

/** Transmission (4EAT TCU, SSM2 address 0x18) live data. */
data class TcuSnapshot(
    val timeMs: Long = 0,
    val atfC: Int? = null,
    val gear: Int? = null,
    val turbineRpm: Int? = null,
    val linePressurePct: Float? = null,
    val lockUpPct: Float? = null,
    val transferPct: Float? = null,
    val frontWheelKmh: Int? = null,
    val rearWheelKmh: Int? = null,
    val brakeClutchPct: Float? = null,
    /** Selector position: "P/N", "R", "D", "3", "2", "1". */
    val range: String? = null,
    /** Fuse in the FWD holder (RomRaider S83): the TCU keeps the rear axle off and the FWD lamp is lit. */
    val fwdFuse: Boolean? = null,
    /** AT OIL TEMP lamp on (RomRaider S106) and the TCU's diagnosis lamp output (S104). */
    val atfLamp: Boolean? = null,
    val diagLamp: Boolean? = null,
    /** Engine vs turbine speed, % (needs the engine rpm, filled in by the service). */
    val slipPct: Float? = null,
    val lock: LockState = LockState.UNKNOWN,
)

/**
 * Torque converter lock-up: what the TCU commands (duty) checked against what happens (slip). [SLIPPING] = the
 * TCU has been asking for lock-up for a few seconds and the clutch still slips: worn clutch or tired ATF.
 */
enum class LockState(val text: String) {
    UNKNOWN("нет данных"), OPEN("разблокирован"), ENGAGING("блокируется"), LOCKED("заблокирован"), SLIPPING("не держит");

    companion object {
        /** Duty under 10 % = open; over 60 % with under ~3 % slip = locked; in between = engaging. */
        fun of(duty: Float?, slip: Float?, slippingForMs: Long): LockState = when {
            duty == null -> UNKNOWN
            duty < 10f -> OPEN
            duty > 60f && slip != null && slip > 10f && slippingForMs >= 3000 -> SLIPPING
            duty > 60f && (slip == null || slip < 3f) -> LOCKED
            else -> ENGAGING
        }
    }
}

object TcuParams {
    const val FRONT_SPEED = 0x48
    const val ATF_OLD = 0x49
    const val GEAR = 0x4A
    const val LINE_PRESSURE = 0x4B
    const val LOCK_UP = 0x4C
    const val TRANSFER = 0x4D
    const val TURBINE = 0x4F
    const val BRAKE_CLUTCH = 0x50
    const val REAR_SPEED = 0x51
    const val ATF_NEW = 0x56
    const val RANGE = 0x6B
    /** Switch byte with the FWD fuse input in bit 0 (RomRaider S83, init byte 28 bit 0). */
    const val FWD = 0x6A
    /** Lamp outputs: bit 1 AT OIL TEMP (RomRaider S106), bit 3 diagnosis lamp (S104); init byte 31. */
    const val LAMPS = 0x6D

    /** (address, ecubyteindex, ecubit) from RomRaider/FreeSSM. */
    private val all = listOf(
        Triple(FRONT_SPEED, 16, 7), Triple(ATF_OLD, 16, 6), Triple(GEAR, 16, 5), Triple(LINE_PRESSURE, 16, 4),
        Triple(LOCK_UP, 16, 3), Triple(TRANSFER, 16, 2), Triple(TURBINE, 16, 0), Triple(BRAKE_CLUTCH, 17, 7),
        Triple(REAR_SPEED, 17, 6), Triple(ATF_NEW, 17, 1),
    )

    /** Addresses this TCU reports as supported (all of them if the capability bytes are too short). */
    fun supported(init: EcuInit): IntArray {
        val known = all.filter { it.second - 8 < init.capabilities.size }
        if (known.isEmpty()) return (all.map { it.first } + RANGE).toIntArray()
        // Range switch byte isn't in the capability table; verified on this car (bit1 R, bit2 D, bit3 3, bit4 2, bit5 1).
        // the FWD switch only where the init lists it: a unit without it may refuse the whole request
        val fwd = if (init.supports(28, 0)) listOf(FWD) else emptyList()
        val lamps = if (init.supports(31, 1) || init.supports(31, 3)) listOf(LAMPS) else emptyList()
        return (known.filter { init.supports(it.second, it.third) }.map { it.first } + RANGE + fwd + lamps).toIntArray()
    }

    fun decode(addrs: IntArray, raw: ByteArray, now: Long): TcuSnapshot {
        val v = HashMap<Int, Int>()
        addrs.forEachIndexed { i, a -> v[a] = raw[i].toInt() and 0xFF }
        val atf = v[ATF_NEW]?.let { it - 50 } ?: v[ATF_OLD]?.let { ATF_TABLE[it] }
        return TcuSnapshot(
            timeMs = now,
            atfC = atf,
            gear = v[GEAR]?.let { it + 1 },
            turbineRpm = v[TURBINE]?.let { it * 32 },
            linePressurePct = v[LINE_PRESSURE]?.let { it / 2f },
            lockUpPct = v[LOCK_UP]?.let { it / 2f },
            transferPct = v[TRANSFER]?.let { it / 2f },
            frontWheelKmh = v[FRONT_SPEED],
            rearWheelKmh = v[REAR_SPEED],
            brakeClutchPct = v[BRAKE_CLUTCH]?.let { it / 2f },
            fwdFuse = v[FWD]?.let { it and 1 == 1 },
            atfLamp = v[LAMPS]?.let { it shr 1 and 1 == 1 },
            diagLamp = v[LAMPS]?.let { it shr 3 and 1 == 1 },
            range = v[RANGE]?.let {
                when {
                    it and 0x02 != 0 -> "R"
                    it and 0x04 != 0 -> "D"
                    it and 0x08 != 0 -> "3"
                    it and 0x10 != 0 -> "2"
                    it and 0x20 != 0 -> "1"
                    else -> "P/N"
                }
            },
        )
    }

    /** FreeSSM 4EAT ATF temperature table for address 0x49 (raw -> °C). */
    private val ATF_TABLE = intArrayOf(
        -60, -60, -51, -45, -40, -37, -34, -31, -29, -27, -25, -23, -22, -20, -19, -17,
        -16, -15, -14, -13, -12, -10, -9, -9, -8, -7, -6, -5, -4, -3, -2, -2,
        -1, 0, 1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8, 8, 9,
        10, 10, 11, 11, 12, 12, 13, 13, 14, 15, 15, 16, 16, 17, 17, 18,
        18, 19, 19, 20, 20, 21, 21, 22, 22, 23, 23, 23, 24, 24, 25, 25,
        26, 26, 27, 27, 28, 28, 29, 29, 29, 30, 30, 31, 31, 32, 32, 33,
        33, 33, 34, 34, 35, 35, 36, 36, 37, 37, 37, 38, 38, 39, 39, 40,
        40, 41, 41, 41, 42, 42, 43, 43, 44, 44, 45, 45, 45, 46, 46, 47,
        47, 48, 48, 49, 49, 50, 50, 50, 51, 51, 52, 52, 53, 53, 54, 54,
        55, 55, 56, 56, 57, 57, 58, 58, 59, 59, 60, 60, 61, 61, 62, 62,
        63, 63, 64, 65, 65, 66, 66, 67, 67, 68, 68, 69, 70, 70, 71, 71,
        72, 73, 73, 74, 75, 75, 76, 77, 77, 78, 79, 79, 80, 81, 81, 82,
        83, 84, 84, 85, 86, 87, 88, 88, 89, 90, 91, 92, 93, 94, 95, 96,
        97, 98, 99, 100, 101, 102, 103, 104, 105, 107, 108, 109, 110, 112, 113, 115,
        116, 118, 119, 121, 123, 125, 127, 129, 131, 133, 135, 138, 140, 143, 146, 149,
        152, 156, 159, 164, 168, 173, 179, 186, 193, 202, 212, 225, 241, 264, 300, 373,
    )
}
