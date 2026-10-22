package kg.forester.sg

import kg.forester.sg.data.Brands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrandsTest {
    private val dir = File("src/main/assets/brands")
    private val brands = Brands(File(dir, "wmi.json").readText(), File(dir, "logos.json").readText())
    private fun make(vin: String) = brands.makeFromVin(vin)?.slug

    @Test fun wmiLookup() {
        assertEquals("subaru", make("JF1GD29632G513286"))   // JDM-built Impreza for export
        assertEquals("subaru", make("JF2SH63659H123456"))   // Forester SH
        assertEquals("subaru", make("4S4BRCCC1B3324531"))   // Outback built in the USA
        assertEquals("toyota", make("JTDKN3DU5A0123456"))
        assertEquals("lexus", make("JTHBK1GG0F2123456"))
        assertEquals("lada", make("XTA210740B1234567"))
        assertEquals("lada", make("XTAGFL110KY123456"))
        assertEquals("uaz", make("XTT316300L1012345"))
        assertEquals("gaz", make("X96330200A2123456"))
        assertEquals("volkswagen", make("WVWZZZ1KZAW123456"))
        assertEquals("kia", make("KNAGM4A77D5123456"))
        assertEquals("hyundai", make("KMHDH41EBCU123456"))
        assertEquals("bmw", make("WBA3A5C51CF123456"))
        assertEquals("mercedes-benz", make("WDD2050461F123456"))
        assertEquals("renault", make("VF1RFB00X56123456"))
        assertEquals("renault", make("X7LHSRDJN51123456"))   // Renault Russia
        assertEquals("honda", make("1HGCM82633A004352"))
        assertEquals("geely", make("L6T7944Z2KN123456"))
        assertEquals("chery", make("LVVDB11B6ND123456"))
    }

    @Test fun brandData() {
        val s = brands.makeFromVin("JF1GD29632G513286")!!
        assertEquals("Subaru", s.name)
        assertEquals("JP", s.country)
        assertEquals("Lada", brands.brand("lada")!!.name)
        assertEquals("RU", brands.brand("lada")!!.country)
        assertEquals("Mercedes-Benz", brands.brand("mercedes-benz")!!.name)
        assertTrue(brands.wmiCount > 1000)
        assertTrue(brands.makeCount > 200)
    }

    @Test fun twoCharFallback() {
        assertEquals("subaru", make("JFZGD29632G513286"))   // a JF? WMI the table lacks
        assertEquals("toyota", make("JTZKN3DU5A0123456"))
    }

    @Test fun unknownAndShared() {
        assertNull(make("D1234567890ABCDEF"))               // no region, no maker
        assertNull(make("Z94CB41AAER123456"))               // Hyundai Rus: Solaris and Rio share it
        assertEquals("EU", brands.region("Z94CB41AAER123456"))
        assertEquals("AS", brands.region("JF1GD29632G513286"))
        assertEquals("NA", brands.region("1HGCM82633A004352"))
        assertNull(brands.region("D1234567890ABCDEF"))
    }

    @Test fun invalidVins() {
        assertNull(make(""))
        assertNull(make("JF1GD2963"))                       // too short
        assertNull(make("JF1GD29632G5132861"))              // 18 chars
        assertNull(make("JF1GD29632G51328O"))               // O is not allowed
        assertNull(make("JF1GD29632G5132I6"))               // nor I
        assertNull(make("QF1GD29632G513286"))               // nor Q
        assertNull(make("JF1GD2963#G513286"))
        assertEquals("subaru", make(" jf1gd29632g513286 ")) // case and spaces don't matter
        assertNull(Brands.normalize("SG5-012345"))          // a JDM frame number is not a VIN
    }

    @Test fun checkDigit() {
        assertEquals(true, Brands.checkDigitOk("1HGCM82633A004352"))
        assertEquals(true, Brands.checkDigitOk("11111111111111111"))
        assertEquals(false, Brands.checkDigitOk("1HGCM82643A004352"))
        assertNull(Brands.checkDigitOk("JF1GD29632G513286"))  // not a North American VIN: no hint
        assertNull(Brands.checkDigitOk("1HGCM8263"))
    }

    @Test fun subaru() {
        assertTrue(brands.isSubaru("JF1GD29632G513286"))
        assertTrue(brands.isSubaru("4S3BL616X77123456"))
        assertFalse(brands.isSubaru("JTDKN3DU5A0123456"))
        assertFalse(brands.isSubaru("garbage"))
    }

    @Test fun logos() {
        for (slug in listOf("subaru", "toyota", "lada", "kia", "hyundai", "volkswagen", "bmw", "geely", "chery")) {
            val path = brands.logoAsset(slug)
            assertEquals("brands/logos/$slug.webp", path)
            assertTrue(slug, File("src/main/assets/$path").length() > 0)
        }
        assertNull(brands.logoAsset("no-such-make"))
        val url = brands.remoteLogoUrl("bugatti")
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://raw.githubusercontent.com/filippofilip95/car-logos-dataset/"))
        assertTrue(Regex("/[0-9a-f]{40}/").containsMatchIn(url))
        assertNull(brands.remoteLogoUrl("subaru"))             // bundled, no need to download
        val total = dir.resolve("logos").listFiles()!!.sumOf { it.length() }
        assertTrue("logos take $total bytes", total < 1_000_000)
    }
}
