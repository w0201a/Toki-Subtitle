package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class PollBindingContractTest {
    class Poll
    open class Item
    class PollItem(@JvmField val LL: Poll) : Item()
    class Cell {
        var seen: PollItem? = null
        fun s6(item: PollItem) { seen = item }
        fun onBindItemView(item: Item) { s6(item as PollItem) }
    }
    open class ParentItem(@JvmField val LL: Poll)
    class InheritedItem(poll: Poll) : ParentItem(poll)
    class InheritedCell { fun s6(item: InheritedItem) = Unit }
    class WrongCell { fun onBindItemView(item: Item) = Unit }

    @Test fun resolvesActualDexNameInsteadOfJadxMergedBridgeName() {
        val contract = PollBindingContract.resolve(Cell::class.java, Poll::class.java)
        val poll = Poll()
        val item = PollItem(poll)
        val cell = Cell()
        assertEquals("s6", contract.bind.name)
        assertSame(poll, contract.data.get(item))
        contract.bind.invoke(cell, item)
        assertSame(item, cell.seen)
    }

    @Test fun supportsModelFieldsDeclaredInParentClass() {
        val contract = PollBindingContract.resolve(InheritedCell::class.java, Poll::class.java)
        val poll = Poll()
        assertSame(poll, contract.data.get(InheritedItem(poll)))
    }

    @Test fun rejectsErasedBridgeWithoutActualTypedMethodWithClearContractError() {
        val error = assertThrows(IllegalStateException::class.java) {
            PollBindingContract.resolve(WrongCell::class.java, Poll::class.java)
        }
        assertTrue(error.message.orEmpty().contains("s6(PollItem):void"))
    }
}
