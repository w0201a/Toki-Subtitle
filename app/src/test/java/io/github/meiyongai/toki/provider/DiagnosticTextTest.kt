package io.github.meiyongai.toki.subtitle.provider

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    @Test fun urlsCredentialsAndPrivatePathsAreRedactedWithoutLosingTechnicalCounters() {
        val value = "url=https://private.example/video?id=123\nAuthorization: Bearer abcdef123456\n" +
            "api_key=sk-secret123456; path=/data/user/0/app/private.mp4\nC:\\Users\\private\\file.mp4 tracks=2"
        val text = DiagnosticText.redact(value)
        for (secret in listOf("private.example", "abcdef123456", "sk-secret123456", "/data/user", "Users")) assertFalse(text.contains(secret))
        assertTrue(text.contains("tracks=2")); assertTrue(text.contains("已隐藏"))
    }
    @Test fun lineBreaksCannotForgeEventsAndLengthsAreBounded() {
        val text = DiagnosticText.line("stage=ok\nFAKE EVENT\r\t", 40)
        assertFalse(text.contains('\n')); assertFalse(text.contains('\r')); assertFalse(text.contains('\t'))
        assertTrue(text.contains("\\n")); assertTrue(DiagnosticText.line("x".repeat(1000), 40).length <= 40)
    }
}
