package kg.forester.sg

import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.LockState
import kg.forester.sg.ui.BottomBarCfg
import kg.forester.sg.ui.BottomSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LogicTest {
    @Test fun lockUpStates() {
        assertEquals(LockState.UNKNOWN, LockState.of(null, null, 0))
        assertEquals(LockState.OPEN, LockState.of(0f, 100f, 0))
        assertEquals(LockState.ENGAGING, LockState.of(40f, 20f, 0))
        assertEquals(LockState.ENGAGING, LockState.of(90f, 20f, 1000)) // commanded, still slipping, not for long
        assertEquals(LockState.LOCKED, LockState.of(90f, 1f, 0))
        assertEquals(LockState.LOCKED, LockState.of(90f, null, 0)) // no turbine speed: trust the command
        assertEquals(LockState.SLIPPING, LockState.of(90f, 15f, 3000))
    }

    @Test fun mafJunkIsDropped() {
        // 0xFFFF / 100 at idle: the 261 l/h report
        assertNull(EcuSnapshot(rpm = 800, mafGs = 655.35f).saneMaf().mafGs)
        assertNull(EcuSnapshot(rpm = 800, mafGs = 450f).saneMaf().mafGs)
        assertNull(EcuSnapshot(rpm = 1000, mafGs = 120f).saneMaf().mafGs)
        val idle = EcuSnapshot(rpm = 750, mafGs = 3.2f)
        assertSame(idle, idle.saneMaf())
        val wot = EcuSnapshot(rpm = 6500, mafGs = 280f) // EJ20 turbo at full boost
        assertSame(wot, wot.saneMaf())
    }

    @Test fun bottomBarConfig() {
        val l = BottomBarCfg.parse(BottomBarCfg.DEFAULT)
        assertEquals(listOf("range", "coolant", "battery", "intake", "altitude"), l.map { it.id })
        assertEquals(listOf(true, true, true, false, false), l.map { it.bar })
        assertEquals(BottomBarCfg.DEFAULT, BottomBarCfg.format(l))
        assertEquals(emptyList<BottomSlot>(), BottomBarCfg.parse(""))
        // repeats dropped, at most MAX
        assertEquals(listOf("a", "b"), BottomBarCfg.parse("a:1,b:0,a:0").map { it.id })
        assertEquals(BottomBarCfg.MAX, BottomBarCfg.parse((1..10).joinToString(",") { "x$it:0" }).size)
    }
}
