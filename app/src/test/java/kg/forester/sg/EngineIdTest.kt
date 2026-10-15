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
}
