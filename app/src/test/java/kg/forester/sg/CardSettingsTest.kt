package kg.forester.sg

import kg.forester.sg.data.MyBoard
import kg.forester.sg.ui.NavCfg
import kg.forester.sg.ui.PresetLayouts
import kg.forester.sg.ui.SportCfg
import org.junit.Assert.*
import org.junit.Test

/** The own settings of the Спорт-дисплей and «Приборка с картой» cards, and the ready-made «Мой экран» layouts. */
class CardSettingsTest {
    @Test fun sportFactoryLookIsAnEmptyStyle() {
        assertEquals("", SportCfg().format())
        assertEquals(SportCfg(), SportCfg.parse(""))
        // the gauge design a card got before it had an editor of its own reads as the factory look
        assertEquals(SportCfg(), SportCfg.parse("neon"))
    }

    @Test fun sportRoundTrip() {
        val c = SportCfg(
            theme = "amber", top = 9000, red = 7500, leds = false, pages = listOf(0, 2),
            arc = listOf("rpm", "oil", "afr", "rr:E19"), table = listOf("speed", "rpm", "boost", "battery", "coolant", "afr", "throttle", "intake", "gear"),
        )
        val s = c.format()
        assertFalse("a ';' would split the saved layout", ';' in s)
        assertEquals(c, SportCfg.parse(s))
    }

    @Test fun sportKeepsItsLimits() {
        // the red zone stays on the scale, a page list never ends up empty, a wrong number of values is ignored
        assertEquals(6000, SportCfg.parse("sd:top=6000|red=9000").red)
        assertEquals(listOf(0, 1, 2), SportCfg.parse("sd:pg=").pages)
        assertEquals(SportCfg.ARC_DEFAULT, SportCfg.parse("sd:a=rpm,oil").arc)
    }

    @Test fun navRoundTrip() {
        assertEquals("", NavCfg().format())
        val c = NavCfg(left = "coolant", right = "gear", gauge = "sti", map = "gta_v", zoom = 17.5, accent = "red", hints = false, extras = false)
        assertFalse(';' in c.format())
        assertEquals(c, NavCfg.parse(c.format()))
        assertEquals(19.0, NavCfg.parse("nav:z=40").zoom, 0.0)
    }

    @Test fun presetsUseRealCardsAndKeepTheRest() {
        val known = MyBoard.defaults.map { it.id }.toSet()
        PresetLayouts.forEach { p ->
            p.tiles.forEach { assertTrue("${p.id}: no card ${it.id}", it.id in known) }
            val parsed = MyBoard.parse(p.format())
            assertEquals("${p.id}: every card of the board is still there", known, parsed.map { it.id }.toSet())
            assertEquals(p.tiles.map { it.id }, parsed.filter { it.on }.map { it.id })
            assertEquals(p.free, MyBoard.isFree(p.format()))
            if (p.free) p.tiles.forEach { t ->
                val r = t.free!!
                assertTrue("${p.id}/${t.id} inside the area", r.x >= 0f && r.y >= 0f && r.x + r.w <= 1.001f && r.y + r.h <= 1.001f)
            } else MyBoard.rows(p.tiles).forEach { row -> assertTrue("${p.id}: a row over 12 columns", row.sumOf { it.span } <= 12) }
        }
        assertEquals(PresetLayouts.size, PresetLayouts.map { it.id }.distinct().size)
    }
}
