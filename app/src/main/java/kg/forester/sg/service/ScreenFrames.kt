package kg.forester.sg.service

/**
 * The colour screen's picture on the wire (hardware/usbscreen/usbscreen.ino): which parts changed since the last frame,
 * and each part packed the way the board unpacks it. Pixels are RGB565 in an IntArray, row by row.
 */
object ScreenFrames {
    /** The board takes a rectangle of at most this many pixels at a time. */
    const val MAX_PX = 16384

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    fun rgb565(argb: Int): Int = ((argb shr 8) and 0xF800) or ((argb shr 5) and 0x07E0) or ((argb shr 3) and 0x001F)

    /**
     * The rectangles that differ between [prev] and [cur] ([prev] null: all of it), found by [tile]-pixel squares, joined
     * along a row and then down while they keep the same span and stay under [MAX_PX]. Edges stay even: AMOLED panels
     * write pixel pairs.
     */
    fun dirty(prev: IntArray?, cur: IntArray, w: Int, h: Int, tile: Int = 16): List<Rect> {
        val tw = (w + tile - 1) / tile
        val th = (h + tile - 1) / tile
        fun changed(tx: Int, ty: Int): Boolean {
            if (prev == null) return true
            val x1 = minOf(w, (tx + 1) * tile)
            val y1 = minOf(h, (ty + 1) * tile)
            for (y in ty * tile until y1) {
                val row = y * w
                for (x in tx * tile until x1) if (prev[row + x] != cur[row + x]) return true
            }
            return false
        }
        // spans of changed tiles on each tile row
        val spans = ArrayList<Rect>()
        for (ty in 0 until th) {
            var tx = 0
            while (tx < tw) {
                if (!changed(tx, ty)) { tx++; continue }
                val start = tx
                while (tx < tw && changed(tx, ty)) tx++
                val x = start * tile
                val y = ty * tile
                spans += Rect(x, y, minOf(w, tx * tile) - x, minOf(h, (ty + 1) * tile) - y)
            }
        }
        // the same span on the next tile rows: one rectangle, as tall as the board takes
        val out = ArrayList<Rect>()
        for (r in spans) {
            val last = out.lastOrNull()
            if (last != null && last.x == r.x && last.w == r.w && last.y + last.h == r.y && last.w * (last.h + r.h) <= MAX_PX) {
                out[out.size - 1] = last.copy(h = last.h + r.h)
            } else out += r
        }
        // still too big (a very wide panel): cut into strips
        return out.flatMap { r ->
            if (r.w * r.h <= MAX_PX) listOf(r) else {
                val rows = maxOf(2, (MAX_PX / r.w) and 1.inv())
                (0 until r.h step rows).map { dy -> Rect(r.x, r.y + dy, r.w, minOf(rows, r.h - dy)) }
            }
        }.map(::even)
    }

    private fun even(r: Rect) = Rect(r.x and 1.inv(), r.y and 1.inv(), (r.w + (r.x and 1) + 1) and 1.inv(), (r.h + (r.y and 1) + 1) and 1.inv())

    /**
     * [r] of [px] (a [w]-wide picture) packed: a byte h, then for h & 0x80 a run of (h & 0x7F) + 1 pixels of the one
     * colour that follows, else h + 1 pixels as they are; colours little-endian.
     */
    fun pack(px: IntArray, w: Int, r: Rect): ByteArray {
        val n = r.w * r.h
        fun at(i: Int) = px[(r.y + i / r.w) * w + r.x + i % r.w]
        val out = java.io.ByteArrayOutputStream(n / 2 + 16)
        val lit = IntArray(128)
        var nl = 0
        fun flush() {
            if (nl == 0) return
            out.write(nl - 1)
            for (k in 0 until nl) { out.write(lit[k] and 0xFF); out.write((lit[k] shr 8) and 0xFF) }
            nl = 0
        }
        var i = 0
        while (i < n) {
            val c = at(i)
            var run = 1
            while (i + run < n && run < 128 && at(i + run) == c) run++
            if (run >= 3) {
                flush()
                out.write(0x80 or (run - 1)); out.write(c and 0xFF); out.write((c shr 8) and 0xFF)
                i += run
            } else {
                lit[nl++] = c
                if (nl == 128) flush()
                i++
            }
        }
        flush()
        return out.toByteArray()
    }

    /** [pack] undone, as the board does it; null when the bytes do not make exactly [count] pixels. */
    fun unpack(b: ByteArray, count: Int): IntArray? {
        val out = IntArray(count)
        var i = 0
        var o = 0
        while (i < b.size && o < count) {
            val h = b[i++].toInt() and 0xFF
            val k = (h and 0x7F) + 1
            if (o + k > count) return null
            if (h and 0x80 != 0) {
                if (i + 2 > b.size) return null
                val c = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                i += 2
                repeat(k) { out[o++] = c }
            } else {
                if (i + 2 * k > b.size) return null
                repeat(k) { out[o++] = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8); i += 2 }
            }
        }
        return if (o == count && i == b.size) out else null
    }
}
