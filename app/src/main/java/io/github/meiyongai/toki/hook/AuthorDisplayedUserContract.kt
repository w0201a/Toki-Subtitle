package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The native nickname route reads cellProxy.LL and conditionally selects a homepage collaborator. */
internal class AuthorDisplayedUserContract(
    component: Class<*>, reused: Class<*>, private val params: Class<*>, aweme: Class<*>,
    user: Class<*>, selector: Class<*>, formatter: Class<*>
) {
    private fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { check(!Modifier.isStatic(modifiers)); isAccessible = true }
            } catch (_: NoSuchFieldException) { current = current.superclass }
        }
        throw NoSuchFieldException(name)
    }
    private fun method(type: Class<*>, name: String, result: Class<*>, static: Boolean, vararg args: Class<*>): Method =
        type.getMethod(name, *args).apply {
            check(returnType == result && Modifier.isStatic(modifiers) == static); isAccessible = true
        }
    private val cell = field(reused, "cellProxy").also { check(reused.isAssignableFrom(component)) }
    private val cellItem = field(cell.type, "LL").also { check(it.type.isAssignableFrom(params)) }
    private val getAweme = method(params, "getAweme", aweme, false)
    private val author = method(aweme, "getAuthor", user, false)
    private val aid = method(aweme, "getAid", String::class.java, false)
    private val uid = method(user, "getUid", String::class.java, false)
    private val region = method(user, "getRegion", String::class.java, false)
    private val sourceEvent = field(params, "mEventType").also { check(it.type == String::class.java) }
    private val page = field(params, "baseFeedPageParams")
    private val route = field(page.type, "eventType").also { check(it.type == String::class.java) }
    private val pageParam = field(page.type, "param")
    private val pageUid = method(pageParam.type, "getUid", String::class.java, false)
    private val selected = method(selector, "LIZIZ", user, true, aweme, String::class.java, String::class.java)
    private val usesProfileUser = method(formatter, "LIZJ", java.lang.Boolean.TYPE, true, params)
    private val name = method(formatter, "LIZ", String::class.java, true, params)
    private val nickname = method(user, "getNickname", String::class.java, false)
    private val unique = method(user, "getUniqueId", String::class.java, false)
    private data class NameKey(val video: String, val user: String, val profile: Boolean, val profileId: String,
        val event: String, val sourceEvent: String, val nickname: String?, val unique: String?)
    private class CachedName(item: Any, val key: NameKey, val value: String?) { val item = WeakReference(item) }
    private val names = WeakHashMap<Any, CachedName>()
    fun invalidate(owner: Any) { names.remove(owner) }


    fun current(owner: Any): AuthorLabelContract.Data? {
        val proxy = cell.get(owner) ?: return null
        val item = cellItem.get(proxy) ?: return null
        if (!params.isInstance(item)) return null
        val video = getAweme.invoke(item) ?: return null
        val videoId = aid.invoke(video) as? String ?: return null
        val profileMode = usesProfileUser.invoke(null, item) == true
        val context = if (profileMode) page.get(item) else null
        val profileParam = context?.let { pageParam.get(it) }
        val profileId = profileParam?.let { pageUid.invoke(it) as? String }.orEmpty()
        val event = context?.let { route.get(it) as? String }.orEmpty()
        fun selectedUser(): Any? = if (profileMode) selected.invoke(null, video, profileId, event) ?: author.invoke(video) else author.invoke(video)
        val displayedUser = selectedUser()
        if (displayedUser == null) return null
        val userId = uid.invoke(displayedUser) as? String ?: return null
        if (videoId.isBlank() || userId.isBlank() || videoId.length > 128 || userId.length > 128) return null
        val displayEvent = (sourceEvent.get(item) as? String).orEmpty()
        val key = NameKey(videoId, userId, profileMode, profileId, event, displayEvent, nickname.invoke(displayedUser) as? String, unique.invoke(displayedUser) as? String)
        val cached = names[owner]?.takeIf { it.item.get() === item && it.key == key }
        val nativeName = if (cached != null) cached.value else (name.invoke(null, item) as? String).also { value ->
            if (owner !in names && names.size >= 256) names.keys.firstOrNull()?.let(names::remove)
            names[owner] = CachedName(item, key, value)
        }
        if (nativeName.isNullOrBlank() || nativeName.length > 8192 || (usesProfileUser.invoke(null, item) == true) != profileMode) return null
        if (cell.get(owner) !== proxy || cellItem.get(proxy) !== item || getAweme.invoke(item) !== video || aid.invoke(video) != videoId || (sourceEvent.get(item) as? String).orEmpty() != displayEvent) return null
        if (profileMode && (page.get(item) !== context || context?.let { pageParam.get(it) } !== profileParam ||
                profileParam?.let { pageUid.invoke(it) as? String }.orEmpty() != profileId || context?.let { route.get(it) as? String }.orEmpty() != event)) return null
        val latestUser = selectedUser() ?: return null
        if (uid.invoke(latestUser) != userId) return null
        return AuthorLabelContract.Data(videoId, userId, region.invoke(latestUser) as? String, nativeName)
    }
}
