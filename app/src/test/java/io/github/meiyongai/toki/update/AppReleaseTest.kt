package io.github.meiyongai.toki.subtitle.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test fun versionsAreNumericAndStableOnly() {
        assertTrue(ReleaseVersion.parse("2.1.100")!! > ReleaseVersion.parse("v2.1.99")!!)
        assertTrue(ReleaseVersion.parse("2.2.0")!! > ReleaseVersion.parse("2.1.99")!!)
        assertTrue(ReleaseVersion.parse("3.0.0")!! > ReleaseVersion.parse("2.99.99")!!)
        for (value in listOf("", "2.1", "v2.1.23-debug", "2.1.24-rc1", "-1.0.0", "2.1.3/path", "99999999999.1.1"))
            assertNull(value, ReleaseVersion.parse(value))
    }
    @Test fun draftPrereleaseAndMissingStabilityFieldsAreRejected() {
        val base = JSONObject().put("tag_name", "v2.1.24").put("draft", false).put("prerelease", false)
        assertNotNull(AppRelease.fromApi(base.toString()))
        assertNull(AppRelease.fromApi(base.put("draft", true).toString()))
        assertNull(AppRelease.fromApi(base.put("draft", false).put("prerelease", true).toString()))
        assertNull(AppRelease.fromApi("{\"tag_name\":\"v2.1.24\"}"))
    }
    @Test fun onlyFixedOfficialPageCanBeOpenedAndNotesStayPlainText() {
        val json = JSONObject().put("tag_name", "v2.1.24").put("draft", false).put("prerelease", false)
            .put("html_url", "https://evil.invalid/payload").put("body", "## 新功能\n<script>not executed</script>\nBug fixes")
        val release = requireNotNull(AppRelease.fromApi(json.toString()))
        assertEquals("https://github.com/w0201a/Toki-Subtitle/releases/tag/v2.1.24", release.pageUrl)
        assertEquals("## 新功能\n<script>not executed</script>\nBug fixes", release.notes)
        assertEquals(release, AppRelease.fromCache(release.encode()))
    }
    @Test fun emptyOrNullBodyIsValidAndLongNotesAreBounded() {
        fun json(body: Any) = JSONObject().put("tag_name", "2.1.24").put("draft", false).put("prerelease", false).put("body", body).toString()
        assertEquals("", AppRelease.fromApi(json(JSONObject.NULL))!!.notes)
        assertEquals(AppRelease.MAX_NOTES, AppRelease.fromApi(json("a".repeat(AppRelease.MAX_NOTES + 50)))!!.notes.length)
    }
    @Test fun invalidCachedVersionCannotBecomeAnExternalUrl() {
        assertThrows(IllegalArgumentException::class.java) { AppRelease.fromCache("{\"tag\":\"../../evil\",\"notes\":\"x\"}") }
    }
}
