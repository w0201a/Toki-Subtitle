package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class OfflineCommentDataTest {
    private fun row(id: String = "comment", translated: String? = "译文") = OfflineCommentData.Row(
        id, "author", "handle", "user", "Original", translated, "zh-Hans", 0, 3)

    @Test fun originalTranslatedAndBilingualModesDoNotMutateTheNativeFacts() {
        val row = row()
        assertEquals("Original", row.text(OfflineCommentData.Mode.ORIGINAL))
        assertEquals("译文", row.text(OfflineCommentData.Mode.TRANSLATED))
        assertEquals("Original\n\n译文", row.text(OfflineCommentData.Mode.BILINGUAL))
        assertEquals("Original", row.source)
        val missing = row(translated = null)
        for (mode in OfflineCommentData.Mode.entries) assertEquals("Original", missing.text(mode))
        assertEquals("Original", row(translated = "Original").text(OfflineCommentData.Mode.BILINGUAL))
    }
    @Test fun savedOriginalTakesPriorityAndMissingTranslationsAreNotInvented() {
        assertEquals("original", OfflineCommentData.original("original", "visible translation"))
        assertEquals("text", OfflineCommentData.original(null, "text"))
        assertNull(OfflineCommentData.translated("original", null, "different", false))
        assertEquals("different", OfflineCommentData.translated("original", null, "different", true))
        assertEquals("cached", OfflineCommentData.translated("original", "cached", "visible", true))
        assertNull(OfflineCommentData.translated("same", "same", "same", true))
        assertNull(OfflineCommentData.translated("source", "x".repeat(16001), null, false))
    }
    @Test fun localPaginationDoesNotClaimUncachedRowsOrSkipAnyCachedRow() {
        val rows = (1..95).map { row(it.toString()) }
        val pages = OfflineCommentPages(rows)
        assertEquals(0, pages.count)
        assertTrue(pages.hasNext)
        assertEquals(40, pages.append())
        assertEquals("40", pages[39].id)
        assertThrows(IllegalArgumentException::class.java) { pages[40] }
        assertEquals(80, pages.append())
        assertEquals(95, pages.append())
        assertFalse(pages.hasNext)
        assertEquals(95, pages.append())
        assertEquals(rows, (0 until pages.count).map { pages[it] })
        assertEquals(0, OfflineCommentPages(emptyList()).append())
    }
    @Test fun longUnicodeTextIsBoundedWithoutSplittingSurrogatePairs() {
        val source = "a".repeat(9) + "🌍" + "tail"
        assertEquals("a".repeat(9), OfflineCommentData.bounded(source, 10))
        assertEquals("a".repeat(9) + "🌍", OfflineCommentData.bounded(source, 11))
        assertEquals("", OfflineCommentData.bounded(null, 20))
    }
    @Test fun snapshotBoundsRejectLargeDeepOrUnclosedArchivesBeforeNativeParsing() {
        OfflineCommentJsonBounds.check("{\"text\":\"[not a container]\",\"rows\":[]}")
        assertThrows(IllegalArgumentException::class.java) { OfflineCommentJsonBounds.check("[".repeat(65) + "]".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { OfflineCommentJsonBounds.check("{\"text\":\"unclosed}") }
        assertThrows(IllegalArgumentException::class.java) { OfflineCommentJsonBounds.check("[]".repeat(2 * 1024 * 1024)) }
        assertThrows(IllegalArgumentException::class.java) { OfflineCommentJsonBounds.check("{") }
    }
    @Test fun accountTokensAreStableAndDifferentWithoutEmbeddingTheUserId() {
        val first = HostOfflineComments.accountToken("123456789")
        assertEquals(64, first.length)
        assertEquals(first, HostOfflineComments.accountToken("123456789"))
        assertNotEquals(first, HostOfflineComments.accountToken("987654321"))
        assertNotEquals(first, HostOfflineComments.accountToken("guest"))
    }
}
