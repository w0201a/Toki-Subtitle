package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.util.Locale

/** Optional online channel preparation; cached-comment reading never depends on this feature being enabled. */
internal object CachedCommentTranslateHook {
    const val KEY = "cached_comment_online_translate_enabled"
    private const val FEATURE = "CachedCommentTranslateHook"
    data class Channel(val target: String, val translate: (String, (String?) -> Unit) -> AutoCloseable)
    @Volatile private var provider: (() -> Channel?)? = null
    private val cancellations = mutableSetOf<() -> Unit>()
    fun channel(): Channel? = if (ConfigClient.isReady && ConfigClient.getBoolean(KEY)) provider?.invoke() else null
    fun watch(cancel: () -> Unit): AutoCloseable {
        synchronized(cancellations) { cancellations.add(cancel) }
        return AutoCloseable { synchronized(cancellations) { cancellations.remove(cancel) } }
    }
    private fun cancelAll() { synchronized(cancellations) { cancellations.toList() }.forEach { cancel ->
        try { cancel() } catch (error: Exception) { HookRuntime.event(FEATURE, "cancellation unavailable type=" + error.javaClass.simpleName) }
    } }

    fun init(module: XposedModule, loader: ClassLoader) {
        val transport = NativeSubtitleTranslator.transport(loader)
        val facade = loader.adaptiveClass("X.03hV")
        val service = facade.adaptiveDeclaredField("LIZIZ")
        val language = facade.adaptiveDeclaredMethod("LLILLIZIL").apply { check(returnType == String::class.java) }
        fun target(): String? = try {
            (language.invoke(service.get(null)) as? String)?.replace('_', '-')
                ?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z]{2,8}(-[a-z0-9]{1,8})*")) }
        } catch (_: Exception) { null } catch (_: LinkageError) { null }
        val main = Handler(Looper.getMainLooper())
        HookRuntime.onDispose(FEATURE) {
            provider = null
            if (Looper.myLooper() == main.looper) cancelAll() else main.post { cancelAll() }
        }
        provider = {
            target()?.let { selected -> Channel(selected, { text, complete ->
                if (!ConfigClient.isReady || !ConfigClient.getBoolean(KEY) || target() != selected) {
                    complete(null); AutoCloseable { }
                } else transport(text) { result -> complete(if (target() == selected) result else null) }
            }) }
        }
        module.trackHook(FEATURE, Activity::class.java.getDeclaredMethod("onPause"), requiresConfiguration = false).intercept { chain ->
            cancelAll(); chain.proceed()
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            if (snapshot == null || !snapshot.boolean(KEY) || snapshot.boolean(TranslationNetworkPolicy.KEY)) cancelAll()
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
    }
}
