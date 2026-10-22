package kg.forester.sg

import kg.forester.sg.data.CustomParam
import kg.forester.sg.ssm.KLineTransport
import kg.forester.sg.ssm.RomRaider
import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.SsmClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A K-line with an ECU on it: echoes every request, answers A8 00 once and A8 01 over and over until the next one. */
private class FakeLine(private val memory: (Int) -> Int) : KLineTransport() {
    override val name = "fake"
    @Volatile private var streamer: Thread? = null

    override fun writeRaw(packet: ByteArray) {
        streamer?.interrupt(); streamer?.join()
        streamer = null
        onBytes(packet) // loopback
        val body = packet.copyOfRange(4, packet.size - 1)
        if (body[0] != 0xA8.toByte() || body.size < 5) return // the stop request (A8 00 alone) gets no answer
        val addrs = IntArray((body.size - 2) / 3) { k ->
            ((body[2 + k * 3].toInt() and 0xFF) shl 16) or ((body[3 + k * 3].toInt() and 0xFF) shl 8) or (body[4 + k * 3].toInt() and 0xFF)
        }
        fun answer() = frame(byteArrayOf(0xE8.toByte()) + ByteArray(addrs.size) { memory(addrs[it]).toByte() })
        if (body[1] == 0x00.toByte()) { onBytes(answer()); return }
        streamer = Thread {
            try { while (true) { onBytes(answer()); Thread.sleep(20) } } catch (_: InterruptedException) {}
        }.also { it.start() }
    }

    private fun frame(body: ByteArray): ByteArray {
        val f = byteArrayOf(0x80.toByte(), 0xF0.toByte(), SsmClient.ECU.toByte(), body.size.toByte()) + body + 0
        f[f.size - 1] = checksum(f, f.size - 1)
        return f
    }

    override fun close() { streamer?.interrupt() }
}

class FastPollTest {
    @Test fun streamDeliversValuesAndStops() {
        var rpm = 0x10
        val client = SsmClient(FakeLine { a -> if (a == 0x0E) rpm else a })
        val got = CopyOnWriteArrayList<ByteArray>()
        assertTrue(client.startStream(intArrayOf(0x08, 0x0E)) { got += it })
        Thread.sleep(200)
        assertTrue("several answers to one request", got.size >= 3)
        assertArrayEquals(byteArrayOf(0x08, 0x10), got.last())
        rpm = 0x20
        Thread.sleep(100)
        assertArrayEquals(byteArrayOf(0x08, 0x20), got.last())
        client.stopStream()
        assertFalse(client.streaming)
        val n = got.size
        Thread.sleep(100)
        assertEquals("nothing after the stop", n, got.size)
        // a plain request afterwards gets its own answer, not a leftover of the stream
        assertArrayEquals(byteArrayOf(0x12), client.read(intArrayOf(0x12)))
        client.close()
    }

    @Test fun anotherRequestStopsTheStream() {
        val client = SsmClient(FakeLine { it })
        assertTrue(client.startStream(intArrayOf(0x08)) { })
        Thread.sleep(100)
        assertArrayEquals(byteArrayOf(0x15, 0x20), client.read(intArrayOf(0x15, 0x20)))
        assertFalse(client.streaming)
        client.close()
    }

    @Test fun formulaOverOtherValues() {
        fun std(id: String, addr: Int, len: Int, f: String) = RrParam(id, RrParam.Kind.STD, 1, id, id, "", addr, len, -1, "", "0.00", "",
            RrExpr.parse(f), emptyList(), emptyMap())
        val rpm = std("P8", 0x0E, 2, "x/4")
        val maf = std("P12", 0x13, 2, "x/100")
        // g/rev from MAF and rpm, no address of its own
        val load = CustomParam("U1", "Нагрузка", -1, 1, "", "P12*60/P8", "г/об", 2, "").toParam()
        val all = listOf(rpm, maf, load)
        val read = RomRaider.readList(listOf(load), all)
        assertEquals(setOf(0x0E, 0x0F, 0x13, 0x14), read.toSet())
        // 3000 rpm (12000 = 0x2EE0), 30.00 g/s (3000 = 0x0BB8)
        val mem = mapOf(0x0E to 0x2E, 0x0F to 0xE0, 0x13 to 0x0B, 0x14 to 0xB8)
        val v = RomRaider.decode(listOf(load), read, ByteArray(read.size) { mem.getValue(read[it]).toByte() }, all)
        assertEquals(0.6, v.getValue("U1"), 1e-9)
    }
}
