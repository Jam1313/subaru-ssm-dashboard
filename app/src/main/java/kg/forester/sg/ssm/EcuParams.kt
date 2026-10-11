package kg.forester.sg.ssm

/** One decoded snapshot of the engine state. Null = not available. */
data class EcuSnapshot(
    val timeMs: Long = 0,
    val coolantC: Int? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val timingDeg: Float? = null,
    val intakeC: Int? = null,
    val mafGs: Float? = null,
    val throttlePct: Float? = null,
    val batteryV: Float? = null,
    val injPwMs: Float? = null,
    val knockDeg: Float? = null,
    val atmKpa: Float? = null,
    val boostKpa: Float? = null,
    val afCorrPct: Float? = null,
    val afLearnPct: Float? = null,
    val lambda: Float? = null,
    val wastegatePct: Float? = null,
    val pedalPct: Float? = null,
    val mapKpa: Float? = null,
    val avcsDeg: Float? = null,
    val altDutyPct: Float? = null,
    val throttleMotorPct: Float? = null,
) {
    val afr: Float? get() = lambda?.let { it * 14.7f }

    /** Gauge pressure = manifold absolute − atmospheric, kPa (negative = vacuum). */
    val gaugeKpa: Float?
        get() = if (mapKpa != null && atmKpa != null) mapKpa - atmKpa else boostKpa

    /** Boost above atmosphere in bar, never negative. */
    val boostBar: Float? get() = gaugeKpa?.let { maxOf(0f, it) / 100f }

    /** Manifold vacuum in kPa (0 when under boost). */
    val vacuumKpa: Float? get() = gaugeKpa?.let { maxOf(0f, -it) }

    /** Engine load, grams of air per revolution. */
    val loadGRev: Float?
        get() = if (mafGs != null && rpm != null && rpm > 300) mafGs * 60f / rpm else null

    /** Injector duty cycle (batch: one injection per 2 revolutions). */
    val injDutyPct: Float?
        get() = if (injPwMs != null && rpm != null) injPwMs * rpm / 1200f else null
}

/**
 * Standard SSM2 single-byte addresses (RomRaider logger "STD" set) polled in one request.
 * Order matters: values come back in the same order.
 */
object EcuParams {
    const val COOLANT = 0x08
    const val RPM_H = 0x0E
    const val RPM_L = 0x0F
    const val SPEED = 0x10
    const val TIMING = 0x11
    const val INTAKE = 0x12
    const val MAF_H = 0x13
    const val MAF_L = 0x14
    const val THROTTLE = 0x15
    const val BATTERY = 0x1C
    const val INJ_PW = 0x20
    const val KNOCK = 0x22
    const val ATM = 0x23
    const val BOOST = 0x24
    const val AF_CORR = 0x09
    const val AF_LEARN = 0x0A
    const val AF_SENSOR = 0x46
    const val WGDC = 0x30
    const val PEDAL = 0x29
    const val MAP = 0x0D
    const val AVCS = 0x3C
    const val ALT_DUTY = 0x3A
    const val THROTTLE_MOTOR = 0xFA

    val pollAddresses = intArrayOf(
        COOLANT, RPM_H, RPM_L, SPEED, TIMING, INTAKE, MAF_H, MAF_L, THROTTLE, BATTERY,
        INJ_PW, KNOCK, ATM, BOOST, AF_CORR, AF_LEARN, AF_SENSOR, WGDC,
        PEDAL, MAP, AVCS, ALT_DUTY, THROTTLE_MOTOR,
    )

    fun decode(raw: ByteArray, now: Long): EcuSnapshot {
        val v = HashMap<Int, Int>(pollAddresses.size)
        pollAddresses.forEachIndexed { i, a -> v[a] = raw[i].toInt() and 0xFF }
        fun g(a: Int) = v.getValue(a)
        val lambdaRaw = g(AF_SENSOR)
        return EcuSnapshot(
            timeMs = now,
            coolantC = g(COOLANT) - 40,
            rpm = ((g(RPM_H) shl 8) or g(RPM_L)) / 4,
            speedKmh = g(SPEED),
            timingDeg = (g(TIMING) - 128) / 2f,
            intakeC = g(INTAKE) - 40,
            mafGs = ((g(MAF_H) shl 8) or g(MAF_L)) / 100f,
            throttlePct = g(THROTTLE) * 100f / 255f,
            batteryV = g(BATTERY) * 0.08f,
            injPwMs = g(INJ_PW) * 0.256f,
            knockDeg = (g(KNOCK) - 128) / 2f,
            atmKpa = g(ATM).toFloat(),
            boostKpa = (g(BOOST) - 128).toFloat(),
            afCorrPct = (g(AF_CORR) - 128) * 100f / 128f,
            afLearnPct = (g(AF_LEARN) - 128) * 100f / 128f,
            lambda = if (lambdaRaw == 0) null else lambdaRaw / 128f,
            wastegatePct = g(WGDC) * 100f / 255f,
            pedalPct = g(PEDAL) * 100f / 255f,
            mapKpa = g(MAP).toFloat(),
            avcsDeg = (g(AVCS) - 50).toFloat(),
            altDutyPct = g(ALT_DUTY).toFloat(),
            throttleMotorPct = (g(THROTTLE_MOTOR) - 128) * 100f / 128f,
        )
    }
}
