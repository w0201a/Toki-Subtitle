package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** TikTok 47.0.3 评论投票。仅读取宿主模型，通过显示控件更新译文。 */
internal object CommentPollTranslation {
    private const val OWNER = "CommentPollTranslation"

    fun install(module: XposedModule, loader: ClassLoader) = HookRuntime.install(OWNER) {
        check(listOf(
            "0259ca5f53357042b72fe6502ca22875aa3035506bfc458fb645146abb19dc16",
            "a104c3b28e2f4ee94292a0dcc464ff114e63f430e760bfd093c434cc6298a129"
        ).any(HostSymbols::matchesCodeIdentity))
        val cell = loader.loadClass("com.ss.android.ugc.aweme.commentv2.commentlist.powercell.PollCell")
        val poll = loader.loadClass("com.ss.android.ugc.aweme.comment.model.PollInfo")
        val title = poll.getField("title")
        val optionText = loader.loadClass("com.ss.android.ugc.aweme.comment.model.OptionCount").getField("text")
        val ordered = loader.loadClass("X.0QGy").getDeclaredMethod("LIZ", poll)
        val titleView = cell.getDeclaredField("LLJJJIL").apply { isAccessible = true }
        val optionViews = cell.getDeclaredField("LLJJJJJIL").apply { isAccessible = true }
        val contract = PollBindingContract.resolve(cell, poll)
        val bind = contract.bind
        val data = contract.data
        val update = cell.getDeclaredMethod("X5", poll)
        val unbind = cell.getDeclaredMethod("unBind")
        val queue = PollTranslationQueue(NativeSubtitleTranslator.transport(loader), { target, index, text ->
            if (index == 0) (titleView.get(target) as? TextView)?.text = text
            else (optionViews.get(target) as? List<*>)?.getOrNull(index - 1)?.let { view ->
                view.javaClass.getMethod("setOptionLabel", String::class.java).invoke(view, text)
            }
        }, { HookRuntime.event(OWNER, it) })
        val main = Handler(Looper.getMainLooper())
        HookRuntime.onDispose(OWNER) { main.post { queue.close() } }
        HookRuntime.subscribe(OWNER) {
            main.post { queue.setEnabled(ConfigClient.getBoolean(CommentTranslateHook.KEY_COMMENT_TRANSLATE_ENABLED)) }
        }
        fun bindPoll(target: Any, info: Any) {
            try {
                val options = (ordered.invoke(null, info) as? List<*>) ?: return
                val views = optionViews.get(target) as? List<*> ?: return
                if (views.size != options.size) { queue.unbind(target); return }
                val texts = listOf(title.get(info) as? String ?: "") + options.map {
                    if (it == null) "" else optionText.get(it) as? String ?: ""
                }
                HookRuntime.event(OWNER, "bound fields=${texts.size} enabled=${ConfigClient.getBoolean(CommentTranslateHook.KEY_COMMENT_TRANSLATE_ENABLED)}")
                queue.setEnabled(ConfigClient.getBoolean(CommentTranslateHook.KEY_COMMENT_TRANSLATE_ENABLED))
                queue.bind(target, texts)
            } catch (error: Exception) {
                queue.unbind(target)
                HookRuntime.event(OWNER, "binding unavailable type=${error.javaClass.simpleName}")
            }
        }
        module.trackHook(OWNER, bind).intercept { chain ->
            val result = chain.proceed()
            val info = chain.args[0]?.let(data::get)
            if (info != null) bindPoll(checkNotNull(chain.thisObject), info)
            result
        }
        module.trackHook(OWNER, update).intercept { chain ->
            val result = chain.proceed()
            chain.args[0]?.let { bindPoll(checkNotNull(chain.thisObject), it) }
            result
        }
        module.trackHook(OWNER, unbind).intercept { chain ->
            chain.thisObject?.let(queue::unbind)
            chain.proceed()
        }
        HookRuntime.event(OWNER, "ready bind=${bind.name}; question and options; display only")
    }
}
