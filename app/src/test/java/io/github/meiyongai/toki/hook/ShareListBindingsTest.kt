package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class ShareListBindingsTest {
    private class Item(val key: String)
    private fun key(value: Any?): String? = (value as? Item)?.key

    @Test fun hotHideShowAndRepeatedFilteringAlwaysStartFromLatestNativeData() {
        val owner = Any()
        val copy = Item("copy")
        val report = Item("report")
        val future = Item("future_action")
        val native = listOf(copy, report, future)
        val rendered = mutableListOf<List<Any?>>()
        lateinit var state: ShareListBindings
        state = ShareListBindings(::key, { target, values -> rendered += state.incoming(target, values) })
        state.configure(setOf("report"))
        assertEquals(listOf(copy, future), state.incoming(owner, native))
        state.configure(emptySet())
        assertEquals(native, rendered.last())
        state.configure(setOf("copy"))
        assertEquals(listOf(report, future), rendered.last())
        state.configure(emptySet())
        assertEquals(native, rendered.last())
        assertSame(report, rendered.last()[1])
        val replacement = listOf(future)
        state.incoming(owner, replacement)
        state.configure(setOf("report"))
        assertEquals(replacement, rendered.last())
        state.close()
    }

    @Test fun destroyedPanelsAreForgottenAndDisposalRestoresRemainingAdapters() {
        val first = Any(); val second = Any()
        val item = Item("report")
        val renders = mutableListOf<Pair<Any, List<Any?>>>()
        val state = ShareListBindings(::key, { target, values -> renders.add(target to values) })
        state.configure(setOf("report"))
        state.incoming(first, listOf(item)); state.incoming(second, listOf(item))
        state.forget(first)
        state.close()
        assertEquals(1, renders.size)
        assertSame(second, renders.single().first)
        assertSame(item, renders.single().second.single())
        assertEquals(listOf(item), state.incoming(second, listOf(item)))
        state.close()
        assertEquals(1, renders.size)
    }

    @Test fun capacityAndUnreadableItemsFailOpenInsteadOfHidingUntrackedItems() {
        val first = Any(); val second = Any()
        val report = Item("report"); val unknown = Any()
        val messages = mutableListOf<String>()
        val state = ShareListBindings(::key, { _, _ -> }, messages::add, capacity = 1)
        state.configure(setOf("report"))
        assertEquals(listOf(unknown), state.incoming(first, listOf(report, unknown)))
        val native = listOf(report)
        assertSame(native, state.incoming(second, native))
        assertEquals(1, messages.size)
        state.close()
    }

    @Test fun oneRefreshFailureDoesNotPreventAnotherAdapterRestoring() {
        val broken = Any(); val healthy = Any()
        val restored = mutableListOf<Any>()
        val state = ShareListBindings(::key, { target, _ ->
            if (target === broken) error("native adapter unavailable")
            restored.add(target)
        })
        state.incoming(broken, listOf(Item("report")))
        state.incoming(healthy, listOf(Item("report")))
        state.configure(setOf("report"))
        assertEquals(listOf(healthy), restored)
        state.close()
    }

    private abstract class AdapterBase { abstract fun setData(values: List<Any?>) }
    private class ConcreteAdapter : AdapterBase() { override fun setData(values: List<Any?>) = Unit }
    private class UnrelatedAdapter { fun setData(values: List<Any?>) = Unit }

    @Test fun onlyConcreteMethodsFromTheVerifiedAdapterHierarchyCanBeHooked() {
        val method = SharePanelCleanupHook.dataMethod(ConcreteAdapter::class.java, AdapterBase::class.java)
        assertEquals("setData", method.name)
        assertThrows(IllegalStateException::class.java) {
            SharePanelCleanupHook.dataMethod(UnrelatedAdapter::class.java, AdapterBase::class.java)
        }
        assertThrows(IllegalStateException::class.java) {
            SharePanelCleanupHook.dataMethod(AdapterBase::class.java, AdapterBase::class.java)
        }
    }
}
