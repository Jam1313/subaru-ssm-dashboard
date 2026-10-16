package kg.forester.sg

import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxFont
import kg.forester.sg.data.DispCfg
import kg.forester.sg.data.DispKind
import kg.forester.sg.data.DispLayout
import kg.forester.sg.data.Displays
import kg.forester.sg.data.Relays
import kg.forester.sg.data.SensorBox
import kg.forester.sg.service.DisplayEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisplayTest {
    private val values = mapOf("coolant" to 97f, "battery" to 14.13f, "boost" to 1.234f, "rpm" to 3250f, "intake" to -5f)
    private fun v(id: String) = values[id]

    @Before fun clean() { SensorBox.slots.value = emptyList(); Relays.channels.value = emptyList(); Displays.list.value = emptyList() }
    @After fun after() { Displays.list.value = emptyList() }

    @Test fun tmFitsFourDigitsWithDegreesWhereThereIsRoom() {
        assertEquals("97~C", Displays.tmText("coolant", 97f))
        assertEquals(" 105", Displays.tmText("coolant", 105f))
        assertEquals("-5~C", Displays.tmText("intake", -5f))
        assertEquals(" 14.1", Displays.tmText("battery", 14.13f))
        assertEquals(" 1.23", Displays.tmText("boost", 1.234f))
        assertEquals("3250", Displays.tmText("rpm", 3250f))
        assertEquals("----", Displays.tmText("coolant", null))
    }

    @Test fun tmSegmentsAreTheBoxsOwn() {
        assertArrayEquals(intArrayOf(0x6F, 0x07, 0x63, 0x39), Displays.tmSegments("97~C"))
        // a dot lights the digit before it and takes no place
        assertArrayEquals(intArrayOf(0, 0x06 or 0x80, 0x5B, 0x4F), Displays.tmSegments(" 1.23"))
        assertArrayEquals(IntArray(4) { 0x7F or 0x80 }, Displays.tmSegments("8.8.8.8."))
    }

    @Test fun oledGetsCp1251AndLcdGetsTranslit() {
        assertEquals(listOf(0xCC, 0xC0, 0xD1, 0xCB, 0xCE, 0x20, 0x39, 0x37, 0x7E, 0x43), Displays.wire("Масло 97°C", DispKind.OLED).map { it.code })
        assertEquals("MASLO 97ßC", Displays.wire("Масло 97°C", DispKind.LCD1602))
        assertEquals("ZHYU", Displays.wire("ЖЮ", DispKind.LCD2004))
        // every character the OLED gets has a glyph in the box's font
        val all = Displays.wire("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ .,:-+%/°", DispKind.OLED)
        all.forEach { assertTrue("no glyph for ${it.code}", BoxFont.glyphs.containsKey(it.code)) }
    }

    @Test fun linesFitTheScreenAndTheBoxsLineBuffer() {
        val cases = listOf(
            DispCfg(1, DispKind.OLED, DispLayout.ONE, listOf("coolant")),
            DispCfg(1, DispKind.OLED, DispLayout.TWO, listOf("coolant", "battery")),
            DispCfg(1, DispKind.OLED, DispLayout.FOUR, listOf("coolant", "battery", "boost", "rpm")),
            DispCfg(1, DispKind.LCD1602, items = listOf("coolant", "battery")),
            DispCfg(1, DispKind.LCD2004, items = listOf("coolant", "battery", "boost", "rpm")),
        )
        for (d in cases) {
            val lines = Displays.lines(d, ::v)
            assertTrue(d.toString(), lines.isNotEmpty())
            for (l in lines) {
                val w = Displays.wire(l.text, d.kind)
                val width = when (d.kind) { DispKind.OLED -> 128 / (6 * l.scale); DispKind.LCD1602 -> 16; else -> 20 }
                assertTrue("'$w' wider than $width", w.length <= width)
                assertTrue(l.row + l.scale <= if (d.kind == DispKind.OLED) 8 else d.slots)
                assertTrue("L ${l.row} ${l.scale} $w".length < 48) // sensorbox.ino: char line[48]
            }
        }
        // a text LCD row is the name on the left and the value on the right, the full width
        val lcd = Displays.lines(DispCfg(1, DispKind.LCD1602, items = listOf("coolant", "battery")), ::v)
        assertEquals("АНТИФРИЗ    97°C", lcd[0].text)
        assertEquals(16, lcd[1].text.length)
        assertTrue(lcd[1].text.endsWith("14.1В"))
        // the big value on one OLED line
        val one = Displays.lines(DispCfg(1, DispKind.OLED, DispLayout.ONE, listOf("coolant")), ::v)
        assertEquals(" 97°C", one.first { it.scale == 3 }.text)
    }

    @Test fun testPatternFitsToo() {
        for (k in DispKind.entries) for (l in DisplayEngine.testLines(DispCfg(1, k))) {
            assertTrue("L ${l.row} ${l.scale} ${Displays.wire(l.text, k)}".length < 48)
        }
    }

    @Test fun displaysTakeTheirPinsFromTheOutputs() {
        Displays.list.value = listOf(DispCfg(1, DispKind.OLED), DispCfg(2, DispKind.TM1637, clk = "D4", dio = "D5"))
        assertEquals(setOf("D2", "D3", "D4", "D5"), Displays.pins(BoxBoard.PROMICRO))
        assertEquals(setOf("A4", "A5", "D4", "D5"), Displays.pins(BoxBoard.NANO))
        // the next relay goes past them
        assertEquals("D0", Relays.free(BoxBoard.PROMICRO)!!.second)
        assertFalse(Displays.freeOutPins(BoxBoard.PROMICRO).any { it in setOf("D2", "D3", "D4", "D5") })
        assertEquals("D3", Relays.free(BoxBoard.NANO)!!.second)
        Displays.list.value = listOf(DispCfg(2, DispKind.TM1637, clk = "D3", dio = "D4"))
        assertEquals("D5", Relays.free(BoxBoard.NANO)!!.second)
        // on I2C one OLED and one text LCD together, one TM1637
        Displays.list.value = listOf(DispCfg(1, DispKind.OLED))
        assertTrue(Displays.canAdd(DispKind.LCD1602))
        assertFalse(Displays.canAdd(DispKind.OLED_13))
        Displays.list.value = listOf(DispCfg(2, DispKind.TM1637, clk = "D3", dio = "D4"))
        assertTrue(Displays.canAdd(DispKind.LCD2004))
        assertFalse(Displays.canAdd(DispKind.TM1637))
    }

    @Test fun boxSensorsGetShortNames() {
        assertEquals("ДАВЛ МАСЛА", Displays.short("Давление масла 0–10 бар"))
        assertEquals("МАСЛО", Displays.short("Температура масла, Bosch M12-H"))
        assertEquals("AFR", Displays.short("ШДК Innovate LC-2 / LC-1 / MTX-L"))
        assertEquals("Подогрев сидений", Displays.short("Подогрев сидений (мой)"))
        assertEquals("1-2", Displays.wire("1–2", DispKind.OLED))
    }

    @Test fun colourScreensKeepEverythingOnTheGlass() {
        for (k in DispKind.entries.filter { it.colour }) for (l in DispLayout.entries) {
            val d = DispCfg(1, k, l, listOf("coolant", "battery", "boost", "rpm"), cs = "D5", dc = "D6")
            val ops = Displays.colourOps(d, ::v) { id, x -> id == "coolant" && x > 95f }
            assertEquals("$k $l keys", ops.size, ops.map { it.key }.toSet().size)
            for (op in ops) when (op) {
                is kg.forester.sg.data.ColOp.Box -> assertTrue("$k $l $op", op.x >= 0 && op.y >= 0 && op.x + op.w <= k.w && op.y + op.h <= k.h)
                is kg.forester.sg.data.ColOp.Txt -> {
                    assertTrue("$k $l $op", op.x >= 0 && op.x + op.text.length * 6 * op.s <= k.w && op.y + 8 * op.s <= k.h)
                    assertTrue(Displays.wire(op, k).length < 72) // sensorbox.ino: char line[72]
                }
            }
            // coolant 97 is past the warning given: its number is red
            assertTrue(ops.any { it is kg.forester.sg.data.ColOp.Txt && it.fg == Displays.RED })
        }
        for (k in DispKind.entries.filter { it.colour }) assertTrue(Displays.colourTest(k).all { Displays.wire(it, k).length < 72 })
    }

    @Test fun theNarrowOledHasFourPages() {
        for (l in DispLayout.entries) for (line in Displays.lines(DispCfg(1, DispKind.OLED_091, l, listOf("coolant", "battery", "boost", "rpm")), ::v)) {
            assertTrue("$l $line", line.row + line.scale <= 4)
            assertTrue(Displays.wire(line.text, DispKind.OLED_091).length <= 128 / (6 * line.scale))
        }
        for (line in DisplayEngine.testLines(DispCfg(1, DispKind.OLED_091))) assertTrue(line.row + line.scale <= 4)
    }

    @Test fun spiScreenWithoutCsStaysOffTheThermocoupleBus() {
        assertEquals(null, Displays.unfit(DispKind.IPS_240, BoxBoard.NANO))
        SensorBox.slots.value = listOf(kg.forester.sg.data.BoxSlot.of("TC0", kg.forester.sg.data.BoxPresets.all.first { it.kind == kg.forester.sg.data.BoxKind.TC }))
        assertTrue(Displays.unfit(DispKind.IPS_240, BoxBoard.NANO) != null)
        assertEquals(null, Displays.unfit(DispKind.IPS_320, BoxBoard.NANO))
    }

    @Test fun savedAndReadBack() {
        val d = DispCfg(3, DispKind.TM1637, DispLayout.ONE, listOf("box_A0"), "D6", "D7", 6)
        assertEquals(d, DispCfg.fromJson(d.toJson()))
    }
}
