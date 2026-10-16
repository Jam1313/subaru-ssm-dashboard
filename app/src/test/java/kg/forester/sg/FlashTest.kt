package kg.forester.sg

import kg.forester.sg.flash.Avr109
import kg.forester.sg.flash.ByteLink
import kg.forester.sg.flash.FlashTimeout
import kg.forester.sg.flash.HexImage
import kg.forester.sg.flash.IntelHex
import kg.forester.sg.flash.Proto
import kg.forester.sg.flash.Stk500v1
import kg.forester.sg.flash.Stk500v2
import kg.forester.sg.flash.Upload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The uploads against simulated bootloaders that answer byte for byte like the real ones (docs/knowledge/arduino-flashing.md). */
class FlashTest {
    /** A simulated board: what the host writes goes through [onByte], replies queue up for [byte]. */
    private abstract class Sim(size: Int) : ByteLink {
        val flash = ByteArray(size) { 0xFF.toByte() }
        val out = ArrayDeque<Int>()
        override fun write(b: ByteArray) = b.forEach { onByte(it.toInt() and 0xFF) }
        override fun byte(timeoutMs: Long) = out.removeFirstOrNull() ?: throw FlashTimeout()
        override fun drain(quietMs: Long) = out.clear()
        abstract fun onByte(b: Int)
        fun reply(vararg b: Int) = b.forEach { out.addLast(it and 0xFF) }
    }

    /** Optiboot: STK500v1 commands ending in 0x20, 128-byte pages, word addresses. */
    private class Optiboot : Sim(32768) {
        private val buf = ArrayList<Int>()
        private var addr = 0
        override fun onByte(b: Int) {
            buf += b
            val need = when (buf[0]) {
                0x30, 0x50, 0x51, 0x75 -> 2
                0x41 -> 3
                0x42 -> 22
                0x55 -> 4
                0x64 -> if (buf.size >= 3) 5 + ((buf[1] shl 8) or buf[2]) else Int.MAX_VALUE
                0x74 -> 5
                else -> { buf.clear(); return }
            }
            if (buf.size < need) return
            val c = buf.toList(); buf.clear()
            if (c.last() != 0x20) { reply(0x15); return }
            reply(0x14)
            when (c[0]) {
                0x41 -> reply(if (c[1] == 0x81) 4 else 4)
                0x75 -> reply(0x1E, 0x95, 0x0F)
                0x55 -> addr = (c[1] or (c[2] shl 8)) * 2
                0x64 -> { val n = (c[1] shl 8) or c[2]; for (k in 0 until n) flash[addr + k] = c[4 + k].toByte() }
                0x74 -> { val n = (c[1] shl 8) or c[2]; for (k in 0 until n) reply(flash[addr + k].toInt()) }
            }
            reply(0x10)
        }
    }

    /** Caterina: single-letter commands, CR replies, big-endian word addresses, auto-increment. */
    private class Caterina : Sim(32768) {
        private val buf = ArrayList<Int>()
        private var addr = 0
        override fun onByte(b: Int) {
            buf += b
            val need = when (buf[0]) {
                0x1B, 0x53, 0x62, 0x50, 0x73, 0x4C, 0x45 -> 1
                0x41 -> 3
                0x42 -> if (buf.size >= 3) 4 + ((buf[1] shl 8) or buf[2]) else Int.MAX_VALUE
                0x67 -> 4
                else -> { buf.clear(); return }
            }
            if (buf.size < need) return
            val c = buf.toList(); buf.clear()
            when (c[0]) {
                0x53 -> "CATERIN".forEach { reply(it.code) }
                0x62 -> reply(0x59, 0x00, 0x80)
                0x50, 0x4C, 0x45 -> reply(0x0D)
                0x73 -> reply(0x87, 0x95, 0x1E)
                0x41 -> { addr = ((c[1] shl 8) or c[2]) * 2; reply(0x0D) }
                0x42 -> { val n = (c[1] shl 8) or c[2]; for (k in 0 until n) flash[addr + k] = c[4 + k].toByte(); addr += n; reply(0x0D) }
                0x67 -> { val n = (c[1] shl 8) or c[2]; for (k in 0 until n) reply(flash[addr + k].toInt()); addr += n }
            }
        }
    }

    /** The Mega's bootloader, with its quirk: each program command erases the page at its own counter, not at the address. */
    private class MegaBoot : Sim(262144) {
        private val buf = ArrayList<Int>()
        private var addr = 0
        private var eraseAt = 0
        override fun onByte(b: Int) {
            buf += b
            if (buf[0] != 0x1B) { buf.clear(); return }
            if (buf.size < 5) return
            val len = (buf[2] shl 8) or buf[3]
            if (buf.size < 6 + len) return
            val f = buf.toList(); buf.clear()
            val body = f.subList(5, 5 + len)
            val ck = f.dropLast(1).fold(0) { x, y -> x xor y }
            if (ck != f.last()) return
            val r = when (body[0]) {
                0x01 -> listOf(0x01, 0x00, 0x08) + "AVRISP_2".map { it.code }
                0x10, 0x11 -> listOf(body[0], 0x00)
                0x1B -> listOf(0x1B, 0x00, listOf(0x1E, 0x98, 0x01)[body[4]], 0x00)
                0x06 -> { addr = (((body[1] and 0x7F) shl 24) or (body[2] shl 16) or (body[3] shl 8) or body[4]) * 2; listOf(0x06, 0x00) }
                0x13 -> {
                    val n = (body[1] shl 8) or body[2]
                    for (k in 0 until 256) flash[eraseAt + k] = 0xFF.toByte()
                    eraseAt += 256
                    // writing only clears bits, as on the chip: an unerased page keeps the old ones
                    for (k in 0 until n) flash[addr + k] = (flash[addr + k].toInt() and body[10 + k]).toByte()
                    addr += n
                    listOf(0x13, 0x00)
                }
                0x14 -> { val n = (body[1] shl 8) or body[2]; listOf(0x14, 0x00) + List(n) { flash[addr + it].toInt() and 0xFF }.also { addr += n } + 0x00 }
                else -> listOf(body[0], 0xC9)
            }
            val head = listOf(0x1B, f[1], r.size ushr 8, r.size and 0xFF, 0x0E)
            reply(*(head + r).toIntArray())
            reply((head + r).fold(0) { x, y -> x xor y })
        }
    }

    private fun image(size: Int, seed: Int): HexImage {
        val r = java.util.Random(seed.toLong())
        val b = ByteArray(32768) { 0xFF.toByte() }
        for (i in 0 until size) b[i] = r.nextInt(256).toByte()
        return HexImage(b, size)
    }

    private fun assertFlashed(img: HexImage, flash: ByteArray) =
        assertArrayEquals(img.bytes.copyOf(img.end), flash.copyOf(img.end))

    @Test fun optibootTakesTheWholeSketch() {
        val sim = Optiboot()
        val p = Stk500v1(sim) {}
        assertTrue(p.sync())
        assertEquals("Optiboot 4.4", p.connect())
        assertArrayEquals(byteArrayOf(0x1E, 0x95.toByte(), 0x0F), p.signature())
        val img = image(6582, 1)
        var last = 0f
        Upload.run(p, img, 128, Proto.STK500V1) { f, _ -> assertTrue(f >= last); last = f }
        assertFlashed(img, sim.flash)
    }

    @Test fun caterinaWritesPageZeroLast() {
        val sim = Caterina()
        val p = Avr109(sim)
        assertEquals("Caterina", p.connect())
        assertArrayEquals(byteArrayOf(0x1E, 0x95.toByte(), 0x87.toByte()), p.signature())
        val img = image(8940, 2)
        Upload.run(p, img, 128, Proto.AVR109) { _, _ -> }
        assertFlashed(img, sim.flash)
    }

    @Test fun megaPagesGoInOrderPastItsSequentialErase() {
        val sim = MegaBoot()
        // last session's leftovers: the sequential erase must clear them before each page is written
        for (i in 0 until 9000) sim.flash[i] = 0x5A
        val p = Stk500v2(sim)
        assertTrue(p.sync())
        p.connect()
        assertArrayEquals(byteArrayOf(0x1E, 0x98.toByte(), 0x01), p.signature())
        val img = image(7904, 3)
        Upload.run(p, img, 256, Proto.STK500V2) { _, _ -> }
        assertFlashed(img, sim.flash)
    }

    /** The shipped .hex files: they parse within each board's sketch area and match the sketch they were built from. */
    @Test fun shippedHexFilesAreCurrent() {
        val dir = File("../hardware/sensorbox")
        val manifest = org.json.JSONObject(File(dir, "hex/manifest.json").readText())
        val sha = MessageDigest.getInstance("SHA-256").digest(File(dir, "sensorbox.ino").readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("sensorbox.ino changed: run hardware/sensorbox/build-hex.sh", manifest.getString("ino_sha256"), sha)
        for ((b, max) in listOf("nano" to 30720, "uno" to 32256, "32u4" to 28672, "mega" to 253952)) {
            val img = IntelHex.parse(File(dir, "hex/sensorbox-$b.hex").readText(), max)
            assertEquals(b, manifest.getJSONObject("files").getJSONObject(b).getInt("bytes"), img.end)
        }
        // the Mega bootloader's debug monitor wakes on "!!!": the image must not contain it
        val mega = IntelHex.parse(File(dir, "hex/sensorbox-mega.hex").readText(), 253952)
        assertTrue((0 until mega.end - 2).none { mega.bytes[it] == '!'.code.toByte() && mega.bytes[it + 1] == '!'.code.toByte() && mega.bytes[it + 2] == '!'.code.toByte() })
    }

    @Test fun appKnowsTheSketchsFirmwareVersion() {
        val ino = File("../hardware/sensorbox/sensorbox.ino").readText()
        val v = Regex("#define FW_VERSION (\\d+)").find(ino)!!.groupValues[1].toInt()
        assertEquals("BOX_FIRMWARE in SensorBox.kt", v, kg.forester.sg.data.BOX_FIRMWARE)
    }

    @Test fun hexParserRefusesTheBootloaderArea() {
        val ok = ":0400000001020304F2\n:00000001FF\n"
        assertEquals(4, IntelHex.parse(ok, 32768).end)
        // a record at 0x7E00 (Optiboot) against a 0x7800 limit
        val bad = ":047E00000102030474\n:00000001FF\n"
        assertTrue(runCatching { IntelHex.parse(bad, 0x7800) }.isFailure)
        assertTrue(runCatching { IntelHex.parse(":0400000001020304F3\n:00000001FF\n", 32768) }.isFailure) // checksum
    }

    /** «Залить свою прошивку»: the shipped files pass as the SensorBox of their board; others are told apart. */
    @Test fun ownFirmwareIsCheckedBeforeUpload() {
        val dir = File("../hardware/sensorbox/hex")
        val nano = kg.forester.sg.flash.FirmwareCheck.of(File(dir, "sensorbox-nano.hex").readText(), kg.forester.sg.data.BoxBoard.NANO)
        assertTrue(nano.ok)
        assertEquals("nano", nano.builtFor)
        assertEquals(kg.forester.sg.data.BOX_FIRMWARE, nano.fw)
        assertTrue(nano.lines.all { it.first })
        // the Uno's file on a Nano: it fits, but it is told to be for another board
        val uno = kg.forester.sg.flash.FirmwareCheck.of(File(dir, "sensorbox-uno.hex").readText(), kg.forester.sg.data.BoxBoard.NANO)
        assertTrue(uno.ok)
        assertTrue(uno.lines.any { !it.first && it.second.contains("Uno") })
        // the Mega's file does not fit a Nano
        assertTrue(!kg.forester.sg.flash.FirmwareCheck.of(File(dir, "sensorbox-mega.hex").readText(), kg.forester.sg.data.BoxBoard.NANO).ok)
        // someone else's sketch: uploadable, with a warning
        val blink = kg.forester.sg.flash.FirmwareCheck.of(":0400000001020304F2\n:00000001FF\n", kg.forester.sg.data.BoxBoard.PROMICRO)
        assertTrue(blink.ok)
        assertEquals(null, blink.builtFor)
        assertTrue(blink.lines.any { !it.first && it.second.contains("не прошивка SensorBox") })
        // not a hex at all
        assertTrue(!kg.forester.sg.flash.FirmwareCheck.of("void setup() {}", kg.forester.sg.data.BoxBoard.NANO).ok)
    }
}
