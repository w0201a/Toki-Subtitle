package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import org.junit.Assert.*
import org.junit.Test

class OfflineCommentRichPolicyTest {
    private fun row(id: String, translated: String? = null, parent: String = "") = OfflineCommentData.Row(id,"a","h","u",id,translated,"zh-hans",if(parent.isEmpty())0 else 1,0L,parentId=parent)
    @Test fun canonicalLimitSupportsExactCountsAndPageShortcutUpTo2500() {
        assertEquals(200, OfflineCommentPreferences.limit(null))
        assertEquals(125, OfflineCommentPreferences.pages(2500)); assertEquals(3, OfflineCommentPreferences.pages(41))
        assertEquals(2500, OfflineCommentPreferences.fromPages(125))
        for (value in listOf("1","20","2500")) ConfigSchema.validate(mapOf(OfflineCommentPreferences.LIMIT to value))
        for (value in listOf("0","2501","-1","1.5")) assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf(OfflineCommentPreferences.LIMIT to value)) }
        for (mode in OfflineCommentPreferences.modes) ConfigSchema.validate(mapOf(OfflineCommentPreferences.MODE to mode))
    }
    @Test fun repliesShareTheSameBudgetAndDuplicateRowsDoNotConsumeNewSlots() {
        val known = setOf("top", "reply")
        val result = OfflineCommentBudget.merge(listOf(row("top", "已译")), listOf(row("top"),row("reply",parent="top"),row("extra")),known,2)
        assertEquals(listOf("top","reply"),result.map { it.id }); assertEquals("已译",result.first().translation)
    }
    @Test fun loweringALimitNeverDeletesExistingRowsAndCannotAdmitExtraRows() {
        val old = (0..9).map { row("c$it") }
        val result = OfflineCommentBudget.merge(old, listOf(row("new")),old.mapTo(hashSetOf()){it.id},3)
        assertEquals(old,result)
    }
    @Test fun nativeSpeedOwnershipIsPerPressVideoAndRevisionAndReleaseRestoresMenuEligibility() {
        val arbiter=OfflineHoldArbiter(); val owner=Any(); val video=Any(); val other=Any()
        arbiter.candidate(owner,video,1L); assertFalse(arbiter.owns(video,1L))
        arbiter.began(owner,true); assertTrue(arbiter.owns(video,1L)); assertFalse(arbiter.owns(other,1L)); assertFalse(arbiter.owns(video,2L))
        arbiter.finish(owner); assertFalse(arbiter.owns(video,1L))
        arbiter.candidate(owner,video,2L);arbiter.began(owner,false);assertFalse(arbiter.owns(video,2L))
    }
    @Test fun nativeRebindAndNewPressCannotInheritAPreviousSpeedClaim() {
        val arbiter=OfflineHoldArbiter();val owner=Any();val video=Any()
        arbiter.candidate(owner,video,1L);arbiter.began(owner,true)
        arbiter.candidate(owner,video,1L);assertFalse(arbiter.owns(video,1L))
        arbiter.began(owner,true);arbiter.clear();assertFalse(arbiter.owns(video,1L))
    }
}
