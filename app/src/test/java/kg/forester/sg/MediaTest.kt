package kg.forester.sg

import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.RadioLogos
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MediaTest {
    @Test fun stationsNamingTheFrequencyComeFirst() {
        val l = listOf(
            RadioLogos.Station("Юмор FM", ""),
            RadioLogos.Station("Радио 101.75", ""), // another frequency
            RadioLogos.Station("Хит 1101.7", ""), // not a frequency
            RadioLogos.Station("Европа Плюс Бишкек 101,7 FM", ""),
        )
        assertEquals("Европа Плюс Бишкек 101,7 FM", RadioLogos.withFreqFirst(l, "101.70").first().name)
        assertEquals("Юмор FM", RadioLogos.withFreqFirst(l, "99.90").first().name)
    }

    @Test fun trackNumbersLeaveTheName() {
        assertEquals("Highway Lights", LocalMusic.pretty(File("01 - Highway Lights.mp3")))
        assertEquals("Дождь на стекле", LocalMusic.pretty(File("2 Дождь на стекле.mp3")))
        assertEquals("Song", LocalMusic.pretty(File("03. Song.flac")))
        assertEquals("1999", LocalMusic.pretty(File("1999.mp3"))) // a title that is a number stays
        assertEquals("Intro", LocalMusic.pretty(File("Intro.mp3")))
    }
}
