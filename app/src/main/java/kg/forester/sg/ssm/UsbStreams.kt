package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * A USB serial port as a pair of streams, for the ELM327 code that also runs over Bluetooth and Wi-Fi
 * sockets. Reading is done by the library's background reader; [close] stops it (the port itself is
 * closed by whoever opened it).
 */
class UsbStreams(private val port: UsbSerialPort) : Closeable {
    private val queue = LinkedBlockingQueue<Byte>()
    @Volatile private var error: Exception? = null
    private val io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) = data.forEach { queue.offer(it) }
        override fun onRunError(e: Exception) { error = e }
    }).also { it.readTimeout = 0; it.start() }

    val input = object : InputStream() {
        override fun available(): Int {
            error?.let { throw IOException(it) }
            return queue.size
        }

        override fun read(): Int {
            while (true) {
                error?.let { throw IOException(it) }
                val b = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                return b.toInt() and 0xFF
            }
        }
    }

    val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) = port.write(b.copyOfRange(off, off + len), 1000)
    }

    override fun close() {
        runCatching { io.stop() }
    }

    companion object {
        /** Speeds USB ELM327 cables come set to, the usual one first. */
        private val ELM_BAUDS = intArrayOf(38400, 9600, 115200, 57600)

        /**
         * Is there an ELM327 behind this USB serial chip (a USB "OBD2" cable rather than a bare KKL)?
         * Asks "ATI" at the usual speeds; a KKL cable only echoes the question back (or nothing), an ELM
         * answers with its name and the '>' prompt. The stray bytes on the K-line are ignored by the car.
         * Returns the working speed, or null. Must run before anything else reads the port. [quick]: only the
         * usual speed (a cable already known as a KKL, in case an ELM on the same chip is plugged in instead).
         */
        fun probeElm(port: UsbSerialPort, quick: Boolean = false): Int? {
            val buf = ByteArray(256)
            for (baud in if (quick) ELM_BAUDS.copyOf(1) else ELM_BAUDS) {
                runCatching {
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    runCatching { port.dtr = true }
                    runCatching { port.rts = true }
                    while (port.read(buf, 20) > 0) { /* drop what was there */ }
                    port.write("\rATI\r".toByteArray(), 500)
                    val sb = StringBuilder()
                    val deadline = System.currentTimeMillis() + 700
                    while (System.currentTimeMillis() < deadline) {
                        val n = port.read(buf, 50)
                        for (i in 0 until n) sb.append((buf[i].toInt() and 0xFF).toChar())
                        if (sb.contains('>')) break
                    }
                    val r = sb.toString()
                    SsmTrace.log("ELM probe $baud: ${r.replace('\r', '|').replace('\n', '|')}")
                    if (r.contains('>') || r.contains("ELM", true) || r.contains("STN", true)) return baud
                }.onFailure { SsmTrace.log("ELM probe $baud: $it") }
            }
            return null
        }
    }
}
