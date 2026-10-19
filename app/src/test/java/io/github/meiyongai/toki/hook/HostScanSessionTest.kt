package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

/** 验证扫描缓存提交和用户确认提示的完整顺序。 */
class HostScanSessionTest {
    @Test fun extensionProgressReportsRealWorkWithoutReusingCorePercent() {
        val session = HostScanSession()
        session.begin(); session.progress(10, 10); session.extensions()
        session.extensionProgress("relationships", 2, 7)
        assertEquals(0, session.status.total)
        assertEquals(2, session.status.extensionCompleted)
        assertEquals(7, session.status.extensionTotal)
        assertThrows(IllegalArgumentException::class.java) { session.extensionProgress("members", 8, 7) }
        session.saving()
        assertThrows(IllegalStateException::class.java) { session.extensionProgress("members", 0, 0) }
    }
    @Test fun workerBudgetSurvivesCoreAndExtensionProgress() {
        val session = HostScanSession()
        session.begin(6); session.progress(1, 2); session.progress(2, 2)
        assertEquals(6, session.status.workers)
        session.extensions(); session.extensionProgress("members", 1, 2)
        assertEquals(6, session.status.workers)
    }
    @Test fun partialInstallationDoesNotStayReady() {
        val session = HostScanSession()
        session.begin()
        session.extensions()
        session.saving()
        session.ready()
        session.partial()
        assertEquals(HostScanPhase.PARTIAL, session.status.phase)
        session.dismissResult()
        assertEquals(HostScanPhase.IDLE, session.status.phase)
    }
    @Test fun completedAdaptationInstallsBeforeShowingReadyAndCannotInstallTwice() {
        val session = HostScanSession()
        session.begin()
        session.extensions()
        session.saving()
        var installs = 0
        session.ready {
            assertEquals(HostScanPhase.SAVING, session.status.phase)
            installs++
        }
        assertEquals(HostScanPhase.READY, session.status.phase)
        assertThrows(IllegalStateException::class.java) { session.ready { installs++ } }
        assertEquals(1, installs)
    }
    @Test fun failedInstallationCannotShowReady() {
        val session = HostScanSession()
        session.begin()
        session.extensions()
        session.saving()
        assertThrows(IllegalStateException::class.java) { session.ready { error("install failed") } }
        assertEquals(HostScanPhase.SAVING, session.status.phase)
        session.fail()
        assertEquals(HostScanPhase.FAILED, session.status.phase)
    }
    @Test fun extensionScanDoesNotKeepCompletedCoreProgress() {
        val session = HostScanSession()
        session.begin()
        session.progress(100, 100)
        session.extensions()
        assertEquals(HostScanPhase.EXTENSIONS, session.status.phase)
        assertEquals(0, session.status.total)
        assertThrows(IllegalStateException::class.java) { session.ready() }
        session.saving()
        session.ready()
        assertEquals(HostScanPhase.READY, session.status.phase)
    }
    @Test fun cachedCoreCanRunOnlyExtensionDiscovery() {
        val session = HostScanSession()
        session.begin()
        session.extensions()
        session.saving()
        session.ready()
        assertEquals(HostScanPhase.READY, session.status.phase)
    }
    /** 保存成功后保留完成摘要，直到用户确认提示。@return Unit。Callers: JUnit。 */
    @Test fun savedResultWaitsForUserAcknowledgement() {
        val session = HostScanSession()
        session.begin()
        session.progress(0, 100)
        session.progress(100, 100)
        session.saving()
        assertEquals(HostScanPhase.SAVING, session.status.phase)
        session.ready()
        assertEquals(HostScanPhase.READY, session.status.phase)
        assertEquals(100, session.status.completed)
        session.dismissResult()
        assertEquals(HostScanPhase.IDLE, session.status.phase)
    }

    /** 失败原因保留到用户确认，确认不伪造扫描成功。@return Unit。Callers: JUnit。 */
    @Test fun failureCanBeAcknowledgedWithoutPublishingSuccess() {
        val session = HostScanSession()
        session.begin()
        session.progress(10, 10)
        session.saving()
        session.fail()
        assertEquals(HostScanPhase.FAILED, session.status.phase)
        session.dismissResult()
        assertEquals(HostScanPhase.IDLE, session.status.phase)
    }

    /** 查找和保存尚未完成时不能确认结果。@return Unit。Callers: JUnit。 */
    @Test fun unfinishedScanCannotBeDismissed() {
        val session = HostScanSession()
        session.begin()
        assertThrows(IllegalStateException::class.java) { session.dismissResult() }
        session.progress(1, 1)
        session.saving()
        assertThrows(IllegalStateException::class.java) { session.dismissResult() }
        assertEquals(HostScanPhase.SAVING, session.status.phase)
    }

    /** 验证进度倒退明确失败，不显示虚构进度。@return Unit。Callers: JUnit。 */
    @Test(expected = IllegalArgumentException::class) fun backwardProgressIsRejected() {
        val session = HostScanSession()
        session.begin()
        session.progress(20, 100)
        session.progress(10, 100)
    }

    /** 验证扫描未结束时不能进入缓存提交阶段。@return Unit。Callers: JUnit。 */
    @Test(expected = IllegalStateException::class) fun incompleteScanCannotBeSaved() {
        val session = HostScanSession()
        session.begin()
        session.progress(99, 100)
        session.saving()
    }
}
