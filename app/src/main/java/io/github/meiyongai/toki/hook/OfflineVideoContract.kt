package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Named model/cell members inspected in the original 47.6.3 DEX, not resource-id guesses. */
internal class OfflineVideoContract(loader: ClassLoader) {
    val holder = loader.loadClass("com.ss.android.ugc.aweme.feed.adapter.VideoViewCell")
    private val aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme")
    private val info = loader.loadClass("com.ss.android.ugc.aweme.feed.model.offlinevideo.OfflineVideoInfo")
    private fun method(owner: Class<*>, name: String, result: Class<*>, vararg arguments: Class<*>): Method =
        owner.getMethod(name, *arguments).apply {
            check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true
        }
    val getAweme = method(holder, "getAweme", aweme)
    private val aid = method(aweme, "getAid", String::class.java)
    private val getInfo = method(aweme, "getOfflineVideoInfo", info)
    private val isOffline = method(info, "isOfflineVideo", Boolean::class.javaPrimitiveType!!)
    private val eventType = method(holder, "getEventType", String::class.java)
    val videoView = method(holder, "getVideoView", View::class.java)
    val rootView = method(holder, "getRootView", View::class.java)
    val selected = method(holder, "onPageSelected", Void.TYPE, Int::class.javaPrimitiveType!!)
    val resumed = method(holder, "onResumePlay", Void.TYPE, String::class.java)
    val progress = method(holder, "onPlayProgressChange", Void.TYPE, String::class.java,
        Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
    val released = listOf("unBind", "onDestroyView").map { method(holder, it, Void.TYPE) }

    data class Video(val id: String, val model: Any, val view: View, val offline: Boolean)
    fun read(instance: Any): Video? {
        if (!holder.isInstance(instance)) return null
        val model = getAweme.invoke(instance) ?: return null
        val id = (aid.invoke(model) as? String)?.takeIf { it.isNotBlank() } ?: return null
        val view = videoView.invoke(instance) as? View ?: return null
        val metadata = getInfo.invoke(model)
        val offline = metadata != null && isOffline.invoke(metadata) == true ||
            eventType.invoke(instance) == "offline_mode_page"
        return Video(id, model, view, offline)
    }
}
