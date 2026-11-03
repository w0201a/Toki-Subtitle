package io.github.meiyongai.toki.subtitle.provider

import org.junit.Assert.*
import org.junit.Test

class OperationJournalTest {
    @Test fun recoverableCandidateFailureDoesNotFinishTheOperationAndRetainsOnlyTheErrorType() {
        val journal = OperationJournal({}); val run = journal.start(OperationJournal.Kind.STICKER_EXPORT)
        run.stage(OperationJournal.Step.SOURCE_FETCH); run.attemptFailed(java.io.IOException("private source URL"))
        run.stage(OperationJournal.Step.PUBLISH); run.finish(OperationJournal.Result.SUCCESS)
        val text = journal.render()
        assertTrue(text.contains("本次操作成功")); assertTrue(text.contains("候选失败次数=1"))
        assertTrue(text.contains("IOException")); assertFalse(text.contains("private source URL"))
    }

    @Test fun oneOperationPreservesReadablePhasesNumericEvidenceDurationAndTerminalResult() {
        var time = 0L; val lines = mutableListOf<String>()
        val journal = OperationJournal(lines::add, wall = { 0 }, clock = { time })
        val run = journal.start(OperationJournal.Kind.OFFLINE_EXPORT)
        time = 12_000_000; run.stage(OperationJournal.Step.SAMPLE_CHECK, mapOf("samples" to 9L))
        time = 23_000_000; run.stage(OperationJournal.Step.READBACK, mapOf("outputBytes" to 4096L))
        time = 25_000_000; run.finish(OperationJournal.Result.SUCCESS)
        val text = journal.render()
        assertTrue(text.contains("OFFLINE_EXPORT-1")); assertTrue(text.contains("本次操作成功"))
        assertTrue(text.contains("耗时=25ms")); assertTrue(text.contains("+12ms 校验本地轨道与样本 samples=9"))
        assertTrue(text.contains("outputBytes=4096")); assertTrue(text.contains("1970-01-01T00:00:00Z"))
        assertEquals(4, lines.size)
    }
    @Test fun firstTerminalResultWinsAndLateStagesOrCatchHandlersCannotChangeIt() {
        val journal = OperationJournal({})
        val run = journal.start(OperationJournal.Kind.STICKER_EXPORT)
        run.finish(OperationJournal.Result.UNAVAILABLE, OperationJournal.Reason.CODE_MISMATCH)
        run.stage(OperationJournal.Step.PUBLISH)
        run.finish(OperationJournal.Result.SUCCESS)
        assertTrue(journal.render().contains("宿主代码不匹配")); assertFalse(journal.render().contains("本次操作成功"))
    }
    @Test fun emptyLogsAreUnknownNotSuccessAndRunningWorkHasNextStepAdvice() {
        val journal = OperationJournal({})
        assertTrue(journal.render().contains("没有记录到")); assertFalse(journal.render().contains("本次操作成功"))
        journal.start(OperationJournal.Kind.OFFLINE_EXPORT)
        assertTrue(journal.render().contains("进行中，尚无结果")); assertTrue(journal.render().contains("保留宿主运行"))
    }
    @Test fun historyAndRecordCapacitiesDeclareOverwrittenRecordsInsteadOfGrowingUnbounded() {
        val journal = OperationJournal({}, capacity = 2)
        val first = journal.start(OperationJournal.Kind.OFFLINE_EXPORT)
        repeat(30) { first.stage(OperationJournal.Step.CACHE_LOOKUP, mapOf("candidateKeys" to it.toLong())) }
        assertEquals(16, journal.render().lineSequence().count { it.startsWith("    +") })
        journal.start(OperationJournal.Kind.STICKER_EXPORT); journal.start(OperationJournal.Kind.STICKER_EXPORT)
        first.finish(OperationJournal.Result.SUCCESS)
        assertTrue(journal.render().contains("历史覆盖=1")); assertFalse(journal.render().contains("OFFLINE_EXPORT-1"))
    }
    @Test fun metricsCannotCarryArbitraryMessagesAndThrownMessagesNeverEnterTheJournal() {
        val journal = OperationJournal({}); val run = journal.start(OperationJournal.Kind.STICKER_EXPORT)
        assertThrows(IllegalArgumentException::class.java) { run.stage(OperationJournal.Step.CODE_CHECK, mapOf("https://secret" to 1)) }
        assertThrows(IllegalArgumentException::class.java) { run.stage(OperationJournal.Step.CODE_CHECK, mapOf("bytes" to -1)) }
        run.stage(OperationJournal.Step.ANIMATION_DECODE)
        run.failed(IllegalStateException("user text https://private.example/key"))
        val text = journal.render()
        assertFalse(text.contains("user text")); assertFalse(text.contains("private.example"))
        assertTrue(text.contains("IllegalStateException")); assertTrue(text.contains("不受支持"))
    }
    @Test fun cancelledAndReadbackFailuresStayDistinctAndLoggerFailureDoesNotBreakTheJob() {
        val journal = OperationJournal({ throw IllegalStateException("logger unavailable") })
        val first = journal.start(OperationJournal.Kind.OFFLINE_EXPORT)
        first.stage(OperationJournal.Step.READBACK); first.failed(IllegalStateException())
        val second = journal.start(OperationJournal.Kind.OFFLINE_EXPORT)
        second.failed(IllegalStateException(), cancelled = true)
        assertTrue(journal.render().contains("源文件或结果与校验信息不一致"))
        assertTrue(journal.render().contains("本次已取消"))
    }

    @Test fun newestFailureIsVisibleDespiteOlderSuccessInRetainedWindow() {
        val journal = OperationJournal({})
        journal.start(OperationJournal.Kind.OFFLINE_EXPORT).finish(OperationJournal.Result.SUCCESS)
        journal.start(OperationJournal.Kind.OFFLINE_EXPORT).finish(OperationJournal.Result.UNAVAILABLE, OperationJournal.Reason.CACHE_MISSING)
        val brief = journal.brief()
        assertTrue(brief.contains("最近 op=OFFLINE_EXPORT-2 [当前条件不可用]"))
        assertTrue(brief.contains("上报成功=1")); assertTrue(brief.contains("不可用=1"))
        assertTrue(brief.contains("没有找到对应本地缓存"))
    }
    @Test fun briefMakesWindowEvictionMissingEvidenceAndRunningWorkExplicit() {
        val journal = OperationJournal({}, capacity = 2)
        journal.start(OperationJournal.Kind.OFFLINE_EXPORT).finish(OperationJournal.Result.SUCCESS)
        journal.start(OperationJournal.Kind.STICKER_EXPORT)
        journal.start(OperationJournal.Kind.STICKER_EXPORT)
        val pair = journal.capture()
        assertTrue(pair.second.contains("全局已覆盖记录=1"))
        assertTrue(pair.second.contains("离线视频导出：保留窗口没有操作证据"))
        assertTrue(pair.second.contains("进行中=2")); assertTrue(pair.second.contains("仍无终态"))
        assertTrue(pair.first.contains("STICKER_EXPORT-3")); assertTrue(pair.second.contains("STICKER_EXPORT-3"))
    }
}
