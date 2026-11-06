package io.github.meiyongai.toki.subtitle.hook
import org.junit.Assert.*
import org.junit.Test
class HostOfflineReplyTotalTest {
    @Test fun nativeReplyCommentTotalStartsReplyPreparationEvenWhenReplyCountIsZero() {
        assertEquals(34L, HostOfflineComments.replyTotal(34,0,1))
        assertEquals(6L, HostOfflineComments.replyTotal(0,6,1))
        assertEquals(2L, HostOfflineComments.replyTotal(0,0,2))
        assertEquals(0L, HostOfflineComments.replyTotal(-1,-1,0))
    }
    @Test fun freshDeviceLocalReaderMatchesWithoutInitializingHostAccountContract() {
        val reader=HostOfflineComments(javaClass.classLoader!!)
        assertTrue(reader.accountMatches(HostOfflineComments.LOCAL_SCOPE))
        assertTrue(reader.accountMatches(HostOfflineComments.LOCAL_SCOPE))
        assertFalse(reader.accountMatches("not-a-local-scope"))
    }

    @Test fun serverReportedTotalLargerThanThePageDoesNotMeanThePageWasTruncated() {
        val row=OfflineCommentData.Row("c","author","handle","u","source",null,"",0,0,mediaKnown=true)
        val page=HostOfflineComments.capturedSnapshot(HostOfflineComments.LOCAL_SCOPE,"video",10,List(20){row.copy(id="c$it")},2500,true,20,false)
        assertEquals(20,page.rows.size);assertEquals(2500L,page.total);assertTrue(page.hasMore);assertFalse(page.truncated)
        assertEquals(20L,OfflineCommentDownloadProgress.advance(0,page))
        assertTrue(HostOfflineComments.capturedSnapshot(HostOfflineComments.LOCAL_SCOPE,"video",10,listOf(row),2500,true,20,true).truncated)
    }

}
