package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LayoutNavigationContractTest {
    interface Ability { fun pageTag(index: Int, alternate: Boolean): String? }
    class Adapter { fun getCount() = 4 }
    class Pager(context: Context) : FrameLayout(context) {
        fun getCurrentItem() = 0
        fun getAdapter() = Adapter()
        fun renamedDestination(index: Int, smooth: Boolean, force: Boolean, velocity: Int) {}
    }
    class Owner : Ability {
        fun renamedPager(): Pager? = null
        fun onViewCreated(view: View) {}
        override fun pageTag(index: Int, alternate: Boolean) = "For You"
    }
    class InvalidPager(context: Context) : FrameLayout(context) {
        fun getCurrentItem() = 0
        fun getAdapter() = Adapter()
        fun unrelated(index: Int, smooth: Boolean) {}
    }
    class InvalidOwner : Ability {
        fun pager(): InvalidPager? = null
        fun onViewCreated(view: View) {}
        override fun pageTag(index: Int, alternate: Boolean) = "For You"
    }

    @Test fun resolvesRenamedNativeDestinationUsingHomePagerContract() {
        val contract = LayoutNavigationHook.Contract(Owner::class.java, Ability::class.java)
        assertEquals("renamedPager", contract.pager.name)
        assertEquals("renamedDestination", contract.select.name)
        assertEquals("pageTag", contract.tag.name)
    }

    @Test fun rejectsPagerWithoutSharedSelectionBoundary() {
        assertThrows(NoSuchElementException::class.java) {
            LayoutNavigationHook.Contract(InvalidOwner::class.java, Ability::class.java)
        }
    }
}
