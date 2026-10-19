package io.github.meiyongai.toki.subtitle.hook
import org.junit.Assert.*
import org.junit.Test
class CaptionOriginalVisibilityTest {
    @Test fun alwaysTranslateCannotHideOriginalOrBaseStateBeforeTranslationArrives() {
        assertFalse(CaptionOriginalVisibility.hidden(true, true, true))
        assertFalse(CaptionOriginalVisibility.hidden(true, true, null))
        assertTrue(CaptionOriginalVisibility.hidden(true, true, false))
        assertTrue(CaptionOriginalVisibility.hidden(true, false, true))
        assertFalse(CaptionOriginalVisibility.hidden(false, true, true))
    }
}
