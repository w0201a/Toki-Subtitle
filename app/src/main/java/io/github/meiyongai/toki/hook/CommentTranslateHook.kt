package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Modifier

/** 使用原生评论批量翻译入口，保留原方法执行和宿主状态流。 */
object CommentTranslateHook {
    const val KEY_COMMENT_TRANSLATE_ENABLED = "comment_translate_enabled"
    fun refreshConfig(context: Context) = Unit

    fun init(module: XposedModule, loader: ClassLoader) {
        HookRuntime.install("CommentTranslationGate") {
        val methods = ExtensionSymbols.commentGate(loader) ?: HostSymbols.resolve(loader, HostSymbol.COMMENT_TRANSLATION).declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType &&
                (it.parameterCount == 0 || it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)))
        }
        check(methods.size >= 2 && methods.any { it.parameterCount == 0 }) {
            "Comment translation gate contract changed"
        }
        methods.forEach { method ->
            check(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
            module.trackHook("CommentTranslationGate", method).intercept { chain ->
                val original = chain.proceed() as Boolean
                original || ConfigClient.getBoolean(KEY_COMMENT_TRANSLATE_ENABLED)
            }
        }
        }
        installRequestPolicy(module, loader)
        installResponseTranslation(module, loader)
        CommentPollTranslation.install(module, loader)
    }
    internal fun requestEnabled(original: Boolean, enabled: Boolean, remote: Boolean): Boolean = original || enabled && remote

    private fun installRequestPolicy(module: XposedModule, loader: ClassLoader) = HookRuntime.install("CommentRequestTranslation") {
        val app = checkNotNull(HookRuntime.hostContext)
        check(CommentPageCodeGate.verify(app)) { "Unverified native comment request policy" }
        // 47.6.3: the old gate only enables the feature. This second policy also requires
        // a native preference and a play-count threshold before setting need_translation.
        val policy = loader.loadClass("X.0F3W")
        val aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme")
        val automatic = policy.getDeclaredMethod("LIZLLL", aweme).apply {
            check(Modifier.isStatic(modifiers) && returnType == Boolean::class.javaPrimitiveType)
        }
        val exclusions = policy.getDeclaredMethod("LIZIZ").apply {
            check(Modifier.isStatic(modifiers) && returnType == String::class.java)
        }
        fun allowed() = ConfigClient.isReady && ConfigClient.getBoolean(KEY_COMMENT_TRANSLATE_ENABLED) &&
            TranslationNetworkPolicy.capture().permitsNetwork()
        module.trackHook("CommentRequestTranslation", automatic).intercept { chain ->
            val original = chain.proceed() as Boolean
            requestEnabled(original, ConfigClient.getBoolean(KEY_COMMENT_TRANSLATE_ENABLED), TranslationNetworkPolicy.capture().permitsNetwork())
        }
        module.trackHook("CommentRequestTranslation", exclusions).intercept { chain ->
            val original = chain.proceed()
            if (allowed()) null else original
        }
        HookRuntime.event("CommentRequestTranslation", "verified need_translation policy; explicit comment switch; native requests retained")
    }

    private fun installResponseTranslation(module: XposedModule, loader: ClassLoader) = HookRuntime.install("CommentResponseTranslation") {
        val app = checkNotNull(HookRuntime.hostContext)
        check(CommentPageCodeGate.verify(app))
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val list = loader.loadClass("com.ss.android.ugc.aweme.comment.model.CommentItemList")
        val getItems = list.getMethod("getItems").apply { check(returnType == List::class.java) }
        val comment = loader.loadClass("com.ss.android.ugc.aweme.comment.model.Comment")
        val id = comment.getMethod("getCid")
        val video = comment.getMethod("getAwemeId")
        val text = comment.getMethod("getText")
        val original = comment.getMethod("getOriginalText")
        val translated = comment.getMethod("getTranslatedText")
        val language = comment.getMethod("getTranslatedTextLanguage")
        val children = comment.getMethod("getReplyComments")
        val variant = comment.getMethod("getTranslationVariant")
        val extra = comment.getMethod("getTranslatedTextExtra")
        val facade = loader.adaptiveClass("X.03hV")
        val service = facade.adaptiveDeclaredField("LIZIZ")
        val target = facade.adaptiveDeclaredMethod("LLILLIZIL")
        val cache = facade.getMethod("LJJLIIIJJIZ")
        val write = loader.loadClass("X.0FTv").getMethod("LIZIZ", comment, String::class.java, String::class.java, List::class.java)
        val reader = HostOfflineComments(loader)
        data class Row(val model: java.lang.ref.WeakReference<Any>, val source: String, val account: String, val video: String, val id: String, val target: String)
        fun enabled() = ConfigClient.isReady && ConfigClient.getBoolean(KEY_COMMENT_TRANSLATE_ENABLED) && TranslationNetworkPolicy.capture().permitsNetwork()
        fun current(row: Row): Boolean = enabled() && reader.accountMatches(row.account) &&
            row.target == CommentTranslationKey.normalizedTarget(target.invoke(service.get(null)) as String) &&
            row.model.get()?.let { row.source == OfflineCommentData.original(original.invoke(it) as? String, text.invoke(it) as? String) } == true
        val queue = CommentResponseTranslationQueue<Row>(NativeSubtitleTranslator.transport(loader),
            { action, delay -> main.postDelayed(action, delay); Unit }, { main.removeCallbacks(it) }, { row, result ->
                val value = row.model.get()
                if (value != null && current(row)) {
                    try {
                        write.invoke(cache.invoke(service.get(null)), value, result, variant.invoke(value), extra.invoke(value))
                        val savedRow = OfflineCommentData.Row(row.id, "", "", "", row.source, result, row.target, 0, 0)
                        val snapshot = OfflineCommentData.Snapshot(row.account, row.video, System.currentTimeMillis(), listOf(savedRow), 1, false, false)
                        CommentTranslationStore.save(app, snapshot, savedRow, row.target, result) { }
                        HookRuntime.count("CommentResponseTranslation", "response comment translated")
                    } catch (error: Exception) {
                        HookRuntime.event("CommentResponseTranslation", "apply unavailable type=" + error.javaClass.simpleName)
                    }
                }
            })
        fun collect(values: List<*>) {
            if (!enabled()) return
            val account = reader.account(app)
            val selectedTarget = CommentTranslationKey.normalizedTarget(target.invoke(service.get(null)) as String) ?: return
            val pending = ArrayDeque<Pair<Any, Int>>()
            values.filterNotNull().take(500).forEach { pending.add(it to 0) }
            var count = 0
            while (pending.isNotEmpty() && count++ < 2500) {
                val (value, depth) = pending.removeFirst()
                if (!comment.isInstance(value)) continue
                if (depth < 3) (children.invoke(value) as? List<*>)?.filterNotNull()?.forEach { pending.add(it to depth + 1) }
                val cid = id.invoke(value) as? String ?: continue
                val aid = video.invoke(value) as? String ?: continue
                if (cid.isBlank() || aid.isBlank()) continue
                val source = OfflineCommentData.original(original.invoke(value) as? String, text.invoke(value) as? String)
                if (source.isBlank() || source.length > ManualCommentTranslation.MAX_SOURCE_LENGTH) continue
                val ready = (translated.invoke(value) as? String)?.isNotBlank() == true &&
                    CommentTranslationKey.normalizedTarget(language.invoke(value) as? String ?: "") == selectedTarget
                if (ready) continue
                val row = Row(java.lang.ref.WeakReference(value), source, account, aid, cid, selectedTarget)
                val key = CommentTranslationKey.key(account, aid, cid, source, selectedTarget)
                if (!queue.offer(key, row, source) { current(row) }) HookRuntime.count("CommentResponseTranslation", "queue bounded; row retained original")
            }
        }
        module.trackHook("CommentResponseTranslation", getItems).intercept { chain ->
            val result = chain.proceed()
            val rows = (result as? List<*>)?.toList()
            if (rows != null) main.post { try { collect(rows) } catch (error: Exception) {
                HookRuntime.event("CommentResponseTranslation", "response unavailable type=" + error.javaClass.simpleName)
            } }
            result
        }
        module.deoptimize(getItems)
        HookRuntime.subscribe("CommentResponseTranslation") { snapshot ->
            if (snapshot == null || !snapshot.boolean(KEY_COMMENT_TRANSLATE_ENABLED) || snapshot.boolean(TranslationNetworkPolicy.KEY)) queue.cancel()
        }
        HookRuntime.onDispose("CommentResponseTranslation") { queue.close() }
    }

}
