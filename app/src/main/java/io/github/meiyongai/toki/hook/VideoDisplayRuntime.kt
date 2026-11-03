package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.VideoFitMode
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** Reversible geometry modes inside an already-installed immersive session, not live installation of all window patches. */
internal object VideoDisplayRuntime {
    const val FEATURE = "ImmersiveVideoModes"
    fun install(module: XposedModule, loader: ClassLoader, feed: Class<*>) {
        val video = SmartVideoLayout(feed,
            loader.loadClass("com.ss.android.ugc.aweme.videoadaption.adaptionparams.VideoAdaptionResult"),
            loader.loadClass("com.ss.android.ugc.aweme.videoadaption.adaptionparams.resultoperator.MultiContainerThresholdResultOperator"))
        val created = feed.getMethod("onViewCreated", View::class.java)
        val rebound = feed.getDeclaredMethod("i4", Any::class.java)
        val released = listOf("unBind", "onHostDestroy").map { feed.getDeclaredMethod(it) }
        val main = Handler(Looper.getMainLooper())
        val crop = VideoAutoCrop()
        val bindings = VideoLayoutBindings({ owner, original, reason -> video.apply.invoke(owner, original, reason) },
            { error -> HookRuntime.event(FEATURE, "visible layout replay unavailable type=" + error.javaClass.simpleName) })
        var closed = false
        HookRuntime.onDispose(FEATURE) {
            fun restore() { closed = true; crop.close(); bindings.close() }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        module.trackHook(FEATURE, created, requiresConfiguration = false).intercept { chain ->
            val owner = chain.thisObject
            val root = chain.args[0] as? View
            if (!closed && owner != null && root != null && Looper.myLooper() == main.looper) bindings.bind(owner, root)
            chain.proceed()
        }
        module.trackHook(FEATURE, rebound, requiresConfiguration = false).intercept { chain ->
            if (Looper.myLooper() == main.looper) {
                bindings.root(chain.thisObject)?.let(crop::unbind)
                bindings.invalidate(chain.thisObject) // A config notification during rebind must not replay the previous video's geometry.
            }
            chain.proceed()
        }
        for (method in released) module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
            if (Looper.myLooper() == main.looper) {
                bindings.root(chain.thisObject)?.let(crop::unbind); bindings.forget(chain.thisObject)
            }
            chain.proceed()
        }
        module.trackHook(FEATURE, video.apply, requiresConfiguration = false).intercept { chain ->
            if (closed || Looper.myLooper() != main.looper || !ConfigClient.isReady) return@intercept chain.proceed()
            val owner = chain.thisObject
            val reason = chain.args[1] as? String ?: ""
            val original = bindings.original(owner, chain.args[0], reason)
            val mode = if (ConfigClient.getBoolean(ImmersiveFullScreenHook.KEY_IMMERSIVE_FULL_SCREEN))
                VideoFitMode.from(ConfigClient.getString(ImmersiveFullScreenHook.KEY_VIDEO_FIT_MODE, "center")) else VideoFitMode.CENTER
            val root = bindings.root(owner)
            if (mode != VideoFitMode.FILL) root?.let(crop::unbind)
            val args = chain.args.toTypedArray()
            val zoom = ConfigClient.getString(ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, "100")?.toIntOrNull() ?: 100
            args[0] = video.replace(original, root?.width ?: 0, root?.height ?: 0, zoom, mode)
            bindings.transformed(owner, args[0])
            val result = chain.proceed(args)
            if (mode == VideoFitMode.FILL && root != null) crop.bind(root, video.contentKey(original))
            HookRuntime.detail(FEATURE, if (mode == VideoFitMode.CENTER) "mode=center original host result" else video.lastGeometry)
            result
        }
        var signature: List<Any?>? = null
        HookRuntime.subscribe(FEATURE) { snapshot ->
            val next = listOf(snapshot?.boolean(ImmersiveFullScreenHook.KEY_IMMERSIVE_FULL_SCREEN),
                snapshot?.string(ImmersiveFullScreenHook.KEY_VIDEO_FIT_MODE, "center"),
                snapshot?.string(ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, "100"),
                snapshot?.boolean(ImmersiveFullScreenHook.KEY_VIDEO_AUTO_CROP))
            if (next != signature) {
                signature = next
                crop.configure(false) // Restore a previous pixel-crop transform before applying a different geometry or zoom.
                crop.configure(snapshot?.boolean(ImmersiveFullScreenHook.KEY_IMMERSIVE_FULL_SCREEN) == true &&
                    VideoFitMode.from(snapshot.string(ImmersiveFullScreenHook.KEY_VIDEO_FIT_MODE, "center")) == VideoFitMode.FILL &&
                    snapshot.boolean(ImmersiveFullScreenHook.KEY_VIDEO_AUTO_CROP))
                bindings.requestRefresh()
            }
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
    }
}
