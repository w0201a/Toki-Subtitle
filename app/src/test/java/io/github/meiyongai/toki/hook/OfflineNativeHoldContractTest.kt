package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OfflineNativeHoldContractTest {
    class GestureSurface(activity: Activity) : View(activity)
    class NativeTimeout(val LL: GestureSurface) : Runnable { override fun run() {} }
    class InvalidTimeout(val LL: View) : Runnable { override fun run() {} }
    @Test fun validatesExactNativeRunnableOwnerAndDoesNotRunNativeBody() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val surface = GestureSurface(controller.get())
        val contract = OfflineNativeHoldContract(NativeTimeout::class.java, GestureSurface::class.java)
        assertSame(surface, contract.surface(NativeTimeout(surface)))
        assertEquals("run", contract.run.name)
        assertThrows(IllegalStateException::class.java) {
            OfflineNativeHoldContract(InvalidTimeout::class.java, GestureSurface::class.java)
        }
        controller.pause().stop().destroy()
    }
    @Test fun nativePlainViewSiblingQualifiesOnlyInsideTheSameSelectedCard() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get(); val root = FrameLayout(activity)
        val player = View(activity); val gesture = GestureSurface(activity).apply { isClickable = true }
        root.addView(player, FrameLayout.LayoutParams(400,600)); root.addView(gesture, FrameLayout.LayoutParams(400,600))
        activity.setContentView(root)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        decor.layout(0,0,500,800)
        assertTrue(OfflineNativeHoldContract.belongs(gesture,root,player,decor))
        // The old early hit check still rejects this plain clickable sibling; native maturation is the fallback.
        val point=IntArray(2);player.getLocationOnScreen(point)
        assertFalse(OfflineVideoHook.videoHit(player,point[0]+40f,point[1]+40f))
        assertFalse(OfflineNativeHoldContract.belongs(root,root,player,decor))
        assertFalse(OfflineNativeHoldContract.belongs(gesture,FrameLayout(activity),player,decor))
        gesture.isEnabled=false
        assertFalse(OfflineNativeHoldContract.belongs(gesture,root,player,decor))
        gesture.isEnabled=true; player.visibility=View.GONE
        assertFalse(OfflineNativeHoldContract.belongs(gesture,root,player,decor))
        controller.pause().stop().destroy()
    }
    @Test fun otherWindowDetachedOrAdjacentCardCannotConsumeCurrentNativeHold() {
        val first=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val second=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val root=FrameLayout(first.get());val player=View(first.get());val gesture=GestureSurface(first.get())
        root.addView(player,FrameLayout.LayoutParams(400,600));root.addView(gesture,FrameLayout.LayoutParams(400,600))
        first.get().setContentView(root)
        first.get().window.decorView.apply { measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY)); layout(0,0,500,800) }
        assertFalse(OfflineNativeHoldContract.belongs(gesture,root,player,second.get().window.decorView))
        val detached=GestureSurface(first.get())
        assertFalse(OfflineNativeHoldContract.belongs(detached,root,player,first.get().window.decorView))
        val nativeGesture=OfflineLongPressState();assertTrue(nativeGesture.claim(nativeGesture.begin(0f,0f)))
        assertTrue(nativeGesture.claimed);nativeGesture.cancel();assertFalse(nativeGesture.claimed)
        first.pause().stop().destroy();second.pause().stop().destroy()
    }
}
