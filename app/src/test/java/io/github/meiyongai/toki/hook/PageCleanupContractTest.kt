package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PageCleanupContractTest {
    class Inflated {
        @JvmField var content: View? = null
        fun onViewCreated(view: View) = Unit
        fun onDestroy() = Unit
    }
    class Source
    open class Widget {
        @JvmField var contentView: View? = null
        var fallback: View? = null
        var returned: View? = null
        fun getView(): View? = returned ?: contentView ?: fallback
        fun onLoad(source: Source, args: Array<Any>) = Unit
        fun onUnload(source: Source) = Unit
        fun show() = Unit
        fun onShow() = Unit
    }
    class Model
    class Holder(@JvmField val itemView: View)
    open class BannerBase { fun Ip(holder: Holder, model: Model, index: Int) = Unit }
    class Banner(val view: View) : BannerBase() {
        fun Kp(parent: ViewGroup, model: Model): Holder = Holder(view)
        fun onDestroy() = Unit
    }
    @Test fun inflatedFieldsAreUsedInsteadOfTheStubLifecycleArgument() {
        val context = RuntimeEnvironment.getApplication()
        val instance = Inflated(); val view = FrameLayout(context); instance.content = view
        val contract = PageCleanupContract.inflated(PageCleanupItem.SEARCH_TAKO, Inflated::class.java, "content")
        assertSame(view, contract.root(instance, arrayOf(ViewStub(context)), null))
        assertNull(contract.root(Any(), emptyArray(), null))
        assertEquals(listOf("onViewCreated"), contract.bound.map { it.name })
        assertEquals(listOf("onDestroy"), contract.released.map { it.name })
    }
    @Test fun widgetFallbackContainerCannotBecomeTheHiddenRoot() {
        val view = FrameLayout(RuntimeEnvironment.getApplication()); val instance = Widget()
        val contract = PageCleanupContract.widget(PageCleanupItem.LIVE_FOLLOW_BOTTOM, Widget::class.java, Source::class.java)
        instance.fallback = view
        assertNull(contract.root(instance, emptyArray(), null))
        instance.contentView = view
        assertSame(view, contract.root(instance, emptyArray(), null))
        instance.returned = FrameLayout(RuntimeEnvironment.getApplication())
        assertNull(contract.root(instance, emptyArray(), null))
        assertEquals(setOf("onLoad", "show", "onShow"), contract.bound.map { it.name }.toSet())
        assertEquals(listOf("onUnload"), contract.released.map { it.name })
    }
    @Test fun inboxCreateAndInheritedRebindUseOnlyHolderItemView() {
        val view = FrameLayout(RuntimeEnvironment.getApplication()); val instance = Banner(view); val holder = Holder(view)
        val contract = PageCleanupContract.inbox(PageCleanupItem.INBOX_SHOP_MIGRATION,
            Banner::class.java, Holder::class.java, Model::class.java)
        assertSame(view, contract.root(instance, emptyArray(), holder))
        assertSame(view, contract.root(instance, arrayOf(holder, Model(), 0), null))
        assertNull(contract.root(instance, arrayOf(view), null))
        assertEquals(setOf("Kp", "Ip"), contract.bound.map { it.name }.toSet())
    }
    open class DirectWidget {
        @JvmField var contentView: View? = null
        fun getView(): View? = contentView
        fun onLoad(args: Array<Any>) = Unit
        fun onUnload() = Unit
    }
    class DirectGiftWidget : DirectWidget()
    @Test fun directGiftLifecycleUsesItsInspectedInheritedOverloadsWithoutRecycleSource() {
        val view = FrameLayout(RuntimeEnvironment.getApplication()); val instance = DirectGiftWidget()
        instance.contentView = view
        for (item in listOf(PageCleanupItem.LIVE_GIFT_TRAY, PageCleanupItem.LIVE_EXTENDED_GIFT_TRAY)) {
            val contract = PageCleanupContract.widget(item, DirectGiftWidget::class.java, null)
            assertSame(view, contract.root(instance, emptyArray(), null))
            assertEquals(listOf("onLoad"), contract.bound.map { it.name })
            assertEquals(listOf(Array<Any>::class.java), contract.bound.single().parameterTypes.toList())
            assertEquals(listOf("onUnload"), contract.released.map { it.name })
            assertEquals(0, contract.released.single().parameterCount)
        }
    }

}
