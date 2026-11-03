package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.VideoFitMode
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class VideoDisplayModesTest {
    @Test fun allModesHaveStableValidatedKeysWithoutEnablingAFeatureByThemselves() {
        assertEquals(setOf("center", "smart", "fill", "contain", "fit_width", "fit_height"), VideoFitMode.keys)
        for (mode in VideoFitMode.entries) {
            assertEquals(mode.key, ConfigSchema.validate(mapOf("video_fit_mode" to mode.key))["video_fit_mode"])
            assertEquals(mode, VideoFitMode.from(mode.key))
            assertTrue(HostFeaturePlan(ConfigSnapshot(mapOf("video_fit_mode" to mode.key))).features.isEmpty())
        }
        assertEquals(VideoFitMode.CENTER, VideoFitMode.from("unknown"))
        assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf("video_fit_mode" to "stretch")) }
    }
    @Test fun containmentKeepsEveryEdgeInsideTheViewportWithoutStretching() {
        for (dimensions in listOf(listOf(1920, 1080, 1080, 2400), listOf(1080, 1920, 2400, 1080),
            listOf(1000, 1000, 1080, 2400), listOf(1080, 1920, 1080, 2400))) {
            val (vw, vh, w, h) = dimensions
            val result = checkNotNull(VideoDisplayGeometry.calculate(vw, vh, w, h, VideoFitMode.CONTAIN))
            assertTrue(result.width <= w && result.height <= h)
            assertFalse(result.cropped)
            assertEquals(vw.toDouble() / vh, result.width.toDouble() / result.height, .004)
        }
    }
    @Test fun widthAndHeightModesUseTheirChosenAxisAndMakeCroppingExplicit() {
        val width = checkNotNull(VideoDisplayGeometry.calculate(1000, 3000, 1080, 2400, VideoFitMode.WIDTH))
        assertEquals(1080, width.width); assertEquals(3240, width.height); assertTrue(width.cropped); assertFalse(width.fitHeight)
        val height = checkNotNull(VideoDisplayGeometry.calculate(1920, 1080, 1080, 2400, VideoFitMode.HEIGHT))
        assertEquals(2400, height.height); assertTrue(height.width > 1080); assertTrue(height.cropped); assertTrue(height.fitHeight)
        val narrow = checkNotNull(VideoDisplayGeometry.calculate(500, 2000, 1080, 2400, VideoFitMode.HEIGHT))
        assertEquals(600, narrow.width); assertFalse(narrow.cropped)
    }
    @Test fun originalAndLegacyModesRetainTheirSemanticsAndBadMeasurementsStayNative() {
        assertNull(VideoDisplayGeometry.calculate(1080, 1920, 1080, 2400, VideoFitMode.CENTER))
        assertEquals(VideoFillGeometry.calculate(1080, 1920, 1080, 2400),
            VideoDisplayGeometry.calculate(1080, 1920, 1080, 2400, VideoFitMode.SMART))
        assertEquals(VideoFillGeometry.calculate(1080, 1920, 1080, 2400, true, 120),
            VideoDisplayGeometry.calculate(1080, 1920, 1080, 2400, VideoFitMode.FILL, 120))
        assertNull(VideoDisplayGeometry.calculate(0, 100, 1080, 2400, VideoFitMode.CONTAIN))
        assertNull(VideoDisplayGeometry.calculate(1, Int.MAX_VALUE, Int.MAX_VALUE, 1, VideoFitMode.WIDTH))
    }
    @Test fun installedModeChangesAreLiveButWindowMasterSwitchRemainsFrozen() {
        val plan = HostFeaturePlan(ConfigSnapshot(mapOf("immersive_full_screen" to true, "video_fit_mode" to "center")))
        for (mode in VideoFitMode.entries) {
            val changed = ConfigSnapshot(mapOf("immersive_full_screen" to true, "video_fit_mode" to mode.key))
            assertFalse(plan.requiresRestart("ImmersiveFullScreenHook", changed))
            assertEquals(mode.key, plan.apply(changed).string("video_fit_mode"))
        }
        val off = ConfigSnapshot(mapOf("immersive_full_screen" to false, "video_fit_mode" to "contain"))
        assertTrue(plan.requiresRestart("ImmersiveFullScreenHook", off))
        assertTrue(plan.apply(off).boolean("immersive_full_screen"))
    }
    @Test fun resultReplacementUsesTheSameOriginalAndCenterRestoresItsIdentity() {
        val contract = SmartVideoLayout(SmartVideoLayoutTest.Feed::class.java, SmartVideoLayoutTest.Result::class.java,
            SmartVideoLayoutTest.Multi::class.java)
        val params = SmartVideoLayoutTest.Params(1000, 3000, 1080, 2400)
        val op = SmartVideoLayoutTest.Multi(1, 2, 30f, 50f, 2300f, 1080f,
            SmartVideoLayoutTest.Scale.FIT, SmartVideoLayoutTest.Align.WIDTH, .1f, params, true)
        val raw = SmartVideoLayoutTest.Result(1080, 3240, 10f, 20f, op)
        val contained = contract.replace(raw, 1080, 2400, mode = VideoFitMode.CONTAIN) as SmartVideoLayoutTest.Result
        assertEquals(800, contained.width); assertEquals(2400, contained.height)
        val width = contract.replace(raw, 1080, 2400, mode = VideoFitMode.WIDTH) as SmartVideoLayoutTest.Result
        assertEquals(1080, width.width); assertEquals(3240, width.height)
        assertSame(raw, contract.replace(raw, 1080, 2400, mode = VideoFitMode.CENTER))
        assertSame(op, raw.resultOperator)
        assertEquals(20f, raw.translateY)
    }
}
