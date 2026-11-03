package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Inspected 47.6.3 ordinary comment-button path; no full-screen touch or generic View.performClick hook. */
internal class OfflineCommentEntryContract(loader: ClassLoader) {
    private val component = loader.loadClass("com.ss.android.ugc.aweme.feed.assem.videocomment.VideoCommentAssem")
    private val params = loader.loadClass("com.ss.android.ugc.aweme.feed.model.VideoItemParams")
    private val aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme")
    private val offlineInfo = loader.loadClass("com.ss.android.ugc.aweme.feed.model.offlinevideo.OfflineVideoInfo")
    val click = instance(component, "vq", Void.TYPE, View::class.java, String::class.java)
    private val root = component.getMethod("getContainerView").apply {
        check(View::class.java.isAssignableFrom(returnType) && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val viewModel = component.getDeclaredMethod("sq").apply {
        check(returnType.name == "com.ss.android.ugc.aweme.feed.assem.videocomment.VideoCommentVM" && !Modifier.isStatic(modifiers))
        isAccessible = true
    }
    private val item = viewModel.returnType.getMethod("getItem").apply {
        check(returnType.isAssignableFrom(params) && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val getAweme = instance(params, "getAweme", aweme)
    private val id = instance(aweme, "getAid", String::class.java)
    private val metadata = instance(aweme, "getOfflineVideoInfo", offlineInfo)
    private val offline = instance(offlineInfo, "isOfflineVideo", java.lang.Boolean.TYPE)
    private val event = params.getField("mEventType").apply { check(type == String::class.java && !Modifier.isStatic(modifiers)); isAccessible = true }
    data class Data(val video: String, val offline: Boolean, val root: View)
    fun current(owner: Any, clicked: View): Data? {
        if (!component.isInstance(owner)) return null
        val container = root.invoke(owner) as? View ?: return null
        if (clicked !== container && generateSequence(clicked.parent) { it.parent }.none { it === container }) return null
        val data = viewModel.invoke(owner)?.let { item.invoke(it) } ?: return null
        if (!params.isInstance(data)) return null
        val model = getAweme.invoke(data) ?: return null
        val video = id.invoke(model) as? String ?: return null
        val info = metadata.invoke(model)
        return Data(video, info != null && offline.invoke(info) == true || event.get(data) == "offline_mode_page", container)
    }
    companion object {
        internal fun instance(type: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
            type.getMethod(name, *args).apply {
                check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true
            }
    }
}
