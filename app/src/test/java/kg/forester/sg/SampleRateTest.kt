package kg.forester.sg

import kg.forester.sg.data.BoxRoles
import kg.forester.sg.data.CanChannel
import kg.forester.sg.data.CanChannels
import kg.forester.sg.data.Derived
import kg.forester.sg.data.LearnedScale
import kg.forester.sg.data.SensorBox
import kg.forester.sg.service.FuelCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What used to count samples now counts time: the same at the plain polling's 4 a second and the fast poll's 12. */
class SampleRateTest {
    /** Feeds [v] every [stepMs] for [ms]; true once the scale widened. */
    private fun widens(stepMs: Long, ms: Long): Boolean {
        val s = LearnedScale("test", 1.2f, 0.2f, 2.4f, sane = 2.5f)
        var t = 1000L
        var widened = false
        while (t <= 1000L + ms) { widened = s.feed(1.15f, t) || widened; t += stepMs }
        return widened
    }

    @Test fun aScaleWidensAfterHalfASecondAtAnyRate() {
        // a spike of 0.3 s: three samples at 12 a second used to be enough, now neither rate widens
        assertFalse(widens(250, 300))
        assertFalse(widens(83, 300))
        // held 0.6 s: both
        assertTrue(widens(250, 600))
        assertTrue(widens(83, 600))
    }

    @Test fun aScaleStreakBreaksOnALowerReading() {
        val s = LearnedScale("test", 1.2f, 0.2f, 2.4f, sane = 2.5f)
        assertFalse(s.feed(1.15f, 1000))
        assertFalse(s.feed(1.15f, 1300))
        assertFalse(s.feed(0.5f, 1400))
        assertFalse(s.feed(1.15f, 1600))
        assertTrue(s.feed(1.15f, 2100))
        // 1.15 and some room above, to the next step
        assertEquals(1.4f, s.top.value, 0.001f)
    }

    @Test fun injectorDeadTimeFollowsTheVoltage() {
        assertEquals(0.6, FuelCalc.deadTimeMs(0.6, 14f), 1e-9)
        assertEquals(0.6, FuelCalc.deadTimeMs(0.6, null), 1e-9)
        // the usual shape of Subaru latency tables: ~1.45× at 12 V, ~2.2× at 10 V, ~0.73× at 16 V
        assertEquals(0.87, FuelCalc.deadTimeMs(0.6, 12f), 0.01)
        assertEquals(1.34, FuelCalc.deadTimeMs(0.6, 10f), 0.02)
        assertEquals(0.44, FuelCalc.deadTimeMs(0.6, 16f), 0.01)
        // an odd reading is clamped, not taken
        assertEquals(FuelCalc.deadTimeMs(0.6, 9f), FuelCalc.deadTimeMs(0.6, 3f), 1e-9)
        assertEquals(FuelCalc.deadTimeMs(0.6, 16f), FuelCalc.deadTimeMs(0.6, 25f), 1e-9)
        assertEquals(FuelCalc.deadTimeMs(0.6, 14f), FuelCalc.deadTimeMs(0.6, Float.NaN), 1e-9)
    }

    @Test fun fuelByInjectors() {
        // 3000 rpm, 4 ms open less 0.6, 380 cc/min: 1500 injections a minute per injector, 4 of them
        val lh = FuelCalc.byInjectors(4f, 3000, 380.0, 0.6)
        val expected = 4 * 1500 * (3.4 / 60_000.0) * 380.0 * 60 / 1000 // l/h
        assertEquals(expected, lh, 1e-6)
        // shorter than the opening time: nothing injected
        assertEquals(0.0, FuelCalc.byInjectors(0.5f, 800, 380.0, 0.6), 1e-9)
        // a low battery opens the injectors later: less fuel for the same pulse
        assertTrue(FuelCalc.byInjectors(2f, 800, 380.0, FuelCalc.deadTimeMs(0.6, 12f)) < FuelCalc.byInjectors(2f, 800, 380.0, 0.6))
    }

    @Test fun derivedValues() {
        // 3.5 bar rail, 1 bar boost: 2.5 over the manifold; 3.5 at -60 kPa: 4.1
        assertEquals(2.5f, Derived.fuelDiffBar(3.5f, 100f)!!, 1e-4f)
        assertEquals(4.1f, Derived.fuelDiffBar(3.5f, -60f)!!, 1e-4f)
        assertNull(Derived.fuelDiffBar(null, 100f))
        assertNull(Derived.fuelDiffBar(3.5f, null))
        assertEquals(50f, Derived.intercoolerDropC(90f, 40f)!!, 1e-4f)
        // 90 after the turbo, 40 after the intercooler, 25 going in: 50 of 65 taken off
        assertEquals(76.9f, Derived.intercoolerEfficiencyPct(90f, 40f, 25f)!!, 0.1f)
        // at cruise the turbo hardly heats the air: no ratio of two small numbers
        assertNull(Derived.intercoolerEfficiencyPct(32f, 28f, 25f))
        assertNull(Derived.intercoolerEfficiencyPct(90f, 40f, null))
        assertEquals(1.8f, Derived.exhaustRatio(1.8f, 100f)!!, 1e-4f)
        assertNull(Derived.exhaustRatio(0.4f, 10f))
        assertEquals(0.8f, Derived.exhaustOverBoostBar(1.8f, 100f)!!, 1e-4f)
        assertEquals(1.0f, Derived.bar(100f, "кПа"), 1e-4f)
        assertEquals(1.0f, Derived.bar(14.5038f, "psi"), 1e-3f)
        assertEquals(2.0f, Derived.bar(2f, "бар"), 1e-4f)
    }

    private fun ch(id: String, role: String, units: String = "") =
        CanChannel(id, id, 0x3EC, false, -1, 0, 2, true, false, "x", units, 0, role)

    @Test fun canChannelsTakeTheSensorBoxRoles() {
        val ids = CanChannel.ROLES.map { it.first }
        for (r in listOf("egt", "oilP", "oil", "fuelP", "emap", "iatIn", "iatOut", "eth")) assertTrue(r, r in ids)
        assertTrue(BoxRoles.all.any { it.first == "eth" })
        // the tank wire's volts are the box's only
        assertFalse("fuelLevel" in ids)
        assertTrue("fault" in ids)
    }

    @Test fun roleLookupBoxFirstThenCan() {
        val list = listOf(ch("C1", "egt", "°C"), ch("C2", "egt", "°C"), ch("C3", "fuelP", "кПа"), ch("C4", ""))
        val values = mapOf("C1" to 640.0, "C2" to 702.0, "C3" to 350.0, "C4" to 1.0)
        val egt = CanChannels.roleValues(list, values, "egt")
        assertEquals(2, egt.size)
        // a channel without a value (quiet for 3 s) is left out
        assertEquals(1, CanChannels.roleValues(list, values - "C1", "egt").size)
        assertTrue(CanChannels.roleValues(list, values, "oilP").isEmpty())
        // the hottest of several, as with a thermocouple per cylinder on the box
        assertEquals(702f, SensorBox.pickRole(emptyList(), egt)!!.first, 1e-3f)
        // the box wins over CAN
        assertEquals(655f, SensorBox.pickRole(listOf(655f to "°C"), egt)!!.first, 1e-3f)
        assertNull(SensorBox.pickRole(emptyList(), emptyList()))
        // a pressure keeps its unit: 350 kPa is 3.5 bar
        val fuel = SensorBox.pickRole(emptyList(), CanChannels.roleValues(list, values, "fuelP"))!!
        assertEquals(3.5f, Derived.bar(fuel.first, fuel.second), 1e-4f)
    }
}
