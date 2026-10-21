package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/** 只读推荐列表：按当前视频的实际位置读取后五条，不按到达顺序猜测。 */
internal class FeedCaptionLookAhead(loader: ClassLoader) {
    companion object {
        internal fun <T> following(items: List<T>, count: Int = 2, current: (T) -> Boolean): List<T> {
            val index = items.indexOfFirst(current)
            return if (index < 0) emptyList() else items.drop(index + 1).take(count.coerceIn(0, 5))
        }
    }
    data class Track(val video: String, val url: String)
    private val model = HostSymbols.resolve(loader, HostSymbol.RECOMMEND_MODEL)
    private val getItems = model.methods.single {
        it.name == "getItems" && it.parameterCount == 0 && it.returnType == List::class.java &&
            !Modifier.isAbstract(it.modifiers)
    }
    private val models = mutableListOf<WeakReference<Any>>()
    private val aweme = loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.Aweme")
    private val aid = aweme.adaptiveMethod("getAid")
    private val video = aweme.adaptiveMethod("getVideo")
    private val caption = video.returnType.adaptiveMethod("getCaptionModel")
    private val tracks = caption.returnType.adaptiveField("captionList")
    private val language = caption.returnType.adaptiveField("originalCaptionLanguage")
    private val languageCode = language.type.adaptiveField("languageCode")
    private val item = loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.CaptionItemModel")
    private val itemCode = item.adaptiveField("languageCode")
    private val itemUrl = item.adaptiveField("url")
    private val itemUrls = item.adaptiveField("urlList")
    private var lastReport = ""
    fun selected(instance: Any): String? {
        if (!aweme.isInstance(instance)) return null
        val id = aid.invoke(instance) as? String ?: return null
        val data = video.invoke(instance)?.let { caption.invoke(it) }
        val original = data?.let { language.get(it) }?.let { languageCode.get(it) as? String }
        val choices = data?.let { tracks.get(it) as? List<*> }?.filterNotNull().orEmpty()
        val selected = choices.firstOrNull { itemCode.get(it) == original } ?: choices.firstOrNull()
        val location = selected?.let { (itemUrl.get(it) as? String)?.takeIf(String::isNotBlank)
            ?: (itemUrls.get(it) as? List<*>)?.filterIsInstance<String>()?.firstOrNull(String::isNotBlank) }
        CaptionPrefetch.note(id, location)
        return id
    }

    fun install(module: XposedModule, owner: String) {
        HookRuntime.onDispose(owner) { synchronized(models) { models.clear() } }
        module.trackHook(owner, getItems).intercept { chain ->
            val result = chain.proceed()
            synchronized(models) {
                models.removeAll { it.get() == null }
                val instance = checkNotNull(chain.thisObject)
                if (model.isInstance(instance) && models.none { it.get() === instance }) {
                    models += WeakReference(instance)
                    while (models.size > 4) models.removeAt(0)
                }
            }
            result
        }
    }

    fun next(current: String, offset: Int = 0): List<Track> {
        val instances = synchronized(models) { models.mapNotNull { it.get() } }
        for (instance in instances.asReversed()) {
            val list = (getItems.invoke(instance) as? List<*>)?.toList() ?: continue
            val index = list.indexOfFirst { it != null && aweme.isInstance(it) && aid.invoke(it) == current }
            if (index < 0) continue
            // 没有字幕的下一条仍计入五条预算，避免越过它预取更远的视频。
            val window = list.drop(index + 1 + offset.coerceAtLeast(0)).take(5)
            val result = window.mapNotNull { entry ->
                if (entry == null || !aweme.isInstance(entry)) return@mapNotNull null
                val id = aid.invoke(entry) as? String ?: return@mapNotNull null
                val unavailable = Track(id, "")
                val data = video.invoke(entry)?.let { caption.invoke(it) } ?: return@mapNotNull unavailable
                val original = language.get(data)?.let { languageCode.get(it) as? String }
                val choices = (tracks.get(data) as? List<*>)?.filterNotNull().orEmpty()
                if (original?.startsWith("zh") == true) return@mapNotNull unavailable
                val selected = choices.firstOrNull { itemCode.get(it) == original &&
                    (!(itemUrl.get(it) as? String).isNullOrBlank() || (itemUrls.get(it) as? List<*>)?.isNotEmpty() == true) }
                    ?: choices.firstOrNull { !(itemCode.get(it) as? String).isNullOrBlank() &&
                        (itemCode.get(it) as? String)?.startsWith("zh") != true &&
                        (!(itemUrl.get(it) as? String).isNullOrBlank() || (itemUrls.get(it) as? List<*>)?.isNotEmpty() == true) }
                    ?: return@mapNotNull unavailable
                val url = (itemUrl.get(selected) as? String)?.takeIf { it.isNotBlank() }
                    ?: (itemUrls.get(selected) as? List<*>)?.filterIsInstance<String>()?.firstOrNull { it.isNotBlank() }
                    ?: return@mapNotNull unavailable
                Track(id, url)
            }
            val report = "$current:$offset:${window.size}:${result.size}"
            if (lastReport != report) {
                lastReport = report
                HookRuntime.event("CaptionPrefetch", "batchOffset=$offset following videos=${window.size} readableTracks=${result.count { it.url.isNotBlank() }}")
            }
            return result
        }
        val report = "$current:missing:${instances.size}"
        if (lastReport != report) {
            lastReport = report
            HookRuntime.event("CaptionPrefetch", "current video absent from feed models=${instances.size}")
        }
        return emptyList()
    }
}
