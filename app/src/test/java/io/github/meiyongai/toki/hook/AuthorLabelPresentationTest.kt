package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AuthorLabelPresentationTest {
    @Test fun prefixPreservesNicknameSpansAndRemovingItRestoresTheirOffsets() {
        val span = StyleSpan(Typeface.BOLD)
        val original = SpannableString("作者 nickname").apply { setSpan(span, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val shown = AuthorLabelPresentation.display(original, "US", true)
        assertEquals("[🇺🇸美国] 作者 nickname", shown.toString())
        val styled = shown as Spanned
        val offset = shown.length - original.length
        assertEquals(offset, styled.getSpanStart(span))
        assertEquals(offset + 2, styled.getSpanEnd(span))
        val restored = AuthorLabelPresentation.display(shown, "US", false) as Spanned
        assertEquals(original.toString(), restored.toString())
        assertEquals(0, restored.getSpanStart(span))
        assertEquals(2, restored.getSpanEnd(span))
        assertEquals(0, original.getSpanStart(span))
    }

    @Test fun repeatedBindingAndCountryChangesDoNotAccumulateOwnedPrefixes() {
        val first = AuthorLabelPresentation.display("name", "US", true)
        val repeated = AuthorLabelPresentation.display(first, "US", true)
        assertEquals(first.toString(), repeated.toString())
        val next = AuthorLabelPresentation.display(repeated, "JP", true)
        assertEquals("[🇯🇵日本] name", next.toString())
        assertEquals("name", AuthorLabelPresentation.display(next, null, true).toString())
        assertEquals("", AuthorLabelPresentation.display("", "US", true).toString())
    }

    @Test fun ordinaryNamesResemblingPrefixesAreNotRemovedOnDisable() {
        val native = "[🇺🇸美国] actual nickname"
        assertSame(native, AuthorLabelPresentation.display(native, "US", false))
        assertSame(native, AuthorLabelPresentation.original(native))
    }

    class User(private val id: String = "u", private val country: String = "US") {
        fun getUid() = id
        fun getRegion() = country
    }
    class Aweme(private val id: String = "v", private val user: User? = User()) {
        fun getAid() = id
        fun getAuthor() = user
    }
    class Params(private val video: Aweme = Aweme()) { fun getAweme() = video }
    class Model(var params: Params = Params()) { fun getItem(): Any = params }
    class Component {
        @JvmField var LLLIIII: TextView? = null
        val model = Model()
        fun xq() = model
        fun Mq(view: TextView, value: CharSequence) { view.text = value }
    }
    class WrongComponent {
        @JvmField var LLLIIII: String = "not a view"
    }

    @Test fun bindingContractReadsCurrentIdentityAndRejectsNonLabelTargets() {
        val contract = AuthorLabelContract(Component::class.java, Params::class.java, Aweme::class.java, User::class.java)
        val owner = Component()
        assertEquals(AuthorLabelContract.Data("v", "u", "US"), contract.current(owner))
        owner.model.params = Params(Aweme("next", User("author2", "JP")))
        assertEquals(AuthorLabelContract.Data("next", "author2", "JP"), contract.current(owner))
        owner.model.params = Params(Aweme("", User()))
        assertNull(contract.current(owner))
        owner.model.params = Params(Aweme("v", null))
        assertNull(contract.current(owner))
        assertThrows(IllegalStateException::class.java) {
            AuthorLabelContract(WrongComponent::class.java, Params::class.java, Aweme::class.java, User::class.java)
        }
    }
}
