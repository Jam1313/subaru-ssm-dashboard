package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/** ECU identification returned by the SSM2 init (0xBF) request. */
data class EcuInit(
    val ssmId: String,
    val romId: String,
    val capabilities: ByteArray,
) {
    /** RomRaider convention: byte index counts from the SSM ID (0-2), ROM ID (3-7), capabilities (8+). */
    fun supports(ecuByteIndex: Int, ecuBit: Int): Boolean {
        val i = ecuByteIndex - 8
        if (i < 0 || i >= capabilities.size) return false
        return (capabilities[i].toInt() shr ecuBit) and 1 == 1
    }
}

/**
 * Subaru Select Monitor 2 over K-line (KKL cable): 4800 8N1, half duplex, so every
 * request is echoed back before the ECU response.
 */
class SsmClient(private val port: UsbSerialPort) {

    // Received bytes are collected by a background reader; short read timeouts lose data on CH34x.
    private val inbox = java.io.ByteArrayOutputStream()
    private val lock = Object()
    private var io: SerialInputOutputManager? = null
    @Volatile var ioError: Exception? = null
        private set

    fun open() {
        port.setParameters(4800, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching { port.dtr = true }
        runCatching { port.rts = true }
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                synchronized(lock) { inbox.write(data) }
            }

            override fun onRunError(e: Exception) {
                ioError = e
            }
        }).also { it.readTimeout = 0; it.start() }
    }

    fun close() {
        runCatching { io?.stop() }
        runCatching { port.close() }
    }

    fun init(dest: Int = ECU): EcuInit? {
        val data = request(byteArrayOf(0xBF.toByte()), dest) ?: return null
        if (data.size < 9 || data[0] != 0xFF.toByte()) return null
        return EcuInit(
            ssmId = data.copyOfRange(1, 4).toHex(),
            romId = data.copyOfRange(4, 9).toHex(),
            capabilities = data.copyOfRange(9, data.size),
        )
    }

    /** Reads single bytes from the given addresses in one 0xA8 request. */
    fun read(addresses: IntArray, dest: Int = ECU): ByteArray? {
        val body = ByteArray(2 + addresses.size * 3)
        body[0] = 0xA8.toByte()
        body[1] = 0x00
        addresses.forEachIndexed { i, a ->
            body[2 + i * 3] = (a shr 16).toByte()
            body[3 + i * 3] = (a shr 8).toByte()
            body[4 + i * 3] = a.toByte()
        }
        val data = request(body, dest) ?: return null
        if (data.isEmpty() || data[0] != 0xE8.toByte() || data.size < 1 + addresses.size) return null
        return data.copyOfRange(1, 1 + addresses.size)
    }

    /** Writes one byte (0xB8). Returns the value the ECU reports back. */
    fun write(address: Int, value: Int, dest: Int = ECU): Int? {
        val body = byteArrayOf(
            0xB8.toByte(),
            (address shr 16).toByte(), (address shr 8).toByte(), address.toByte(),
            value.toByte(),
        )
        val data = request(body, dest) ?: return null
        if (data.size < 2 || data[0] != 0xF8.toByte()) return null
        return data[1].toInt() and 0xFF
    }

    @Synchronized
    private fun request(body: ByteArray, dest: Int): ByteArray? {
        val packet = ByteArray(body.size + 5)
        packet[0] = 0x80.toByte()
        packet[1] = dest.toByte()
        packet[2] = 0xF0.toByte()
        packet[3] = body.size.toByte()
        body.copyInto(packet, 4)
        packet[packet.size - 1] = checksum(packet, packet.size - 1)

        ioError?.let { throw IOException(it) }
        purge()
        port.write(packet, 1000)

        // Wait for echo + header(4) + len + checksum.
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
            val all = synchronized(lock) { inbox.toByteArray() }
            val resp = stripEcho(all, packet) ?: continue
            if (resp.size >= 4) {
                val start = indexOfHeader(resp, dest)
                if (start < 0) continue
                val len = resp[start + 3].toInt() and 0xFF
                val total = 4 + len + 1
                if (resp.size - start >= total) {
                    val frame = resp.copyOfRange(start, start + total)
                    if (checksum(frame, total - 1) != frame[total - 1]) return null
                    return frame.copyOfRange(4, 4 + len)
                }
            }
        }
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

    private fun purge() {
        synchronized(lock) { inbox.reset() }
    }

    companion object {
        const val ECU = 0x10
        const val TCU = 0x18

        fun checksum(b: ByteArray, len: Int): Byte {
            var s = 0
            for (i in 0 until len) s += b[i].toInt() and 0xFF
            return s.toByte()
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
