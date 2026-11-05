package io.github.meiyongai.toki.subtitle.hook

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class OfflineCommentRequestFailureTest {
    class NetworkError : IOException("secret-token https://private.invalid/path", SocketTimeoutException("private body")) {
        fun getStatusCode(): Int = 429
        fun getCronetInternalErrorCode(): Int = -118
        fun getRequestLog(): String = error("Must never read request logs")
    }
    @Test fun unwrapsNativeErrorCodesAndCauseWithoutMessagesHeadersOrUrls() {
        val value = OfflineCommentRequestFailure.describe("source", InvocationTargetException(NetworkError()))
        assertTrue(value.contains("http=429")); assertTrue(value.contains("network=-118")); assertTrue(value.contains("SocketTimeoutException"))
        for (secret in listOf("secret-token", "private.invalid", "private body", "https://")) assertFalse(value.contains(secret))
    }
    @Test fun unknownErrorsRemainTypedAndDoNotRequireANativeClassName() {
        val value = OfflineCommentRequestFailure.describe("translation", IllegalStateException("sensitive source"))
        assertEquals("stage=translation cause=IllegalStateException", value)
    }
}
