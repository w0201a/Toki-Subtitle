package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class HostSettingsEntryTest {
    @Test fun homeLabelRequiresAnExactNavigationLabel() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val label=TextView(activity)
        for (value in listOf("Home", "首页", "首頁", "ホーム", "Home, selected")) {
            label.text=value;assertTrue(value, HostSettingsEntry.homeLabel(label))
        }
        for (value in listOf("home sweet home", "homepage", "个人主页", "settings")) {
            label.text=value;assertFalse(value, HostSettingsEntry.homeLabel(label))
        }
    }
    @Test fun videoTextNamedHomeIsNeverANavigationTarget() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=owner.get()
        lateinit var label: TextView
        val nav=FrameLayout(activity).apply {
            label=TextView(activity).apply { text="Home" }
            addView(label,FrameLayout.LayoutParams(100,20,android.view.Gravity.BOTTOM))
        }
        activity.setContentView(FrameLayout(activity).apply {
            addView(TextView(activity).apply { text="Home" },FrameLayout.LayoutParams(100,60))
            addView(nav,FrameLayout.LayoutParams(100,80,android.view.Gravity.BOTTOM))
        })
        val decor=activity.window.decorView
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(800,1073741824))
        decor.layout(0,0,400,800)
        assertNull(HostSettingsEntry.homeAt(activity,20f,30f))
        val rect=android.graphics.Rect();nav.getGlobalVisibleRect(rect)
        assertSame(nav,HostSettingsEntry.homeAt(activity,rect.centerX().toFloat(),rect.top+5f))
        owner.pause().stop().destroy()
    }
}
