package io.github.meiyongai.toki.subtitle.hook

import android.widget.TextView
import java.lang.reflect.Modifier

/** Inspected 47.6.3 label binding; the primary author field and current Aweme identity must both match. */
internal class AuthorLabelContract(component: Class<*>, params: Class<*>, aweme: Class<*>, user: Class<*>,
    private val nativeCurrent: ((Any) -> Data?)? = null) {
    val label = component.getDeclaredField("LLLIIII").apply {
        check(TextView::class.java.isAssignableFrom(type) && !Modifier.isStatic(modifiers))
        isAccessible = true
    }
    val bind = component.getDeclaredMethod("Mq", label.type, CharSequence::class.java).apply {
        check(returnType == Void.TYPE && !Modifier.isStatic(modifiers))
        isAccessible = true
    }
    private val viewModel = component.getDeclaredMethod("xq").apply {
        check(!Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val item = viewModel.returnType.getMethod("getItem").apply {
        check(returnType.isAssignableFrom(params))
        check(!Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val getAweme = params.getMethod("getAweme").apply {
        check(returnType == aweme && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val author = aweme.getMethod("getAuthor").apply {
        check(returnType == user && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val aid = aweme.getMethod("getAid").apply {
        check(returnType == String::class.java); isAccessible = true
    }
    private val uid = user.getMethod("getUid").apply {
        check(returnType == String::class.java); isAccessible = true
    }
    private val region = user.getMethod("getRegion").apply {
        check(returnType == String::class.java); isAccessible = true
    }
    private val paramsType = params
    data class Data(val video: String, val author: String, val region: String?, val nativeName: String? = null)

    fun current(owner: Any): Data? {
        nativeCurrent?.let { return it(owner) }
        val model = viewModel.invoke(owner) ?: return null
        val params = item.invoke(model) ?: return null
        if (!paramsType.isInstance(params)) return null
        val video = getAweme.invoke(params) ?: return null
        val user = author.invoke(video) ?: return null
        val videoId = aid.invoke(video) as? String ?: return null
        val authorId = uid.invoke(user) as? String ?: return null
        if (videoId.isBlank() || authorId.isBlank()) return null
        return Data(videoId, authorId, region.invoke(user) as? String)
    }
}
