package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OfflineCommentRichArchiveTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val account="a".repeat(64)
    private fun row(id:String,parent:String="")=OfflineCommentData.Row(id,"author","h","uid",id,"译文","zh-hans",if(parent.isEmpty())0 else 1,0L,parentId=parent,mediaKnown=true)
    private fun page(video:String,rows:List<OfflineCommentData.Row>)=OfflineCommentData.Snapshot(account,video,1L,rows,2600L,false,false)
    @Test fun fullDisplayCanReach2500WhileStoredPagesRemainBoundedAndExistingExtraRowsRemain() {
        val video="large";for(i in 0..129)OfflineCommentArchive.save(app,page(video,(0..19).map{row("c${i*20+it}")}),i*20L)
        val result=OfflineCommentArchive.display(app,account,video,2500)!!
        assertEquals(2500,result.snapshot.rows.size);assertEquals("c2499",result.snapshot.rows.last().id)
        assertEquals(100,OfflineCommentArchive.display(app,account,video,100)!!.snapshot.rows.size)
        assertNotNull(OfflineCommentArchive.page(app,account,video,2580L))
    }
    @Test fun repliesRemainInTheLocalTreeWithinTheSharedLimit() {
        val video="tree";OfflineCommentArchive.save(app,page(video,listOf(row("top"),row("second"))),0L)
        OfflineCommentArchive.save(app,page(video,listOf(row("r1","top"),row("r2","top"))),0L,"top")
        val rows=OfflineCommentArchive.display(app,account,video,3)!!.snapshot.rows
        assertEquals(listOf("top","r1","r2"),rows.map{it.id})
        assertEquals("top",rows[1].parentId)
    }
    @Test fun perVideoLimitsAreAccountIsolatedAndRestoringDefaultDoesNotDeletePages() {
        OfflineCommentArchive.save(app,page("v",listOf(row("one"))),0L)
        OfflineCommentLimitOverrides.set(app,account,"v",40)
        assertEquals(40,OfflineCommentLimitOverrides.effective(app,account,"v",2500))
        assertEquals(2500,OfflineCommentLimitOverrides.effective(app,"b".repeat(64),"v",2500))
        OfflineCommentLimitOverrides.set(app,account,"v",null)
        assertEquals(2500,OfflineCommentLimitOverrides.effective(app,account,"v",2500))
        assertNotNull(OfflineCommentArchive.page(app,account,"v",0L))
    }
    @Test fun cachedMediaFilesAreAccountVideoBoundAndMissingExportNeverDownloads() {
        val asset=OfflineCommentData.Media("c".repeat(64),OfflineCommentData.MediaKind.STICKER,listOf("https://p16.tiktokcdn.com/image.gif"),true)
        val bytes="GIF89a-test-original-sequence".toByteArray()
        assertNull(OfflineCommentMediaStore.file(app,account,"v",asset))
        var validated=0
        OfflineCommentMediaStore.store(app,account,"v",asset,bytes,{true}){validated++}
        assertEquals(1,validated);assertArrayEquals(bytes,OfflineCommentMediaStore.bytes(app,account,"v",asset))
        assertNull(OfflineCommentMediaStore.file(app,account,"other",asset))
        assertNull(OfflineCommentMediaStore.file(app,"b".repeat(64),"v",asset))
        assertThrows(IllegalStateException::class.java){OfflineCommentMediaStore.bytes(app,account,"missing",asset)}
    }
    @Test fun cancelledMediaWriteDoesNotRegisterAnIncompleteAsset() {
        val asset=OfflineCommentData.Media("d".repeat(64),OfflineCommentData.MediaKind.IMAGE,listOf("https://p16.tiktokcdn.com/image.png"))
        assertThrows(IllegalStateException::class.java){OfflineCommentMediaStore.store(app,account,"v",asset,"GIF89a-test".toByteArray(),{false}){}}
        assertNull(OfflineCommentMediaStore.file(app,account,"v",asset))
    }
    @Test fun savedPerVideoLimitIsExplicitAndRestoringDefaultUsesTheActualSetting() {
        assertNull(OfflineCommentLimitOverrides.savedLimit(app, account, "inherited"))
        assertEquals(200, OfflineCommentLimitOverrides.effective(app, account, "inherited", 200))
        OfflineCommentLimitOverrides.set(app, account, "inherited", 2500)
        assertEquals(2500, OfflineCommentLimitOverrides.savedLimit(app, account, "inherited"))
        assertEquals(2500, OfflineCommentLimitOverrides.effective(app, account, "inherited", 200))
        OfflineCommentLimitOverrides.set(app, account, "inherited", null)
        assertEquals(200, OfflineCommentLimitOverrides.effective(app, account, "inherited", 200))
    }

    @Test fun separateBudgetsKeepAllRootsAndBoundEachThreadAfterDurableReopen() {
        val video = "independent-budgets"
        val roots = listOf(row("top1").copy(replyCount = 10), row("top2").copy(replyCount = 10), row("top3"))
        OfflineCommentArchive.save(app, page(video, roots), 0)
        OfflineCommentArchive.save(app, page(video, (1..8).map { row("a$it", "top1") }), 0, "top1")
        OfflineCommentArchive.save(app, page(video, (1..8).map { row("b$it", "top2") }), 0, "top2")
        val quotas = OfflineCommentBudget.Quotas(3, 4, 2)
        val actual = OfflineCommentArchive.scoped(app, account, video, quotas)!!.snapshot.rows
        assertEquals(3, quotas.rootCount(actual)); assertEquals(4, quotas.replyCount(actual))
        assertEquals(2, actual.count { it.parentId == "top1" }); assertEquals(2, actual.count { it.parentId == "top2" })
        assertNotNull(OfflineCommentArchive.page(app, account, video, 0, "top1"))
        val lower = OfflineCommentArchive.scoped(app, account, video, OfflineCommentBudget.Quotas(3, 0, 0))!!.snapshot.rows
        assertEquals(3, lower.size)
        assertEquals(8, OfflineCommentArchive.page(app, account, video, 0, "top1")!!.rows.size)
    }

    @Test fun overlappingEmbeddedAndReplyPagesConsumeOnlyUniqueReplySlotsAndReportDiskCountsSeparately() {
        val video = "overlapping-counts"
        val top = row("root").copy(replyCount = 4)
        val r1 = row("r1", "root"); val r2 = row("r2", "root"); val r3 = row("r3", "root"); val r4 = row("r4", "root")
        OfflineCommentArchive.save(app, page(video, listOf(top, r1)), 0)
        OfflineCommentArchive.save(app, page(video, listOf(r1, r2)), 0, "root")
        OfflineCommentArchive.save(app, page(video, listOf(r2, r3, r4)), 20, "root")
        val read = OfflineCommentArchive.scoped(app, account, video, OfflineCommentBudget.Quotas(1, 3, 3))!!
        assertEquals(listOf("root", "r1", "r2", "r3"), read.snapshot.rows.map { it.id })
        assertEquals(1, read.stored!!.comments); assertEquals(4, read.stored!!.replies)
        val lower = OfflineCommentArchive.scoped(app, account, video, OfflineCommentBudget.Quotas(1, 1, 1))!!
        assertEquals(2, lower.snapshot.rows.size); assertEquals(4, lower.stored!!.replies)
    }
    @Test fun durableWriteEventsAreAccountVideoBoundAndStopWhenUnsubscribed() {
        val changes = mutableListOf<Pair<String,String>>()
        val subscription = OfflineCommentArchive.observe { account, video -> changes += account to video }
        OfflineCommentArchive.save(app, page("live-events", listOf(row("one"))), 0)
        assertTrue(account to "live-events" in changes)
        val count = changes.size
        subscription.close()
        OfflineCommentArchive.save(app, page("live-events", listOf(row("two"))), 20)
        assertEquals(count, changes.size)
    }

    @Test fun legacyZeroReplyCountPagesAreNotTreatedAsVerifiedWhenMediaIsAlreadyKnown() {
        val video = "reply-total-upgrade"
        val old = page(video, listOf(row("root").copy(replyCount = 0)))
        OfflineCommentArchive.save(app, old, 0)
        val quotas = OfflineCommentBudget.Quotas(200, 1000, 15)
        assertFalse(OfflineCommentArchive.replyTotalsVerified(app, account, video, 0))
        assertEquals(OfflineCommentArchive.Page(0, ""), OfflineCommentArchive.metadataPage(app, account, video, 200, quotas = quotas))
        val fresh = old.copy(rows = listOf(old.rows.single().copy(replyCount = 34)))
        OfflineCommentArchive.save(app, fresh, 0, verifiedReplyTotals = true)
        assertTrue(OfflineCommentArchive.replyTotalsVerified(app, account, video, 0))
        assertNull(OfflineCommentArchive.metadataPage(app, account, video, 200, quotas = quotas))
        val read = OfflineCommentArchive.scoped(app, account, video, quotas)!!.snapshot
        assertEquals(34L, read.rows.single().replyCount)
        assertEquals(OfflineCommentStreamPlan.Step.Replies("root", 0), OfflineCommentStreamPlan.next(read, true, false, 200, true, quotas))
        assertEquals(old.rows.single().source, read.rows.single().source)
        assertEquals(old.rows.single().translation, read.rows.single().translation)
    }

}
