package kg.forester.sg.service

import kg.forester.sg.data.DispCfg
import kg.forester.sg.data.DispKind
import kg.forester.sg.data.DispLine
import kg.forester.sg.data.Displays

/**
 * Keeps the box's displays showing what the driver chose: sets them up whenever the box (re)connects or the choice
 * changes, then twice a second sends only the lines that changed (an I2C OLED line takes ~10 ms of the box's time).
 */
object DisplayEngine {
    private var session = -1
    private var configHash = 0
    private val sent = HashMap<String, String>()
    private var last = 0L
    private var wasTest = false
    /** A test pattern asked for from the settings: shown instead of the values until this time. */
    @Volatile var testUntil = 0L
    @Volatile private var rescan = false
    @Volatile private var redraw = false
    private var lastSend = 0L

    /** «Проверить экран»: five seconds of a pattern on every display. */
    fun test() { testUntil = System.currentTimeMillis() + 5000 }

    /** Asks the box to look for an I2C display again (one plugged in after it started); it answers with its I line. */
    fun findAgain() { rescan = true }

    /** The box said «Y R»: it had no word from the app for a while and wrote НЕТ СВЯЗИ over the values. */
    fun redraw() { redraw = true }

    fun tick(now: Long, boxSession: Int, value: (String) -> Float?) {
        if (!SensorBoxLink.canDisplay()) { session = -1; return }
        if (rescan) { rescan = false; SensorBoxLink.send("Y S"); session = -1 }
        if (redraw) { redraw = false; session = -1 }
        val list = Displays.list.value
        val tc = kg.forester.sg.data.SensorBox.tcCs.value
        val hash = list.hashCode() * 31 + tc.hashCode()
        val test = now < testUntil
        if (boxSession != session || hash != configHash || test != wasTest) {
            session = boxSession
            configHash = hash
            wasTest = test
            sent.clear()
            for (d in list) when {
                d.kind.lcd -> SensorBoxLink.send("Y L ${d.kind.w} ${d.kind.h}")
                d.kind.oled -> SensorBoxLink.send("Y O ${d.kind.h} ${d.kind.code}")
                d.kind == DispKind.TM1637 -> if (d.clk.isNotEmpty() && d.dio.isNotEmpty()) {
                    SensorBoxLink.send("Y T ${d.clk} ${d.dio}")
                    SensorBoxLink.send("Y B ${d.bright}")
                }
                d.kind.colour -> if (d.dc.isNotEmpty() && (d.cs.isNotEmpty() || !d.kind.cs))
                    SensorBoxLink.send("Y C ${d.kind.code} ${d.cs.ifEmpty { "-" }} ${d.dc}")
            }
            if (list.none { it.kind == DispKind.TM1637 }) SensorBoxLink.send("Y T -")
            if (list.none { it.kind.colour }) SensorBoxLink.send("Y C -")
            // the extra thermocouples' CS pins (the box keeps them off the outputs)
            SensorBoxLink.send("Y K " + listOf("TC1", "TC2", "TC3").joinToString(" ") { tc[it]?.takeIf { p -> p.isNotEmpty() } ?: "-" })
            // a layout change or the end of a test leaves old rows behind: start clean
            SensorBoxLink.send("Z")
            last = 0L
        }
        if (now - last < 500) return
        last = now
        // values that do not change send nothing: a bare H tells the box the app is still there
        if (list.isNotEmpty() && now - lastSend > 2000) { SensorBoxLink.send("H"); lastSend = now }
        for (d in list) {
            if (d.kind.colour) {
                for (op in if (test) Displays.colourTest(d.kind) else Displays.colourOps(d, value, ::warn)) {
                    val cmd = Displays.wire(op, d.kind)
                    val key = "${d.id}:${op.key}"
                    if (sent[key] != cmd) { SensorBoxLink.send(cmd); sent[key] = cmd; lastSend = now }
                }
                continue
            }
            val lines = if (test) testLines(d) else Displays.lines(d, value)
            for (l in lines) {
                val cmd = when {
                    d.kind == DispKind.TM1637 -> "N ${l.text}"
                    // a text LCD has its own command: an OLED may be on the same bus
                    d.kind.lcd -> "M ${l.row} ${Displays.wire(l.text, d.kind)}"
                    else -> "L ${l.row} ${l.scale} ${Displays.wire(l.text, d.kind)}"
                }
                val key = "${d.id}:${l.row}"
                if (sent[key] != cmd) { SensorBoxLink.send(cmd); sent[key] = cmd; lastSend = now }
            }
        }
    }

    /** Past the warning the app has for this value (the same as on its cards): drawn red. */
    fun warn(id: String, v: Float): Boolean =
        kg.forester.sg.ui.Metrics.of(id, kg.forester.sg.data.Repo.rrAvailable.value)?.warn(v) == true

    /** «Проверить экран»: every line shows something, so a dead row or a wrong address is plain to see. */
    fun testLines(d: DispCfg): List<DispLine> = when {
        d.kind == DispKind.TM1637 -> listOf(DispLine(0, 1, "8.8.8.8."))
        d.kind.oled && d.kind.h == 32 -> listOf(
            DispLine(0, 1, "ПРОВЕРКА ЭКРАНА"), DispLine(1, 2, "SUBARU 25"), DispLine(3, 1, "АБВГД 0123456789"),
        )
        d.kind.oled -> listOf(
            DispLine(0, 1, "ПРОВЕРКА ЭКРАНА"), DispLine(2, 2, "SUBARU 25"),
            DispLine(5, 1, "АБВГД 0123456789"), DispLine(7, 1, "~C % / + - : ."),
        )
        else -> (0 until d.slots).map { DispLine(it, 1, "ROW ${it + 1} 0123456789ABCDEF") }
    }
}
