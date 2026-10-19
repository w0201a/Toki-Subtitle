package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ConfigStoreRetiredKeysTest {
    @Test fun retiredStoredFieldsDoNotBlockUpgradeOrBecomeExported() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        prefs.edit().putBoolean("retired_translation_option", true)
            .putBoolean("video_translate_enabled", true).commit()
        val store = ConfigStore({}, { throw AssertionError(it) })
        store.attachLocal(prefs)
        assertTrue(store.snapshot().boolean("video_translate_enabled"))
        assertFalse(store.snapshot().configuration().containsKey("retired_translation_option"))
        assertTrue(prefs.contains("retired_translation_option"))
        assertThrows(IllegalArgumentException::class.java) {
            ConfigArchive.validate(mapOf("retired_translation_option" to true))
        }
    }
}
