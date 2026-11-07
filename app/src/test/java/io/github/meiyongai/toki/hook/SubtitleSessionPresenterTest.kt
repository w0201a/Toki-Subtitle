package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SubtitleSessionPresenterTest {
    private val settings=ConfigSnapshot(mapOf("subtitle_style_enabled" to true,"subtitle_render_mode" to "custom","subtitle_text_mode" to "original"))
    @Test fun rendererChangesReuseOneLayerAndRestoreOnlyVerifiedCaptionRootsOnClose() {
        HookRuntime.start("single-subtitle-session"){_,_->}
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();val activity=owner.get()
        val page=FrameLayout(activity);val video=SurfaceView(activity)
        val first=FrameLayout(activity).apply{addView(TextView(activity).apply{text="one"})}
        val second=FrameLayout(activity).apply{addView(TextView(activity).apply{text="two"})}
        page.addView(video,FrameLayout.LayoutParams(1080,2293));page.addView(first,FrameLayout.LayoutParams(600,100));page.addView(second,FrameLayout.LayoutParams(600,100))
        activity.setContentView(page)
        activity.window.decorView.apply{measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,1080,2400)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val presenter=SubtitleSessionPresenter(null){settings};presenter.select(video,"v");presenter.native(first,"v","EXPANDED","one")
        val overlayField=SubtitleSessionPresenter::class.java.getDeclaredField("overlay").apply{isAccessible=true}
        val binding=overlayField.get(presenter) as SubtitleOverlay
        val layerField=SubtitleOverlay::class.java.getDeclaredField("layer").apply{isAccessible=true}
        val layer=layerField.get(binding) as FrameLayout
        assertEquals("one",(layer.getChildAt(0) as TextView).text.toString());assertEquals(0f,first.alpha);assertEquals(1f,video.alpha)
        presenter.native(second,"v","HIDDEN","")
        assertEquals("A hidden sibling must not erase the current visible cue","one",(layer.getChildAt(0) as TextView).text.toString())
        assertSame(layer,layerField.get(binding))
        presenter.native(second,"v","EXPANDED","two")
        assertSame(binding,overlayField.get(presenter));assertSame(layer,layerField.get(binding))
        assertEquals("two",(layer.getChildAt(0) as TextView).text.toString());assertEquals(0f,second.alpha)
        presenter.native(first,"v","HIDDEN","")
        assertEquals("A previous renderer's hidden frame must not blank the new renderer","two",(layer.getChildAt(0) as TextView).text.toString())
        presenter.native(second,"v","HIDDEN","")
        assertEquals("The owner hiding must hide the replacement rather than resurrect an old line",View.INVISIBLE,layer.visibility)
        second.visibility=View.GONE
        presenter.native(second,"v","EXPANDED","collapsed native container")
        presenter.progress(video,"v",2500)
        assertEquals("Collapsed native geometry does not invalidate an explicit EXPANDED frame on the selected player",View.VISIBLE,layer.visibility)
        assertEquals("collapsed native container",(layer.getChildAt(0) as TextView).text.toString())
        second.visibility=View.VISIBLE
        presenter.native(second,"v","EXPANDED","last line")
        page.removeView(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertFalse(second.isAttachedToWindow)
        presenter.progress(video,"v",3000)
        assertEquals("Detached caption sources must not keep the previous sentence stuck",View.INVISIBLE,layer.visibility)
        presenter.close();assertEquals(1f,first.alpha);assertEquals(1f,second.alpha);assertNull(layerField.get(binding))
        owner.pause().stop().destroy()
    }
    @Test fun authoritativeGapCannotExposeStaleNativeCaption() {
        HookRuntime.start("single-subtitle-gap"){_,_->}
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();val activity=owner.get()
        val page=FrameLayout(activity);val video=SurfaceView(activity);val native=FrameLayout(activity).apply{addView(TextView(activity).apply{text="old"})}
        page.addView(video,FrameLayout.LayoutParams(1080,2293));page.addView(native,FrameLayout.LayoutParams(600,100));activity.setContentView(page)
        activity.window.decorView.apply{measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,1080,2400)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        var gap=false
        val binding=SubtitleOverlay(video,null,true,true,{listOf(native)},{gap}){settings}
        binding.update("EXPANDED","current","v");binding.onPreDraw();assertEquals(0f,native.alpha)
        gap=true;binding.update("HIDDEN","","v");binding.onPreDraw();assertEquals(0f,native.alpha);assertEquals(1f,video.alpha)
        binding.close();assertEquals(1f,native.alpha);owner.pause().stop().destroy()
    }
    @Test fun postedNativeTextChangesReplaceTheOldSentenceWithoutAnotherFrameCallback() {
        HookRuntime.start("subtitle-native-text-watch"){_,_->}
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();val activity=owner.get()
        val page=FrameLayout(activity);val video=SurfaceView(activity)
        val text=TextView(activity).apply{this.text="first native cue"}
        val renderer=FrameLayout(activity).apply{addView(text)}
        val siblingText=TextView(activity).apply{this.text="unselected stale cue"}
        val sibling=FrameLayout(activity).apply{addView(siblingText)}
        page.addView(video,FrameLayout.LayoutParams(1080,2293));page.addView(renderer,FrameLayout.LayoutParams(600,100));page.addView(sibling,FrameLayout.LayoutParams(600,100))
        activity.setContentView(page);activity.window.decorView.apply{measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,1080,2400)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val presenter=SubtitleSessionPresenter(null){settings};presenter.select(video,"v");presenter.native(renderer,"v","EXPANDED","first native cue")
        val binding=SubtitleSessionPresenter::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(presenter) as SubtitleOverlay
        val layer=SubtitleOverlay::class.java.getDeclaredField("layer").apply{isAccessible=true}.get(binding) as FrameLayout
        val label=layer.getChildAt(0) as TextView
        text.text="posted second native cue";page.viewTreeObserver.dispatchOnPreDraw()
        assertEquals("posted second native cue",label.text.toString())
        siblingText.text="must not steal current cue";page.viewTreeObserver.dispatchOnPreDraw()
        assertEquals("posted second native cue",label.text.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));page.viewTreeObserver.dispatchOnPreDraw()
        assertEquals("Pause alone cannot expire a valid long sentence","posted second native cue",label.text.toString())
        text.text="";page.viewTreeObserver.dispatchOnPreDraw();assertEquals(View.INVISIBLE,layer.visibility)
        text.text="third native cue";page.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(View.VISIBLE,layer.visibility);assertEquals("third native cue",label.text.toString())
        presenter.close();text.text="after close";page.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1f,renderer.alpha);owner.pause().stop().destroy()
    }

}
