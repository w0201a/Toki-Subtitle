package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class SubtitlePlacementTest {
    @Test fun movementUsesPageAreaInsteadOfCaptionContainer() {
        assertEquals(340 to 1700, SubtitlePlacement.place(1080, 2400, 400, 100, 1700f, 0f, 0f, 12))
        assertEquals(12 to 12, SubtitlePlacement.place(1080, 2400, 400, 100, 1700f, -100f, -100f, 12))
        assertEquals(668 to 2288, SubtitlePlacement.place(1080, 2400, 400, 100, 1700f, 100f, 100f, 12))
    }
    @Test fun oversizedTextDoesNotCreateInvalidClampRange() {
        assertEquals(0 to 0, SubtitlePlacement.place(100, 100, 120, 120, 50f, 100f, -100f, 12))
    }
}
