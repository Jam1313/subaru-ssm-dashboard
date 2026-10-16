package kg.forester.sg.ssm

/**
 * «Демо Link» (adb only: am start … --ez linkdemo true): what a Link G4X on a CANable would send, made from the demo
 * drive — the Dash2Pro stream on 1000–1003 plus an EGT on 1004, the way a tuner adds one to a user stream. It goes
 * through the same decoding as a real adapter, so the screens show exactly what a Link car would.
 */
class LinkDemo : CanSource {
    override val errors = 0
    private var lastMs = 0L

    override fun read(timeoutMs: Int): List<Slcan.Frame> {
        Thread.sleep(50)
        val now = System.currentTimeMillis()
        if (now - lastMs < 50) return emptyList()
        lastMs = now
        val e = kg.forester.sg.service.Demo.ecu(now).first
        val rpm = e.rpm ?: 0
        val load = ((e.throttlePct ?: 0f) / 87f).coerceIn(0f, 1f)
        fun b(vararg v: Int) = ByteArray(8) { (v.getOrElse(it) { 0 } and 0xFF).toByte() }
        fun hi(x: Int) = (x shr 8) and 0xFF
        fun lo(x: Int) = x and 0xFF
        val map = (e.mapKpa ?: 100f).toInt()
        val tps = ((e.throttlePct ?: 0f) * 10).toInt()
        val ign = (((e.timingDeg ?: 10f) + 100f) * 10).toInt()
        val lam = ((e.lambda ?: 1f) * 1000).toInt()
        val pw = ((e.injPwMs ?: 2f) * 1000).toInt()
        val oilP = (12 + rpm / 150).coerceAtMost(60) // ×0.1 bar
        val fuelP = 30 + ((map - 100).coerceAtLeast(0) / 10) // ×0.1 bar: the regulator follows the boost
        val gear = when { (e.speedKmh ?: 0) < 1 -> 0; (e.speedKmh ?: 0) < 22 -> 1; (e.speedKmh ?: 0) < 45 -> 2; (e.speedKmh ?: 0) < 75 -> 3; (e.speedKmh ?: 0) < 105 -> 4; else -> 5 }
        val egt = (380 + load * 480 + rpm / 40).toInt()
        return listOf(
            Slcan.Frame(1000, false, b(hi(rpm), lo(rpm), hi(map), lo(map), (e.coolantC ?: 85) + 50, (e.intakeC ?: 30) + 50,
                ((e.batteryV ?: 14f) * 10).toInt(), (e.coolantC ?: 85) + 58)),
            Slcan.Frame(1001, false, b(hi(tps), lo(tps), hi(ign), lo(ign), e.speedKmh ?: 0, oilP, fuelP, 92)),
            Slcan.Frame(1002, false, b(hi(lam), lo(lam), hi(lam + 12), lo(lam + 12), 0, 0, hi(1000), lo(1000))),
            Slcan.Frame(1003, false, b(gear, if (load < 0.03f && rpm > 1500) 1 else 0, 0, hi(pw), lo(pw), 0, 0, if (load > 0.9f) 18 else 3)),
            Slcan.Frame(1004, false, b(hi(egt), lo(egt))),
        )
    }

    override fun close() {}
}
