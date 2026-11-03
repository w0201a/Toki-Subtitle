package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

/** Reads MDL cache-index metadata only; never calls preload, URL resolution, native export or download methods. */
internal class HostLocalVideoCache(private val loader: ClassLoader) {
    fun resolve(context: Context, aweme: Any, active: () -> Boolean = { true }): LocalVideoTracks.Plan {
        if (!OfflineCacheCodeGate.verify(context) || !OfflineExportCodeGate.verify(context))
            throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
        val candidates = OfflineExportCandidates(loader).read(aweme)
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
        val inspected = mutableMapOf<OfflineExportCandidates.Source, LocalVideoTracks.Source>()
        fun read(candidate: OfflineExportCandidates.Source): LocalVideoTracks.Source {
            inspected[candidate]?.let { return it }
            var reason = LocalVideoFile.Failure.MISSING
            for (key in candidate.keys) {
                check(active() && !Thread.currentThread().isInterrupted)
                val metadata = query.invoke(null, key, 100L, TimeUnit.MILLISECONDS, singleton) ?: continue
                val location = path.get(metadata) as? String ?: continue
                if (location.isBlank() || !File(location).isAbsolute) continue
                val bytes = total.getLong(metadata)
                if (candidate.expected > 0 && candidate.expected != bytes) { reason = LocalVideoFile.Failure.INCOMPLETE; continue }
                try {
                    val verified = LocalVideoFile.inspect(File(location), bytes, cached.getLong(metadata), roots)
                    return LocalVideoTracks.inspect(verified, active).also { inspected[candidate] = it }
                } catch (error: LocalVideoFile.Unavailable) { reason = error.reason }
                catch (_: Exception) { reason = LocalVideoFile.Failure.UNSUPPORTED }
            }
            throw LocalVideoFile.Unavailable(reason)
        }
        for (candidate in candidates) {
            check(active() && !Thread.currentThread().isInterrupted)
            try {
                val video = read(candidate.video)
                val sound = if (video.tracks.any { it.mime.startsWith("audio/") }) null else candidate.audio?.let(::read)
                return LocalVideoTracks.plan(video, sound, candidate.requireAudio, candidate.durationMs)
            } catch (error: LocalVideoFile.Unavailable) { failure = error.reason }
            catch (_: Exception) { failure = LocalVideoFile.Failure.UNSUPPORTED }
        }
        throw LocalVideoFile.Unavailable(failure)
    }
}
