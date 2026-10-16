package kg.forester.sg

import kg.forester.sg.service.Alerts
import kg.forester.sg.service.Analyzer
import kg.forester.sg.service.IdleRef
import kg.forester.sg.service.IdleSample
import kg.forester.sg.service.MemoryRuleStore
import kg.forester.sg.service.RuleInput
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.EngineInfo
import kg.forester.sg.ssm.TcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private class Run(val store: MemoryRuleStore = MemoryRuleStore()) {
        val said = ArrayList<Triple<String, Alerts.Level, String>>()
        var idle: IdleSample? = null
        val a = Analyzer(store, { id, text, level, _ -> said += Triple(id, level, text) }) { idle = it }
        var now = 1_000_000_000L

        /** [seconds] of samples every half second. */
        fun feed(seconds: Int, input: (Long) -> RuleInput) {
            repeat(seconds * 2) { now += 500; a.process(input(now)) }
        }

        fun ids() = said.map { it.first }.toSet()
        fun level(id: String) = said.last { it.first == id }.second
    }

    /** A healthy warm car cruising: 2500 rpm, light throttle, trims near zero. */
    private fun cruise(t: Long, c: Int = 90) = EcuSnapshot(
        timeMs = t, coolantC = c, rpm = 2500, speedKmh = 80, timingDeg = 35f, intakeC = 25, mafGs = 18f, throttlePct = 15f,
        batteryV = 14.1f, injPwMs = 3.2f, atmKpa = 95f, mapKpa = 55f, afCorrPct = if ((t / 500) % 2 == 0L) 1.5f else -1.2f,
        afLearnPct = 2.3f, lambda = 1f,
    )

    private fun warmUp(r: Run, turbo: Boolean = false) = r.feed(6 * 60) { RuleInput(cruise(it), turbo = turbo, iam = 1f) }

    @Test fun healthyCarStaysQuiet() {
        val r = Run()
        r.feed(20 * 60) { RuleInput(cruise(it), iam = 1f, fbkc = 0f, flkc = 0f, oilC = 90f) }
        assertEquals(emptySet<String>(), r.ids())
    }

    @Test fun warmIsByTheCoolantAt80NotTheOilEstimate() {
        // revving a 75° engine: still cold by the ECU's own warm-up enrichment
        val cold = Run()
        cold.feed(30) { RuleInput(cruise(it, c = 75).copy(rpm = 4000), oilC = 70f) }
        assertTrue(cold.ids().contains("cold"))
        // 82° coolant with the oil estimate still behind: warm, no warning
        val warm = Run()
        warm.feed(30) { RuleInput(cruise(it, c = 82).copy(rpm = 4000), oilC = 55f) }
        assertFalse(warm.ids().contains("cold"))
    }

    @Test fun lowIamAfterEcuResetIsNotADanger() {
        val r = Run(MemoryRuleStore().apply { lastIam = 1f })
        r.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.5f) }
        assertTrue(r.ids().contains("iamRelearn"))
        assertFalse(r.ids().contains("iam"))
        assertTrue(r.store.iamResetMs != 0L)
    }

    @Test fun lowIamWithoutResetIsADangerOnATurbo() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.72f })
        r.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.7f) }
        assertTrue(r.ids().contains("iam"))
        assertEquals(Alerts.Level.DANGER, r.level("iam"))
    }

    @Test fun flkcLadderAndLowLoadNoise() {
        val r = Run()
        warmUp(r)
        // light load at low revs: the sensor's taps on a healthy engine, nothing to say
        r.feed(30) { RuleInput(cruise(it).copy(rpm = 2000, mafGs = 15f), flkc = -3.0f, fbkc = -4.5f) }
        assertEquals(emptySet<String>(), r.ids())
        // −4° under real load: the ECU is about to reset IAM
        r.feed(10) { RuleInput(cruise(it).copy(rpm = 3500, mafGs = 70f, throttlePct = 70f), flkc = -4.2f) }
        assertEquals(Alerts.Level.DANGER, r.level("flkc"))
    }

    @Test fun fbkcRightAfterAThrottleStabIsIgnored() {
        val r = Run()
        warmUp(r, turbo = true)
        var i = 0
        // the throttle jumps every second: always transient
        r.feed(20) { RuleInput(cruise(it).copy(rpm = 4000, mafGs = 90f, mapKpa = 160f, throttlePct = if (i++ % 2 == 0) 90f else 30f), turbo = true, fbkc = -4.5f) }
        assertFalse(r.ids().contains("fbkc"))
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 4000, mafGs = 90f, mapKpa = 160f, throttlePct = 90f), turbo = true, fbkc = -4.5f) }
        assertTrue(r.ids().contains("fbkc"))
    }

    @Test fun leanTrimsInClosedLoop() {
        val r = Run()
        warmUp(r)
        r.feed(150) { RuleInput(cruise(it).copy(afCorrPct = if ((it / 500) % 2 == 0L) 9f else 11f, afLearnPct = 8f)) }
        assertTrue(r.ids().contains("lean"))
        assertEquals(Alerts.Level.WARN, r.level("lean"))
    }

    @Test fun fuelCutAfterHighBoost() {
        val r = Run()
        warmUp(r, turbo = true)
        // the injectors off at full throttle without boost before it: not an overboost cut
        r.feed(2) { RuleInput(cruise(it).copy(rpm = 4500, mafGs = 60f, mapKpa = 95f, throttlePct = 100f, injPwMs = 0.2f, lambda = 0.8f), turbo = true) }
        assertFalse(r.ids().contains("fuelCut"))
        r.feed(2) { RuleInput(cruise(it).copy(rpm = 4500, mafGs = 120f, mapKpa = 190f, throttlePct = 100f, injPwMs = 9f, lambda = 0.8f), turbo = true) }
        // the cut: the boost falls away as the fuel stops
        r.feed(1) { RuleInput(cruise(it).copy(rpm = 4600, mafGs = 80f, mapKpa = 120f, throttlePct = 100f, injPwMs = 0.2f, lambda = 0.8f), turbo = true) }
        assertTrue(r.ids().contains("fuelCut"))
        // at the rev limiter the ECU cuts fuel on purpose
        val r2 = Run()
        warmUp(r2, turbo = true)
        r2.feed(2) { RuleInput(cruise(it).copy(rpm = 6300, mafGs = 120f, mapKpa = 190f, throttlePct = 100f, injPwMs = 9f, lambda = 0.8f), turbo = true) }
        r2.feed(1) { RuleInput(cruise(it).copy(rpm = 6500, mafGs = 120f, mapKpa = 190f, throttlePct = 100f, injPwMs = 0.2f, lambda = 0.8f), turbo = true) }
        assertFalse(r2.ids().contains("fuelCut"))
    }

    @Test fun trafficThenANightThenTheMorning() {
        val r = Run()
        warmUp(r)
        // a summer jam: 101° standing for two minutes, then the engine off
        r.feed(120) { RuleInput(cruise(it, c = 101).copy(speedKmh = 0, throttlePct = 0f, rpm = 750, mafGs = 3f)) }
        r.feed(5) { RuleInput(cruise(it, c = 101).copy(speedKmh = 0, rpm = 0)) }
        // the next morning: cold start, off we go
        r.now += 10 * 3600_000L
        r.feed(4 * 60) { RuleInput(cruise(it, c = 40)) }
        assertFalse(r.ids().contains("fans"))
    }

    @Test fun hotRestartIsNotOverheating() {
        val r = Run()
        warmUp(r)
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 0, speedKmh = 0)) }
        r.now += 10 * 60_000L // ten minutes at the shop
        // heat soak: the sensor reads 106° for the first minute after the restart
        r.feed(60) { RuleInput(cruise(it, c = 106).copy(speedKmh = 0, throttlePct = 0f, rpm = 800)) }
        assertFalse(r.ids().contains("hot"))
        assertFalse(r.ids().contains("warm102"))
    }

    @Test fun highAltitudeIdleIsFine() {
        val r = Run()
        warmUp(r)
        // a pass at 2500 m: 75 kPa outside, the idle manifold as everywhere
        r.feed(90) { RuleInput(cruise(it).copy(rpm = 720, speedKmh = 0, throttlePct = 0f, mafGs = 2.6f, atmKpa = 75f, mapKpa = 34f)) }
        assertFalse(r.ids().contains("idleMap"))
        assertFalse(r.ids().contains("atm"))
    }

    @Test fun tunedIdleOnALinkIsNoFault() {
        // cams and a raised idle target: 1150 rpm at 50 kPa
        val idle = { r: Run, sport: Boolean -> r.feed(120) { RuleInput(cruise(it).copy(rpm = 1150, speedKmh = 0, throttlePct = 0f, mafGs = 4f, mapKpa = 50f), sportEcu = sport) } }
        val stock = Run(); warmUp(stock); idle(stock, false)
        assertTrue(stock.ids().contains("idleMap"))
        assertTrue(stock.ids().contains("idleHigh"))
        val link = Run(); warmUp(link); idle(link, true)
        assertFalse(link.ids().contains("idleMap"))
        assertFalse(link.ids().contains("idleHigh"))
    }

    @Test fun iamRecoveringAfterBadFuelIsNotAReset() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.81f })
        var iam = 0.81f
        r.feed(10 * 60) { iam = minOf(0.875f, iam + 0.0002f); RuleInput(cruise(it), iam = iam) }
        assertEquals(0L, r.store.iamResetMs)
    }

    @Test fun transmissionHeldInThirdIsTheFailsafe() {
        val r = Run()
        var v = 0
        r.feed(60) { RuleInput(cruise(it).copy(speedKmh = minOf(90, v++)), TcuSnapshot(timeMs = it, atfC = 60, gear = 3, range = "D")) }
        assertTrue(r.ids().contains("limp"))
    }

    @Test fun lockUpSlipByTheFactoryCriterion() {
        val r = Run()
        // 50 rpm apart at 90 km/h is within 40 + 90/2: fine
        r.feed(15) { RuleInput(cruise(it).copy(speedKmh = 90), TcuSnapshot(timeMs = it, atfC = 70, gear = 4, lockUpPct = 95f, turbineRpm = 2450, range = "D")) }
        assertFalse(r.ids().contains("lockSlipF"))
        r.feed(15) { RuleInput(cruise(it).copy(speedKmh = 90), TcuSnapshot(timeMs = it, atfC = 70, gear = 4, lockUpPct = 95f, turbineRpm = 2380, range = "D")) }
        assertTrue(r.ids().contains("lockSlipF"))
    }

    @Test fun sensorsDisagreeAfterALongPark() {
        val r = Run(MemoryRuleStore().apply { lastRunMs = 1_000_000_000L - 10 * 3600_000L })
        r.feed(1) { RuleInput(EcuSnapshot(timeMs = it, coolantC = 4, intakeC = 19, rpm = 0)) }
        assertTrue(r.ids().contains("tempPlaus"))
        // a block heater warmed the coolant overnight: that is not a faulty sensor
        val h = Run(MemoryRuleStore().apply { lastRunMs = 1_000_000_000L - 10 * 3600_000L })
        h.feed(1) { RuleInput(EcuSnapshot(timeMs = it, coolantC = 25, intakeC = -15, rpm = 0)) }
        assertFalse(h.ids().contains("tempPlaus"))
    }

    @Test fun warmIdleIsSampledAndJudged() {
        val r = Run()
        warmUp(r)
        r.feed(70) {
            RuleInput(cruise(it).copy(rpm = 920, speedKmh = 0, throttlePct = 0f, mafGs = 3.0f, injPwMs = 1.9f, timingDeg = 12f, mapKpa = 33f, afCorrPct = 1f, afLearnPct = 3f))
        }
        val sample = r.idle
        assertNotNull(sample)
        assertEquals(920f, sample!!.values["rpm"]!!, 1f)
        val table = IdleRef.forEngine(EngineInfo("EJ20", true, "EJ205", "настройки"))!!
        val lines = IdleRef.judge(table, sample.values).associateBy { it.row.key }
        assertEquals(IdleRef.Verdict.HIGH, lines["rpm"]!!.verdict)
        assertEquals(IdleRef.Verdict.OK, lines["maf"]!!.verdict)
        assertEquals(IdleRef.Verdict.OK, lines["map"]!!.verdict)
    }

    @Test fun oneAtfMisreadIsNotASensorFault() {
        val r = Run()
        var n = 0
        // one sample of nonsense in the middle of a normal 70°
        r.feed(60) { RuleInput(cruise(it), TcuSnapshot(timeMs = it, atfC = if (n++ == 40) 15 else 70, gear = 4, range = "D")) }
        assertFalse(r.ids().contains("atfSensor"))
        // the reading keeps leaping: that is the sensor or its wiring
        r.feed(60) { RuleInput(cruise(it), TcuSnapshot(timeMs = it, atfC = if (n++ % 8 == 0) 15 else 70, gear = 4, range = "D")) }
        assertTrue(r.ids().contains("atfSensor"))
    }

    @Test fun factoryRangesAndJustOutside() {
        assertEquals(29.5f to 37f, IdleRef.factory("29,5–37 кПа"))
        assertEquals(-10f to 10f, IdleRef.factory("−10…+10 %"))
        assertEquals(-5f to 5f, IdleRef.factory("±5°"))
        assertEquals(null, IdleRef.factory("около 700"))
        val table = IdleRef.forEngine(EngineInfo("EJ20", true, "EJ205", "ЭБУ"))!!
        val l = IdleRef.judge(table, mapOf("inj" to 2.3f, "rpm" to 700f, "avcs" to 0.5f, "map" to 45f)).associateBy { it.row.key }
        assertEquals(IdleRef.Verdict.NEAR, l["inj"]!!.verdict)   // 2.3 against 1.2–2.2: just outside, healthy cars do it
        assertEquals(IdleRef.Verdict.OK, l["rpm"]!!.verdict)
        assertEquals(IdleRef.Verdict.OK, l["avcs"]!!.verdict)
        assertEquals(IdleRef.Verdict.HIGH, l["map"]!!.verdict)
    }

    @Test fun idleHistoryKeepsOnePerHalfHour() {
        val h = kg.forester.sg.service.RulesHost
        var list = emptyList<IdleSample>()
        list = h.addToHistory(list, IdleSample(0L, mapOf("rpm" to 700f)))
        list = h.addToHistory(list, IdleSample(60_000L, mapOf("rpm" to 710f)))       // the same stop: replaces
        list = h.addToHistory(list, IdleSample(3600_000L, mapOf("rpm" to 720f)))     // another day: added
        assertEquals(listOf(710f, 720f), list.map { it.values["rpm"] })
        repeat(50) { list = h.addToHistory(list, IdleSample(7200_000L + it * 3600_000L, mapOf("rpm" to 700f))) }
        assertEquals(40, list.size)
    }
}
