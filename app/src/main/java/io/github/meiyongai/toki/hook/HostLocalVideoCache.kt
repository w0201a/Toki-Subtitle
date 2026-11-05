package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.OperationJournal
import android.content.Context
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

/** Native offline DB/files first; ordinary cache index is only a fallback. Never fetches media. */
internal class HostLocalVideoCache(private val loader: ClassLoader) {
    fun resolve(context: Context, aweme: Any, active: () -> Boolean = { true }, operation: OperationJournal.Run? = null): LocalVideoTracks.Plan {
        operation?.stage(OperationJournal.Step.CODE_CHECK)
        if (!OfflineExportCodeGate.verify(context)) {
            operation?.finish(OperationJournal.Result.UNAVAILABLE, OperationJournal.Reason.CODE_MISMATCH)
            throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
        }
        val candidates = OfflineExportCandidates(loader).read(aweme)
        operation?.stage(OperationJournal.Step.CANDIDATES, mapOf("candidatePlans" to candidates.size.toLong()))
        val roots = listOfNotNull(context.filesDir, context.cacheDir) +
            context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
        // Prefer the native offline record: playback/rate candidates need not contain its stored key.
        operation?.stage(OperationJournal.Step.OFFLINE_LOOKUP)
        val direct = HostOfflineVideoFiles(loader).find(context, aweme, roots)
        if (direct != null) {
            check(active() && !Thread.currentThread().isInterrupted)
            val verified = OfflineMdlFile.inspect(File(direct.directory, direct.key + ".mdl"),
                File(direct.directory, direct.key + ".mdlnodeconf"), direct.key, listOf(direct.directory)) { active() && direct.current() }
            operation?.stage(OperationJournal.Step.HEADER_RESTORE, mapOf("sourceBytes" to verified.size))
            val video = LocalVideoTracks.inspect(verified, active)
            val matching = candidates.filter { direct.key in it.video.keys }
            val duration = matching.firstOrNull()?.durationMs ?: candidates.firstOrNull()?.durationMs ?: 0
            // The native offline cache is a muxed stream. Do not guess a separate audio partner.
            val plan = LocalVideoTracks.plan(video, null, candidates.any { it.requireAudio }, duration)
            operation?.stage(OperationJournal.Step.PLAN_READY, mapOf("tracks" to plan.tracks.size.toLong(),
                "samples" to plan.tracks.sumOf { it.second.count }, "sourceBytes" to verified.size))
            return plan
        }
        if (!OfflineCacheCodeGate.verify(context)) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
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
        var failure = LocalVideoFile.Failure.MISSING
        val inspected = mutableMapOf<OfflineExportCandidates.Source, LocalVideoTracks.Source>()
        fun read(candidate: OfflineExportCandidates.Source): LocalVideoTracks.Source {
            inspected[candidate]?.let { return it }
            var reason = LocalVideoFile.Failure.MISSING
            operation?.stage(OperationJournal.Step.CACHE_LOOKUP, mapOf("candidateKeys" to candidate.keys.size.toLong()))
            for (key in candidate.keys) {
                check(active() && !Thread.currentThread().isInterrupted)
                val metadata = query.invoke(null, key, 100L, TimeUnit.MILLISECONDS, singleton) ?: continue
                val location = path.get(metadata) as? String ?: continue
                if (location.isBlank() || !File(location).isAbsolute) continue
                val bytes = total.getLong(metadata)
                if (candidate.expected > 0 && candidate.expected != bytes) { reason = LocalVideoFile.Failure.INCOMPLETE; continue }
                try {
                    val verified = LocalVideoFile.inspect(File(location), bytes, cached.getLong(metadata), roots)
                    operation?.stage(OperationJournal.Step.SAMPLE_CHECK, mapOf("sourceBytes" to bytes, "fragmented" to if (verified.fragmented) 1L else 0L))
                    return LocalVideoTracks.inspect(verified, active).also { inspected[candidate] = it }
                } catch (error: LocalVideoFile.Unavailable) { reason = error.reason; operation?.attemptFailed(error) }
                catch (error: Exception) { reason = LocalVideoFile.Failure.UNSUPPORTED; operation?.attemptFailed(error) }
            }
            throw LocalVideoFile.Unavailable(reason)
        }
        for (candidate in candidates) {
            check(active() && !Thread.currentThread().isInterrupted)
            try {
                val video = read(candidate.video)
                val sound = if (video.tracks.any { it.mime.startsWith("audio/") }) null else candidate.audio?.let(::read)
                return LocalVideoTracks.plan(video, sound, candidate.requireAudio, candidate.durationMs).also { plan ->
                    operation?.stage(OperationJournal.Step.PLAN_READY, mapOf("tracks" to plan.tracks.size.toLong(),
                        "samples" to plan.tracks.sumOf { it.second.count }, "sourceBytes" to plan.tracks.sumOf { it.second.bytes }))
                }
            } catch (error: LocalVideoFile.Unavailable) { failure = error.reason; operation?.attemptFailed(error) }
            catch (error: Exception) { failure = LocalVideoFile.Failure.UNSUPPORTED; operation?.attemptFailed(error) }
        }
        throw LocalVideoFile.Unavailable(failure)
    }
}
