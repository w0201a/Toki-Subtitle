package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.media.MediaExtractor
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

/** Reads MDL cache-index metadata only; never calls preload, URL resolution, native export or download methods. */
internal class HostLocalVideoCache(private val loader: ClassLoader) {
    private data class Candidate(val key: String, val expected: Long)
    fun resolve(context: Context, aweme: Any): LocalVideoFile.Verified {
        if (!OfflineCacheCodeGate.verify(context)) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
        val candidates = candidates(aweme)
        val helper = Class.forName("com.ss.ttvideoengine.DataLoaderHelper", false, loader)
        val singleton = helper.getDeclaredMethod("LJIIJJI").apply {
            check(returnType == helper && Modifier.isStatic(modifiers)); isAccessible = true
        }.invoke(null) ?: throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.MISSING)
        val base = Class.forName("X.0Cqi", false, loader)
        val result = Class.forName("X.0Cqj", false, loader)
        check(result.superclass == base)
        val total = base.getDeclaredField("LIZ").apply { check(type == java.lang.Long.TYPE); isAccessible = true }
        val cached = base.getDeclaredField("LIZIZ").apply { check(type == java.lang.Long.TYPE); isAccessible = true }
        val path = base.getDeclaredField("LIZJ").apply { check(type == String::class.java); isAccessible = true }
        val query = Class.forName("X.04ZT", false, loader).getDeclaredMethod("LIZJ",
            String::class.java, java.lang.Long.TYPE, TimeUnit::class.java, helper).apply {
            check(returnType == result && Modifier.isStatic(modifiers)); isAccessible = true
        }
        val roots = listOfNotNull(context.filesDir, context.cacheDir) +
            context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
        var failure = LocalVideoFile.Failure.MISSING
        for (candidate in candidates) {
            val metadata = query.invoke(null, candidate.key, 100L, TimeUnit.MILLISECONDS, singleton) ?: continue
            val location = path.get(metadata) as? String ?: continue
            if (location.isBlank() || !File(location).isAbsolute) continue
            val bytes = total.getLong(metadata)
            if (candidate.expected > 0 && candidate.expected != bytes) { failure = LocalVideoFile.Failure.INCOMPLETE; continue }
            try {
                val verified = LocalVideoFile.inspect(File(location), bytes, cached.getLong(metadata), roots)
                // Use a local descriptor, never a URI/string that the framework might resolve remotely.
                FileInputStream(verified.file).use { input ->
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(input.fd, 0, verified.size)
                        if (!extractor.psshInfo.isNullOrEmpty() || (0 until extractor.trackCount).none {
                            extractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true
                        }) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
                    } finally { extractor.release() }
                }
                return verified
            } catch (error: LocalVideoFile.Unavailable) { failure = error.reason }
            catch (_: Exception) { failure = LocalVideoFile.Failure.UNSUPPORTED }
        }
        throw LocalVideoFile.Unavailable(failure)
    }

    private fun candidates(aweme: Any): List<Candidate> {
        val video = aweme.javaClass.getMethod("getVideo").invoke(aweme)
            ?: throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.MISSING)
        if (video.javaClass.getMethod("hasVideoModel").invoke(video) == true)
            throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
        val url = Class.forName("com.ss.android.ugc.aweme.base.model.UrlModel", false, loader)
        val key = url.getMethod("getUrlKey")
        val uri = url.getMethod("getUri")
        val size = url.getMethod("getSize")
        val sources = listOf("getPlayAddr", "getPlayAddrH264", "getPlayAddrBytevc1").mapNotNull {
            video.javaClass.getMethod(it).invoke(video)
        }.toMutableList()
        val rates = video.javaClass.getMethod("getBitRate").invoke(video) as? List<*>
        rates.orEmpty().take(24).filterNotNull().forEach { rate ->
            rate.javaClass.getMethod("getPlayAddr").invoke(rate)?.let(sources::add)
        }
        return sources.filter(url::isInstance).mapNotNull { source ->
            val value = (key.invoke(source) as? String)?.takeIf { it.isNotBlank() }
                ?: (uri.invoke(source) as? String)?.takeIf { it.isNotBlank() }
            value?.takeIf { it.length <= 1024 }?.let { Candidate(it, size.invoke(source) as Long) }
        }.distinctBy { it.key }.take(32)
    }
}
