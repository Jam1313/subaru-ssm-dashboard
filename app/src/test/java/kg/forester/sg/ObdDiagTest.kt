package kg.forester.sg

import kg.forester.sg.ssm.GenericDtc
import kg.forester.sg.ssm.Obd2
import kg.forester.sg.ssm.ObdLink
import kg.forester.sg.ssm.ObdPids
import kg.forester.sg.ssm.Obdb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

/** An OBD-II engine unit behind any adapter: [answers] by request hex. */
private class FakeObd(override val isCan: Boolean, val answers: Map<String, List<ByteArray>>) : ObdLink {
    override val name = "fake"
    val asked = ArrayList<String>()
    val toUnit = ArrayList<String>()
    var unitAnswer: ByteArray? = null
    override fun request(req: ByteArray): List<ByteArray>? {
        val k = req.joinToString("") { "%02X".format(it) }
        asked += k
        return answers[k]
    }
    override fun requestTo(tx: Int, rx: Int, req: ByteArray): ByteArray? {
        toUnit += "%03X>%03X ".format(tx, rx) + req.joinToString("") { "%02X".format(it) }
        return unitAnswer
    }
    override fun close() {}
}

class ObdDiagTest {
    private val vin = "JF1SG5LR59G012345"

    @Test fun vinOnCanAndOnKline() {
        val can = FakeObd(true, mapOf("0902" to listOf(b(0x49, 0x02, 0x01) + ascii(vin))))
        assertEquals(vin, Obd2(can).vin())
        // K-line: five answers of 4 bytes, numbered, the first with three padding zeros; they may come in any order
        val parts = (b(0, 0, 0) + ascii(vin)).toList().chunked(4)
        val kl = FakeObd(false, mapOf("0902" to parts.mapIndexed { i, p -> b(0x49, 0x02, i + 1) + p.toByteArray() }.reversed()))
        assertEquals(vin, Obd2(kl).vin())
        assertNull(Obd2(FakeObd(true, emptyMap())).vin())
    }

    @Test fun vehicleInfo() {
        val o = FakeObd(true, mapOf(
            "0904" to listOf(b(0x49, 0x04, 0x02) + ascii("22611AN560\u0000\u0000\u0000\u0000\u0000\u0000") + ascii("A2WC522N        ")),
            "0906" to listOf(b(0x49, 0x06, 0x01, 0x12, 0x34, 0xAB, 0xCD)),
            "090A" to listOf(b(0x49, 0x0A, 0x01) + ascii("ECM\u0000-EngineControl\u0000")),
        ))
        val i = Obd2(o).vehicleInfo()
        assertNull(i.vin)
        assertEquals(listOf("22611AN560", "A2WC522N"), i.calIds)
        assertEquals(listOf("1234ABCD"), i.cvns)
        assertEquals("ECM -EngineControl", i.ecuName)
    }

    @Test fun readinessOfAPetrolEngine() {
        // MIL on, 2 codes; misfire+fuel+components supported, components not ready; catalyst, evap, O2, O2 heater supported, evap not ready
        val o = FakeObd(true, mapOf("0101" to listOf(b(0x41, 0x01, 0x82, 0x47, 0x65, 0x04))))
        val r = Obd2(o).readiness()!!
        assertTrue(r.mil)
        assertEquals(2, r.dtcCount)
        assertFalse(r.diesel)
        assertEquals(7, r.monitors.size)
        assertEquals(listOf("Прочие компоненты", "Улавливание паров топлива (EVAP)"), r.monitors.filter { !it.ready }.map { it.name })
        assertEquals(2, r.notReady)
    }

    @Test fun readinessOfADiesel() {
        val r = ObdPids.readiness(b(0x00, 0x0F, 0xC0, 0x40))!!
        assertTrue(r.diesel)
        assertFalse(r.mil)
        assertEquals(listOf("Сажевый фильтр"), r.monitors.filter { !it.ready }.map { it.name })
    }

    @Test fun freezeFrameOfTheFirstCode() {
        val o = FakeObd(true, mapOf(
            "020200" to listOf(b(0x42, 0x02, 0x00, 0x01, 0x71)),
            "020000" to listOf(b(0x42, 0x00, 0x00, 0x18, 0x18, 0x00, 0x00)), // 04 05 0C 0D
            "020400" to listOf(b(0x42, 0x04, 0x00, 0x80)),
            "020500" to listOf(b(0x42, 0x05, 0x00, 0x7B)),
            "020C00" to listOf(b(0x42, 0x0C, 0x00, 0x0B, 0xB8)),
            "020D00" to listOf(b(0x42, 0x0D, 0x00, 0x3C)),
        ))
        val f = Obd2(o).freezeFrame()!!
        assertEquals("P0171", f.dtc)
        val m = f.values.toMap()
        assertEquals("83 °C", m["Температура ОЖ"])
        assertEquals("750 об/мин", m["Обороты двигателя"])
        assertEquals("60 км/ч", m["Скорость"])
        assertEquals("50.2 %", m["Расчётная нагрузка"]!!.replace(',', '.'))
        // none kept
        val none = Obd2(FakeObd(true, mapOf("020200" to listOf(b(0x42, 0x02, 0x00, 0x00, 0x00))))).freezeFrame()!!
        assertNull(none.dtc)
    }

    @Test fun mode06OnCan() {
        // MID 01 (O2 B1S1): TID 01, UASID 0A (mV), 450 mV within 0…1100 mV; MID 21 (catalyst B1): ratio over its max
        val o = FakeObd(true, mapOf(
            "0600" to listOf(b(0x46, 0x00, 0x80, 0x00, 0x80, 0x00)), // MIDs 01 and 11?
            "0601" to listOf(b(0x46, 0x01, 0x01, 0x0A, 0x0E, 0x6A, 0x00, 0x00, 0x23, 0x36)),
            "0611" to listOf(b(0x46, 0x11)),
        ))
        val t = Obd2(o).testResults()!!
        assertEquals(1, t.size)
        assertTrue(t[0].name.startsWith("Кислородный датчик Б1 Д1"))
        assertEquals(true, t[0].pass)
        assertTrue(t[0].value.startsWith("450"))
        val cat = ObdPids.testsCan(b(0x46, 0x21, 0x80, 0x01, 0x00, 0x90, 0x00, 0x00, 0x00, 0x80))
        assertEquals(false, cat[0].pass)
        assertTrue(cat[0].name.contains("заводской"))
    }

    @Test fun mode06OnKline() {
        val r = ObdPids.testsKline(b(0x46, 0x01, 0x81, 0x00, 0x20, 0x00, 0x30))!!
        assertEquals(false, r.pass) // 32 under the minimum 48
        assertEquals("48", r.min)
    }

    @Test fun supportedPidsAndExtraValues() {
        val o = FakeObd(true, mapOf(
            "0100" to listOf(b(0x41, 0x00, 0x18, 0x3B, 0x80, 0x11)), // 04 05 0B 0C 0D 0F 10 11 20 … and 1C 20
            "0120" to listOf(b(0x41, 0x20, 0x00, 0x00, 0x20, 0x01)), // 33 40
            "0140" to listOf(b(0x41, 0x40, 0x00, 0x00, 0x00, 0x10)), // 5C
            "010C0D10110B05" to listOf(b(0x41, 0x0C, 0x0B, 0xB8, 0x0D, 0x3C, 0x10, 0x01, 0xF4, 0x11, 0x33, 0x0B, 0x21, 0x05, 0x7B)),
            "015C" to listOf(b(0x41, 0x5C, 0x82)),
        ))
        val obd = Obd2(o)
        assertTrue(obd.init())
        assertTrue(0x5C in obd.supported)
        assertTrue(obd.extraParams().any { it.id == "OBD_5C" })
        obd.watched = setOf("OBD_5C")
        val s = obd.poll(1000)!!
        assertEquals(750, s.rpm)
        assertEquals(60, s.speedKmh)
        assertEquals(5.0f, s.mafGs!!, 0.01f)
        assertEquals(90.0, obd.extraValues()["OBD_5C"]!!, 0.01)
        assertTrue("the oil temperature asked on its own", o.asked.any { it.contains("5C") })
    }

    @Test fun troubleCodes() {
        val o = FakeObd(false, mapOf(
            "03" to listOf(b(0x43, 0x01, 0x71, 0x03, 0x00, 0x00, 0x00), b(0x43, 0xC1, 0x00, 0x00, 0x00, 0x00, 0x00)),
            "07" to listOf(b(0x43 + 4, 0x91, 0x23, 0x00, 0x00, 0x00, 0x00)),
        ))
        val (stored, pending) = Obd2(o).readDtcs()!!
        assertEquals(listOf("P0171", "P0300", "U0100"), stored)
        assertEquals(listOf("B1123"), pending)
    }

    // ---- OBDb

    private val set = """
        { "commands": [
          { "hdr": "7E0", "cmd": {"22": "1234"}, "freq": 1,
            "signals": [
              {"id": "T_OIL", "path": "Engine", "name": "Oil temp", "fmt": {"len": 8, "add": -40, "max": 215, "unit": "celsius"}},
              {"id": "T_V", "name": "Volts", "fmt": {"bix": 8, "len": 16, "blsb": true, "div": 1000, "max": 20, "unit": "volts"}},
              {"id": "T_S", "name": "Signed", "fmt": {"bix": 24, "len": 8, "sign": true, "max": 127, "min": -128, "unit": "degrees"}}
            ]},
          { "hdr": "750", "rax": "758", "cmd": {"21": "01"}, "freq": 10, "filter": {"from": 2019},
            "signals": [ {"id": "NEW", "name": "New", "fmt": {"len": 1, "max": 1, "unit": "offon"}} ]},
          { "hdr": "18DA10F1", "cmd": {"22": "0101"}, "freq": 1, "signals": [ {"id": "EXT", "name": "x", "fmt": {"len": 8, "max": 1, "unit": "scalar"}} ]},
          { "hdr": "7E0", "eax": "10", "cmd": {"22": "0101"}, "freq": 1, "signals": [ {"id": "EAX", "name": "x", "fmt": {"len": 8, "max": 1, "unit": "scalar"}} ]},
          { "hdr": "7E0", "proto": "14230", "cmd": {"21": "01"}, "freq": 1, "signals": [ {"id": "KL", "name": "x", "fmt": {"len": 8, "max": 1, "unit": "scalar"}} ]}
        ]}
    """.trimIndent()

    @Test fun obdbParsesWhatCanBeAsked() {
        val all = Obdb.parse(set, null)
        assertEquals(2, all.size)
        assertEquals(0x7E8, all[0].rx)
        assertEquals(0x758, all[1].rx)
        assertEquals(1, Obdb.parse(set, 2015).size) // the 2019+ command left out for a 2015 car
        assertEquals(2, Obdb.parse(set, 2020).size)
    }

    @Test fun obdbDecodes() {
        val sig = Obdb.parse(set, null)[0].signals
        val payload = b(0x82, 0xD0, 0x30, 0xF6) // 130 → 90 °C; 0x30D0 LSB first = 12.496 V; -10
        assertEquals(90.0, Obdb.decode(sig[0], payload)!!, 1e-9)
        assertEquals(12.496, Obdb.decode(sig[1], payload)!!, 1e-9)
        assertEquals(-10.0, Obdb.decode(sig[2], payload)!!, 1e-9)
        assertNull(Obdb.decode(sig[2], b(0x82))) // answer too short
        assertEquals(kg.forester.sg.ssm.RrParam.Kind.SWITCH, Obdb.param(Obdb.parse(set, null)[1].signals[0]).kind)
        assertEquals("°C", Obdb.param(sig[0]).units)
    }

    @Test fun obdbPollsTickedSignalsOnly() {
        val cmds = Obdb.parse(set, null)
        val p = Obdb.Poller(cmds)
        val link = FakeObd(true, emptyMap())
        link.unitAnswer = b(0x62, 0x12, 0x34, 0x82, 0xD0, 0x30, 0xF6)
        p.poll(link, 10_000)
        assertTrue(link.toUnit.isEmpty()) // nothing ticked
        p.watched = setOf("OBDB_T_OIL")
        p.poll(link, 20_000)
        assertEquals(listOf("7E0>7E8 221234"), link.toUnit)
        assertEquals(mapOf("OBDB_T_OIL" to 90.0), p.values())
        p.poll(link, 20_500) // again within its 1 s: not asked
        assertEquals(1, link.toUnit.size)
        // an answer to another PID is not taken
        link.unitAnswer = b(0x62, 0x99, 0x99, 0x00)
        p.poll(link, 30_000)
        assertEquals(90.0, p.values()["OBDB_T_OIL"]!!, 1e-9)
    }

    @Test fun obdbMakesAndYears() {
        assertEquals(listOf("Toyota"), Obdb.makePrefixes("toyota"))
        assertEquals(listOf("Land-Rover", "Landrover"), Obdb.makePrefixes("land-rover"))
        assertTrue("VauxhallOpel" in Obdb.makePrefixes("Opel"))
        assertEquals(2009, Obdb.modelYear("JF1SG5LR59G012345", 2026))
        assertEquals(2014, Obdb.modelYear("JF1SJ5LC5EG012345", 2026))
        assertNull(Obdb.modelYear("short"))
    }

    // ---- trouble code texts

    private val generic = GenericDtc("""{"codes": {"P0171": ["System Too Lean (Bank 1)", "Слишком бедная смесь, банк 1"],
        "U0100": ["Lost Communication With ECM/PCM A", "Нет связи с ЭБУ двигателя A"]}}""")

    @Test fun dtcLookupOrder() {
        val subaru = mapOf("P0171" to ("SYSTEM TOO LEAN" to "Бедная смесь (Subaru)"), "P1491" to ("PCV FUNCTION" to "Система вентиляции картера"))
        assertEquals("Бедная смесь (Subaru)", generic.describe("P0171", subaru, "Subaru").ru)
        assertEquals("Система вентиляции картера", generic.describe("P1491", subaru, "Subaru").ru)
        assertEquals("Слишком бедная смесь, банк 1", generic.describe("P0171", null, "Toyota").ru)
        assertEquals("Заводской код Toyota, описание недоступно", generic.describe("P1491", null, "Toyota").ru)
        assertEquals("Заводской код производителя, описание недоступно", generic.describe("B1123", null, null).ru)
        assertFalse(generic.describe("P3000", null, null).known)
        assertEquals("Нет связи с ЭБУ двигателя A", generic.describe("u0100").ru)
    }

    @Test fun theBundledBase() {
        val f = listOf(java.io.File("src/main/assets/obd_dtc_ru.json"), java.io.File("app/src/main/assets/obd_dtc_ru.json")).first { it.isFile }
        val g = GenericDtc(f.readText())
        assertTrue(g.size > 2000)
        listOf("P0011", "P0101", "P0171", "P0300", "P0420", "P0442", "P2135", "P3400", "U0100", "C0035").forEach {
            val t = g.describe(it, null, "Toyota")
            assertTrue(it, t.known && t.en.isNotBlank() && t.ru.any { c -> c in 'а'..'я' })
        }
        // the make's own ranges get no standard text
        assertFalse(g.describe("P1101", null, "Toyota").known)
        assertNotNull(g["p0171"])
    }

    @Test fun manufacturerRanges() {
        listOf("P1000", "P1FFF", "P3000", "P33FF", "B1000", "B2ABC", "C1234", "C2000", "U1000", "U2FFF").forEach {
            assertTrue(it, GenericDtc.isManufacturer(it))
        }
        listOf("P0100", "P2000", "P2FFF", "P3400", "P34FF", "B0001", "C0035", "U0100", "U3000", "X1000", "P01").forEach {
            assertFalse(it, GenericDtc.isManufacturer(it))
        }
    }
}

