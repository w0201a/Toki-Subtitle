package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.TextView
import java.time.Duration
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class AuthorLabelBindingsTest {
    private class Owner(var view: TextView?, var data: AuthorLabelContract.Data?)
    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val page = FrameLayout(activity.get())
        val primary = TextView(activity.get()).apply { text = "Alice" }
        val secondary = TextView(activity.get()).apply { text = "unrelated label" }
        val owner = Owner(primary, AuthorLabelContract.Data("v", "a", "US", "Alice"))
        var reads = 0
        val bindings = AuthorLabelBindings({ (it as Owner).view }, { reads++; (it as Owner).data })
        init {
            page.addView(primary);page.addView(secondary);activity.get().setContentView(page)
            activity.get().window.decorView.apply {
                measure(android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY))
                layout(0,0,320,640)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        fun close() { bindings.close();activity.pause().stop().destroy() }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun fixture() = Fixture().also { fixtures += it }
    @Before fun manualFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun close() { fixtures.forEach { it.close() } }
    @Test fun directNativeTextRouteDecoratesOnlyTheProvenPrimaryLabel() {
        val f=fixture();f.bindings.observe(f.owner);f.bindings.configure(true)
        assertEquals("[🇺🇸美国] Alice",f.primary.text.toString())
        assertEquals("unrelated label",f.secondary.text.toString());assertEquals(1,f.bindings.statistics().decorated)
        repeat(8){ f.bindings.refresh(f.primary) }
        assertEquals("[🇺🇸美国] Alice",f.primary.text.toString())
    }
    @Test fun disabledObservationDoesNotReadModelsAndToggleUpdatesKnownCurrentPage() {
        val f=fixture();f.bindings.observe(f.owner);assertEquals(0,f.reads)
        f.bindings.configure(true);assertTrue(f.primary.text.toString().startsWith("[🇺🇸美国]"))
        f.bindings.configure(false);assertEquals("Alice",f.primary.text.toString())
        f.bindings.configure(true);assertEquals("[🇺🇸美国] Alice",f.primary.text.toString())
    }
    @Test fun initiallyUnreadyModelsAndLateRegionsRecoverWithoutAnotherMqCall() {
        val f=fixture();f.owner.data=null;f.bindings.configure(true);f.bindings.observe(f.owner)
        assertEquals("Alice",f.primary.text.toString());assertEquals(1,f.bindings.statistics().waiting)
        f.owner.data=AuthorLabelContract.Data("v","a",null,"Alice");f.bindings.refresh(f.primary)
        assertEquals(1,f.bindings.statistics().missingRegion)
        f.owner.data=AuthorLabelContract.Data("v","a","JP","Alice");f.bindings.refresh(f.primary)
        assertEquals("[🇯🇵日本] Alice",f.primary.text.toString())
    }
    @Test fun holderReuseCannotPutTheNextCountryOnThePreviousNativeName() {
        val f=fixture();f.bindings.observe(f.owner);f.bindings.configure(true)
        f.owner.data=AuthorLabelContract.Data("next","b","JP","Bob");f.bindings.refresh(f.primary)
        assertEquals("Alice",f.primary.text.toString());assertEquals(1,f.bindings.statistics().mismatch)
        f.primary.text="Bob";f.bindings.refresh(f.primary)
        assertEquals("[🇯🇵日本] Bob",f.primary.text.toString())
    }
    @Test fun nativeRichBadgesNeedTheActualRawNameWitnessAndKeepTheirSpans() {
        val f=fixture();val style=StyleSpan(Typeface.BOLD)
        val native=SpannableString("Sponsored Alice").apply { setSpan(style,0,9,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val before=f.primary.text;f.primary.text=native
        f.bindings.observe(f.owner,"Alice",before);f.bindings.configure(true)
        assertEquals("[🇺🇸美国] Sponsored Alice",f.primary.text.toString())
        val shown=f.primary.text as Spanned;assertTrue(shown.getSpanStart(style)>0)
        f.bindings.configure(false);assertEquals("Sponsored Alice",f.primary.text.toString())
        assertEquals(0,(f.primary.text as Spanned).getSpanStart(style))
    }
    @Test fun unprovenSimilarSuffixAndNativeTextReplacementAreNotStrippedOrMisdecorated() {
        val f=fixture();f.primary.text="not Alice";f.bindings.observe(f.owner);f.bindings.configure(true)
        assertEquals("not Alice",f.primary.text.toString())
        f.primary.text="[🇺🇸美国] native nickname";f.bindings.configure(false)
        assertEquals("[🇺🇸美国] native nickname",f.primary.text.toString())
    }
    @Test fun detachRestoresAndAttachRevalidatesTheSameCurrentOwner() {
        val f=fixture();f.bindings.observe(f.owner);f.bindings.configure(true)
        f.page.removeView(f.primary);assertEquals("Alice",f.primary.text.toString())
        f.page.addView(f.primary);f.bindings.refresh(f.primary)
        assertEquals("[🇺🇸美国] Alice",f.primary.text.toString())
        f.owner.view=f.secondary;f.bindings.refresh(f.primary)
        assertEquals("Alice",f.primary.text.toString());assertEquals(0,f.bindings.statistics().known)
    }
    @Test fun capacityDoesNotDecorateAnUntrackedLabelAndCloseRestoresWithoutNativeRebind() {
        val f=fixture();val bindings=AuthorLabelBindings({(it as Owner).view},{(it as Owner).data},capacity=1)
        try {
            bindings.configure(true);bindings.observe(f.owner)
            val second=Owner(f.secondary,AuthorLabelContract.Data("b","b","JP","unrelated label"));bindings.observe(second)
            assertEquals("unrelated label",f.secondary.text.toString());assertEquals(1,bindings.statistics().known)
        } finally { bindings.close() }
        assertEquals("Alice",f.primary.text.toString())
    }
    @Test fun synchronousNativeRebindDuringModuleWriteNeverKeepsTheOldCountry() {
        val f=fixture();f.bindings.observe(f.owner);var armed=true
        f.primary.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
            override fun afterTextChanged(s:Editable?) {
                if(armed && s.toString().startsWith("[")) { armed=false;f.owner.data=AuthorLabelContract.Data("new","b","JP","Bob");f.primary.text="Bob" }
            }
        })
        f.bindings.configure(true);assertEquals("Bob",f.primary.text.toString())
        f.bindings.refresh(f.primary);assertEquals("[🇯🇵日本] Bob",f.primary.text.toString())
    }
    @Test fun controlCharactersAndMissingRegionsDoNotCreateFabricatedCountryLabels() {
        val f=fixture();f.owner.data=AuthorLabelContract.Data("v","a","US"+10.toChar()+"JP","Alice")
        f.bindings.observe(f.owner);f.bindings.configure(true)
        assertEquals("Alice",f.primary.text.toString());assertEquals(1,f.bindings.statistics().missingRegion)
    }
}
