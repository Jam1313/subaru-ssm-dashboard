package kg.forester.sg

import kg.forester.sg.ssm.EngineId
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineIdTest {
    private fun e(car: String) = EngineId.fromCar(car).let { "${it.family} ${if (it.turbo) "T" else "NA"} ${it.code}" }

    @Test fun carsToEngines() {
        assertEquals("EJ20 T EJ205", e("JDM Forester XT 2003 AT"))
        assertEquals("EJ20 T EJ207", e("JDM Impreza STi 2005 MT/AT"))
        assertEquals("EJ25 T EJ257", e("USDM Impreza STI 2007 MT"))
        assertEquals("EJ25 T EJ255", e("USDM Forester XT 2005 MT"))
        assertEquals("EJ20 T EJ205", e("USDM Impreza WRX 2003 MT"))
        assertEquals("EJ25 T EJ255", e("USDM Impreza WRX 2010 MT"))
        assertEquals("FA20 T FA20DIT", e("USDM Impreza WRX 2015 MT"))
        assertEquals("FA20 NA FA20", e("USDM BRZ FA20 2015 MT"))
        assertEquals("EJ25 NA EJ253", e("USDM Impreza 2.5i 2008 AT"))
        assertEquals("EJ25 NA EJ251", e("USDM Forester 2.5 2003 MT"))
        assertEquals("EZ30 NA EZ30", e("JDM Legacy 3.0R 2004"))
        assertEquals("EZ36 NA EZ36", e("CAD Legacy 3.6R 2013 AT"))
        assertEquals("FB NA FB25", e("KDM Forester 2.5 NA 2016 MT"))
        assertEquals("EE20 T EE20", e("EDM Forester 2.0D 2013 CVT"))
        assertEquals("EJ20 T EJ20X/EJ20Y", e("JDM Legacy GT 2005 AT"))
    }

    /** ROMs the base doesn't know: the engine from the ROM ID's own bytes. */
    @Test fun fromRomIdBytes() {
        fun r(id: String) = EngineId.fromRomId(id)?.let { "${it.family} ${if (it.turbo) "T" else "NA"}" }
        assertEquals("EJ20 T", r("2A04446305"))   // JDM Forester XT 2003 (EJ205)
        assertEquals("EJ25 T", r("3B02594006"))   // JDM Forester STi 2004 (EJ255)
        assertEquals("EJ25 NA", r("3B12044206"))  // USDM Forester 2.5 2005
        assertEquals("EJ20 NA", r("2F04015206"))  // JDM Legacy 2.0 2004
        assertEquals("EJ20 T", r("2A44506005"))   // EDM Forester Turbo 2003
        assertEquals("EZ30 NA", r("2F0A366106"))  // JDM Legacy 3.0R
        assertEquals(null, r("13521031FF"))       // the later 32-bit scheme: not decoded
        assertEquals(null, r("A229703007"))
    }

    @Test fun headerFromTheEcu() {
        val e = EngineId.fromCar("JDM Forester XT 2003 AT", "ЭБУ", "2A04446305")
        assertEquals("FORESTER XT" to "2003 · EJ205 · 2.0 · турбо", kg.forester.sg.ui.carHeader(e, "FORESTER SG5", "2003   EJ20 turbo", false))
        assertEquals("Мой Форик" to "2003 · EJ205 · 2.0 · турбо", kg.forester.sg.ui.carHeader(e, "Мой Форик", "", true))
        // decoded from the ROM ID only: no model name, the engine still from the ECU
        val r = EngineId.fromRomId("3B12044206")!!
        assertEquals("SUBARU" to "EJ25 · 2.5 · атмо", kg.forester.sg.ui.carHeader(r, "FORESTER SG5", "x", false))
        // the base's "Legacy(Outback) GT(XT)": the brackets go
        val l = EngineId.fromCar("USDM Legacy(Outback) GT(XT) 2008 AT", "ЭБУ", "5212525107")
        assertEquals("LEGACY GT", kg.forester.sg.ui.carHeader(l, "x", "", false).first)
        // no ECU yet: nothing made up, only the driver's own name if there is one
        val none = kg.forester.sg.ssm.EngineInfo("EJ", false, "EJ", "нет")
        assertEquals("SUBARU" to "подключите ЭБУ", kg.forester.sg.ui.carHeader(none, "FORESTER SG5", "2003   EJ20 turbo", false))
        assertEquals("Мой Форик" to "подключите ЭБУ", kg.forester.sg.ui.carHeader(none, "Мой Форик", "", true))
    }

    @Test fun car3dFromTheEcu() {
        assertEquals("SG", kg.forester.sg.ui.car3dFor("JDM Forester XT 2003 AT"))
        assertEquals("SHNA", kg.forester.sg.ui.car3dFor("USDM Forester 2.5i 2009 AT"))
        assertEquals("GD", kg.forester.sg.ui.car3dFor("USDM Impreza STi 2005 MT"))
        assertEquals("BM", kg.forester.sg.ui.car3dFor("USDM Legacy GT 2005 MT"))
    }
}
