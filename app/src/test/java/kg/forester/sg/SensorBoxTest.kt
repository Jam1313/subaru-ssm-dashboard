package kg.forester.sg

import kg.forester.sg.data.BoxFrame
import kg.forester.sg.data.BoxPresets
import kg.forester.sg.data.BoxSlot
import kg.forester.sg.data.SensorBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorBoxTest {
    private fun slot(preset: String, input: String = "A0") = BoxSlot.of(input, BoxPresets.of(preset)!!)
    private fun frame(vararg raw: Pair<String, Float>, tc: Float? = null, fault: Int = 0, hz: Float = 0f) =
        BoxFrame(0, 5000, raw.toMap(), tc, fault, hz)

    @Test fun parsesInfoAndFrames() {
        val i = SensorBox.parseInfo("I SENSORBOX 1 board=nano id=1A2B rate=20 ch=A0,A1,A2,A3,A6,A7,TC0,F0", "USB")!!
        assertEquals("nano", i.board)
        assertEquals("1A2B", i.id)
        assertEquals(20, i.rate)
        assertEquals(8, i.inputs.size)
        assertNull(SensorBox.parseInfo("ELM327 v1.5", "USB"))

        val f = SensorBox.parseFrame("D t=81234 vcc=4987 A0=512.3 A1=88.0 TC0=745.25 F0=60.0")!!
        assertEquals(81234L, f.timeMs)
        assertEquals(4987, f.vccMv)
        assertEquals(512.3f, f.raw["A0"]!!, 0.01f)
        assertEquals(745.25f, f.tc!!, 0.01f)
        assertEquals(60f, f.hz!!, 0.01f)
        assertEquals(1, SensorBox.parseFrame("D t=1 vcc=5000 TC0=!1 F0=0.0")!!.tcFault)
        assertNull(SensorBox.parseFrame("I SENSORBOX"))
    }

    @Test fun pressureSender() {
        val s = slot("oil_p_10")
        // 0.5 V is zero, 4.5 V full scale, 2.5 V half
        assertEquals(0f, SensorBox.read(s, frame("A0" to 102.3f)).value!!, 0.01f)
        assertEquals(10f, SensorBox.read(s, frame("A0" to 920.7f)).value!!, 0.01f)
        assertEquals(5f, SensorBox.read(s, frame("A0" to 511.5f)).value!!, 0.01f)
        // a broken signal wire reads near 0 V: no value, a reason
        val open = SensorBox.read(s, frame("A0" to 3f))
        assertNull(open.value)
        assertNotNull(open.problem)
    }

    @Test fun thermistorTable() {
        val s = slot("oil_t_bosch")
        // 2500 Ω (Bosch at 20 °C) against the preset's pull-up
        val x = 2500f / (2500f + s.p.pull)
        assertEquals(20f, SensorBox.read(s, frame("A0" to x * 1023f)).value!!, 0.3f)
        // the demo drives the conversion backwards: what goes in comes out
        for (t in listOf(35f, 90f, 117f)) assertEquals(t, SensorBox.read(s, frame("A0" to SensorBox.rawFor(s, t))).value!!, 0.3f)
        // nothing on the input: the pull-up reads 5 V
        assertNull(SensorBox.read(s, frame("A0" to 1023f)).value)
    }

    @Test fun theCatalogueLoadsAndEveryPresetReadsBack() {
        val text = java.io.File("../hardware/sensorbox/sensors-catalog.json").readText()
        BoxPresets.loadText(text)
        val list = BoxPresets.parse(text)
        assertTrue(list.size >= 30)
        assertEquals(list.size, list.map { it.id }.toSet().size)
        for (p in list) {
            val s = BoxSlot.of(if (p.kind == kg.forester.sg.data.BoxKind.TC) "TC0" else if (p.kind == kg.forester.sg.data.BoxKind.FREQ) "F0" else "A0", p)
            assertTrue(p.id, p.wires.isNotEmpty())
            // a value in the middle of the scale survives the demo's way back to raw counts and the reading
            val mid = (p.min + p.max) / 2
            when (p.kind) {
                kg.forester.sg.data.BoxKind.TC, kg.forester.sg.data.BoxKind.FREQ -> {}
                else -> {
                    val r = SensorBox.read(s, frame("A0" to SensorBox.rawFor(s, mid)))
                    assertNotNull(p.id, r.value)
                    assertEquals(p.id, mid, r.value!!, (p.max - p.min) * 0.03f)
                }
            }
        }
        // a VDO sender: resistance rising with the pressure
        val vdo = BoxSlot.of("A0", list.first { it.kind == kg.forester.sg.data.BoxKind.RES })
        val x = 82f / (82f + vdo.p.pull) // 82 Ω is 2 bar on the 5 bar sender
        assertEquals(2f, SensorBox.read(vdo, frame("A0" to x * 1023f)).value!!, 0.05f)
    }

    @Test fun widebandAndThermocoupleAndFlex() {
        // LC-2: 0 V = 7.35, 5 V = 22.39 AFR
        assertEquals(14.87f, SensorBox.read(slot("afr_lc2"), frame("A0" to 511.5f)).value!!, 0.02f)
        assertEquals(812.5f, SensorBox.read(slot("egt_k", "TC0"), frame(tc = 812.5f)).value!!, 0.01f)
        assertNull(SensorBox.read(slot("egt_k", "TC0"), frame(fault = 1)).value)
        assertEquals(10f, SensorBox.read(slot("flex", "F0"), frame(hz = 60f)).value!!, 0.01f)
    }
}
