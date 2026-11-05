package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import io.github.meiyongai.toki.subtitle.model.FontTestFixture
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SubtitleFontStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun validCopiesRegisterOnlyAfterBothValidationStagesAndKeepTheSourceUntouched() {
        val bytes = FontTestFixture.font()
        val source = File(context.cacheDir, "chosen-original.ttf").apply { writeBytes(bytes) }
        var platformChecked = false
        val asset = SubtitleFontStore.importFont(context, "Chosen.ttf", source.inputStream()) { copy ->
            platformChecked = true; assertArrayEquals(bytes, copy.readBytes())
            assertNull(context.getSharedPreferences("toki_subtitle_font_assets_v1", 0).getString(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, null))
        }
        assertTrue(platformChecked); assertEquals("ttf", asset.kind)
        assertNotEquals(source.canonicalFile, asset.file.canonicalFile)
        assertArrayEquals(bytes, source.readBytes())
        assertArrayEquals(bytes, SubtitleFontStore.resolve(context, asset.id).file.readBytes())
        assertTrue(org.json.JSONArray(SubtitleFontStore.recent(context)).length() >= 1)
    }
    @Test fun malformedAndPlatformRejectedInputsNeverBecomeSelectable() {
        val preferences = context.getSharedPreferences("toki_subtitle_font_assets_v1", 0)
        val before = preferences.all.toMap()
        assertThrows(IllegalArgumentException::class.java) { SubtitleFontStore.importFont(context, "bad.ttf", ByteArrayInputStream(byteArrayOf(1, 2, 3))) {} }
        assertEquals(before, preferences.all)
        assertThrows(IllegalStateException::class.java) { SubtitleFontStore.importFont(context, "unsupported.ttf", ByteArrayInputStream(FontTestFixture.font())) { error("Platform rejected font") } }
        assertEquals(before, preferences.all)
    }
    @Test fun storedBytesCannotBeSubstitutedEvenIfTheirLengthDoesNotChange() {
        val asset = SubtitleFontStore.importFont(context, "test.ttf", ByteArrayInputStream(FontTestFixture.font())) {}
        val bytes = asset.file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); asset.file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { SubtitleFontStore.resolve(context, asset.id) }
    }
    @Test fun identifiersAndReadUrisCannotSelectArbitraryPathsOrQueries() {
        val id = "a".repeat(64)
        assertEquals(id, SubtitleFontStore.id(SubtitleFontStore.uri(id)))
        for (value in listOf("../font", "A".repeat(64), "a".repeat(63), "a".repeat(65), "")) assertFalse(SubtitleFontStore.validId(value))
        for (uri in listOf("content://other/font/$id", "content://${SubtitleFontStore.AUTHORITY}/../$id",
            "content://${SubtitleFontStore.AUTHORITY}/font/$id?extra=1", "content://${SubtitleFontStore.AUTHORITY}/font/$id#fragment",
            "file:///font/$id", "content://${SubtitleFontStore.AUTHORITY}/font/$id/more")) {
            assertThrows(IllegalArgumentException::class.java) { SubtitleFontStore.id(android.net.Uri.parse(uri)) }
        }
    }
    @Test fun displayNamesAreBoundedWithoutChangingTheOriginalInputName() {
        val name = "a".repeat(119) + "\uD83D\uDE00" + "\n/path\\name"
        val safe = SubtitleFontStore.label(name)
        assertTrue(safe.length <= 120); assertFalse(safe.last().isHighSurrogate())
        assertTrue(safe.none { it.isISOControl() || it == '/' || it == '\\' })
        assertTrue(name.contains("/path"))
    }
}
