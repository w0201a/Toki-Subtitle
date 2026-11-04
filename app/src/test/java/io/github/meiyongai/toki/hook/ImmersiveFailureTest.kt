package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class ImmersiveFailureTest {
    @Test fun stageAndFieldIdentifyTheActualRestoreAndUnwrapReflectionWithoutPrintingContent() {
        val native = SecurityException("user-private-value https://private.example/token")
        val failure = ImmersiveFailure.wrap(ImmersiveFailure.Phase.RESTORE,
            ImmersiveFailure.Field.FITS, InvocationTargetException(native))
        val text = failure.summary()
        assertTrue(text.contains("恢复窗口")); assertTrue(text.contains("系统栏适配")); assertTrue(text.contains("SecurityException"))
        assertFalse(text.contains("user-private-value")); assertFalse(text.contains("private.example")); assertSame(native, failure.cause!!.cause)
    }
    @Test fun anExistingSpecificFieldFailureIsNotOverwrittenByAGenericParentStage() {
        val specific = ImmersiveFailure.wrap(ImmersiveFailure.Phase.BACKGROUND_RESTORE,
            ImmersiveFailure.Field.BACKGROUND, IllegalStateException("private"))
        assertSame(specific, ImmersiveFailure.wrap(ImmersiveFailure.Phase.WINDOW_REFRESH, ImmersiveFailure.Field.WINDOW, specific))
    }
    @Test fun cyclicCausesAreBoundedAndMessagesAreNeverAnAlternateDiagnosticPath() {
        val first = IllegalStateException("first-private"); val next = IllegalArgumentException("next-private")
        first.initCause(next); next.initCause(first)
        val text = ImmersiveFailure.wrap(ImmersiveFailure.Phase.CONFIGURATION, ImmersiveFailure.Field.CONFIGURATION, first).summary()
        assertTrue(text.length < 220); assertFalse(text.contains("private")); assertTrue(text.contains("配置快照"))
    }
}
