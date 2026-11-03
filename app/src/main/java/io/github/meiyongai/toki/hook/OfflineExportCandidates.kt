package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Modifier

/** Read inspected model fields only: no computed model getter, service, URL resolution or download operation. */
internal class OfflineExportCandidates internal constructor(private val video: Class<*>, private val url: Class<*>,
    private val rate: Class<*>, private val audio: Class<*>, private val meta: Class<*>, private val videoUrl: Class<*>) {
    constructor(loader: ClassLoader) : this(
        loader.loadClass("com.ss.android.ugc.aweme.feed.model.Video"),
        loader.loadClass("com.ss.android.ugc.aweme.base.model.UrlModel"),
        loader.loadClass("com.ss.android.ugc.aweme.feed.model.BitRate"),
        loader.loadClass("com.ss.android.ugc.aweme.feed.model.BitrateAudioStruct"),
        loader.loadClass("com.ss.android.ugc.aweme.feed.model.BitrateMetaStruct"),
        loader.loadClass("com.ss.android.ugc.aweme.feed.model.VideoUrlModel"))
    data class Source(val keys: List<String>, val expected: Long)
    data class Plan(val video: Source, val audio: Source? = null, val requireAudio: Boolean = false, val durationMs: Int = 0)
    private fun field(type: Class<*>, name: String, expected: Class<*>) = type.getDeclaredField(name).apply {
        check(this.type == expected && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val addresses = listOf("playAddrValue", "h264PlayAddrValue", "playAddrBytevc1Value").map { field(video, it, videoUrl) }
    private val rates = field(video, "bitRateList", List::class.java)
    private val audios = field(video, "bitRateAudio", List::class.java)
    private val duration = field(video, "videoLength", Int::class.javaPrimitiveType!!)
    private val format = field(video, "format", String::class.java)
    private val rateAddress = field(rate, "playAddr", url)
    private val audioMeta = field(audio, "audioMeta", meta)
    private val selectedAudio = field(videoUrl, "hitDashAudioBitrate", audio)
    private val selectedVideo = field(videoUrl, "hitDashVideoBitrate", rate)
    private val key = field(url, "urlKey", String::class.java)
    private val uri = field(url, "uri", String::class.java)
    private val hash = field(url, "fileHash", String::class.java)
    private val size = field(url, "size", Long::class.javaPrimitiveType!!)
    private val audioHash = field(meta, "fileHash", String::class.java)
    private val audioSize = field(meta, "size", Long::class.javaPrimitiveType!!)

    private fun boundedKeys(values: List<Any?>): List<String> = values.filterIsInstance<String>()
        .filter { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) }.distinct().take(3)
    private fun address(value: Any?): Source? {
        if (!url.isInstance(value)) return null
        val keys = boundedKeys(listOf(key.get(value), uri.get(value), hash.get(value)))
        return keys.takeIf { it.isNotEmpty() }?.let { Source(it, size.getLong(value)) }
    }
    private fun sound(value: Any?): Source? {
        if (!audio.isInstance(value)) return null
        val data = audioMeta.get(value) ?: return null
        // Inspected native cache reader uses audioMeta.fileHash for DASH audio; fileId/quality are not interchangeable keys.
        val keys = boundedKeys(listOf(audioHash.get(data)))
        return keys.takeIf { it.isNotEmpty() }?.let { Source(it, audioSize.getLong(data)) }
    }
    fun read(aweme: Any): List<Plan> {
        val getVideo = aweme.javaClass.getMethod("getVideo").apply {
            check(returnType == video && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val model = getVideo.invoke(aweme) ?: return emptyList()
        check(video.isInstance(model))
        val rawSounds = (audios.get(model) as? List<*>).orEmpty()
        val sounds = rawSounds.take(24).mapNotNull(::sound).distinct()
        val urls = addresses.mapNotNull { it.get(model) }
        val needsAudio = rawSounds.isNotEmpty() || urls.any { selectedAudio.get(it) != null } || format.get(model) == "dash"
        val length = duration.getInt(model)
        val result = mutableListOf<Plan>()
        // Prefer the explicit host-selected pair. Do not arbitrarily select a language from several audio alternatives.
        urls.forEach { value ->
            val selected = selectedVideo.get(value)?.let { address(rateAddress.get(it)) }
            val sound = sound(selectedAudio.get(value))
            if (selected != null && sound != null) result += Plan(selected, sound, true, length)
        }
        val unambiguousAudio = if (rawSounds.size <= 24 && rawSounds.all { sound(it) != null }) sounds.singleOrNull() else null
        urls.forEach { value -> address(value)?.let { own ->
            val selected = selectedVideo.get(value)?.let { address(rateAddress.get(it)) }
            // A selected audio belongs to its selected video, not automatically to every fallback address.
            val sameSelection = selected == null || selected.keys.any { it in own.keys }
            val selectedSound = if (sameSelection) sound(selectedAudio.get(value)) else null
            result += Plan(own, selectedSound ?: unambiguousAudio, needsAudio, length)
        } }
        (rates.get(model) as? List<*>).orEmpty().take(24).filter(rate::isInstance).forEach { value ->
            address(rateAddress.get(value))?.let { result += Plan(it, unambiguousAudio, needsAudio, length) }
        }
        return result.distinct().take(32)
    }
}
