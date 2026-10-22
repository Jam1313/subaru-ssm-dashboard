package kg.forester.sg

import kg.forester.sg.data.BoxBoard
import kg.forester.sg.flash.FirmwareCheck
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The SensorBox on an ESP32-S3: the shipped image, its check before an upload, the board's pins. */
class EspBoxTest {
    private val dir = File("../hardware/sensorbox")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun shippedImageIsBuiltFromTheSketch() {
        val manifest = org.json.JSONObject(File(dir, "bin/manifest.json").readText())
        assertEquals("sensorbox.ino changed: run hardware/sensorbox/build-bin.sh", sha(File(dir, "sensorbox.ino").readBytes()), manifest.getString("ino_sha256"))
        val img = File(dir, "bin/sensorbox-esp32s3.bin").readBytes()
        assertEquals(manifest.getJSONObject("files").getJSONObject("esp32s3").getString("sha256"), sha(img))
        assertEquals("sensorbox/bin/sensorbox-esp32s3.bin", kg.forester.sg.flash.FirmwareFlasher.ESP_ASSET)
    }

    @Test fun shippedImagePassesAsTheEspBox() {
        val c = FirmwareCheck.ofEsp(File(dir, "bin/sensorbox-esp32s3.bin").readBytes())
        assertTrue(c.ok)
        assertNotNull(c.bin)
        assertEquals("esp32s3", c.builtFor)
        assertEquals(kg.forester.sg.data.BOX_FIRMWARE, c.fw)
        assertTrue(c.lines.all { it.first })
    }

    @Test fun otherImagesAreToldApart() {
        // the colour screen's firmware: an image the chip starts from, but not the box
        val screen = File("../hardware/usbscreen/bin").listFiles { f -> f.name.endsWith(".bin") }!!.first().readBytes()
        val s = FirmwareCheck.ofEsp(screen)
        assertTrue(s.ok)
        assertTrue(s.lines.any { !it.first && it.second.contains("цветного экрана") })
        // too small, no image mark
        assertFalse(FirmwareCheck.ofEsp(ByteArray(1000)).ok)
        // an AVR .hex picked for the ESP32-S3
        assertFalse(FirmwareCheck.of(File(dir, "hex/sensorbox-nano.hex").readText(), BoxBoard.ESP32S3).ok)
    }

    @Test fun boardPinsMatchTheSketch() {
        val b = BoxBoard.ESP32S3
        val ino = File(dir, "sensorbox.ino").readText()
        val esp = ino.substringAfter("#elif defined(ARDUINO_ARCH_ESP32)").substringBefore("#elif")
        fun list(name: String) = Regex("$name\\[\\] = \\{([^}]*)\\}").find(esp)!!.groupValues[1].split(',').map { it.trim().trim('"') }
        assertEquals(list("OUT_PINS"), b.outPins)
        assertEquals(list("AIN").mapIndexed { i, p -> "A$i" to p }, b.analog.take(4).map { it to b.pinOf(it) })
        assertEquals(Regex("#define FREQ_PIN (\\d+)").find(esp)!!.groupValues[1], b.freqPin)
        // every pin the app wires to is printed on the board, the ADS1115's inputs aside
        val printed = (b.topRow + b.bottomRow).toSet()
        (b.outPins + b.freqPin + b.sck + b.miso + b.cs + b.sda + b.scl + b.mosi).forEach { assertTrue(it, it in printed) }
        b.analog.forEach { a -> assertTrue(a, b.onAds(a) || b.pinOf(a) in printed) }
        assertEquals(listOf("A4", "A5", "A6", "A7"), b.analog.filter { b.onAds(it) })
        assertTrue(b.lowVolt)
        assertEquals("ESP32-S3", b.short)
    }

    @Test fun boxInfoFromTheEsp() {
        val i = kg.forester.sg.data.SensorBox.parseInfo("I SENSORBOX 2 board=esp32s3 id=1A2B fw=5 rate=20 disp=none ch=A0,A1,A2,A3,A4,A5,A6,A7,TC0,F0 out=D15", "USB 303A:1001")!!
        assertEquals(BoxBoard.ESP32S3, BoxBoard.of(i.board))
        assertTrue("A7" in i.inputs)
    }
}
