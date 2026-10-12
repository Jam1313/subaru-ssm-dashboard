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

        val deadline = System.currentTimeMillis() + 1500
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

/** Plain USB KKL cable (CH340/FTDI/PL2303/CP210x): the K-line is the serial port itself, 4800 8N1. */
class KklTransport(private val port: UsbSerialPort) : KLineTransport() {
    override val name = "KKL, K-Line"
    private var io: SerialInputOutputManager? = null

    fun open() {
        port.setParameters(4800, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching { port.dtr = true }
        runCatching { port.rts = true }
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
        val data = transport.exchange(dest, byteArrayOf(cmd.toByte())) ?: return null
        if (data.size < 9 || data[0] != ok.toByte()) return null
        return EcuInit(
            ssmId = data.copyOfRange(1, 4).toHex(),
            romId = data.copyOfRange(4, 9).toHex(),
            capabilities = data.copyOfRange(9, data.size),
        )
    }

    fun read(addresses: IntArray, dest: Int = ECU): ByteArray? {
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
