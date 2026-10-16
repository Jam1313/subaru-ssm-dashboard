package kg.forester.sg

import kg.forester.sg.data.Manual
import kg.forester.sg.data.OutEvent
import kg.forester.sg.data.OutTemplates
import kg.forester.sg.service.RuleRunner
import kg.forester.sg.data.SensorBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    private fun tpl(id: String) = OutTemplates.all.first { it.id == id }.make(1, "D4")

    @Test fun fanSwitchesWithItsOwnGapAndNoChatter() {
        val fan = tpl("fan")
        val r = RuleRunner()
        val temps = listOf(90, 95, 97, 98, 99, 97, 96, 95, 94, 93, 96, 97, 98)
        val states = temps.mapIndexed { i, t -> r.evaluate(fan, { if (it == "coolant") t.toFloat() else null }, i * 1000L, false).want }
        // on at 98, still on through 97..95 (between the two numbers), off below 94, back on at 98
        assertEquals(listOf(false, false, false, true, true, true, true, true, true, false, false, false, true), states)
    }

    @Test fun methNeedsBoostAndLeanForAMomentAndNeverBelow2500() {
        val meth = tpl("meth")
        val r = RuleRunner()
        fun at(t: Long, boost: Float, afr: Float, rpm: Float) = r.evaluate(meth, { when (it) { "boost" -> boost; "afr" -> afr; "rpm" -> rpm; else -> null } }, t, false)
        assertFalse(at(0, 1.1f, 12.5f, 4000f).want)       // conditions met, the 0.3 s has not passed
        assertTrue(at(400, 1.1f, 12.5f, 4000f).want)      // held long enough
        assertFalse(at(500, 1.1f, 12.5f, 2000f).want)     // below 2500 rpm: the block wins
        assertTrue(at(600, 1.1f, 12.5f, 4000f).want)      // back above 2500: the block lets go, the pump runs again
        assertFalse(at(10_000, 0.7f, 12.5f, 4000f).want)  // boost under 0.8 bar
    }

    @Test fun shiftLightBlinksOnItsSecondStage() {
        val s = tpl("shift")
        val r = RuleRunner()
        fun at(t: Long, rpm: Float) = r.evaluate(s, { if (it == "rpm") rpm else null }, t, false)
        assertFalse(at(0, 6000f).want)
        assertTrue(at(1, 6400f).let { it.want && !it.blink })
        assertTrue(at(2, 6900f).let { it.want && it.blink })
        assertFalse(at(3, 6000f).want)
    }

    @Test fun manualPanicAndEvents() {
        val r = RuleRunner()
        val fan = tpl("fan")
        assertTrue(r.evaluate(fan.copy(manual = Manual.ON), { 80f }, 0, false).want)
        assertFalse(r.evaluate(fan.copy(manual = Manual.ON), { 80f }, 0, true).want) // «Всё выключить» beats everything
        val light = tpl("light")
        assertFalse(r.evaluate(light, { null }, 0, false).want)
        r.fire(OutEvent.WOKE, 0, listOf(light))
        assertTrue(r.evaluate(light, { null }, 1000, false).want)
        // the button: off until pressed, and never on with a weak battery
        val b = tpl("button")
        assertFalse(r.evaluate(b, { 12.6f }, 0, false).want)
        assertTrue(r.evaluate(b.copy(manual = Manual.ON), { 12.6f }, 1, false).want)
        assertFalse(r.evaluate(b.copy(manual = Manual.ON), { 11.6f }, 2, false).want)
    }

    @Test fun noDataSwitchesAFanOnButNotAPump() {
        val r = RuleRunner()
        assertTrue(r.evaluate(tpl("fan"), { null }, 0, false).want)     // a fan with lost = on: cool rather than overheat
        assertFalse(r.evaluate(tpl("meth"), { null }, 0, false).want)   // a pump with lost = off
    }

    @Test fun anotherOutputIsACondition() {
        val r = RuleRunner()
        // the oil cooler fan follows the radiator fan: on while output 1 is on
        val follower = tpl("empty").copy(ch = 2, rules = listOf(kg.forester.sg.data.OutRule("f", kg.forester.sg.data.RuleKind.LEVEL,
            main = kg.forester.sg.data.Threshold("out_1", true, 1f, 1f))))
        assertFalse(r.evaluate(follower, { if (it == "out_1") 0f else null }, 0, false).want)
        assertTrue(r.evaluate(follower, { if (it == "out_1") 1f else null }, 1, false).want)
        assertFalse(r.evaluate(follower, { if (it == "out_1") 0f else null }, 2, false).want)
    }

    @Test fun setupLineMatchesTheFirmware() {
        val fan = tpl("fan")
        assertEquals("C 1 4 L 30000 10000 0 1", fan.setup())
        assertEquals("C 1 4 L 0 1000 60 0", tpl("meth").setup())
        // the oil fan follows a SensorBox oil input when there is one
        SensorBox.slots.value = emptyList()
        assertEquals("oil", tpl("oilfan").rules.first().main!!.src)
    }
}
