package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The bare K-line of a KKL cable as the ISO inits need it: the bit rate, the line held low, bytes in and out.
 * The clock and the sleep are here too, so the tests can run the inits on made-up time.
 */
interface KLinePort {
    fun setBaud(baud: Int)
    /** True holds the line low (the serial chip's break: TxD at 0, the cable's transistor pulls K to ground). */
    fun setBreak(on: Boolean)
    fun write(b: ByteArray)
    /** The next byte off the line, or -1 after [timeoutMs] of silence. */
    fun read(timeoutMs: Long): Int
    fun clearInput()
    fun now(): Long = System.nanoTime() / 1_000_000
    fun sleep(ms: Long) { if (ms > 0) Thread.sleep(ms) }
    fun close() {}
}

/**
 * A USB KKL cable (FTDI, CH340, PL2303, CP210x) or an OpenPort 1.3 as a [KLinePort]. None of these chips can run at
 * 5 baud, but all of them can hold the line low on request (break), and that is all the inits need: the 5-baud address
 * goes out as timed breaks, 200 ms a bit, the fast init's 25 ms pulse as one break or as a 0x00 byte at 360 baud
 * (start bit and eight zeros = 25 ms, timed by the chip itself).
 */
class UsbKLinePort(private val port: UsbSerialPort) : KLinePort {
    private val rx = LinkedBlockingQueue<Byte>()
    private var io: SerialInputOutputManager? = null
    @Volatile private var ioError: Exception? = null
    private var latency = -1

    fun open() {
        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching { port.setBreak(false) }
        // FTDI holds received bytes up to 16 ms before passing them on: too late for the init's 25–50 ms answers
        (port as? FtdiSerialDriver.FtdiSerialPort)?.let { f ->
            runCatching { latency = f.latencyTimer; f.latencyTimer = 1 }
        }
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) { for (b in data) rx.offer(b) }
            override fun onRunError(e: Exception) { ioError = e }
        }).also { it.readTimeout = 0; it.start() }
    }

    override fun setBaud(baud: Int) = port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    override fun setBreak(on: Boolean) = port.setBreak(on)
    override fun write(b: ByteArray) {
        ioError?.let { throw IOException(it) }
        port.write(b, 500)
    }

    override fun read(timeoutMs: Long): Int {
        ioError?.let { throw IOException(it) }
        return rx.poll(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)?.toInt()?.and(0xFF) ?: -1
    }

    override fun clearInput() = rx.clear()

    /** Stops reading and gives the chip back as it was (the SSM session after this one runs at 4800 on it); the port itself stays open. */
    override fun close() {
        runCatching { io?.stop() }
        runCatching { port.setBreak(false) }
        if (latency > 0) (port as? FtdiSerialDriver.FtdiSerialPort)?.let { runCatching { it.latencyTimer = latency } }
    }
}

/**
 * Standard OBD-II on the K-line without an ELM327, the way the scanners of the 2000s did it on a plain KKL cable:
 * ISO 14230-4 (KWP2000, fast or 5-baud init) and ISO 9141-2 (5-baud init), 10400 baud. Cars from before CAN that are
 * not Subaru (or a Subaru whose ECU does not answer SSM) are read with it.
 *
 * Framing: ISO 9141-2 requests go out as 68 6A F1 <data> <sum>, answers come back as 48 6B <unit> <data> <sum>;
 * KWP as C<n> 33 F1 <data> <sum> to the functional address 33, answers 8<n> F1 <unit> <data> <sum>. Every unit that
 * has an answer sends it, one after another: the engine's values are kept, the trouble codes of all of them.
 */
class KLineObd private constructor(private val p: KLinePort, val protocol: Protocol, val keyBytes: Int) : ObdLink {
    enum class Protocol(val title: String) { ISO9141("ISO 9141-2"), KWP_SLOW("KWP2000, 5 бод"), KWP_FAST("KWP2000, быстрый") }

    override val name get() = "KKL, OBD-II (${protocol.title})"
    override val isCan = false
    private val kwp get() = protocol != Protocol.ISO9141
    /** The cable sends every byte back (a single-wire bus): known from the init, dropped after each request. */
    private var echo = true
    /** The engine unit (0x10 by J1979, else the first that answered): its answers are the values. */
    @Volatile private var main = -1
    @Volatile private var lastMs = p.now()
    @Volatile private var closed = false
    private val lock = Object()
    private var keeper: Thread? = null

    /** Sends a keep-alive (01 00) when nothing else was asked for [KEEPALIVE_MS]: past P3max (5 s) the units drop the session. */
    private fun startKeepAlive() {
        keeper = Thread {
            try {
                while (!closed) {
                    Thread.sleep(500)
                    if (!closed && p.now() - lastMs > KEEPALIVE_MS) synchronized(lock) {
                        if (!closed && p.now() - lastMs > KEEPALIVE_MS) runCatching { exchange(byteArrayOf(0x01, 0x00)) }
                    }
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.name = "kline-keepalive"; it.start() }
    }

    override fun request(req: ByteArray): List<ByteArray>? = synchronized(lock) {
        if (closed) return null
        val frames = exchange(req) ?: return null
        val sid = (req[0] + 0x40).toByte()
        val ok = frames.filter { it.second.isNotEmpty() && it.second[0] == sid }
        if (ok.isEmpty()) return null
        if (main < 0) main = ok.firstOrNull { it.first == 0x10 }?.first ?: ok.first().first
        // trouble codes (03, 07) and their clearing (04): every unit's; anything else: the engine's
        val mode = req[0].toInt() and 0xFF
        val mine = if (mode == 0x03 || mode == 0x07 || mode == 0x04) ok else ok.filter { it.first == main }.ifEmpty { ok }
        mine.map { it.second }
    }

    /** One request and every answer to it: (unit address, data from the response code on). Null when nobody answered. */
    private fun exchange(req: ByteArray): List<Pair<Int, ByteArray>>? {
        if (req.isEmpty() || req.size > 7) return null
        // P3min: 55 ms of quiet after the last answer before a new request
        p.sleep(P3_MIN_MS - (p.now() - lastMs))
        val frame = frame(protocol, req)
        p.clearInput()
        SsmTrace.log("K TX", frame)
        send(frame)
        val got = receive()
        lastMs = p.now()
        if (got.isEmpty()) { SsmTrace.log("K timeout"); return null }
        SsmTrace.log("K RX", got)
        val frames = if (kwp) parseKwp(got) else parse9141(got)
        // 7F xx 78: "busy, the answer follows" (up to P2*max, 5 s); any other 7F is a refusal
        if (frames.any { it.second.size >= 3 && it.second[0] == 0x7F.toByte() && it.second[2] == 0x78.toByte() } &&
            frames.none { it.second.isNotEmpty() && it.second[0] == (req[0] + 0x40).toByte() }) {
            val late = receive(firstMs = 5000)
            lastMs = p.now()
            if (late.isNotEmpty()) return (if (kwp) parseKwp(late) else parse9141(late)).ifEmpty { null }
        }
        return frames.ifEmpty { null }
    }

    /** The bytes one by one with P4 (5 ms) between them, then the cable's echo taken off the line. */
    private fun send(frame: ByteArray) {
        for (i in frame.indices) {
            p.write(byteArrayOf(frame[i]))
            if (i < frame.size - 1) p.sleep(P4_MS)
        }
        if (!echo) return
        for (b in frame) {
            val c = p.read(ECHO_MS)
            if (c < 0) { echo = false; SsmTrace.log("K: no echo, the cable has none"); return }
            if (c != (b.toInt() and 0xFF)) { SsmTrace.log("K: echo garbled (collision?)"); return }
        }
    }

    /**
     * Everything the units send until the line stays quiet: the first byte within [firstMs] (P2max 50 ms plus USB
     * delays), then answers keep coming while the next byte follows within [GAP_MS] (P2max between two units' answers).
     */
    private fun receive(firstMs: Long = FIRST_MS): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var c = p.read(firstMs)
        while (c >= 0) {
            out.write(c)
            c = p.read(GAP_MS)
        }
        return out.toByteArray()
    }

    override fun close() {
        closed = true
        keeper?.interrupt()
        p.close()
    }

    companion object {
        const val ADDR = 0x33
        const val TESTER = 0xF1
        private const val P3_MIN_MS = 60L
        private const val P4_MS = 5L
        private const val ECHO_MS = 60L
        private const val FIRST_MS = 400L
        private const val GAP_MS = 70L
        private const val KEEPALIVE_MS = 2500L
        /** W5/TIdle: the line high this long before any init. */
        private const val IDLE_MS = 300L

        fun checksum(b: ByteArray, len: Int = b.size): Byte {
            var s = 0
            for (i in 0 until len) s += b[i].toInt() and 0xFF
            return s.toByte()
        }

        /** A request in the protocol's framing, checksum included. */
        fun frame(protocol: Protocol, data: ByteArray): ByteArray {
            val head = if (protocol == Protocol.ISO9141) byteArrayOf(0x68, 0x6A, TESTER.toByte())
            else byteArrayOf((0xC0 or data.size).toByte(), ADDR.toByte(), TESTER.toByte())
            val f = head + data + 0
            f[f.size - 1] = checksum(f, f.size - 1)
            return f
        }

        /** KWP StartCommunication to the OBD functional address: C1 33 F1 81 66. */
        val START_COMM: ByteArray get() = frame(Protocol.KWP_FAST, byteArrayOf(0x81.toByte()))

        /**
         * KWP answers out of a burst: format byte (bits 7–6 addressing, 5–0 length, 0 = a length byte follows the
         * addresses), target and source when addressed, data, sum. Bytes that do not add up are skipped one at a time.
         */
        fun parseKwp(b: ByteArray): List<Pair<Int, ByteArray>> {
            val out = ArrayList<Pair<Int, ByteArray>>()
            var i = 0
            while (i < b.size) {
                val fmt = b[i].toInt() and 0xFF
                val addressed = fmt and 0xC0 != 0
                var pos = i + 1 + if (addressed) 2 else 0
                var len = fmt and 0x3F
                if (len == 0) { if (pos >= b.size) break; len = b[pos].toInt() and 0xFF; pos++ }
                val end = pos + len // the checksum's index
                if (len == 0 || end >= b.size) { i++; continue }
                if (checksum(b.copyOfRange(i, end)) != b[end]) { i++; continue }
                // our own request heard back (a cable that echoes but the echo was not taken): not an answer
                val src = if (addressed) b[i + 2].toInt() and 0xFF else 0
                val tgt = if (addressed) b[i + 1].toInt() and 0xFF else TESTER
                if (tgt == TESTER || !addressed) out += src to b.copyOfRange(pos, end)
                i = end + 1
            }
            return out
        }

        /**
         * ISO 9141-2 answers out of a burst: 48 6B <unit> <1–7 data bytes> <sum>. The frame carries no length, so the
         * end is where the sum matches and the next answer (or nothing) begins.
         */
        fun parse9141(b: ByteArray): List<Pair<Int, ByteArray>> {
            val out = ArrayList<Pair<Int, ByteArray>>()
            var i = 0
            while (i + 4 < b.size) {
                if (b[i] != 0x48.toByte() || b[i + 1] != 0x6B.toByte()) { i++; continue }
                var found = -1
                var s = (0x48 + 0x6B + (b[i + 2].toInt() and 0xFF))
                for (j in i + 3 until minOf(b.size, i + 12)) {
                    if (j >= i + 4 && s.toByte() == b[j] && (j + 1 == b.size || (j + 2 < b.size && b[j + 1] == 0x48.toByte() && b[j + 2] == 0x6B.toByte()))) {
                        found = j; break
                    }
                    s += b[j].toInt() and 0xFF
                }
                if (found < 0) { i++; continue }
                out += (b[i + 2].toInt() and 0xFF) to b.copyOfRange(i + 3, found)
                i = found + 1
            }
            return out
        }

        /** Sleeps until [t] on the port's clock. */
        private fun KLinePort.until(t: Long) { sleep(t - now()) }

        /**
         * KWP fast init: 25 ms low, 25 ms high, StartCommunication; the answer (C1 KB1 KB2) comes within P2max.
         * [byBaud]: the low pulse as a 0x00 byte at 360 baud instead of a timed break (for a chip whose break is slow to react).
         */
        fun fastInit(p: KLinePort, byBaud: Boolean = false): KLineObd? {
            p.setBaud(10400)
            p.setBreak(false)
            p.sleep(IDLE_MS)
            val t0: Long
            if (byBaud) {
                p.setBaud(360)
                p.write(byteArrayOf(0))
                t0 = p.now()
                p.until(t0 + 29) // the byte with its stop bit is out after 27.8 ms
                p.setBaud(10400)
            } else {
                t0 = p.now()
                p.setBreak(true)
                p.until(t0 + 25)
                p.setBreak(false)
            }
            p.until(t0 + 50)
            p.clearInput()
            val sc = START_COMM
            for (i in sc.indices) { p.write(byteArrayOf(sc[i])); if (i < sc.size - 1) p.sleep(P4_MS) }
            val got = ArrayList<Byte>()
            var c = p.read(FIRST_MS)
            while (c >= 0) { got += c.toByte(); c = p.read(GAP_MS) }
            val bytes = got.toByteArray()
            // the echo (if any) is the request itself: it adds up but is addressed to 33, so the parser leaves it out
            val echo = bytes.size >= sc.size && bytes.copyOf(sc.size).contentEquals(sc)
            val ans = parseKwp(bytes).firstOrNull { it.second.isNotEmpty() && it.second[0] == 0xC1.toByte() }
            SsmTrace.log("KWP fast init${if (byBaud) " (360 бод)" else ""}: ${bytes.toHex(" ").ifEmpty { "нет ответа" }}")
            if (ans == null) return null
            val d = ans.second
            val kb = if (d.size >= 3) ((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF) else 0
            return KLineObd(p, Protocol.KWP_FAST, kb).also { it.echo = echo; it.main = -1; it.lastMs = p.now(); it.startKeepAlive() }
        }

        /** The address byte 0x33 at 5 baud: start bit, 8 bits LSB first (0x33 carries its odd parity), stop bit; 200 ms each. */
        fun fiveBaudBits(addr: Int = ADDR): BooleanArray =
            BooleanArray(10) { i -> if (i == 0) false else if (i == 9) true else (addr shr (i - 1)) and 1 == 1 }

        /**
         * 5-baud init (ISO 9141-2 and KWP alike): 0x33 at 5 baud, the unit answers 55 (sync at 10400), KB1, KB2; we send
         * ~KB2, it sends ~33 (CC). The key bytes tell which: 08 08 or 94 94 = ISO 9141-2, KB2 8F = KWP2000.
         */
        fun slowInit(p: KLinePort): KLineObd? {
            p.setBaud(10400)
            p.setBreak(false)
            p.sleep(IDLE_MS)
            val bits = fiveBaudBits()
            val t0 = p.now()
            for (i in 0 until 9) {
                p.until(t0 + i * 200L)
                p.setBreak(!bits[i])
            }
            p.until(t0 + 9 * 200L)
            p.setBreak(false) // the stop bit
            // what the line read back while held low (zeros, framing errors) is not the answer
            p.clearInput()
            // W1: the sync byte 60–300 ms after the stop bit (which itself lasts 200 ms)
            var c = p.read(700)
            var skipped = 0
            while (c >= 0 && c != 0x55 && skipped < 4) { c = p.read(300); skipped++ }
            if (c != 0x55) { SsmTrace.log("5-baud init: no sync (${if (c < 0) "нет ответа" else "%02X".format(c)})"); return null }
            val kb1 = p.read(100)
            val kb2 = p.read(100)
            if (kb1 < 0 || kb2 < 0) { SsmTrace.log("5-baud init: no key bytes"); return null }
            p.sleep(30) // W4 25–50 ms
            val inv = kb2.inv() and 0xFF
            p.write(byteArrayOf(inv.toByte()))
            var a = p.read(150)
            var echo = false
            if (a == inv) { echo = true; a = p.read(150) }
            SsmTrace.log("5-baud init: 55 %02X %02X, answer %s".format(kb1, kb2, if (a < 0) "—" else "%02X".format(a)))
            if (a != (ADDR.inv() and 0xFF)) return null
            val proto = if (kb2 == 0x8F) Protocol.KWP_SLOW else Protocol.ISO9141
            return KLineObd(p, proto, (kb1 shl 8) or kb2).also { it.echo = echo; it.lastMs = p.now(); it.startKeepAlive() }
        }

        /**
         * Tries the inits in turn: KWP fast (break, then the 360-baud pulse), then the 5-baud one that serves both KWP
         * and ISO 9141-2. Each failed try leaves the line idle long enough (W5) for the next.
         */
        fun open(p: KLinePort): KLineObd? {
            for (attempt in 0 until 4) {
                val link = runCatching {
                    when (attempt) {
                        0 -> fastInit(p)
                        1 -> fastInit(p, byBaud = true)
                        else -> slowInit(p)
                    }
                }.onFailure { SsmTrace.log("K-line init: $it") }.getOrNull()
                if (link != null) {
                    SsmTrace.log("K-line OBD: ${link.protocol.title}, key bytes %04X".format(link.keyBytes))
                    return link
                }
                // a unit that saw a broken init waits for silence before it listens again
                p.sleep(if (attempt >= 2) 2600 else 400)
            }
            return null
        }
    }
}
