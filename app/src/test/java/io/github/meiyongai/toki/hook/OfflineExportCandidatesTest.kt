package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class OfflineExportCandidatesTest {
    open class Url(@JvmField var urlKey: String = "", @JvmField var uri: String = "", @JvmField var fileHash: String = "", @JvmField var size: Long = 10)
    class Rate(@JvmField var playAddr: Url?)
    class Meta(@JvmField var fileHash: String = "", @JvmField var size: Long = 10)
    class Sound(@JvmField var audioMeta: Meta?)
    class VideoUrl(key: String = "") : Url(urlKey = key) {
        @JvmField var hitDashAudioBitrate: Sound? = null
        @JvmField var hitDashVideoBitrate: Rate? = null
    }
    class Video {
        @JvmField var playAddrValue: VideoUrl? = null
        @JvmField var h264PlayAddrValue: VideoUrl? = null
        @JvmField var playAddrBytevc1Value: VideoUrl? = null
        @JvmField var bitRateList: List<Rate> = emptyList()
        @JvmField var bitRateAudio: List<Sound> = emptyList()
        @JvmField var videoLength: Int = 1000
        @JvmField var format: String = "mp4"
        fun getPlayAddr(): Nothing = error("Must not call computed model getters")
        fun hasVideoModel(): Nothing = error("Must not blanket-reject model-based video")
        fun getVideoModelStr(): Nothing = error("Must not request model expansion")
    }
    class Aweme(private val model: Video) { fun getVideo(): Video = model }
    private fun reader() = OfflineExportCandidates(Video::class.java, Url::class.java, Rate::class.java, Sound::class.java, Meta::class.java, VideoUrl::class.java)
    @Test fun rawAddressesAndBitratesWorkWithoutComputedModelOrNetworkGetters() {
        val video = Video().apply {
            playAddrValue = VideoUrl("primary"); bitRateList = listOf(Rate(Url(urlKey = "rate")))
        }
        val rows = reader().read(Aweme(video))
        assertEquals(listOf("primary", "rate"), rows.map { it.video.keys.first() })
        assertTrue(rows.all { it.audio == null && !it.requireAudio && it.durationMs == 1000 })
    }
    @Test fun exactHostSelectedPairPrecedesAlternativesWithoutChoosingAnArbitraryAudioLanguage() {
        val first = Sound(Meta("audio-en")); val second = Sound(Meta("audio-ja"))
        val video = Video().apply {
            bitRateAudio = listOf(first, second)
            playAddrValue = VideoUrl("container").apply {
                hitDashVideoBitrate = Rate(Url(urlKey = "selected-video")); hitDashAudioBitrate = second
            }
        }
        val rows = reader().read(Aweme(video))
        assertEquals("selected-video", rows.first().video.keys.first())
        assertEquals("audio-ja", rows.first().audio?.keys?.single())
        assertTrue(rows.first().requireAudio)
        assertNull(rows.last().audio); assertTrue(rows.last().requireAudio)
    }
    @Test fun oneUnambiguousAudioHashCanAccompanyRawVideoWhileInvalidAudioCannotBecomeSilentSuccess() {
        val video = Video().apply { playAddrValue = VideoUrl("v"); bitRateAudio = listOf(Sound(Meta("audio-cache-hash"))) }
        assertEquals("audio-cache-hash", reader().read(Aweme(video)).single().audio?.keys?.single())
        video.bitRateAudio = listOf(Sound(null))
        val rejectedPair = reader().read(Aweme(video)).single()
        assertTrue(rejectedPair.requireAudio); assertNull(rejectedPair.audio)
    }
    @Test fun dashMetadataWithoutDiscoverableAudioDoesNotBecomeSilentSuccess() {
        val video = Video().apply { playAddrValue = VideoUrl("v"); format = "dash" }
        val plan = reader().read(Aweme(video)).single()
        assertTrue(plan.requireAudio); assertNull(plan.audio)
    }
    @Test fun selectedAudioWithoutAnAudioListOrSelectedRateCannotBeDropped() {
        val url = VideoUrl("v").apply { hitDashAudioBitrate = Sound(Meta("chosen-audio")) }
        val video = Video().apply { playAddrValue = url }
        val plan = reader().read(Aweme(video)).single()
        assertTrue(plan.requireAudio); assertEquals("chosen-audio", plan.audio?.keys?.single())
        url.hitDashAudioBitrate = Sound(null)
        val damaged = reader().read(Aweme(video)).single()
        assertTrue(damaged.requireAudio); assertNull(damaged.audio)
    }
    @Test fun invalidOrDuplicateCacheKeysAreBoundedAndDoNotCreateNetworkRequests() {
        val url = VideoUrl("same").apply { uri = "same"; fileHash = "hash" }
        val video = Video().apply { playAddrValue = url; h264PlayAddrValue = url; playAddrBytevc1Value = VideoUrl("bad\nkey") }
        val rows = reader().read(Aweme(video))
        assertEquals(1, rows.size); assertEquals(listOf("same", "hash"), rows.single().video.keys)
        url.urlKey = "x".repeat(1025); url.uri = ""; url.fileHash = ""
        assertTrue(reader().read(Aweme(video)).isEmpty())
    }
}
