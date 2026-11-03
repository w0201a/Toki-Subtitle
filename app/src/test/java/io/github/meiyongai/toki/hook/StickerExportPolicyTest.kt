package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class StickerExportPolicyTest {
    @Test fun animatedModesRequireAnAnimatedSourceAndNeverUseStaticPreviewChoices() {
        val enabled = StickerExportPolicy.modes(StickerSource.AUTOMATIC, true)
        assertTrue(StickerExportMode.ANIMATED_GIF in enabled && StickerExportMode.ANIMATED_WEBP in enabled)
        assertTrue(StickerExportPolicy.modes(StickerSource.STATIC, true).none { it.animated })
        assertTrue(StickerExportPolicy.modes(StickerSource.AUTOMATIC, false).none { it.animated })
        assertFalse(StickerExportMode.ORIGINAL in enabled)
    }

    @Test fun outputDimensionsRemainBoundedWithoutUpscalingSmallStickers() {
        assertEquals(StickerExportPolicy.Dimensions(320, 180), StickerExportPolicy.target(320, 180))
        assertEquals(StickerExportPolicy.Dimensions(2048, 1024), StickerExportPolicy.target(8000, 4000))
        assertEquals(StickerExportPolicy.Dimensions(1024, 2048), StickerExportPolicy.target(4000, 8000))
        assertEquals(StickerExportPolicy.Dimensions(1, 2048), StickerExportPolicy.target(1, 65536))
        for (size in listOf(0 to 1, -1 to 1, 1 to 0, Int.MAX_VALUE to 1, 20000 to 20000)) {
            assertThrows(IllegalArgumentException::class.java) { StickerExportPolicy.target(size.first, size.second) }
        }
    }

    @Test fun selectedSourcesKeepTheirOwnBoundariesAndFilterUnsafeAddresses() {
        val animated = "https://p16.tiktokcdn.com/a.gif"
        val static = "https://p16.tiktokcdn.com/a.png"
        assertEquals(listOf(animated), StickerExportPolicy.sources(listOf(animated), listOf(static), StickerSource.ANIMATED))
        assertEquals(listOf(static), StickerExportPolicy.sources(listOf(animated), listOf(static), StickerSource.STATIC))
        assertEquals(listOf(animated, static), StickerExportPolicy.sources(listOf(animated), listOf(static), StickerSource.AUTOMATIC))
        assertTrue(StickerExportPolicy.sources(listOf("http://localhost/a"), emptyList(), StickerSource.AUTOMATIC).isEmpty())
    }
}
