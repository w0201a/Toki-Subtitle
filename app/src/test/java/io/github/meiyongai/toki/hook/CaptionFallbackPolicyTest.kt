package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionFallbackPolicyTest {
    @Test fun missingTranslationFallsBackToOriginal() { assertEquals(7, CaptionFallbackPolicy.choose(-1, 7)) }
    @Test fun availableTranslationIsPreserved() { assertEquals(4, CaptionFallbackPolicy.choose(4, 7)) }
    @Test fun missingOriginalStaysUnavailable() { assertEquals(-1, CaptionFallbackPolicy.choose(-1, null)) }
    @Test fun invalidOriginalStaysUnavailable() { assertEquals(-1, CaptionFallbackPolicy.choose(-1, -1)) }
    @Test fun zeroIsValidTrack() { assertEquals(0, CaptionFallbackPolicy.choose(-1, 0)) }
    @Test fun otherSelectedTrackIsUntouched() { assertEquals(2, CaptionFallbackPolicy.choose(2, null)) }
    @Test fun fileTrackWithNoDataFallsBack() { assertEquals(7, CaptionFallbackPolicy.choose(4, 7, false)) }
    @Test fun realtimeTrackWithoutFileIsPreserved() { assertEquals(4, CaptionFallbackPolicy.choose(4, 7, true)) }
    @Test fun missingDataWithoutOriginalPreservesHost() { assertEquals(4, CaptionFallbackPolicy.choose(4, null, false)) }
}
