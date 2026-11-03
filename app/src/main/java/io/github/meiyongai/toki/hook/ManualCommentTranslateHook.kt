package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.os.Handler
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Opt-in ordinary-comment long-press fallback with an explicit path to all native actions. */
internal object ManualCommentTranslateHook {
    const val KEY = "comment_manual_translate_enabled"
    private const val FEATURE = "ManualCommentTranslateHook"

    internal fun textGetter(comment: Class<*>): Method = comment.getMethod("getText").apply {
        check(returnType == String::class.java && parameterCount == 0 && !Modifier.isStatic(modifiers)); isAccessible = true
    }

    /** Per-dialog translation needs readable text, not a persisted comment identity. Never fetch IDs here. */
    internal fun sourceText(comment: Any, getter: Method): String? = (getter.invoke(comment) as? String)
        ?.takeIf { it.isNotBlank() && it.length <= ManualCommentTranslation.MAX_SOURCE_LENGTH }

    fun init(module: XposedModule, loader: ClassLoader) {
        val roles = listOf("copy", "clip", "menu", "getComment", "item", "display", "text", "extra", "cid", "translated")
        val contract = CommentCopyHook.Contract(loader, roles.associateWith { HostSymbols.members(HostSymbol.COMMENT_COPY, it).single() })
        val getText = textGetter(loader.loadClass("com.ss.android.ugc.aweme.comment.model.Comment"))
        val fragmentIndex = contract.menu.parameterTypes.indices.single { index ->
            contract.menu.parameterTypes[index].methods.any {
                it.name == "getActivity" && it.parameterCount == 0 && Activity::class.java.isAssignableFrom(it.returnType)
            }
        }
        val getActivity = contract.menu.parameterTypes[fragmentIndex].getMethod("getActivity")
        val transport = NativeSubtitleTranslator.transport(loader)
        val main = Handler(Looper.getMainLooper())
        val dialogs = WeakHashMap<Activity, AlertDialog>()
        fun closeDialogs() { dialogs.values.toList().forEach { it.dismiss() }; dialogs.clear() }
        HookRuntime.onDispose(FEATURE) {
            if (Looper.myLooper() == main.looper) closeDialogs() else main.post { closeDialogs() }
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            if (snapshot == null || !snapshot.boolean(KEY) || snapshot.boolean(TranslationNetworkPolicy.KEY)) closeDialogs()
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        module.trackHook(FEATURE, Activity::class.java.getDeclaredMethod("onPause"), requiresConfiguration = false).intercept { chain ->
            dialogs.remove(chain.thisObject)?.dismiss()
            chain.proceed()
        }
        module.trackHook(FEATURE, contract.menu).intercept { chain ->
            if (CommentNativeMenuScope.active || !ConfigClient.getBoolean(KEY) || Looper.myLooper() != Looper.getMainLooper())
                return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            val comment = args[contract.itemIndex]?.let { contract.getComment.invoke(it) } ?: return@intercept chain.proceed()
            val source = try { sourceText(comment, getText) } catch (error: Exception) {
                HookRuntime.event(FEATURE, "当前评论原文不可读，保留原生菜单；type=" + error.javaClass.simpleName)
                null
            } ?: return@intercept chain.proceed()
            val activity = args[fragmentIndex]?.let { getActivity.invoke(it) } as? Activity ?: return@intercept chain.proceed()
            if (activity.isFinishing || activity.isDestroyed) return@intercept chain.proceed()
            val context = HostSettingsEntry.localizedContext(activity)
            fun nativeMenu() {
                if (activity.isFinishing || activity.isDestroyed) return
                try { CommentNativeMenuScope.native { contract.menu.invoke(null, *args) } } catch (error: Exception) {
                    HookRuntime.event(FEATURE, "native comment menu unavailable type=" + error.javaClass.simpleName)
                    Toast.makeText(activity, context.getString(R.string.manual_comment_native_failed), Toast.LENGTH_SHORT).show()
                }
            }
            dialogs.remove(activity)?.dismiss()
            val choose = AlertDialog.Builder(context).setTitle(context.getString(R.string.manual_comment_menu_title))
                .setItems(arrayOf(context.getString(R.string.manual_comment_translate),
                    context.getString(R.string.manual_comment_native_actions))) { _, index ->
                    if (index == 1) nativeMenu() else if (!activity.isFinishing && !activity.isDestroyed &&
                        ConfigClient.isReady && ConfigClient.getBoolean(KEY)) {
                        dialogs.remove(activity)?.dismiss()
                        val remote = TranslationNetworkPolicy.capture()
                        if (!remote.permitsNetwork()) {
                            TranslationNetworkPolicy.showMissingTranslation()
                            return@setItems
                        }
                        val guarded: (String, (String?) -> Unit) -> AutoCloseable = { text, done ->
                            if (remote.permitsNetwork()) transport(text) { done(if (remote.permitsNetwork()) it else null) }
                            else { done(null); AutoCloseable { } }
                        }
                        val result = ManualCommentDialog.show(activity, source, guarded, ::nativeMenu) { closed ->
                            if (dialogs[activity] === closed) dialogs.remove(activity)
                        }
                        dialogs[activity] = result
                    }
                }.create()
            dialogs[activity] = choose
            choose.setOnDismissListener { if (dialogs[activity] === choose) dialogs.remove(activity) }
            choose.show()
            null
        }
    }
}
