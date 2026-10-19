package io.github.meiyongai.toki.subtitle.provider

import android.os.Bundle
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SubtitlePositionProviderTest {
    @Test fun unknownCallerCannotWritePosition() {
        val provider = Robolectric.buildContentProvider(SubtitlePositionProvider::class.java).create().get()
        assertThrows(IllegalStateException::class.java) {
            provider.call("position", null, Bundle().apply {
                putFloat("subtitle_x", 10f); putFloat("subtitle_y", 20f)
            })
        }
    }
}
