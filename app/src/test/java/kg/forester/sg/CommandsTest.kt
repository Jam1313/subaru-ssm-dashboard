package kg.forester.sg

import kg.forester.sg.service.Commands
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsTest {
    /** ~110 Russian phrases (navigation, music, volume, radio, values, wake word) parse to the expected command. */
    @Test fun grammar() = assertEquals(emptyList<String>(), Commands.selfTest())
}
