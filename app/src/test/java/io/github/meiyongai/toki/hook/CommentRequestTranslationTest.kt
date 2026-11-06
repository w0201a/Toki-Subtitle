package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentRequestTranslationTest {
    @Test fun moduleAutomaticTranslationRequiresBothSwitchAndRemoteWhileKeepingHostResult() {
        assertFalse(CommentTranslateHook.requestEnabled(false, false, true))
        assertFalse(CommentTranslateHook.requestEnabled(false, true, false))
        assertTrue(CommentTranslateHook.requestEnabled(false, true, true))
        assertTrue(CommentTranslateHook.requestEnabled(true, false, false))
    }
}
