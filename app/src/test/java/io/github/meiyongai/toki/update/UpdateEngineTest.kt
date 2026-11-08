package io.github.meiyongai.toki.subtitle.update

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateEngineTest {
    private class Store(var state: UpdateState = UpdateState()) : UpdateStateStore {
        var failWrite = false
        override fun read() = state
        override fun write(state: UpdateState) { check(!failWrite); this.state = state }
    }
    private val installed = ReleaseVersion(2, 1, 23)
    private fun release(patch: Int = 24) = AppRelease(ReleaseVersion(2, 1, patch), "v2.1.$patch", "Features\nBug fixes")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun firstOpeningChecksOnceAcrossForegroundEntriesAndProcessRecreation() {
        val store = Store(); var calls = 0; val source = ReleaseSource { calls++; null }
        val now = time("2026-11-08T00:00:00Z")
        val first = UpdateEngine(installed, store, source, { now })
        assertEquals(UpdateResult.UpToDate, first.check(false))
        repeat(5) { assertEquals(UpdateResult.NotDue, first.check(false)) }
        assertEquals(UpdateResult.NotDue, UpdateEngine(installed, store, source, { now + 1_000 }).check(false))
        assertEquals(1, calls)
    }
    @Test fun monthlyScheduleUsesCalendarMonthsAndCanCheckOnTheNextOpening() {
        var now = time("2026-01-31T12:00:00Z"); val store = Store(); var calls = 0
        val engine = UpdateEngine(installed, store, ReleaseSource { calls++; null }, { now })
        engine.check(false)
        assertEquals(time("2026-02-28T12:00:00Z"), store.state.nextAutomaticAt)
        now = time("2026-02-28T11:59:59Z"); assertEquals(UpdateResult.NotDue, engine.check(false))
        now = time("2026-03-03T12:00:00Z"); assertEquals(UpdateResult.UpToDate, engine.check(false)); assertEquals(2, calls)
        assertEquals(time("2026-04-03T12:00:00Z"), store.state.nextAutomaticAt)
    }
    @Test fun leapYearAndYearRolloverAreHandled() {
        assertEquals(time("2028-02-29T03:00:00Z"), UpdateEngine.nextMonth(time("2028-01-31T03:00:00Z")))
        assertEquals(time("2027-01-08T03:00:00Z"), UpdateEngine.nextMonth(time("2026-12-08T03:00:00Z")))
    }
    @Test fun automaticFailureIsThrottledButManualRetryRemainsPossible() {
        val store = Store(); var calls = 0; var fail = true
        val engine = UpdateEngine(installed, store, ReleaseSource { calls++; if (fail) error("offline"); null }, { 1_000_000 })
        assertEquals(UpdateResult.Failed, engine.check(false))
        assertEquals(UpdateResult.NotDue, engine.check(false)); assertEquals(1, calls)
        fail = false; assertEquals(UpdateResult.UpToDate, engine.check(true)); assertEquals(2, calls)
        assertEquals(UpdateResult.NotDue, engine.check(false))
    }
    @Test fun noUpdateOrOlderPublicReleaseNeverReturnsAvailable() {
        for (release in listOf(null, AppRelease(installed, "v2.1.23", ""), release(22))) {
            val engine = UpdateEngine(installed, Store(), ReleaseSource { release }, { 1_000_000 })
            assertEquals(UpdateResult.UpToDate, engine.check(false))
        }
    }
    @Test fun sameNewVersionCanPromptAutomaticallyOnlyOnceAcrossRestartsAndMonths() {
        val store = Store(); var now = 1_000_000L; var calls = 0; val item = release()
        val source = ReleaseSource { calls++; item }
        val engine = UpdateEngine(installed, store, source, { now })
        assertEquals(UpdateResult.Available(item), engine.check(false))
        assertTrue(engine.claimNotice(item, false)); assertFalse(engine.claimNotice(item, false))
        now = store.state.nextAutomaticAt
        val recreated = UpdateEngine(installed, store, source, { now })
        assertEquals(UpdateResult.NotDue, recreated.check(false)); assertEquals(2, calls)
        assertFalse(recreated.claimNotice(item, false))
    }
    @Test fun explicitManualCheckCanRedisplayAnAlreadyAnnouncedNewVersion() {
        val store = Store(); val item = release(); val engine = UpdateEngine(installed, store, ReleaseSource { item }, { 1_000_000 })
        engine.check(false); assertTrue(engine.claimNotice(item, false))
        assertEquals(UpdateResult.Available(item), engine.check(true)); assertTrue(engine.claimNotice(item, true))
    }
    @Test fun newerReleaseGetsItsOwnOneTimeNoticeButRollbackCannotSpam() {
        val store = Store(); var now = 1_000_000L; var current = release(24)
        val engine = UpdateEngine(installed, store, ReleaseSource { current }, { now })
        engine.check(false); assertTrue(engine.claimNotice(current, false))
        now = store.state.nextAutomaticAt; current = release(25)
        assertEquals(UpdateResult.Available(current), engine.check(false)); assertTrue(engine.claimNotice(current, false))
        now = store.state.nextAutomaticAt; current = release(24)
        assertEquals(UpdateResult.NotDue, engine.check(false)); assertFalse(engine.claimNotice(current, false))
    }
    @Test fun fetchedButNotDisplayedReleaseSurvivesActivityOrProcessLossWithoutAnotherRequest() {
        val store = Store(); var calls = 0; val source = ReleaseSource { calls++; release() }
        val engine = UpdateEngine(installed, store, source, { 1_000_000 })
        assertTrue(engine.check(false) is UpdateResult.Available)
        val recreated = UpdateEngine(installed, store, source, { 1_000_001 })
        assertTrue(recreated.check(false) is UpdateResult.Available); assertEquals(1, calls)
        assertTrue(recreated.claimNotice(release(), false))
    }
    @Test fun installingTheOfferedVersionStopsTheOldPromptWithoutClearingHistory() {
        val store = Store(UpdateState(9_000_000, release(), installed))
        val engine = UpdateEngine(release().version, store, ReleaseSource { error("must not fetch") }, { 1_000_000 })
        assertEquals(UpdateResult.NotDue, engine.check(false)); assertFalse(engine.claimNotice(release(), true))
        assertNotNull(store.state.pending)
    }
    @Test fun failedThrottlePersistencePreventsNetworkAndNeverConsumesNotice() {
        val store = Store().apply { failWrite = true }; var calls = 0
        val engine = UpdateEngine(installed, store, ReleaseSource { calls++; release() }, { 1_000_000 })
        assertEquals(UpdateResult.Failed, engine.check(false)); assertEquals(0, calls)
        assertFalse(engine.claimNotice(release(), false)); assertNull(store.state.notified)
    }
    @Test fun failedNoticePersistenceCannotClaimAnAutomaticPrompt() {
        val store = Store(); val engine = UpdateEngine(installed, store, ReleaseSource { release() }, { 1_000_000 })
        engine.check(false); store.failWrite = true
        assertFalse(engine.claimNotice(release(), false)); assertNull(store.state.notified)
    }
    @Test fun concurrentChecksDoNotQueueExtraNetworkCalls() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val calls = AtomicInteger()
        val engine = UpdateEngine(installed, Store(), ReleaseSource { calls.incrementAndGet(); entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); null }, { 1_000_000 })
        val thread = Thread { engine.check(false) }; thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { assertEquals(UpdateResult.Busy, engine.check(false)); assertEquals(UpdateResult.Busy, engine.check(true)) }
        finally { finish.countDown(); thread.join(5_000) }
        assertEquals(1, calls.get()); assertFalse(thread.isAlive)
    }
    @Test fun twoEngineInstancesReserveTheSharedStoreBeforeNetworking() {
        val store = Store(); val entered = CountDownLatch(1); val finish = CountDownLatch(1); val calls = AtomicInteger()
        val source = ReleaseSource { calls.incrementAndGet(); entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); null }
        val first = UpdateEngine(installed, store, source, { 1_000_000 }); val other = UpdateEngine(installed, store, source, { 1_000_000 })
        val thread = Thread { first.check(false) }; thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { assertEquals(UpdateResult.NotDue, other.check(false)) } finally { finish.countDown(); thread.join(5_000) }
        assertEquals(1, calls.get())
    }
    @Test fun wallClockRollbackDoesNotCreateAnAutomaticRequestStorm() {
        val store = Store(); var now = 2_000_000L; var calls = 0
        val engine = UpdateEngine(installed, store, ReleaseSource { calls++; null }, { now })
        engine.check(false); now = 1_000_000
        repeat(10) { assertEquals(UpdateResult.NotDue, engine.check(false)) }; assertEquals(1, calls)
        assertEquals(UpdateResult.UpToDate, engine.check(true)); assertEquals(2, calls)
    }
}
