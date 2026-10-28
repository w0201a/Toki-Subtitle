package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 使用宿主字幕状态与内容；自定义显示层不再受原字幕容器约束。 */
internal object SubtitleFeature {
    private fun translating() = ConfigClient.getBoolean("video_translate_enabled")

    fun install(module: XposedModule, loader: ClassLoader, owner: String) {
        HookRuntime.install("SubtitleLanguage") { installLanguage(module, loader, "SubtitleLanguage") }
        HookRuntime.install("SubtitleExcludedLanguages") { installExcludedLanguages(module, loader, "SubtitleExcludedLanguages") }
        HookRuntime.install("SubtitleMenu") { SubtitleMenu.install(module, loader, "SubtitleMenu") }
        installDisplay(module, loader, owner)
    }

    private fun installLanguage(module: XposedModule, loader: ClassLoader, owner: String) {
        val target = ExtensionSymbols.captionLanguage(loader, true)
            ?: loader.adaptiveClass("X.08ZO").adaptiveDeclaredMethod("LIZLLL")
        check(target.returnType == String::class.java)
        module.trackHook(owner, target).intercept { chain ->
            val original = chain.proceed()
            if (translating()) "zh-Hans" else original
        }
    }

    private fun installExcludedLanguages(module: XposedModule, loader: ClassLoader, owner: String) {
        val excluded = ExtensionSymbols.captionLanguage(loader, false)
            ?: loader.adaptiveClass("X.08ZO").adaptiveDeclaredMethod("LIZJ")
        check(excluded.returnType == Array<String>::class.java)
        module.trackHook(owner, excluded).intercept { chain ->
            val original = chain.proceed() as? Array<*>
            if (translating()) original?.filterIsInstance<String>()?.let(SubtitlePolicy::includeEnglish) else original
        }
    }

    private fun installDisplay(module: XposedModule, loader: ClassLoader, owner: String) {
        val translator = try {
            val serviceTarget = loader.adaptiveClass("X.03hV").adaptiveDeclaredMethod("LLILLIZIL")
            check(serviceTarget.returnType == String::class.java)
            val connected = NativeSubtitleTranslator.connect(loader)
            HookRuntime.onDispose(owner) { connected.close() }
            module.trackHook(owner, serviceTarget).intercept { chain ->
                val original = chain.proceed()
                if (translating()) "zh-Hans" else original
            }
            HookRuntime.event("NativeSubtitleTranslation", "native text service ready; target=zh-Hans")
            connected
        } catch (error: Exception) {
            HookRuntime.event("NativeSubtitleTranslation", "unavailable contract=${error.javaClass.simpleName}; original retained")
            null
        } catch (error: LinkageError) {
            HookRuntime.event("NativeSubtitleTranslation", "unavailable contract=${error.javaClass.simpleName}; original retained")
            null
        }
        val renderer = loader.adaptiveClass("X.0B1g")
        if (translator != null) {
            val prefetchOwner = "SubtitleFeedPrefetch"
            HookRuntime.dependent(owner, prefetchOwner)
            HookRuntime.install(prefetchOwner) {
                val upcoming = FeedCaptionLookAhead(loader)
                HookRuntime.onDispose(prefetchOwner) { translator.upcomingTracks = { _, _ -> emptyList() } }
                upcoming.install(module, prefetchOwner)
                translator.upcomingTracks = upcoming::next
                val holder = loader.loadClass("com.ss.android.ugc.aweme.feed.adapter.VideoViewCell")
                val getAweme = holder.getMethod("getAweme")
                val selection = CaptionPlaybackSelection()
                var timelineOverlay: SubtitleOverlay? = null
                var timelineVideo = ""
                var nextTimelineRefresh = 0L
                val getVideoView = holder.getMethod("getVideoView")
                HookRuntime.onDispose(prefetchOwner) {
                    selection.clear(); timelineOverlay?.close(); timelineOverlay = null
                }
                module.trackHook(prefetchOwner, android.app.Activity::class.java.getDeclaredMethod("onPause")).intercept { chain ->
                    selection.clear()
                    translator.cancel()
                    timelineOverlay?.close(); timelineOverlay = null; timelineVideo = ""
                    chain.proceed()
                }
                val progress = holder.getMethod("onPlayProgressChange", String::class.java,
                    Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                module.trackHook(prefetchOwner, progress).intercept { chain ->
                    val result = chain.proceed()
                    val id = chain.args[0] as? String
                    if (!id.isNullOrBlank() && selection.owns(chain.thisObject, id)) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now >= nextTimelineRefresh) {
                            nextTimelineRefresh = now + 1000
                            translator.readAhead(id, "") {
                                selection.current(id) && translating()
                            }
                        }
                        val cue = if (translating() && translator.isEnabled(id) && !translator.hasNativeFrame(id))
                            translator.cueAt(id, chain.args[1] as Long) else null
                        if (CaptionDebugOverlay.enabled) {
                            val debugView = getVideoView.invoke(chain.thisObject) as? View
                            CaptionDebugOverlay.update(debugView, id, "timeline",
                                if (cue == null) "HIDDEN" else "EXPANDED", cue?.length ?: 0, translator)
                        }
                        if (timelineVideo != id) { timelineOverlay?.close(); timelineOverlay = null; timelineVideo = id }
                        if (cue != null) {
                            val view = getVideoView.invoke(chain.thisObject) as? View
                            if (view != null) {
                                val binding = timelineOverlay ?: SubtitleOverlay(view, translator, timelineSource = true).also {
                                    timelineOverlay = it
                                    HookRuntime.event("SubtitleTimelineDisplay", "playback timeline display attached")
                                }
                                binding.update("EXPANDED", cue, id)
                            }
                        } else timelineOverlay?.update("HIDDEN", "", id)
                    }
                    result
                }
                for (method in listOf(holder.getMethod("onPageSelected", Int::class.javaPrimitiveType),
                    holder.getMethod("onResumePlay", String::class.java))) {
                    module.trackHook(prefetchOwner, method).intercept { chain ->
                        val result = chain.proceed()
                        val instance = checkNotNull(chain.thisObject)
                        getAweme.invoke(instance)?.let(upcoming::selected)?.let { id ->
                            if (id != selection.video) {
                                timelineOverlay?.close(); timelineOverlay = null; timelineVideo = ""; nextTimelineRefresh = 0L
                                CaptionDebugOverlay.clear()
                            }
                            translator.selectVideo(id)
                            selection.select(instance, id)
                            translator.readAhead(id, "") {
                                selection.current(id) && translating()
                            }
                        }
                        result
                    }
                }
            }
        }
        val rootField = renderer.adaptiveDeclaredField("LIZJ").apply { isAccessible = true }
        val videoField = runCatching { renderer.adaptiveDeclaredField("LJI").apply {
            check(type == String::class.java); isAccessible = true
        } }.getOrNull()
        val display = try {
            ExtensionSymbols.methodByRole(renderer, "LJIIIZ").also {
                check(it.returnType == Void.TYPE && it.parameterTypes.size >= 3 &&
                    it.parameterTypes[0] == String::class.java && it.parameterTypes[2] == String::class.java &&
                    !it.parameterTypes[1].isPrimitive) { "Caption display contract changed" }
            }
        } catch (error: ReflectiveOperationException) {
            HookRuntime.event(owner, "renderer method unavailable; independently verified frame path required")
            null
        } catch (error: IllegalStateException) {
            HookRuntime.event(owner, "renderer method unresolved; independently verified frame path required")
            null
        } catch (error: NoSuchElementException) {
            HookRuntime.event(owner, "renderer method not found; independently verified frame path required")
            null
        }
        val bindings = WeakHashMap<View, WeakReference<SubtitleOverlay>>()
        val verifiedRenderers = WeakHashMap<Any, Boolean>()
        val frameVideos = WeakHashMap<Any, String>()
        val gestures = WeakHashMap<android.app.Activity, SubtitleGesture>()
        HookRuntime.onDispose(owner) {
            gestures.values.forEach { it.close() }
            gestures.clear()
            bindings.values.toList().forEach { it.get()?.close() }
            bindings.clear()
            verifiedRenderers.clear()
            frameVideos.clear()
        }
        HookRuntime.dependent(owner, "SubtitleRendererBinding")
        HookRuntime.dependent(owner, "SubtitleCleanFrames")
        val activity = loader.adaptiveClass("com.ss.android.ugc.aweme.splash.SplashActivity")
        module.trackHook(owner, android.app.Activity::class.java.adaptiveDeclaredMethod("onPause")).intercept { chain ->
            gestures.remove(chain.thisObject)?.close()
            chain.proceed()
        }
        module.trackHook(owner, activity.adaptiveMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)).intercept { chain ->
            val screen = chain.thisObject as android.app.Activity
            val event = chain.args[0] as android.view.MotionEvent
            val gesture = gestures.getOrPut(screen) { SubtitleGesture(screen) }
            if (gesture.touch(event) { x, y -> bindings.values.firstNotNullOfOrNull { ref ->
                    ref.get()?.takeIf { it.hit(screen, x, y) }
                } }) true else chain.proceed()
        }
        var unknownRootReported = false
        // 原生清屏期间 bs 会因 onBlockUIUpdate 提前返回，渲染器收不到新帧。
        // 直接旁听 VM 已送达的字幕帧，不解除宿主的全局阻塞或改写播放器生命周期。
        val captionAssem = loader.adaptiveClass("com.ss.android.ugc.aweme.autocaption.refactor.CLACaptionAssemV2")
        val rendererField = captionAssem.adaptiveDeclaredField("LLLLLILLIL").apply { isAccessible = true }
        HookRuntime.install("SubtitleRendererBinding") {
            val created = captionAssem.getMethod("onViewCreated", View::class.java)
            module.trackHook("SubtitleRendererBinding", created).intercept { chain ->
                val result = chain.proceed()
                if (captionAssem.isInstance(chain.thisObject)) rendererField.get(chain.thisObject)?.let { verifiedRenderers[it] = true }
                result
            }
        }
        HookRuntime.install("SubtitleCleanFrames") {
            val captionFrame = loader.adaptiveClass("X.0B14")
            val frameText = captionFrame.adaptiveDeclaredField("LIZ").apply { isAccessible = true }
            val frameState = captionFrame.adaptiveDeclaredField("LIZIZ").apply { isAccessible = true }
            val viewModel = captionAssem.adaptiveDeclaredMethod("Sr")
            val videoKey = runCatching {
                loader.adaptiveClass("X.0B1s").adaptiveDeclaredMethod("LIZ", loader.adaptiveClass("X.08vj")).also {
                    check(it.parameterTypes.single().isAssignableFrom(viewModel.returnType))
                }
            }.getOrNull()
            val directVideoKey = if (videoKey == null) viewModel.returnType.getMethod("getAwemeId").also {
                check(it.returnType == String::class.java && it.parameterCount == 0)
            } else null
            module.trackHook("SubtitleCleanFrames", captionAssem.adaptiveDeclaredMethod("bs", captionFrame)).intercept { chain ->
                val instance = checkNotNull(chain.thisObject)
                val frameVideo = (videoKey?.invoke(null, viewModel.invoke(instance))
                    ?: directVideoKey?.invoke(viewModel.invoke(instance))) as? String ?: ""
                val frame = chain.args[0]
                if (frame != null && (frameState.get(frame) as? Enum<*>)?.name == "EXPANDED" &&
                    !(frameText.get(frame) as? String).isNullOrBlank()) translator?.noteNativeFrame(frameVideo)
                rendererField.get(instance)?.let { verifiedRenderers[it] = true; frameVideos[it] = frameVideo }
                val previousRoot = rendererField.get(instance)?.let { rootField.get(it) as View }
                val previousBinding = previousRoot?.let { bindings[it]?.get() }
                val previousRevision = previousBinding?.frameRevision
                val result = chain.proceed()
                rendererField.get(instance)?.let { nativeRenderer ->
                    verifiedRenderers[nativeRenderer] = true
                    frameVideos[nativeRenderer] = frameVideo
                    val root = rootField.get(nativeRenderer) as View
                    if (verifiedRenderers.containsKey(nativeRenderer)) {
                        val updated = bindings[root]?.get()
                        // 原生渲染已送帧时不重复以 VM 原始内容覆盖；仅补足清屏阻塞的路径。
                        if (updated != null && (updated !== previousBinding || updated.frameRevision != previousRevision))
                            return@intercept result
                        val binding = bindings[root]?.get() ?: SubtitleOverlay(root, translator).also {
                            bindings[root] = WeakReference(it)
                        }
                        val frame = checkNotNull(chain.args[0])
                        binding.update((frameState.get(frame) as? Enum<*>)?.name ?: "UNKNOWN",
                            frameText.get(frame) as? String ?: "",
                            frameVideo)
                    }
                }
                result
            }
        }
        // SUB-RENDER / SUB-STATE：宿主送达的字幕保持原文，译文仅替换独立显示层。
        if (display != null) module.trackHook(owner, display).intercept { chain ->
            val result = chain.proceed()
            val root = rootField.get(chain.thisObject) as View
            if (!verifiedRenderers.containsKey(chain.thisObject)) {
                if (!unknownRootReported) {
                    unknownRootReported = true
                    HookRuntime.event("SubtitleOverlay", "未核验字幕容器 id=${root.id}，保留原生显示")
                }
                return@intercept result
            }
            val binding = bindings[root]?.get() ?: SubtitleOverlay(root, translator).also {
                bindings[root] = WeakReference(it)
            }
            val id = (videoField?.get(chain.thisObject) as? String)?.takeIf(String::isNotBlank)
                ?: frameVideos[chain.thisObject].orEmpty()
            if ((chain.args[1] as? Enum<*>)?.name == "EXPANDED" &&
                !(chain.args[2] as? String).isNullOrBlank()) translator?.noteNativeFrame(id)
            binding.update((chain.args[1] as? Enum<*>)?.name ?: "UNKNOWN", chain.args[2] as? String ?: "", id)
            result
        }
        check(display != null || HookRuntime.scope("SubtitleCleanFrames").active) {
            "Neither a verified renderer method nor a verified frame observer is available"
        }
        var cacheClearRevision = ConfigClient.getString("subtitle_cache_clear_revision", "0")
        var cacheRenderMode = ConfigClient.getString("subtitle_render_mode", "native")
        HookRuntime.subscribe(owner) { snapshot ->
            if (snapshot == null) {
                translator?.cancel()
                CaptionDebugOverlay.hide()
                return@subscribe
            }
            CaptionDebugOverlay.enabled = snapshot.boolean("subtitle_debug_overlay_enabled")
            if (!CaptionDebugOverlay.enabled) CaptionDebugOverlay.clear()
            val renderMode = snapshot.string("subtitle_render_mode", "native")
            if (renderMode != cacheRenderMode) {
                cacheRenderMode = renderMode
                translator?.clearCache()
            }
            val requestedClear = snapshot.string("subtitle_cache_clear_revision", "0")
            if (requestedClear != cacheClearRevision) {
                cacheClearRevision = requestedClear
                translator?.clearCache()
            }
            if (!snapshot.boolean("video_translate_enabled")) translator?.cancel()
        }
        HookRuntime.onDispose(owner) { CaptionDebugOverlay.hide() }
        HookRuntime.detail(owner, "字幕扩展：原生轨优先 / 原生文字翻译${if (translator == null) "不可用" else "已就绪"} / ${if (display == null) "字幕帧独立驱动" else "渲染与字幕帧"} / 独立绘制层")
    }
}
