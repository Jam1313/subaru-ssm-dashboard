package kg.forester.sg.devices.innovate

import java.util.Locale

/** The function bits of an Innovate (MTS) lambda word: what its 13-bit value means. */
enum class InnovateStatus(val label: String) {
    VALID("Измерение"), O2_LEVEL("Кислород в воздухе"), FREE_AIR("Калибровка на воздухе"),
    NEEDS_CALIBRATION("Нужна калибровка на воздухе"), WARMING("Прогрев"), HEATER_CALIBRATION("Калибровка нагревателя"),
    ERROR("Ошибка датчика"), RESERVED("Неизвестное состояние")
}

data class InnovateReading(val status: InnovateStatus, val raw: Int, val factor: Int) {
    /** AFR with the controller's own stoichiometric factor (147 for petrol). */
    val afr: Float? get() = if (status == InnovateStatus.VALID && factor > 0) (raw + 500) * factor / 10000f else null
    /** True lambda, whatever the fuel: (L + 500) / 1000. */
    val lambda: Float? get() = if (status == InnovateStatus.VALID) (raw + 500) / 1000f else null

    /** The status in words, with what the value means when it is not a measurement: O2 in free air, warm-up, error code. */
    fun text(): String = when (status) {
        InnovateStatus.O2_LEVEL -> "${status.label}: %.1f %%".format(Locale.US, raw / 10f)
        InnovateStatus.WARMING -> "${status.label}: %.0f %%".format(Locale.US, raw / 10f)
        InnovateStatus.HEATER_CALIBRATION -> "${status.label}: ещё $raw"
        InnovateStatus.ERROR -> "${status.label}, код $raw"
        else -> status.label
    }
}

/** LC/MTXL, one channel only. Input is serial data (USB chip status bytes already removed by the driver).
 * Multi-channel packets are deliberately rejected until hardware captures are available. */
class InnovateDecoder {
    private val frame = IntArray(6)
    private var size = 0

    fun reset() { size = 0 }

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<InnovateReading> {
        require(count in 0..bytes.size)
        val result = ArrayList<InnovateReading>()
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 255
            if (InnovateProbe.headerHigh(b)) {
                frame[0] = b
                size = 1
                continue
            }
            when (size) {
                0 -> Unit
                1 -> {
                    val length = 2 * (((frame[0] and 1) shl 7) or (b and 127))
                    if (b and 128 == 0 || length != 4) reset()
                    else { frame[1] = b; size = 2 }
                }
                else -> {
                    val valid = when (size) {
                        2 -> b and 0x42 == 0x42 && b and 0xA0 == 0
                        4 -> b and 0xC0 == 0
                        else -> b and 0x80 == 0
                    }
                    if (!valid) { reset(); continue }
                    frame[size++] = b
                    if (size == 6) {
                        val status = InnovateStatus.entries[(frame[2] and 0x1C) shr 2]
                        result += InnovateReading(status, ((frame[4] and 63) shl 7) or frame[5],
                            ((frame[2] and 1) shl 7) or frame[3])
                        reset()
                    }
                }
            }
        }
        return result
    }
}

/**
 * Tells an Innovate controller from any other USB-serial cable by listening only (nothing is sent): an MTS stream is
 * packets, each starting with a header word that holds the packet length, and the next header comes exactly that
 * many bytes later. Two such headers in a row are nothing a KKL cable or an Arduino sends by chance.
 */
object InnovateProbe {
    /** At 19200 baud the LC-2 sends about 12 packets a second: 1.5 s is plenty. */
    const val LISTEN_MS = 1500L

    /**
     * The high byte of a packet header: bits 7, 5, 4 and 1 set (RomRaider's InnovateRunner, "1x11xx1x"); bit 6 is
     * R, the chain being recorded (an LM-2 or LogWorks logging), and says nothing about the data; bit 0 is the
     * length's bit 8.
     */
    fun headerHigh(b: Int) = (b and 0xB2) == 0xB2

    /** Words a packet may carry for the probe: an MTS chain is a few devices, not the 255 a stream of 0xFF would say. */
    private const val MAX_WORDS = 32

    /** The first [count] bytes of [bytes] hold two packet headers, the second where the first one's length says. */
    fun sees(bytes: ByteArray, count: Int = bytes.size): Boolean {
        for (i in 0 until count - 1) {
            val b0 = bytes[i].toInt() and 255
            val b1 = bytes[i + 1].toInt() and 255
            if (!headerHigh(b0) || b1 and 0x80 == 0) continue
            val length = 2 * (((b0 and 1) shl 7) or (b1 and 127))
            val next = i + 2 + length
            if (length == 0 || length > 2 * MAX_WORDS || next + 1 >= count) continue
            if (headerHigh(bytes[next].toInt() and 255) && bytes[next + 1].toInt() and 0x80 != 0) return true
        }
        return false
    }
}
