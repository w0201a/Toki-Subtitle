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
        if (translator != null) try {
            val upcoming = FeedCaptionLookAhead(loader)
            upcoming.install(module, owner)
            translator.upcomingTracks = upcoming::next
        } catch (error: Exception) {
            HookRuntime.event("CaptionPrefetch", "next-video contract unavailable=${error.javaClass.simpleName}")
        }
        val rootField = renderer.adaptiveDeclaredField("LIZJ").apply { isAccessible = true }
        val videoField = renderer.adaptiveDeclaredField("LJI").apply { isAccessible = true }
        val display = ExtensionSymbols.methodByRole(renderer, "LJIIIZ")
        check(display.returnType == Void.TYPE && display.parameterTypes.size >= 3 &&
            display.parameterTypes[0] == String::class.java && display.parameterTypes[2] == String::class.java &&
            !display.parameterTypes[1].isPrimitive) { "Caption display contract changed" }
        val bindings = WeakHashMap<View, WeakReference<SubtitleOverlay>>()
        val verifiedRenderers = WeakHashMap<Any, Boolean>()
        val gestures = WeakHashMap<android.app.Activity, SubtitleGesture>()
        HookRuntime.onDispose(owner) {
            gestures.values.forEach { it.close() }
            gestures.clear()
            bindings.values.toList().forEach { it.get()?.close() }
            bindings.clear()
            verifiedRenderers.clear()
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
                rendererField.get(instance)?.let { verifiedRenderers[it] = true }
                val previousRoot = rendererField.get(instance)?.let { rootField.get(it) as View }
                val previousBinding = previousRoot?.let { bindings[it]?.get() }
                val previousRevision = previousBinding?.frameRevision
                val result = chain.proceed()
                rendererField.get(instance)?.let { nativeRenderer ->
                    verifiedRenderers[nativeRenderer] = true
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
                            (videoKey?.invoke(null, viewModel.invoke(instance)) ?: directVideoKey?.invoke(viewModel.invoke(instance))) as? String ?: "")
                    }
                }
                result
            }
        }
        // SUB-RENDER / SUB-STATE：宿主送达的字幕保持原文，译文仅替换独立显示层。
        module.trackHook(owner, display).intercept { chain ->
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
            binding.update((chain.args[1] as? Enum<*>)?.name ?: "UNKNOWN", chain.args[2] as? String ?: "",
                videoField.get(chain.thisObject) as? String ?: "")
            result
        }
        HookRuntime.subscribe(owner) { snapshot ->
            if (snapshot == null || !snapshot.boolean("video_translate_enabled"))
                translator?.cancel()
        }
        HookRuntime.detail(owner, "字幕扩展：原生轨优先 / 原生文字翻译${if (translator == null) "不可用" else "已就绪"} / 独立绘制层")
    }
}
