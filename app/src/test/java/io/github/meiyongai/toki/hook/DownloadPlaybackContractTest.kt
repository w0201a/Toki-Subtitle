package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class DownloadPlaybackContractTest {
    open class Url
    class VideoWithRenamedRawGetter {
        fun getPlayAddr() = Url()
        fun getPlayAddrBytevc1() = Url()
    }
    class WrongType { fun getPlayAddr() = "not a URL model" }
    @Test fun missingRawVariantDoesNotDiscardTwoVerifiedAddressGetters() {
        assertEquals(listOf("getPlayAddr", "getPlayAddrBytevc1"), DownloadHook.playbackGetters(VideoWithRenamedRawGetter::class.java, Url::class.java).map { it.name })
        assertThrows(IllegalStateException::class.java) { DownloadHook.playbackGetters(WrongType::class.java, Url::class.java) }
    }
}
