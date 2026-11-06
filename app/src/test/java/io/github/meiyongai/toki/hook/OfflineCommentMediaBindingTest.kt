package io.github.meiyongai.toki.subtitle.hook

import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineCommentMediaBindingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun row(id: String = "comment", media: String = "a") = OfflineCommentData.Row(id, "a", "h", "u", "source", null, "", 0, 0,
        media = listOf(OfflineCommentData.Media(media.repeat(64), OfflineCommentData.MediaKind.STICKER, listOf("https://p16.tiktokcdn.com/sticker.gif"), true)))
    private fun snapshot(row: OfflineCommentData.Row, account: String = "a".repeat(64), video: String = "video") =
        OfflineCommentData.Snapshot(account, video, 1, listOf(row), 1, false, false)
    @Test fun repeatedRefreshKeepsTheSameImageAndDrawableAndDoesNotDetachIt() {
        val container = LinearLayout(context)
        val parent = FrameLayout(context).apply { addView(container) }
        val row = row(); val snapshot = snapshot(row)
        var oldValid = true
        OfflineCommentMediaViews.bind(container, context, snapshot, row, javaClass.classLoader!!) { oldValid }
        val tile = container.getChildAt(0)
        val image = (tile as FrameLayout).getChildAt(0) as ImageView
        val drawable = ColorDrawable(Color.GREEN); image.setImageDrawable(drawable)
        var detached = 0
        image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { detached++ }
        })
        oldValid = false
        repeat(50) {
            OfflineCommentMediaViews.bind(container, context, snapshot.copy(capturedAt = it.toLong()), row, javaClass.classLoader!!) { true }
            assertSame(tile, container.getChildAt(0)); assertSame(image, tile.getChildAt(0)); assertSame(drawable, image.drawable)
        }
        assertEquals(0, detached); assertSame(parent, container.parent)
    }
    @Test fun recycledDifferentCommentAndAccountCannotReuseThePreviousDrawable() {
        val container = LinearLayout(context)
        val first = row()
        OfflineCommentMediaViews.bind(container, context, snapshot(first), first, javaClass.classLoader!!) { true }
        val before = container.getChildAt(0)
        val second = row("other")
        OfflineCommentMediaViews.bind(container, context, snapshot(second), second, javaClass.classLoader!!) { true }
        assertNotSame(before, container.getChildAt(0))
        val secondTile = container.getChildAt(0)
        OfflineCommentMediaViews.bind(container, context, snapshot(second, "b".repeat(64)), second, javaClass.classLoader!!) { true }
        assertNotSame(secondTile, container.getChildAt(0))
    }
    @Test fun emptyMediaRemovesOnlyTheCurrentRowsOldTiles() {
        val container = LinearLayout(context); val row = row()
        OfflineCommentMediaViews.bind(container, context, snapshot(row), row, javaClass.classLoader!!) { true }
        assertEquals(1, container.childCount)
        val empty = row.copy(media = emptyList())
        OfflineCommentMediaViews.bind(container, context, snapshot(empty), empty, javaClass.classLoader!!) { true }
        assertEquals(0, container.childCount); assertEquals(View.GONE, container.visibility)
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun realAnimatedStickerDrawableIsNotRecreatedOnTranslationOrCountRefresh() {
        val bytes = java.util.Base64.getDecoder().decode("R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7")
        val drawable = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        assertTrue(drawable is android.graphics.drawable.AnimatedImageDrawable)
        val container = LinearLayout(context); val row = row(); val snapshot = snapshot(row)
        OfflineCommentMediaViews.bind(container, context, snapshot, row, javaClass.classLoader!!) { true }
        val tile = container.getChildAt(0) as FrameLayout
        val image = tile.getChildAt(0) as ImageView
        image.setImageDrawable(drawable)
        repeat(30) {
            OfflineCommentMediaViews.bind(container, context, snapshot, row.copy(translation = "译文$it"), javaClass.classLoader!!) { true }
            assertSame(drawable, (container.getChildAt(0) as FrameLayout).getChildAt(0).let { it as ImageView }.drawable)
        }
        (drawable as android.graphics.drawable.AnimatedImageDrawable).stop()
    }

}
