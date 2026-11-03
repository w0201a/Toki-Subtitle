package io.github.meiyongai.toki.subtitle.provider

import org.junit.Assert.*
import org.junit.Test

class TrialLogTest {
    @Test fun hugeAndRepeatedLogMessagesRemainBoundedWithVisibleLossCounters() {
        repeat(700) { TrialLog.add("test", "row=$it " + "x".repeat(800)) }
        val text = TrialLog.snapshot()
        assertTrue(text.length < 100 * 1024)
        assertTrue(text.contains("已覆盖旧行=")); assertTrue(text.contains("row=699"))
        assertFalse(text.contains("row=0 ")); assertTrue(text.contains("UTC时间"))
    }
    @Test fun sourceUrlsAndForgedNewlinesAreNotCopiedIntoTheEventStream() {
        TrialLog.add("privacy", "url=https://private.example/video\nFAKE")
        val last = TrialLog.snapshot().lineSequence().last()
        assertFalse(last.contains("private.example")); assertTrue(last.contains("\\nFAKE"))
    }
}
