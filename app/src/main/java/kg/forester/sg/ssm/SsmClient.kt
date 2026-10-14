package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

data class EcuInit(
    val ssmId: String,
    val romId: String,
    val capabilities: ByteArray,
) {
    fun supports(ecuByteIndex: Int, ecuBit: Int): Boolean {
        val i = ecuByteIndex - 8
        if (i < 0 || i >= capabilities.size) return false
        return (capabilities[i].toInt() shr ecuBit) and 1 == 1
    }
}

/**
 * Carries one SSM command to a control unit and returns its answer (starting with the response code,
 * e.g. E8 for A8). The SSM commands are the same on K-line and CAN, only the framing differs.
 */
interface SsmTransport {
    /** Shown in the status line, e.g. "KKL, K-Line". */
    val name: String
    /** CAN units answer the init with 0xAA/0xEA instead of 0xBF/0xFF. */
    val isCan: Boolean
    /** Addresses per read request (an ELM327 fits only one into its single frame). */
    val maxReadAddrs: Int get() = 64
    /**
     * States of the cable's control lines worth trying when the ECU stays silent: KKL cables with a chip
     * inside (VAG K+CAN Commander and the like) only pass the K-line through with DTR/RTS set their way.
     */
    val lineModes: Int get() = 1
    fun setLineMode(i: Int) {}
    fun exchange(dest: Int, body: ByteArray): ByteArray?
    fun close()
}

/** K-line SSM2 framing: 80 <dest> F0 <len> <body> <checksum>, the answer comes back as 80 F0 <dest> … */
abstract class KLineTransport : SsmTransport {
    private val inbox = java.io.ByteArrayOutputStream()
    private val lock = Object()
    @Volatile protected var ioError: Exception? = null
    override val isCan = false

    protected abstract fun writeRaw(packet: ByteArray)

    /** Bytes received from the line (with or without the echo of our own request). */
    protected fun onBytes(data: ByteArray) {
        synchronized(lock) { inbox.write(data) }
    }

    @Synchronized
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        val packet = ByteArray(body.size + 5)
        packet[0] = 0x80.toByte()
        packet[1] = dest.toByte()
        packet[2] = 0xF0.toByte()
        packet[3] = body.size.toByte()
        body.copyInto(packet, 4)
        packet[packet.size - 1] = checksum(packet, packet.size - 1)

        ioError?.let { throw IOException(it) }
        synchronized(lock) { inbox.reset() }
        SsmTrace.log("TX", packet)
        writeRaw(packet)

        // 2 s like RomRaider and FreeSSM: older (16-bit) ECUs can be slow with the first answer
        val deadline = System.currentTimeMillis() + 2000
        var all = ByteArray(0)
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
            all = synchronized(lock) { inbox.toByteArray() }
            val resp = stripEcho(all, packet) ?: continue
            if (resp.size >= 4) {
                val start = indexOfHeader(resp, dest)
                if (start < 0) continue
                val len = resp[start + 3].toInt() and 0xFF
                val total = 4 + len + 1
                if (resp.size - start >= total) {
                    val frame = resp.copyOfRange(start, start + total)
                    SsmTrace.log("RX", all)
                    if (checksum(frame, total - 1) != frame[total - 1]) {
                        SsmTrace.log("bad checksum")
                        return null
                    }
                    return frame.copyOfRange(4, 4 + len)
                }
            }
        }
        SsmTrace.log("timeout, got ${all.size} bytes: ${all.toHex(" ")}")
        return null
    }

    private fun stripEcho(all: ByteArray, packet: ByteArray): ByteArray? {
        if (all.size < packet.size) return null
        for (i in packet.indices) if (all[i] != packet[i]) return all // no echo (cable without loopback)
        return all.copyOfRange(packet.size, all.size)
    }

    private fun indexOfHeader(b: ByteArray, dest: Int): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 0x80.toByte() && b[i + 1] == 0xF0.toByte() && b[i + 2] == dest.toByte()) return i
        }
        return -1
    }

    companion object {
        fun checksum(b: ByteArray, len: Int): Byte {
            var s = 0
            for (i in 0 until len) s += b[i].toInt() and 0xFF
            return s.toByte()
        }
    }
}

/** Tactrix OpenPort 1.3 (M, S and U versions): an FTDI K-line cable with Tactrix product ids. */
object OpenPort13 {
    const val VID = 0x0403
    val PIDS = intArrayOf(0xCC48, 0xCC49, 0xCC4A)

    fun matches(vid: Int, pid: Int) = vid == VID && pid in PIDS
}

/**
 * Plain USB KKL cable (CH340/FTDI/PL2303/CP210x) or OpenPort 1.3: the K-line is the serial port itself, 4800 8N1.
 * DTR and RTS start on (RTS off for the OpenPort 1.3, as RomRaider does); the other combinations are tried
 * while the ECU stays silent, both off being what Windows programs leave them at.
 */
class KklTransport(private val port: UsbSerialPort, private val openPort13: Boolean = false) : KLineTransport() {
    private val modes = if (openPort13) listOf(true to false, true to true, false to false, false to true)
    else listOf(true to true, false to false, true to false, false to true)
    private var mode = 0
    override val name: String
        get() = (if (openPort13) "OpenPort 1.3, K-Line" else "KKL, K-Line") +
            if (mode == 0) "" else modes[mode].let { (d, r) -> ", DTR ${if (d) 1 else 0} RTS ${if (r) 1 else 0}" }
    override val lineModes get() = modes.size
    private var io: SerialInputOutputManager? = null

    override fun setLineMode(i: Int) {
        mode = i.mod(modes.size)
        val (d, r) = modes[mode]
        runCatching { port.dtr = d }
        runCatching { port.rts = r }
        SsmTrace.log("lines: DTR ${if (d) 1 else 0} RTS ${if (r) 1 else 0}")
    }

    fun open() {
        port.setParameters(4800, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        setLineMode(0)
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = onBytes(data)
            override fun onRunError(e: Exception) {
                ioError = e
            }
        }).also { it.readTimeout = 0; it.start() }
    }

    override fun writeRaw(packet: ByteArray) = port.write(packet, 1000)

    /** Stops reading; the port itself is closed by whoever opened it. */
    override fun close() {
        runCatching { io?.stop() }
    }
}

class SsmClient(val transport: SsmTransport) {

    fun init(dest: Int = ECU): EcuInit? {
        val (cmd, ok) = if (transport.isCan) 0xAA to 0xEA else 0xBF to 0xFF
        // the engine unit gets one more try on K-line: just woken up, it may let the first request pass unanswered
        // (not the transmission: its retries every minute would freeze the gauges twice as long)
        val data = transport.exchange(dest, byteArrayOf(cmd.toByte()))
            ?: (if (transport.isCan || dest != ECU) null else transport.exchange(dest, byteArrayOf(cmd.toByte())))
            ?: return null
        if (data.size < 9 || data[0] != ok.toByte()) return null
        return EcuInit(
            ssmId = data.copyOfRange(1, 4).toHex(),
            romId = data.copyOfRange(4, 9).toHex(),
            capabilities = data.copyOfRange(9, data.size),
        )
    }

    fun read(addresses: IntArray, dest: Int = ECU): ByteArray? {
        val step = transport.maxReadAddrs.coerceAtLeast(1)
        if (addresses.size <= step) return readChunk(addresses, dest)
        val out = java.io.ByteArrayOutputStream(addresses.size)
        for (i in addresses.indices step step) {
            out.write(readChunk(addresses.copyOfRange(i, minOf(i + step, addresses.size)), dest) ?: return null)
        }
        return out.toByteArray()
    }

    private fun readChunk(addresses: IntArray, dest: Int): ByteArray? {
        val body = ByteArray(2 + addresses.size * 3)
        body[0] = 0xA8.toByte()
        body[1] = 0x00
        addresses.forEachIndexed { i, a ->
            body[2 + i * 3] = (a shr 16).toByte()
            body[3 + i * 3] = (a shr 8).toByte()
            body[4 + i * 3] = a.toByte()
        }
        val data = transport.exchange(dest, body) ?: return null
        if (data.isEmpty() || data[0] != 0xE8.toByte() || data.size < 1 + addresses.size) return null
        return data.copyOfRange(1, 1 + addresses.size)
    }

    fun write(address: Int, value: Int, dest: Int = ECU): Int? {
        val body = byteArrayOf(
            0xB8.toByte(),
            (address shr 16).toByte(), (address shr 8).toByte(), address.toByte(),
            value.toByte(),
        )
        val data = transport.exchange(dest, body) ?: return null
        if (data.size < 2 || data[0] != 0xF8.toByte()) return null
        return data[1].toInt() and 0xFF
    }

    fun close() = transport.close()

    companion object {
        const val ECU = 0x10
        const val TCU = 0x18
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
