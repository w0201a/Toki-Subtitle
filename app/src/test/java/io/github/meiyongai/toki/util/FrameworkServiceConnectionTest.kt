package io.github.meiyongai.toki.subtitle.util

import org.junit.Assert.*
import org.junit.Test

class FrameworkServiceConnectionTest {
    @Test fun oldBinderDeathCannotDisconnectItsLiveReplacement() {
        val session = FrameworkServiceConnection();val first = Any();val replacement = Any()
        var active = false;var disconnected = 0
        session.bind(first) { active = true };session.bind(replacement) { active = true }
        session.died(first) { active = false;disconnected++ }
        assertTrue(active);assertEquals(0, disconnected)
        session.died(replacement) { active = false;disconnected++ }
        assertFalse(active);assertEquals(1, disconnected)
        session.died(replacement) { fail("duplicate death callback") }
    }
    @Test fun equalButDifferentServiceObjectsDoNotShareOwnership() {
        val session = FrameworkServiceConnection();val first = String(charArrayOf('x'));val second = String(charArrayOf('x'))
        assertEquals(first, second);assertNotSame(first, second)
        session.bind(first) {};session.bind(second) {}
        session.died(first) { fail("identity must not use equals") }
        var died = false;session.died(second) { died = true };assertTrue(died)
    }
    @Test fun realDisconnectIsImmediateAndLaterRebindStillWorks() {
        val session = FrameworkServiceConnection();val first = Any();val next = Any();var active = false
        session.bind(first) { active = true };session.died(first) { active = false };assertFalse(active)
        session.bind(next) { active = true };assertTrue(active)
        session.died(first) { fail("already retired") };assertTrue(active)
    }
}
