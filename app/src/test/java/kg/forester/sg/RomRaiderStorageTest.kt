package kg.forester.sg

import kg.forester.sg.ssm.RomRaider
import kg.forester.sg.ssm.RomRaiderXml
import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How a parameter's bytes become its raw number: storage type, width and sign as RomRaider's convertor takes them. */
class RomRaiderStorageTest {
    private val base by lazy { RomRaider.parseBase(File("src/main/assets/romraider_ssm.json").readText()) }

    private fun ext(id: String, rom: String) = base.second.getValue(rom).single { it.id == id }

    private fun floatBytes(v: Float) = java.lang.Float.floatToIntBits(v).let { b -> ByteArray(4) { k -> (b shr (24 - 8 * k)).toByte() } }

    private fun decode(p: RrParam, bytes: ByteArray) = RomRaider.decode(listOf(p), p.addresses, bytes, listOf(p)).getValue(p.id)

    /**
     * E36 Target Boost (4-byte): RomRaider v370 gives "float" on every conversion but the first (kPa absolute), and
     * that one was read as a 32-bit integer: 1520 mmHg came out as ~1.5e8 kPa and the target boost card and the
     * boost rules got nonsense.
     */
    @Test fun e36TargetBoostIsAFloatOnItsKpaConversionToo() {
        val p = ext("E36", "134291236F")
        assertEquals("kPa absolute", p.units)
        assertEquals("float", p.storage)
        assertEquals(1520 * 0.1333224, decode(p, floatBytes(1520f)), 1e-3)
    }

    /** E145 Gear lists its float without a length: four bytes are read, not one. */
    @Test fun e145GearFloatReadsFourBytes() {
        val p = ext("E145", "6144D87207")
        assertEquals(1, p.len)
        assertEquals(4, p.addresses.size)
        assertEquals(3.0, decode(p, floatBytes(3f)), 1e-9)
    }

    /** RomRaider takes intN signed at the width read: an int16 at one address 0xFF is −1, not 255. */
    @Test fun signedAtTheWidthRead() {
        fun p(storage: String, len: Int) = RrParam("T", RrParam.Kind.STD, 1, "T", "T", "", 0x8D, len, -1, "", "0", storage,
            RrExpr.parse("x"), emptyList(), emptyMap())
        assertEquals(-1.0, p("int16", 1).raw(byteArrayOf(0xFF.toByte())), 0.0)
        assertEquals(-2.0, p("int16", 2).raw(byteArrayOf(0xFF.toByte(), 0xFE.toByte())), 0.0)
        assertEquals(-1.0, p("int8", 1).raw(byteArrayOf(0xFF.toByte())), 0.0)
        assertEquals(255.0, p("", 1).raw(byteArrayOf(0xFF.toByte())), 0.0)
        assertEquals(65534.0, p("", 2).raw(byteArrayOf(0xFF.toByte(), 0xFE.toByte())), 0.0)
    }

    /** P148 Steering Angle Sensor lists two addresses: one int16, high byte at 0x8D, low at 0x15A, as RomRaider reads it. */
    @Test fun severalAddressesAreOneValue() {
        val p148 = base.first.single { it.id == "P148" }
        assertEquals(listOf(0x8D, 0x15A), p148.addresses.toList())
        assertEquals(-1.0, decode(p148, byteArrayOf(0xFF.toByte(), 0xFF.toByte())), 0.0)
        assertEquals(-450.0, decode(p148, byteArrayOf(0xFE.toByte(), 0x3E.toByte())), 0.0)
        assertEquals(300.0, decode(p148, byteArrayOf(0x01, 0x2C)), 0.0)
        // both bytes go into the read list
        assertEquals(listOf(0x8D, 0x15A), RomRaider.readList(listOf(p148), base.first).toList())
        // the driver's own logger.xml the same way
        val own = RomRaiderXml.parse("""
            <parameter id="P148" name="Steering Angle Sensor" ecubyteindex="52" ecubit="3" target="2">
              <address>0x00008D</address>
              <address>0x00015A</address>
              <conversions><conversion units="degrees" storagetype="int16" expr="x" format="0" /></conversions>
            </parameter>""".trimIndent()).params.single()
        assertEquals(listOf(0x15A), own.more)
        assertTrue(RomRaiderXml.same(own, p148))
        assertEquals(-450.0, decode(own, byteArrayOf(0xFE.toByte(), 0x3E.toByte())), 0.0)
    }

    /** The driver's own logger.xml goes through the same choice. */
    @Test fun ownXmlTakesTheStorageOfTheOtherConversions() {
        val d = RomRaiderXml.parse("""
            <ecuparam id="E36" name="Target Boost (4-byte)*" target="1">
              <ecu id="134291236F"><address length="4">0xFF4BA8</address></ecu>
              <conversions>
                <conversion units="kPa absolute" expr="x*0.1333224" format="0.00" />
                <conversion units="bar absolute" storagetype="float" expr="x*0.001333224" format="0.000" />
              </conversions>
            </ecuparam>
        """.trimIndent())
        val p = d.ext.single().param
        assertEquals("float", p.storage)
        assertTrue(p.units.startsWith("kPa"))
    }
}
