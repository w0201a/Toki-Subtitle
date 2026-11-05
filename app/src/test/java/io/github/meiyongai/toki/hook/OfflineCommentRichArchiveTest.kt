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
}
