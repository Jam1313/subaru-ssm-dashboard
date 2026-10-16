package kg.forester.sg.flash

import java.security.MessageDigest

/**
 * The ESP32 ROM loader's serial protocol (esptool's «serial protocol» page): SLIP frames, a command with its
 * checksum, a response ending in four status bytes. Enough to write a firmware image and check it by MD5; no stub,
 * no compression, so it works with any ESP32-S3 the same way.
 */
class EspRom(private val link: ByteLink) {
    companion object {
        const val SYNC = 0x08
        const val READ_REG = 0x0A
        const val SPI_SET_PARAMS = 0x0B
        const val SPI_ATTACH = 0x0D
        const val FLASH_BEGIN = 0x02
        const val FLASH_DATA = 0x03
        const val FLASH_END = 0x04
        const val SPI_FLASH_MD5 = 0x13
        /** Where every chip keeps a value telling which chip it is; the S3's is 9. */
        const val CHIP_MAGIC_REG = 0x40001000L
        const val S3_MAGIC = 9L
        /** The ROM loader's flash write block. */
        const val BLOCK = 0x400

        fun slip(p: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(p.size + 8)
            out.write(0xC0)
            for (b in p) when (b.toInt() and 0xFF) {
                0xC0 -> { out.write(0xDB); out.write(0xDC) }
                0xDB -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b.toInt() and 0xFF)
            }
            out.write(0xC0)
            return out.toByteArray()
        }

        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        fun checksum(data: ByteArray, from: Int = 0): Int {
            var c = 0xEF
            for (i in from until data.size) c = c xor (data[i].toInt() and 0xFF)
            return c
        }

        fun md5Hex(b: ByteArray, len: Int = b.size): String =
            MessageDigest.getInstance("MD5").apply { update(b, 0, len) }.digest().joinToString("") { "%02x".format(it) }
    }

    class Reply(val value: Long, val data: ByteArray)

    /** One frame from the loader, unescaped; skips whatever text the ROM printed before it. */
    private fun frame(timeoutMs: Long): ByteArray {
        val end = System.currentTimeMillis() + timeoutMs
        fun next(): Int {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw FlashTimeout()
            return link.byte(left)
        }
        while (next() != 0xC0) Unit
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            when (val b = next()) {
                0xC0 -> if (out.size() == 0) continue else return out.toByteArray()
                0xDB -> out.write(when (next()) { 0xDC -> 0xC0; 0xDD -> 0xDB; else -> throw FlashProtocol("SLIP") })
                else -> out.write(b)
            }
        }
    }

    /** Sends [op] with [data] and waits for its own response; a status other than 0 is an error with the loader's code. */
    fun command(op: Int, data: ByteArray, chk: Int = 0, timeoutMs: Long = 3000): Reply {
        val p = ByteArray(8 + data.size)
        p[0] = 0; p[1] = op.toByte(); p[2] = data.size.toByte(); p[3] = (data.size shr 8).toByte()
        le32(chk.toLong()).copyInto(p, 4)
        data.copyInto(p, 8)
        link.write(slip(p))
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val f = frame((end - System.currentTimeMillis()).coerceAtLeast(1))
            if (f.size < 8 || f[0].toInt() != 1 || (f[1].toInt() and 0xFF) != op) continue // a late answer to something else
            val size = (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8)
            val value = (f[4].toLong() and 0xFF) or ((f[5].toLong() and 0xFF) shl 8) or ((f[6].toLong() and 0xFF) shl 16) or ((f[7].toLong() and 0xFF) shl 24)
            val body = f.copyOfRange(8, minOf(f.size, 8 + size))
            if (body.size < 4) throw FlashProtocol("короткий ответ на 0x%02X".format(op))
            val status = body[body.size - 4].toInt() and 0xFF
            if (status != 0) throw FlashProtocol("загрузчик ответил ошибкой 0x%02X на команду 0x%02X".format(body[body.size - 3].toInt() and 0xFF, op))
            return Reply(value, body.copyOfRange(0, body.size - 4))
        }
    }

    /** Says hello until the loader answers (it wants a few tries after a reset). */
    fun sync(tries: Int = 8): Boolean {
        val data = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(tries) {
            try {
                command(SYNC, data, timeoutMs = 300)
                link.drain(150) // the loader answers SYNC several times
                return true
            } catch (_: FlashTimeout) {
            } catch (_: FlashProtocol) {
            }
        }
        return false
    }

    fun readReg(addr: Long): Long = command(READ_REG, le32(addr)).value

    fun isS3(): Boolean = readReg(CHIP_MAGIC_REG) == S3_MAGIC

    fun attach() {
        command(SPI_ATTACH, ByteArray(8))
        // id, total size, block, sector, page, status mask: what esptool tells the ROM
        command(SPI_SET_PARAMS, le32(0) + le32(16L * 1024 * 1024) + le32(64L * 1024) + le32(4096) + le32(256) + le32(0xFFFF))
    }

    /** Erases what [img] needs at [addr] and writes it block by block; [progress] gets 0..1. */
    fun write(addr: Long, img: ByteArray, progress: (Float) -> Unit) {
        val blocks = (img.size + BLOCK - 1) / BLOCK
        // erasing takes the ROM a while: about 30 s a megabyte at worst
        val eraseMs = 10_000L + img.size.toLong() * 40_000 / (1024 * 1024)
        command(FLASH_BEGIN, le32(img.size.toLong()) + le32(blocks.toLong()) + le32(BLOCK.toLong()) + le32(addr) + le32(0), timeoutMs = eraseMs)
        for (i in 0 until blocks) {
            val chunk = ByteArray(BLOCK) { 0xFF.toByte() }
            img.copyInto(chunk, 0, i * BLOCK, minOf(img.size, (i + 1) * BLOCK))
            command(FLASH_DATA, le32(BLOCK.toLong()) + le32(i.toLong()) + le32(0) + le32(0) + chunk, checksum(chunk), timeoutMs = 5000)
            progress((i + 1).toFloat() / blocks)
        }
    }

    /** The MD5 the chip computes of [size] bytes of flash at [addr], lower-case hex. */
    fun md5(addr: Long, size: Int): String {
        val r = command(SPI_FLASH_MD5, le32(addr) + le32(size.toLong()) + le32(0) + le32(0), timeoutMs = 10_000L + size / 20)
        return if (r.data.size >= 32) String(r.data, 0, 32, Charsets.US_ASCII).lowercase()
        else r.data.joinToString("") { "%02x".format(it) }
    }

    /** Leaves the loader; the caller resets the chip into the new program. */
    fun finish() = runCatching { command(FLASH_END, le32(1), timeoutMs = 1000) }
}
