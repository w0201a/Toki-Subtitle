package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaybackStateTest {
    @Test fun selectedOfflineVideoMayUseNetworkUnlessLocalOnlyWasExplicitlyChosen() {
        val state = OfflinePlaybackState()
        val selected = Any()
        state.select(selected, "offline", true)
        val revision = state.revision
        val ticket = LocalOnlyRequest { state.revision == revision && TranslationNetworkPolicy.allowsRemote(true, false, true) }
        assertTrue(ticket.permitsNetwork())
        state.select(Any(), "online", false)
        assertFalse(ticket.permitsNetwork())
        assertTrue(LocalOnlyRequest { TranslationNetworkPolicy.allowsRemote(true, false, true) }.permitsNetwork())
    }
    @Test fun repeatedProgressDoesNotChangeIdentityButNewHolderOrModeDoes() {
        val state = OfflinePlaybackState()
        val holder = Any()
        state.select(holder, "same", true)
        val revision = state.revision
        state.select(holder, "same", true)
        assertEquals(revision, state.revision)
        assertFalse(state.owns(Any(), "same"))
        state.clear(Any())
        assertEquals(revision, state.revision)
        state.retire(holder)
        assertTrue(state.offline)
        assertFalse(state.owns(holder, "same"))
        state.select(holder, "same", false)
        assertFalse(state.offline)
        assertTrue(state.revision > revision)
        state.clear()
        assertFalse(state.offline)
    }
    @Test fun playbackRevisionRejectsOldTasksEvenWhenTheNewVideoIsOnline() {
        val state = OfflinePlaybackState()
        state.select(Any(), "first", false)
        val revision = state.revision
        val task = LocalOnlyRequest { state.revision == revision && !state.offline }
        assertTrue(task.permitsNetwork())
        state.select(Any(), "second", false)
        assertFalse(task.permitsNetwork())
    }
    @Test fun offlineControlsAreIndependentDefaultOffAndLiveInstallable() {
        val key = OfflineVideoHook.KEY
        assertFalse(ConfigSchema.booleanDefault(key))
        val request = ConfigSnapshot(mapOf(key to true))
        ConfigSchema.validate(request.configuration())
        val startup = HostFeaturePlan(request)
        assertEquals(setOf("OfflineVideoHook"), startup.directHostFeatures)
        assertTrue(startup.symbols.isEmpty())
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("OfflineVideoHook"), late.liveCandidates(request))
        assertFalse(late.apply(request).boolean(key))
        assertTrue(late.admitLive("OfflineVideoHook"))
        assertTrue(late.apply(request).boolean(key))
    }
}
