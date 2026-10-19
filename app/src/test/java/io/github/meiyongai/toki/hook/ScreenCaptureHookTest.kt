package io.github.meiyongai.toki.subtitle.hook

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureHookTest {
    @Test fun disabledCompatibilityPreservesSecureAndOtherFlags() {
        val flags = WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(flags, ScreenCaptureHook.compatibleFlags(flags, false))
    }
    @Test fun enabledCompatibilityPreservesUnrelatedWindowBehavior() {
        val flags = WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, ScreenCaptureHook.compatibleFlags(flags, true))
    }
}
