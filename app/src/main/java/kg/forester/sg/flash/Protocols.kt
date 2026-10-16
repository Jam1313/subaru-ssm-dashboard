package kg.forester.sg.flash

/*
 * Uploading the SensorBox firmware through the boards' own bootloaders, the way avrdude does it, with no computer.
 * Byte-level details and their sources: docs/knowledge/arduino-flashing.md. We never touch fuses, lock bits or EEPROM:
 * a failed upload leaves the bootloader as it was, and the next attempt starts over.
 */

class FlashTimeout : Exception("плата не ответила вовремя")
class FlashProtocol(msg: String) : Exception(msg)

/** Bytes to and from a board: the USB serial port in the app, a simulated bootloader in the tests. */
interface ByteLink {
    fun write(b: ByteArray)
    /** The next byte (0..255), or [FlashTimeout] after [timeoutMs]. */
    fun byte(timeoutMs: Long): Int
    /** Throws away what arrives until the line is quiet for [quietMs]. */
    fun drain(quietMs: Long = 30)
}

fun ByteLink.bytes(n: Int, timeoutMs: Long) = ByteArray(n) { byte(timeoutMs).toByte() }

enum class Proto { STK500V1, AVR109, STK500V2 }

/** One board's bootloader as far as uploading goes. */
interface Programmer {
    /** Who answered, for the screen and the log ("Optiboot 4.4", "Caterina", "STK500v2"). */
    fun connect(): String
    fun signature(): ByteArray
    fun writePage(byteAddr: Int, data: ByteArray)
    fun readPage(byteAddr: Int, len: Int): ByteArray
    fun finish()
}

// ---------------------------------------------------------------- Intel HEX

/** The sketch as the flash will hold it: 0xFF where the file has nothing, [end] = one past the last byte written. */
class HexImage(val bytes: ByteArray, val end: Int) {
    fun pages(pageSize: Int) = (end + pageSize - 1) / pageSize
    fun page(i: Int, pageSize: Int): ByteArray = bytes.copyOfRange(i * pageSize, (i + 1) * pageSize)
}

object IntelHex {
    /** Parses [text]; anything at or beyond [maxSize] (the bootloader's area) is an error, as is a bad checksum. */
    fun parse(text: String, maxSize: Int): HexImage {
        val img = ByteArray(maxSize) { 0xFF.toByte() }
        var base = 0
        var end = 0
        var eof = false
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val n = i + 1
            require(!eof) { "строка $n: данные после конца файла" }
            require(line[0] == ':' && line.length >= 11 && line.length % 2 == 1) { "строка $n: это не запись HEX" }
            val b = IntArray((line.length - 1) / 2) { line.substring(1 + 2 * it, 3 + 2 * it).toInt(16) }
            val len = b[0]
            require(b.size == len + 5) { "строка $n: длина" }
            require(b.sum() and 0xFF == 0) { "строка $n: контрольная сумма" }
            val off = (b[1] shl 8) or b[2]
            when (b[3]) {
                0x00 -> {
                    val a = base + off
                    require(a >= 0 && a + len <= maxSize) { "строка $n: адрес 0x%X заходит в загрузчик".format(a + len) }
                    for (k in 0 until len) img[a + k] = b[4 + k].toByte()
                    end = maxOf(end, a + len)
                }
                0x01 -> eof = true
                0x02 -> base = ((b[4] shl 8) or b[5]) shl 4
                0x04 -> base = ((b[4] shl 8) or b[5]) shl 16
                0x03, 0x05 -> Unit
                else -> error("строка $n: тип записи ${b[3]}")
            }
        }
        require(eof) { "нет записи конца файла" }
        require(end > 0) { "пустой файл" }
        return HexImage(img, end)
    }
}

// ---------------------------------------------------------------- STK500v1: Optiboot (Nano, Uno) and the old ATmegaBOOT (old Nano)

class Stk500v1(private val l: ByteLink, private val pulse: () -> Unit) : Programmer {
    private fun cmd(body: ByteArray, replyLen: Int, timeoutMs: Long = 500): ByteArray {
        l.write(body + 0x20)
        when (val b = l.byte(timeoutMs)) {
            0x14 -> Unit
            0x15 -> throw FlashProtocol("загрузчик потерял синхронизацию")
            else -> throw FlashProtocol("ждал 0x14, пришло %02X".format(b))
        }
        val data = l.bytes(replyLen, timeoutMs)
        l.byte(timeoutMs).let { if (it != 0x10) throw FlashProtocol("ждал 0x10, пришло %02X".format(it)) }
        return data
    }

    /** Finds the bootloader right after a reset: Optiboot blinks for ~0.4 s first and drops what it missed, hence the retries. */
    fun sync(tries: Int = 8): Boolean {
        val s = byteArrayOf(0x30, 0x20)
        l.write(s); l.drain(); l.write(s); l.drain()
        repeat(tries) { t ->
            if (t > 0) { pulse(); l.drain(20) }
            l.write(s)
            val a = runCatching { l.byte(250) }.getOrNull()
            val b = runCatching { l.byte(250) }.getOrNull()
            if (a == 0x14 && b == 0x10) {
                l.drain()
                return runCatching { cmd(byteArrayOf(0x30), 0) }.isSuccess
            }
        }
        return false
    }

    override fun connect(): String {
        val maj = cmd(byteArrayOf(0x41, 0x81.toByte()), 1)[0].toInt() and 0xFF
        val min = cmd(byteArrayOf(0x41, 0x82.toByte()), 1)[0].toInt() and 0xFF
        cmd(SET_DEVICE_M328P, 0)
        cmd(byteArrayOf(0x50), 0)
        return if (maj == 1) "ATmegaBOOT $maj.$min" else "Optiboot $maj.$min"
    }

    override fun signature() = cmd(byteArrayOf(0x75), 3)

    /** Word address, low byte first; reloaded before every page (ATmegaBOOT moves its own while reading). */
    private fun load(byteAddr: Int) {
        val w = byteAddr ushr 1
        cmd(byteArrayOf(0x55, w.toByte(), (w ushr 8).toByte()), 0)
    }

    override fun writePage(byteAddr: Int, data: ByteArray) {
        load(byteAddr)
        cmd(byteArrayOf(0x64, (data.size ushr 8).toByte(), data.size.toByte(), 0x46) + data, 0, 1000)
    }

    override fun readPage(byteAddr: Int, len: Int): ByteArray {
        load(byteAddr)
        return cmd(byteArrayOf(0x74, (len ushr 8).toByte(), len.toByte(), 0x46), len)
    }

    override fun finish() { runCatching { cmd(byteArrayOf(0x51), 0) } }

    private companion object {
        /** Read and ignored by both bootloaders; the shape avrdude sends for an ATmega328P. */
        val SET_DEVICE_M328P = byteArrayOf(
            0x42, 0x86.toByte(), 0, 0, 1, 1, 1, 1, 3, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0, 0x80.toByte(), 4, 0, 0, 0, 0x80.toByte(), 0,
        )
    }
}

// ---------------------------------------------------------------- AVR109: Caterina (Pro Micro, Leonardo, Micro)

class Avr109(private val l: ByteLink) : Programmer {
    private fun cr(timeoutMs: Long = 1000) {
        val b = l.byte(timeoutMs)
        if (b != 0x0D) throw FlashProtocol("ждал 0x0D, пришло %02X".format(b))
    }

    override fun connect(): String {
        l.write(byteArrayOf(0x1B)); l.drain()
        var id = ""
        for (i in 0 until 5) {
            l.write(byteArrayOf(0x53))
            id = String(l.bytes(7, 1000))
            if (!id.startsWith("?")) break
            l.drain()
        }
        if (!id.startsWith("CATERIN")) throw FlashProtocol("ответил не Caterina: $id")
        l.write(byteArrayOf(0x62))
        val block = l.bytes(3, 1000)
        if (block[0].toInt() != 0x59 || (((block[1].toInt() and 0xFF) shl 8) or (block[2].toInt() and 0xFF)) != 128)
            throw FlashProtocol("неожиданный размер блока")
        l.write(byteArrayOf(0x50)); cr()
        return "Caterina"
    }

    /** Caterina gives it reversed (SIG3, SIG2, SIG1). */
    override fun signature(): ByteArray {
        l.write(byteArrayOf(0x73))
        return l.bytes(3, 1000).reversedArray()
    }

    /** Word address, big endian. */
    private fun address(byteAddr: Int) {
        val w = byteAddr ushr 1
        l.write(byteArrayOf(0x41, (w ushr 8).toByte(), w.toByte())); cr()
    }

    override fun writePage(byteAddr: Int, data: ByteArray) {
        address(byteAddr)
        l.write(byteArrayOf(0x42, (data.size ushr 8).toByte(), data.size.toByte(), 0x46) + data); cr()
    }

    override fun readPage(byteAddr: Int, len: Int): ByteArray {
        address(byteAddr)
        l.write(byteArrayOf(0x67, (len ushr 8).toByte(), len.toByte(), 0x46))
        return l.bytes(len, 1000)
    }

    /** Leave programming, exit: 500 ms later the sketch starts. */
    override fun finish() {
        runCatching { l.write(byteArrayOf(0x4C)); cr() }
        runCatching { l.write(byteArrayOf(0x45)); cr() }
    }
}

// ---------------------------------------------------------------- STK500v2: the Mega 2560's bootloader

class Stk500v2(private val l: ByteLink) : Programmer {
    private var seq = 1

    private fun command(body: ByteArray, timeoutMs: Long = 1000): ByteArray {
        val f = ByteArray(body.size + 6)
        f[0] = 0x1B; f[1] = seq.toByte(); f[2] = (body.size ushr 8).toByte(); f[3] = body.size.toByte(); f[4] = 0x0E
        body.copyInto(f, 5)
        f[f.size - 1] = f.copyOf(f.size - 1).fold(0) { x, b -> x xor (b.toInt() and 0xFF) }.toByte()
        l.write(f)
        while (l.byte(timeoutMs) != 0x1B) Unit
        val s = l.byte(timeoutMs)
        val len = (l.byte(timeoutMs) shl 8) or l.byte(timeoutMs)
        if (l.byte(timeoutMs) != 0x0E) throw FlashProtocol("нет метки 0x0E")
        val r = l.bytes(len, timeoutMs)
        val ck = l.byte(timeoutMs)
        val calc = r.fold(0x1B xor s xor (len ushr 8) xor (len and 0xFF) xor 0x0E) { x, b -> x xor (b.toInt() and 0xFF) }
        if (ck != calc || s != (seq and 0xFF)) throw FlashProtocol("контрольная сумма или номер ответа")
        if (r[0] != body[0] || r[1].toInt() != 0) throw FlashProtocol("отказ: %02X".format(r[1]))
        seq = (seq + 1) and 0xFF
        return r
    }

    /** SIGN_ON until the bootloader answers (it listens ~1 s after the reset). */
    fun sync(tries: Int = 10): Boolean {
        repeat(tries) {
            if (runCatching { command(byteArrayOf(0x01), 200) }.getOrNull()?.let { String(it.copyOfRange(3, it.size)) == "AVRISP_2" } == true) return true
            l.drain(20)
        }
        return false
    }

    override fun connect(): String {
        command(byteArrayOf(0x10, 0xC8.toByte(), 0x64, 0x19, 0x20, 0x00, 0x53, 0x03, 0xAC.toByte(), 0x53, 0x00, 0x00))
        return "STK500v2 (Mega)"
    }

    override fun signature() = ByteArray(3) { i -> command(byteArrayOf(0x1B, 0x04, 0x30, 0x00, i.toByte(), 0x00))[2] }

    private fun load(byteAddr: Int) {
        val w = byteAddr ushr 1
        command(byteArrayOf(0x06, ((w ushr 24) or 0x80).toByte(), (w ushr 16).toByte(), (w ushr 8).toByte(), w.toByte()))
    }

    /** Its bootloader erases page after page by a counter of its own: pages must come 0, 1, 2… in one go (see [Upload]). */
    override fun writePage(byteAddr: Int, data: ByteArray) {
        load(byteAddr)
        command(byteArrayOf(0x13, (data.size ushr 8).toByte(), data.size.toByte(), 0xC1.toByte(), 0x0A, 0x40, 0x4C, 0x20, 0, 0) + data)
    }

    override fun readPage(byteAddr: Int, len: Int): ByteArray {
        load(byteAddr)
        val r = command(byteArrayOf(0x14, (len ushr 8).toByte(), len.toByte(), 0x20))
        return r.copyOfRange(2, 2 + len)
    }

    override fun finish() { runCatching { command(byteArrayOf(0x11, 0x01, 0x01)) } }
}

// ---------------------------------------------------------------- the upload itself

object Upload {
    /**
     * Writes [img] page by page and reads it back. Caterina: page 0 is erased first and written last, so a cut-off
     * upload leaves "no sketch" and the bootloader waits forever instead of starting half a program. The Mega's bootloader
     * erases in its own order, so its pages go strictly 0..N and a mismatch means starting over.
     */
    fun run(p: Programmer, img: HexImage, pageSize: Int, proto: Proto, progress: (Float, String) -> Unit) {
        val pages = img.pages(pageSize)
        val ff = ByteArray(pageSize) { 0xFF.toByte() }
        fun write(i: Int) = p.writePage(i * pageSize, img.page(i, pageSize))
        fun same(i: Int) = p.readPage(i * pageSize, pageSize).contentEquals(img.page(i, pageSize))
        val order = if (proto == Proto.AVR109) (1 until pages) else (0 until pages)
        if (proto == Proto.AVR109) p.writePage(0, ff)
        order.forEachIndexed { n, i ->
            write(i)
            progress(0.05f + 0.65f * (n + 1) / pages, "Записываю ${n + 1} из $pages")
        }
        order.forEachIndexed { n, i ->
            if (!same(i)) {
                if (proto == Proto.STK500V2) throw FlashProtocol("проверка не сошлась на 0x%X".format(i * pageSize))
                write(i)
                if (!same(i)) throw FlashProtocol("проверка не сошлась на 0x%X".format(i * pageSize))
            }
            progress(0.70f + 0.25f * (n + 1) / pages, "Проверяю ${n + 1} из $pages")
        }
        if (proto == Proto.AVR109) {
            write(0)
            if (!same(0)) { write(0); if (!same(0)) throw FlashProtocol("проверка не сошлась на 0x0") }
        }
        progress(0.98f, "Завершаю")
        p.finish()
    }
}
