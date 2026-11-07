package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentReaderIndexTest {
    private fun row(id:String,parent:String="",source:String=id)=OfflineCommentData.Row(id,"Author","handle","user",source,null,"",if(parent.isBlank())0 else 1,0,parentId=parent,mediaKnown=true)
    @Test fun previewExpandAndParentLookupUseOneConsistentThreadIndex() {
        val rows=listOf(row("a"),row("a1","a"),row("a2","a"),row("a3","a2"),row("a4","a3"),row("b"),row("b1","b"))
        val index=CommentReaderIndex(rows)
        assertEquals(listOf("a","a1","a2","b","b1"),index.visible(emptySet(),2).map{it.id})
        assertEquals(listOf("a","a1","a2","a3","a4","b","b1"),index.visible(setOf("a"),2).map{it.id})
        assertEquals(4,index.replies("a").size)
        assertEquals("a2",index.byId["a3"]?.parentId)
        assertTrue(index.matches("a3","a3"));assertFalse(index.matches("a3","changed source"))
    }
    @Test fun orphanCycleAndOverdeepRepliesCannotEnterAnUnrelatedThread() {
        val index=CommentReaderIndex(listOf(row("a"),row("one","a"),row("two","one"),row("three","two"),row("four","three"),row("five","four"),row("orphan","missing"),row("x","y"),row("y","x")))
        assertEquals(listOf("one","two","three","four"),index.replies("a").map{it.id})
        assertEquals(3,index.visible(emptySet(),1).size)
        assertEquals(listOf("a","one","two","three","four"),index.visible(setOf("a"),1).map{it.id})
    }
    @Test fun largeSnapshotIsConsumedOnlyWhileBuildingTheIndexNotOnEveryAdapterLookup() {
        val data=(1..200).flatMap { n -> listOf(row("root$n"))+(1..15).map{r->row("r$n-$r","root$n")} }
        var accesses=0
        val counted=object:AbstractList<OfflineCommentData.Row>() { override val size get()=data.size;override fun get(index:Int)=data[index].also{accesses++} }
        val index=CommentReaderIndex(counted);val built=accesses
        repeat(100){index.byId["r100-15"];index.replies("root100");index.matches("r100-15","r100-15");index.visible(setOf("root1"),40)}
        assertEquals("Adapter operations must not reread the original snapshot list",built,accesses)
        assertEquals(200,index.roots.size);assertEquals(15,index.replies("root100").size)
    }
    @Test fun replacingSnapshotCannotKeepOldTranslationOrSourceEligibility() {
        val old=row("a",source="old");val before=CommentReaderIndex(listOf(old))
        val after=CommentReaderIndex(listOf(old.copy(source="new",translation="译文")))
        assertTrue(before.matches("a","old"));assertFalse(after.matches("a","old"))
        assertEquals("译文",after.visible(emptySet(),40).single().translation)
    }
}
