package kg.forester.sg

import kg.forester.sg.ssm.KLineObd
import kg.forester.sg.ssm.KLinePort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
private fun sum(f: ByteArray) = f.fold(0) { s, x -> s + (x.toInt() and 0xFF) }.toByte()
private fun withSum(vararg x: Int) = b(*x).let { it + sum(it) }

/**
 * A K-line on made-up time with one or two units on it: the cable echoes every byte; the engine (0x10) and optionally
 * the transmission (0x18) answer after the init their protocol wants: KWP fast (25 ms low, 25 ms high, StartCommunication),
 * or the 5-baud address 0x33 (then 55 KB1 KB2, ~KB2 from us, CC back).
 */
private class FakeKLine(
    val fast: Boolean = true,
    val slowKeys: Pair<Int, Int>? = null,
    val echo: Boolean = true,
    val tcu: Boolean = false,
    val answer: (ByteArray) -> List<Pair<Int, ByteArray>> = { req -> defaultAnswer(req) },
) : KLinePort {
    var t = 0L
    var rate = 0
    var low = false
    private val rx = ArrayDeque<Pair<Long, Int>>()
    private val sent = java.io.ByteArrayOutputStream()
    /** Line level changes since it last went idle: (time, low). */
    private val edges = ArrayList<Pair<Long, Boolean>>()
    private var lastRelease = -1L
    private var lastPulse = 0L
    var proto = "" // "", "kwp", "9141"
    val requests = ArrayList<ByteArray>()
    private var waitKb2 = -1
    private var firstAt = 0L

    @Synchronized override fun now() = t
    @Synchronized override fun sleep(ms: Long) { if (ms > 0) t += ms }
    @Synchronized override fun setBaud(baud: Int) { rate = baud }

    @Synchronized override fun setBreak(on: Boolean) {
        if (on == low) return
        // the line idle (high) longer than 0x33 at 5 baud ever is (two bits): whatever came before was another try
        if (on && edges.isNotEmpty() && t - edges.last().first >= 450 && !edges.last().second) edges.clear()
        edges += t to on
        if (!on) {
            lastRelease = t
            lastPulse = if (edges.size >= 2) t - edges[edges.size - 2].first else 0
        }
        low = on
        if (!on) checkFiveBaud()
    }

    /** The address byte at 5 baud: 10 bits of 200 ms from the first edge, sampled mid-bit. */
    private fun checkFiveBaud() {
        val keys = slowKeys ?: return
        // the start bit began 1.8 s ago (9 bits), whatever pulses came before it
        val t0 = edges.firstOrNull { it.second && t - it.first in 1790..1810 }?.first ?: return
        fun lowAt(x: Long) = edges.lastOrNull { it.first <= x }?.second ?: false
        var v = 0
        if (!lowAt(t0 + 100)) return
        for (i in 0 until 8) if (!lowAt(t0 + 300 + i * 200L)) v = v or (1 shl i)
        if (v != 0x33) return
        val at = t + 200 + 120
        rx += at to 0x55; rx += at + 10 to keys.first; rx += at + 20 to keys.second
        waitKb2 = keys.second.inv() and 0xFF
        edges.clear()
    }

    @Synchronized override fun write(b: ByteArray) {
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (echo) rx += t to c
            if (rate != 10400) continue // the 360-baud wake-up byte
            if (waitKb2 >= 0) {
                if (c == waitKb2) { rx += t + 30 to 0xCC; proto = if (slowKeys?.second == 0x8F) "kwp" else "9141" }
                waitKb2 = -1
                continue
            }
            if (sent.size() == 0) firstAt = t
            sent.write(c)
        }
        frameIn()
    }

    private fun frameIn() {
        val s = sent.toByteArray()
        if (s.isEmpty()) return
        if (proto == "" && fast) {
            if (s.size < 5) return
            sent.reset()
            // StartCommunication 25 ms after a 25 ms low pulse
            if (s.contentEquals(withSum(0xC1, 0x33, 0xF1, 0x81)) && lastPulse in 24..26 && firstAt - lastRelease in 24..30) {
                proto = "kwp"
                reply(0x10, b(0xC1, 0xE9, 0x8F))
            }
            return
        }
        val n = if (proto == "kwp") 4 + (s[0].toInt() and 0x3F) else -1
        if (proto == "kwp" && s.size < n) return
        if (proto == "9141" && (s.size < 5 || sum(s.copyOf(s.size - 1)) != s.last())) return
        if (proto == "") { sent.reset(); return }
        sent.reset()
        val data = s.copyOfRange(3, s.size - 1)
        requests += data
        var at = t + 30
        for ((src, d) in answer(data)) {
            if (src == 0x18 && !tcu) continue
            at = reply(src, d, at) + 30
        }
    }

    private fun reply(src: Int, d: ByteArray, start: Long = t + 30): Long {
        val f = if (proto == "9141") withSum(0x48, 0x6B, src, *d.map { it.toInt() and 0xFF }.toIntArray())
        else withSum(0x80 or d.size, 0xF1, src, *d.map { it.toInt() and 0xFF }.toIntArray())
        var at = start
        for (x in f) { rx += at to (x.toInt() and 0xFF); at += 1 }
        return at
    }

    @Synchronized override fun read(timeoutMs: Long): Int {
        val next = rx.firstOrNull()
        if (next == null || next.first > t + timeoutMs) { t += timeoutMs; return -1 }
        rx.removeFirst()
        if (next.first > t) t = next.first
        return next.second
    }

    @Synchronized override fun clearInput() { rx.removeAll { it.first <= t } }

    companion object {
        fun defaultAnswer(req: ByteArray): List<Pair<Int, ByteArray>> {
            val r = req.map { it.toInt() and 0xFF }
            return when {
                r == listOf(0x01, 0x00) -> listOf(0x10 to b(0x41, 0x00, 0xBE, 0x1F, 0xB8, 0x10), 0x18 to b(0x41, 0x00, 0x80, 0x00, 0x00, 0x00))
                r == listOf(0x01, 0x0C) -> listOf(0x10 to b(0x41, 0x0C, 0x1A, 0xF8))
                r == listOf(0x03) -> listOf(0x10 to b(0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00), 0x18 to b(0x43, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00))
                else -> emptyList()
            }
        }
    }
}

class KLineObdTest {
    @Test fun framesAndChecksums() {
        assertArrayEquals(b(0xC2, 0x33, 0xF1, 0x01, 0x00, 0xE7), KLineObd.frame(KLineObd.Protocol.KWP_FAST, b(0x01, 0x00)))
        assertArrayEquals(b(0x68, 0x6A, 0xF1, 0x01, 0x00, 0xC4), KLineObd.frame(KLineObd.Protocol.ISO9141, b(0x01, 0x00)))
        assertArrayEquals(b(0xC1, 0x33, 0xF1, 0x81, 0x66), KLineObd.START_COMM)
        assertArrayEquals(b(0xC1, 0x33, 0xF1, 0x03, 0xE8), KLineObd.frame(KLineObd.Protocol.KWP_SLOW, b(0x03)))
    }

    @Test fun fiveBaudAddressBits() {
        // start bit low, 0x33 LSB first (1 1 0 0 1 1 0 0), stop bit high
        val bits = KLineObd.fiveBaudBits()
        assertEquals(listOf(false, true, true, false, false, true, true, false, false, true), bits.toList())
    }

    @Test fun parsesKwpAnswersOfSeveralUnits() {
        val burst = withSum(0x86, 0xF1, 0x10, 0x41, 0x00, 0xBE, 0x1F, 0xB8, 0x10) +
            withSum(0x86, 0xF1, 0x18, 0x41, 0x00, 0x80, 0x00, 0x00, 0x00) +
            // a length byte after the addresses (format 0x80, length 0)
            withSum(0x80, 0xF1, 0x10, 0x03, 0x43, 0x01, 0x33)
        val f = KLineObd.parseKwp(b(0x00, 0x55) + burst) // line noise in front
        assertEquals(listOf(0x10, 0x18, 0x10), f.map { it.first })
        assertArrayEquals(b(0x41, 0x00, 0xBE, 0x1F, 0xB8, 0x10), f[0].second)
        assertArrayEquals(b(0x43, 0x01, 0x33), f[2].second)
        // our own request heard back is no answer
        assertTrue(KLineObd.parseKwp(KLineObd.frame(KLineObd.Protocol.KWP_FAST, b(0x01, 0x00))).isEmpty())
        // a broken checksum is skipped
        val bad = withSum(0x82, 0xF1, 0x10, 0x41, 0x0C).also { it[it.size - 1] = 0 }
        assertTrue(KLineObd.parseKwp(bad).isEmpty())
    }

    @Test fun parses9141AnswersWithoutLength() {
        val a = withSum(0x48, 0x6B, 0x10, 0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13)
        val c = withSum(0x48, 0x6B, 0x18, 0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00)
        val f = KLineObd.parse9141(a + c)
        assertEquals(2, f.size)
        assertEquals(0x18, f[1].first)
        assertArrayEquals(b(0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00), f[1].second)
    }

    @Test fun fastInitThenRequests() {
        val line = FakeKLine(tcu = true)
        val link = KLineObd.fastInit(line)
        assertNotNull(link)
        link!!
        try {
            assertEquals(KLineObd.Protocol.KWP_FAST, link.protocol)
            assertEquals(0xE98F, link.keyBytes)
            // values: the engine's answer only, the echo taken off
            val r = link.request(b(0x01, 0x00))
            assertEquals(1, r!!.size)
            assertArrayEquals(b(0x41, 0x00, 0xBE, 0x1F, 0xB8, 0x10), r[0])
            assertArrayEquals(b(0x41, 0x0C, 0x1A, 0xF8), link.request(b(0x01, 0x0C))!![0])
            // trouble codes: every unit's
            assertEquals(2, link.request(b(0x03))!!.size)
            assertNull(link.request(b(0x01, 0x42)))
            assertEquals(listOf(listOf(1, 0), listOf(1, 0x0C), listOf(3), listOf(1, 0x42)), line.requests.map { it.map { x -> x.toInt() } })
        } finally { link.close() }
    }

    @Test fun fastInitByTheBaudTrick() {
        val line = FakeKLine(echo = false)
        // the 0x00 byte at 360 baud is the low pulse here: the fake sees no break, so it must not answer
        assertNull(KLineObd.fastInit(line, byBaud = true))
        assertEquals(10400, line.rate)
    }

    @Test fun slowInitIso9141() {
        val line = FakeKLine(fast = false, slowKeys = 0x08 to 0x08)
        assertNull(KLineObd.fastInit(line))
        val link = KLineObd.slowInit(line)
        assertNotNull(link)
        link!!
        try {
            assertEquals(KLineObd.Protocol.ISO9141, link.protocol)
            assertEquals("9141", line.proto)
            assertArrayEquals(b(0x41, 0x0C, 0x1A, 0xF8), link.request(b(0x01, 0x0C))!![0])
        } finally { link.close() }
    }

    @Test fun slowInitKwpAndTheOrderOfTries() {
        val line = FakeKLine(fast = false, slowKeys = 0xE9 to 0x8F, echo = false)
        val link = KLineObd.open(line)
        assertNotNull(link)
        link!!
        try {
            assertEquals(KLineObd.Protocol.KWP_SLOW, link.protocol)
            assertArrayEquals(b(0x41, 0x0C, 0x1A, 0xF8), link.request(b(0x01, 0x0C))!![0])
        } finally { link.close() }
    }

    @Test fun nothingOnTheLine() {
        val line = FakeKLine(fast = false)
        assertNull(KLineObd.open(line))
    }

    @Test fun keepAliveWhenIdle() {
        val line = FakeKLine()
        val link = KLineObd.fastInit(line)!!
        try {
            line.sleep(3000) // nothing asked for 3 s (made-up time)
            Thread.sleep(1300)
            assertTrue("01 00 sent to keep the session", line.requests.any { it.contentEquals(b(0x01, 0x00)) })
        } finally { link.close() }
    }
}
