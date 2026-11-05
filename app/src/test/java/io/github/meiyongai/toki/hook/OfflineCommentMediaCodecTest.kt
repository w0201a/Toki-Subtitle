package io.github.meiyongai.toki.subtitle.hook

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class OfflineCommentMediaCodecTest {
    private fun page()=OfflineCommentData.Snapshot("a".repeat(64),"video",1L,listOf(
        OfflineCommentData.Row("c","a","h","u","text","译文","zh-hans",0,1L,media=listOf(
            OfflineCommentData.Media("b".repeat(64),OfflineCommentData.MediaKind.IMAGE,listOf("https://p16.tiktokcdn.com/image.png?token=x")),
            OfflineCommentData.Media("c".repeat(64),OfflineCommentData.MediaKind.STICKER,listOf("https://p16.tiktokcdn.com/sticker.gif"),true)),mediaKnown=true)),1L,false,false)
    @Test fun v3RoundTripPreservesMediaTypeAnimationAndReplyCursorWithExactText() {
        val value=page().copy(replyCursors=mapOf("c" to OfflineCommentData.ReplyCursor(20L,true)))
        assertEquals(value,CommentSnapshotCodec.decode(CommentSnapshotCodec.encode(value)))
    }
    private fun legacy(version:Int):ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value:String){val raw=value.toByteArray(Charsets.UTF_8);out.writeInt(raw.size);out.write(raw)}
            out.writeInt(0x54434d31);out.writeInt(version);text("a".repeat(64));text("video")
            out.writeLong(1L);out.writeLong(1L);out.writeBoolean(false);out.writeBoolean(false);out.writeLong(-1L);out.writeBoolean(false);out.writeInt(1)
            text("c");text("author");text("handle");text("uid");text("original");out.writeBoolean(true);text("译文");text("zh-hans")
            out.writeInt(0);out.writeLong(1L);out.writeBoolean(false);text("")
            if(version>=2){out.writeInt(1);text("c");out.writeLong(20L);out.writeBoolean(true)}
        };return bytes.toByteArray()
    }
    @Test fun oldV1AndV2PagesStayReadableAndRequireOnlyMediaMetadataBackfill() {
        for(version in 1..2){val value=CommentSnapshotCodec.decode(legacy(version));assertEquals("original",value.rows.single().source)
            assertEquals("译文",value.rows.single().translation);assertTrue(value.rows.single().media.isEmpty());assertFalse(value.rows.single().mediaKnown)
            assertEquals(version>=2,value.replyCursors.isNotEmpty())}
    }
    @Test fun rejectsUntrustedSchemesTraversalIdsAndOversizedMediaLists() {
        val page=page();val row=page.rows.single();val asset=row.media.first()
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = asset.copy(urls = listOf("file:///private"))
            CommentSnapshotCodec.encode(page.copy(rows = listOf(row.copy(media = listOf(invalid)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = asset.copy(id = "../../bad")
            CommentSnapshotCodec.encode(page.copy(rows = listOf(row.copy(media = listOf(invalid)))))
        }
        assertThrows(IllegalArgumentException::class.java){CommentSnapshotCodec.encode(page.copy(rows=listOf(row.copy(media=List(13){asset}))))}
    }
    @Test fun signedTokenRotationDoesNotChangeTheCachedAssetIdentity() {
        assertEquals(OfflineCommentMediaReader.identity("image","id","https://p16.tiktokcdn.com/a.png?sign=one"),
            OfflineCommentMediaReader.identity("image","id","https://p16.tiktokcdn.com/a.png?sign=two"))
    }
}
