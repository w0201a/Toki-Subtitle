package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthorDisplayedUserContractTest {
    class User(private val id: String, var country: String?, @JvmField var nickname: String) {
        fun getUid() = id
        fun getRegion() = country
        fun getNickname() = nickname
        fun getUniqueId() = id + "-handle"
    }
    class Aweme(private val id: String, private val authorValue: User?, val collaborator: User? = null) {
        fun getAid() = id
        fun getAuthor() = authorValue
    }
    class PageParam(private val id: String) { fun getUid() = id }
    class Page(@JvmField var param: PageParam?, @JvmField var eventType: String?)
    class Params(var video: Aweme, @JvmField var baseFeedPageParams: Page? = null, var profileMode: Boolean = false) { @JvmField var mEventType: String = ""; fun getAweme() = video }
    class Cell(@JvmField var LL: Any?)
    open class Reused(@JvmField var cellProxy: Cell?)
    class Owner(cell: Cell?) : Reused(cell)
    class Selector { companion object {
        @JvmStatic fun LIZIZ(video: Aweme, uid: String, event: String): User? =
            video.collaborator?.takeIf { event in setOf("personal_homepage", "others_homepage") && it.getUid() == uid } ?: video.getAuthor()
    } }
    class Formatter { companion object {
        var calls = 0
        var duringName: (() -> Unit)? = null
        @JvmStatic fun LIZJ(params: Params) = params.profileMode
        @JvmStatic fun LIZ(params: Params): String {
            calls++; duringName?.invoke()
            val page = params.baseFeedPageParams
            val user = if (params.profileMode) Selector.LIZIZ(params.video, page?.param?.getUid().orEmpty(), page?.eventType.orEmpty()) else params.video.getAuthor()
            return user?.getNickname().orEmpty()
        }
    } }
    private fun contract() = AuthorDisplayedUserContract(Owner::class.java, Reused::class.java, Params::class.java, Aweme::class.java,
        User::class.java, Selector::class.java, Formatter::class.java)
    @Before fun reset() { Formatter.calls = 0; Formatter.duringName = null }
    @Test fun readsCurrentCellRatherThanAStaleViewModelItem() {
        val user = User("author", "US", "Alice")
        val owner = Owner(Cell(Params(Aweme("video", user))))
        assertEquals(AuthorLabelContract.Data("video", "author", "US", "Alice"), contract().current(owner))
    }
    @Test fun homepageCollaboratorRegionBelongsToTheActuallyDisplayedUser() {
        val actual = User("original", "US", "Original"); val collaborator = User("profile", "JP", "Profile")
        val item = Params(Aweme("v", actual, collaborator), Page(PageParam("profile"), "others_homepage"), true)
        val result = contract().current(Owner(Cell(item)))
        assertEquals(AuthorLabelContract.Data("v", "profile", "JP", "Profile"), result)
        assertEquals("US", actual.country); assertEquals("Original", actual.nickname)
    }
    @Test fun nativeProfileExperimentAndFallbackAreHonoredRatherThanForcingAProfileUser() {
        val author = User("a", "US", "Author"); val collaborator = User("p", "JP", "Profile")
        val item = Params(Aweme("v", author, collaborator), Page(PageParam("p"), "personal_homepage"), false)
        val owner = Owner(Cell(item));val reader = contract()
        assertEquals("a", reader.current(owner)?.author)
        item.profileMode = true; assertEquals("p", reader.current(owner)?.author)
        item.baseFeedPageParams = Page(PageParam("missing"), "others_homepage")
        assertEquals("a", reader.current(owner)?.author)
    }
    @Test fun absentWrongOrIncompleteCurrentModelsAreNotInvented() {
        val reader = contract()
        assertNull(reader.current(Owner(null))); assertNull(reader.current(Owner(Cell("wrong"))))
        assertNull(reader.current(Owner(Cell(Params(Aweme("", User("u", "US", "name")))))))
        assertNull(reader.current(Owner(Cell(Params(Aweme("v", null))))))
    }
    @Test fun rebindingWhileFormattingDropsTheOldResult() {
        val owner = Owner(Cell(Params(Aweme("old", User("a", "US", "Alice")))))
        Formatter.duringName = { owner.cellProxy!!.LL = Params(Aweme("new", User("b", "JP", "Bob"))) }
        val reader = contract();assertNull(reader.current(owner))
        Formatter.duringName = null; assertEquals("new", reader.current(owner)?.video)
    }
    @Test fun nativeNameFormattingIsCachedButInvalidationAndModelNameChangesRecomputeIt() {
        val user = User("u", "US", "Alice");val owner = Owner(Cell(Params(Aweme("v", user))))
        val reader = contract();repeat(12) { assertEquals("Alice", reader.current(owner)?.nativeName) }
        assertEquals(1, Formatter.calls)
        reader.invalidate(owner);reader.current(owner);assertEquals(2, Formatter.calls)
        user.nickname = "Alice updated";assertEquals("Alice updated", reader.current(owner)?.nativeName);assertEquals(3, Formatter.calls)
    }
    @Test fun missingCountryStaysMissingInsteadOfUsingTheViewerCountry() {
        val item = Params(Aweme("v", User("a", "US", "Author"), User("p", null, "Profile")), Page(PageParam("p"), "others_homepage"), true)
        val value = contract().current(Owner(Cell(item)))!!
        assertEquals("p", value.author);assertNull(value.region)
    }
}
