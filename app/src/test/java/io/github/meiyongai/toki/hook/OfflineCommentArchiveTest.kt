package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OfflineCommentArchiveTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val account="a".repeat(64)
    private fun row(id:String,reply:String="",translation:String?="译文")=OfflineCommentData.Row(id,"a","h","u","source-$id",translation,"zh-hans",if(reply.isEmpty())0 else 1,0,parentId=reply)
    private fun page(video:String,rows:List<OfflineCommentData.Row>,more:Boolean=false,cursor:Long?=null,total:Long=rows.size.toLong())=
        OfflineCommentData.Snapshot(account,video,10,rows,total,more,false,cursor)
    @Test fun originalAndTranslationSurviveReopenAndAccountVideoIsolation() {
        val p=page("reopen",listOf(row("c")))
        OfflineCommentArchive.save(context,p,0)
        OfflineCommentArchive.status(context,account,p.video,"complete")
        assertEquals(p.rows,OfflineCommentArchive.window(context,account,p.video)!!.snapshot.rows)
        assertNull(OfflineCommentArchive.window(context,"b".repeat(64),p.video))
        assertNull(OfflineCommentArchive.window(context,account,"other"))
        assertEquals("complete",OfflineCommentArchive.state(context,account,p.video))
        OfflineCommentArchive.saveModel(context,account,p.video,"{\"aid\":\"reopen\"}")
        assertEquals("{\"aid\":\"reopen\"}",OfflineCommentArchive.model(context,account,p.video))
    }
    @Test fun manyPagesAndRepliesRemainAvailableBeyondSingleSnapshotWindow() {
        val video="pages";val top=(0..499).map{row("c$it")}
        OfflineCommentArchive.save(context,page(video,top,total=501),0)
        OfflineCommentArchive.save(context,page(video,listOf(row("c500")),total=501),500)
        OfflineCommentArchive.save(context,page(video,listOf(row("r1","c0")),total=1),0,"c0")
        val first=OfflineCommentArchive.window(context,account,video)!!
        assertEquals(500,first.snapshot.rows.size);assertEquals("c0",first.snapshot.rows[0].id)
        assertEquals("r1",first.snapshot.rows[1].id);assertEquals(500,first.next)
        val second=OfflineCommentArchive.window(context,account,video,first.next!!)!!
        assertEquals(listOf("c499","c500"),second.snapshot.rows.map{it.id});assertNull(second.next)
        assertEquals(501,first.snapshot.total)
    }
    @Test fun moreThan100VideosDoNotEvictOrRefuseTheNextVideo() {
        for(i in 0..101)OfflineCommentArchive.save(context,page("many-$i",listOf(row("c"))),0)
        assertNotNull(OfflineCommentArchive.window(context,account,"many-0"))
        assertNotNull(OfflineCommentArchive.window(context,account,"many-101"))
    }
    @Test fun cancellationAndMissingPageNeverEraseEarlierCache() {
        val p=page("cancel",listOf(row("c")))
        OfflineCommentArchive.save(context,p,0)
        assertThrows(IllegalStateException::class.java){OfflineCommentArchive.save(context,p.copy(rows=listOf(row("new"))),0,active={false})}
        assertEquals(p.rows,OfflineCommentArchive.page(context,account,p.video,0)!!.rows)
        assertNull(OfflineCommentArchive.page(context,account,p.video,99))
        assertEquals("missing",OfflineCommentArchive.state(context,account,p.video))
    }
    @Test fun translationsAreStoredWithExactOriginalAndReplyIdentity() {
        val p=page("translation",listOf(row("c",translation=null)))
        OfflineCommentArchive.save(context,p,0)
        OfflineCommentArchive.updateTranslation(context,p,p.rows[0],"zh-hans","已翻译")
        val r=OfflineCommentArchive.page(context,account,p.video,0)!!.rows.single()
        assertEquals("source-c",r.source);assertEquals("已翻译",r.translation)
        assertNull(OfflineCommentArchive.page(context,account,p.video,0,"other-root"))
    }
    @Test fun incompleteCountsAndMissingTranslationsCannotBePresentedComplete() {
        val p=page("coverage",listOf(row("c").copy(replyCount=1)),total=2)
        OfflineCommentArchive.save(context,p,0)
        assertFalse(OfflineCommentArchive.coverage(context,account,p.video))
        OfflineCommentArchive.save(context,page(p.video,listOf(row("c2")),total=2),20)
        assertFalse(OfflineCommentArchive.coverage(context,account,p.video))
        OfflineCommentArchive.save(context,page(p.video,listOf(row("r","c"))),0,"c")
        assertTrue(OfflineCommentArchive.coverage(context,account,p.video))
        OfflineCommentArchive.status(context,account,p.video,"partial","offline")
        assertTrue(OfflineCommentArchive.window(context,account,p.video)!!.snapshot.truncated)
    }
}
