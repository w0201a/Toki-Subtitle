package io.github.meiyongai.toki.subtitle.hook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CommentStickerRoutingTest {
 class StickerComment { fun getStickerStruct(): Any = Any() }
 class TextComment { fun getStickerStruct(): Any? = null }
 @Test fun stickerMenusHavePriorityOverOrdinaryTextTranslationFallback() {
  assertTrue(CommentStickerDownloadHook.isStickerComment(StickerComment()))
  assertFalse(CommentStickerDownloadHook.isStickerComment(TextComment()))
  assertFalse(CommentStickerDownloadHook.isStickerComment(Any()))
 }
}
