package kg.forester.sg

import kg.forester.sg.service.EcuPt
import kg.forester.sg.service.FuelCalc
import kg.forester.sg.service.GuardState
import kg.forester.sg.service.Interval
import kg.forester.sg.service.LogColumns
import kg.forester.sg.service.LogRow
import kg.forester.sg.service.PerfCalc
import kg.forester.sg.service.RunTrace
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.floor

/** The plain polling (4 samples a second) and the fast poll (~12) must give the same numbers. */
class RateParityTest {
    /** A pull from a standstill at t = 1 s that fades with speed, as a real car's does: v = 230 (1 − e^(−t/14)). */
    private fun kmh(t: Double) = if (t < 1) 0.0 else 230.0 * (1 - exp(-(t - 1) / 14.0))

    /** When [kmh] reaches [v], analytically. */
    private fun at(v: Double) = 1 - 14.0 * kotlin.math.ln(1 - v / 230.0)

    /** ECU samples [dtMs] apart, speed in whole km/h as the ECU sends it. */
    private fun trace(dtMs: Int) = RunTrace(0L, 1f, (0..(40_000 / dtMs)).map { i ->
        val t = i * dtMs / 1000.0
        EcuPt(i * dtMs, floor(kmh(t)).toFloat(), pedal = 100f)
    }, emptyList())

    @Test fun intervalsTheSameAtFourAndTwelveSamplesASecond() {
        val iv = Interval.list("0-100,100-200")
        val slow = PerfCalc.measure(trace(250), iv).results()
        val fast = PerfCalc.measure(trace(83), iv).results()
        val want0100 = at(100.0) - 1
        val want100200 = at(200.0) - at(100.0)
        assertEquals(want0100, fast["0-100"]!!.toDouble(), 0.1)
        assertEquals(want100200, fast["100-200"]!!.toDouble(), 0.1)
        assertEquals(fast["0-100"]!!, slow["0-100"]!!, 0.1f)
        // above 150 km/h a car gains a km/h in ~0.5 s: whole km/h at 4 samples a second can't place the crossing closer
        // than a sample (0.25 s); the fast poll's 83 ms (or a GPS of 10 Hz) can
        assertEquals(fast["100-200"]!!, slow["100-200"]!!, 0.3f)
    }

    /** A drive with a jam, a pull and engine braking: litres by time come out the same at either rate. */
    @Test fun fuelOfADriveTheSameAtBothRates() {
        fun snap(t: Double): EcuSnapshot = when {
            t < 600 -> EcuSnapshot(rpm = 750, mafGs = 3.2f, speedKmh = if ((t / 20).toInt() % 2 == 0) 0 else 5)
            t < 660 -> EcuSnapshot(rpm = 4500, mafGs = 120f, injPwMs = 9f, lambda = 0.8f, speedKmh = 100)
            t < 700 -> EcuSnapshot(rpm = 2500, mafGs = 6f, injPwMs = 0f, speedKmh = 80) // fuel cut
            else -> EcuSnapshot(rpm = 2500, mafGs = 25f, afCorrPct = 3f, afLearnPct = 2f, speedKmh = 90)
        }
        fun litres(dtMs: Long): Double {
            var last = 0L
            var l = 0.0
            var now = 1L
            while (now < 1_200_000L) {
                val dt = FuelCalc.sampleDt(last, now)
                last = now
                l += FuelCalc.litersPerHour(snap(now / 1000.0), 1.0) * dt / 3600
                now += dtMs
            }
            return l
        }
        val slow = litres(250)
        val fast = litres(83)
        assertTrue("$slow vs $fast", kotlin.math.abs(slow - fast) / fast < 0.01)
        // and a silent line counts 2 s, not the whole gap
        assertEquals(2.0, FuelCalc.sampleDt(1000, 60_000), 0.0)
    }

    /** Every CSV line has as many values as the header has names, the new columns after the old ones. */
    @Test fun csvRowMatchesItsHeader() {
        val row = LogRow(EcuSnapshot(timeMs = 5, rpm = 800, lambda = 1f), null, 0.9, null, GuardState(oilC = 85f), elapsedMs = 0L)
        for (on in listOf(false, true)) {
            val cols = LogColumns.offered(widebandOn = on)
            val values = cols.map { it.get(row) }
            assertEquals(cols.size, values.size)
            assertTrue(values.none { it.contains(',') })
            assertEquals(cols.map { it.key }.distinct().size, cols.size)
        }
        val keys = LogColumns.offered(false).map { it.key }
        assertTrue(keys.indexOf("oil_c") > keys.indexOf("fuel_sensor_ohm"))
        assertEquals("85", LogColumns.all.first { it.key == "oil_c" }.get(row))
        assertEquals("1", LogColumns.all.first { it.key == "oil_estimated" }.get(row))
    }

    /** «time» first is the epoch ms as up to 0.9-beta20, «t_ms» from the file's start is the last column. */
    @Test fun csvTimeColumns() {
        val t0 = 1_760_000_000_000L
        val cols = LogColumns.offered(false)
        val row = LogRow(EcuSnapshot(timeMs = t0 + 1234, rpm = 800), null, 0.9, null, GuardState(), elapsedMs = 0L)
        val extras = listOf("P12 Boost psi", "E5 Knock count")
        val head = LogColumns.header(cols.map { it.key } + extras).split(',')
        val line = LogColumns.line(t0 + 1234, t0, (cols.map { it.get(row) } + extras.map { "" }).joinToString(",")).split(',')
        assertEquals(head.size, line.size)
        assertEquals("time", head.first()); assertEquals("t_ms", head.last())
        assertEquals((t0 + 1234).toString(), line.first())
        assertEquals("1234", line.last())
        assertEquals("800", line[head.indexOf("rpm")])
        // nothing in between (all columns off): still the two clocks
        assertEquals("time,t_ms", LogColumns.header(emptyList()))
        assertEquals("${t0 + 5},5", LogColumns.line(t0 + 5, t0, ""))
    }

    /** The injectors' opening time: the 14 V figure as set, longer on a weak battery, clamped on a wild reading. */
    @Test fun deadTimeByVoltage() {
        assertEquals(0.6, FuelCalc.deadTimeMs(0.6, 14f), 1e-9)
        assertEquals(0.6 * 1.45, FuelCalc.deadTimeMs(0.6, 12f), 0.02)
        assertEquals(FuelCalc.deadTimeMs(0.6, 9f), FuelCalc.deadTimeMs(0.6, 3f), 1e-9)
        assertEquals(0.6, FuelCalc.deadTimeMs(0.6, null), 1e-9)
    }
}
