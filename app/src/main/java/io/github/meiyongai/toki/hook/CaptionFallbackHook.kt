package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 字幕轨补偿；未知宿主须通过独立结构匹配，失败时由注册事务撤销。
 *
 * 同时覆盖播放器、字幕服务与显示策略。仅修改返回值，不写字幕缓存和翻译偏好。
 * 有效译文轨优先保留；普通文件轨缺少地址或轨号不可用时补回可读取的原字幕。
 * 实时字幕可能通过独立通道传输，不能仅凭 URL 为空就认定它无效。
 *
 * 实验补丁保持故障开放：契约不匹配只记录并跳过，不能让翻译功能整体注册失败。
 */
internal object CaptionFallbackHook {
    private const val TAG = "TokiCaptionFallback"
    private const val EXPLAINED_LIMIT = 64

    /** 每个视频只详细解释一次，避免刷屏；无用户内容以外的新增数据。 */
    private val explained = LinkedHashMap<String, Boolean>()
    private val runtimeError = java.util.concurrent.atomic.AtomicBoolean()

    private fun <T> guarded(original: T, block: () -> T): T = try { block() } catch (error: Exception) {
        if (runtimeError.compareAndSet(false, true))
            HookRuntime.event(TAG, "trial-5 runtime fallback error=${error.javaClass.simpleName}")
        original
    }

    fun install(module: XposedModule, loader: ClassLoader, feature: String = "VideoTranslateHook") {
        installVerified(module, loader, feature)
    }

    /** 一次性解析并校验全部反射契约；任一成员不符即由调用方记录并跳过。 */
    private class Contract(loader: ClassLoader) {
        val loader: ClassLoader = loader
        val aweme: Class<*> = loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.Aweme")
        val model: Class<*> = loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.CaptionModel")
        val item: Class<*> = loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.CaptionItemModel")
        val selection: Class<*> = loader.adaptiveClass("X.08ZQ")
        // Service readers are independently verified. A changed private helper must not
        // prevent recovery through those public service paths.
        private val selector: Class<*>? = runCatching { loader.adaptiveClass("X.061i") }.getOrNull()
        val trackId: Method? = selector?.let { runCatching { it.adaptiveDeclaredMethod("LIZLLL", aweme) }.getOrNull() }
        val result: Method? = selector?.let { runCatching { it.adaptiveDeclaredMethod("LJFF", aweme, String::class.java) }.getOrNull() }
        val subId: Field = selection.adaptiveDeclaredField("LIZIZ").apply { isAccessible = true }
        val enabled: Field = selection.adaptiveDeclaredField("LIZ").apply { isAccessible = true }
        // ICLAPerfETServiceImpl.LJI 明确将 true 映射为 DO_NOT_LOAD，绝非 visible。
        val skipLoading: Field = selection.adaptiveDeclaredField("LIZLLL").apply { isAccessible = true }
        val captionModel: Field = selection.adaptiveDeclaredField("LJFF").apply { isAccessible = true }
        val constructor = selection.getDeclaredConstructor(java.lang.Boolean.TYPE, Integer.TYPE,
            java.lang.Boolean.TYPE, model, Integer.TYPE)
        val extras = listOf("LIZJ", "LJ").map { selection.adaptiveDeclaredField(it).apply { isAccessible = true } }
        val originalLanguage: Field = model.adaptiveDeclaredField("originalCaptionLanguage").apply { isAccessible = true }
        val tracks: Field = model.adaptiveDeclaredField("captionList").apply { isAccessible = true }
        val languageCode: Field = originalLanguage.type.adaptiveDeclaredField("languageCode").apply { isAccessible = true }
        val languageId: Field = originalLanguage.type.adaptiveDeclaredField("languageId").apply { isAccessible = true }
        val realtime: Field = originalLanguage.type.adaptiveDeclaredField("canTranslateRealtime").apply { isAccessible = true }
        val itemCode: Field = item.adaptiveDeclaredField("languageCode").apply { isAccessible = true }
        val itemLanguageId: Field = item.adaptiveDeclaredField("languageId").apply { isAccessible = true }
        val itemSubId: Field = item.adaptiveDeclaredField("subId").apply { isAccessible = true }
        val itemUrl: Field = item.adaptiveDeclaredField("url").apply { isAccessible = true }
        val itemUrls: Field = item.adaptiveDeclaredField("urlList").apply { isAccessible = true }
        val itemRealtime: Field = item.adaptiveDeclaredField("isRealtimeTranslated").apply { isAccessible = true }
        val hostCaptionsEnabled: Method = loader.adaptiveClass("X.08ZA").adaptiveDeclaredMethod("LJJ", aweme)
        val strategySkipLoading: Method = loader.adaptiveClass("com.ss.android.ugc.aweme.strategy.CaptionStrategyServiceImpl")
            .adaptiveDeclaredMethod("ii", aweme)
        val serviceClass: Class<*> = loader.adaptiveClass("com.ss.android.ugc.aweme.service.CaptionServiceImpl")
        val serviceResult: Method = serviceClass.adaptiveDeclaredMethod("LJIIIZ", aweme)
        val serviceTrackId: Method = serviceClass.adaptiveDeclaredMethod("LJIIJ", aweme)
        val cacheResult: Method? = runCatching { loader.adaptiveClass("X.08ZE").adaptiveDeclaredMethod("LIZ", aweme) }.getOrNull()
        val service: Method? = runCatching { loader.adaptiveClass("X.08Zd").adaptiveDeclaredMethod("LIZ") }.getOrNull()
        val keva: Class<*> = loader.adaptiveClass("com.ss.android.ugc.aweme.service.ICaptionKevaService")
        val captionsForNative: Method? = runCatching { keva.adaptiveDeclaredMethod("LIZIZ") }.getOrNull()
        val captionsForForeign: Method? = runCatching { keva.adaptiveDeclaredMethod("LJL") }.getOrNull()

        init {
            check(trackId == null || trackId.returnType == Integer.TYPE) { "字幕轨号读取签名不符" }
            check(result == null || result.returnType == selection) { "字幕选择返回类型不符" }
            check(serviceTrackId.returnType == Integer.TYPE && serviceResult.returnType == selection)
            check(subId.type == Integer.TYPE && captionModel.type == model) { "字幕选择字段类型不符" }
        }
    }

    private fun installVerified(module: XposedModule, loader: ClassLoader, feature: String) {
        val contract = Contract(loader)

        for (method in listOfNotNull(contract.trackId, contract.serviceTrackId)) {
        module.trackHook(feature, method).intercept { chain ->
            val reported = chain.proceed() as Int
            guarded(reported) {
            val aweme = chain.args[0]
            // 正轨号可能来自服务缓存中新增的译文轨，不能用 Aweme 的旧模型否定它。
            val chosen = if (reported != -1) reported else decide(contract, aweme, reported, modelOf(contract, aweme))
            if (chosen != reported) {
                HookRuntime.count(feature, "trackIdFallback")
            }
            chosen
            }
        }
        }

        for (method in listOfNotNull(contract.result, contract.serviceResult, contract.cacheResult)) {
        module.trackHook(feature, method).intercept { chain ->
            val original = chain.proceed()
            guarded(original) {
            val aweme = chain.args[0]
            val reported = original?.let { contract.subId.getInt(it) } ?: -1
            // 宿主结果可能包含刚到达的译文轨，必须优先使用它，不能换回 Aweme 上的旧模型。
            val model = original?.let { contract.captionModel.get(it) } ?: modelOf(contract, aweme)
            val chosen = decide(contract, aweme, reported, model)
            val active = active(contract, aweme)
            val load = active && hasTrack(contract, model, chosen)
            val wasSkipped = original?.let { contract.skipLoading.getBoolean(it) } ?: false
            val wasEnabled = original?.let { contract.enabled.getBoolean(it) } ?: false
            NativeSubtitleTranslator.noteTrack(aidText(contract, aweme), tracksOf(contract, model)
                .firstOrNull { contract.itemSubId.getInt(it) == chosen }?.let { contract.itemCode.get(it) as? String })
            CaptionPrefetch.note(aidText(contract, aweme), tracksOf(contract, model)
                .firstOrNull { contract.itemSubId.getInt(it) == chosen }?.let {
                    (contract.itemUrl.get(it) as? String)?.takeIf(String::isNotBlank)
                        ?: (contract.itemUrls.get(it) as? List<*>)?.filterIsInstance<String>()?.firstOrNull(String::isNotBlank)
                })
            explainOnce(contract, aweme, reported, "${method.declaringClass.simpleName}.${method.name}",
                "chosen=$chosen active=$active enabled=$wasEnabled skipLoading=$wasSkipped load=$load", model)
            if (!active || model == null || (chosen == reported && (!load || (!wasSkipped && wasEnabled)))) original
            else {
                HookRuntime.count(feature, "selectionFallback")
                val replacement = contract.constructor.newInstance(wasEnabled || load, chosen, wasSkipped && !load, model, 20)
                // 构造器默认清空事件附加信息；已有结果必须保留，避免破坏播放事件统计。
                if (original != null) contract.extras.forEach { it.set(replacement, it.get(original)) }
                replacement
            }
            }
        }
        }

        module.trackHook(feature, contract.strategySkipLoading).intercept { chain ->
            val skipped = chain.proceed() as Boolean
            guarded(skipped) {
            val aweme = chain.args[0]
            val model = modelOf(contract, aweme)
            val active = active(contract, aweme)
            val recover = skipped && active && originalTrackId(contract, model) != null
            if (recover) HookRuntime.count(feature, "loadSkipFallback")
            explainOnce(contract, aweme, -1, "strategy.ii", "active=$active skipLoading=$skipped recovered=$recover", model)
            skipped && !recover
            }
        }

        // SUB-TRANSLATE-GATE：译文尚未可用时，宿主的 foreign_original_hidden 会隐藏有效原字幕。
        // true 为原字幕帧，null 为到帧前的基础状态判定；false 为译文帧，始终保留宿主结果。
        // 到帧前也必须允许有效原字幕，否则基础状态可能先被设为 HIDDEN。
        val hideOriginal = loader.adaptiveClass("X.08ZA").adaptiveDeclaredMethod(
            "LJJIJIIJI", contract.aweme, java.lang.Boolean::class.java)
        module.trackHook(feature, hideOriginal).intercept { chain ->
            val hidden = chain.proceed() as Boolean
            if (!hidden || chain.args[1] == false) return@intercept hidden
            guarded(hidden) {
                val aweme = chain.args[0]
                val model = modelOf(contract, aweme)
                val target = "zh-Hans"
                val hasTranslation = tracksOf(contract, model).any {
                    contract.itemCode.get(it) == target && usable(contract, it)
                }
                if (active(contract, aweme) &&
                    originalTrackId(contract, model) != null && !hasTranslation) {
                    HookRuntime.count(feature, "originalVisibleWhileTranslationUnavailable")
                    false
                } else hidden
            }
        }

        HookRuntime.event(TAG, "verified readers installed owner=$feature selector=${contract.result != null} cache=${contract.cacheResult != null} service=true identity=${HostSymbols.codeIdentity()}")
    }

    private fun decide(contract: Contract, aweme: Any?, selected: Int, model: Any?): Int {
        if (!active(contract, aweme)) return selected
        val items = tracksOf(contract, model)
        val selectedItem = items.firstOrNull { contract.itemSubId.getInt(it) == selected }
        // 实时轨不要求文件地址；宿主在其他通道提供数据时必须保持选择。
        val usable = selectedItem?.let { usable(contract, it) } ?: false
        return CaptionFallbackPolicy.choose(selected, originalTrackId(contract, model), usable)
    }

    private fun active(contract: Contract, aweme: Any?): Boolean = aweme != null &&
        (ConfigClient.getBoolean(VideoTranslateHook.KEY_VIDEO_TRANSLATE_ENABLED) ||
            ConfigClient.getBoolean(CommentTranslateHook.KEY_COMMENT_TRANSLATE_ENABLED)) && captionsEnabled(contract, aweme)

    /** 按当前视频的原语言选取开关；不能用 native OR foreign 覆盖用户对某类视频的关闭。 */
    private fun captionsEnabled(contract: Contract, aweme: Any?): Boolean = try {
        contract.hostCaptionsEnabled.invoke(null, aweme) as Boolean
    } catch (error: Exception) {
        false
    }

    private fun modelOf(contract: Contract, aweme: Any?): Any? = try {
        val video = aweme?.let { contract.aweme.adaptiveMethod("getVideo").invoke(it) }
        video?.javaClass?.adaptiveMethod("getCaptionModel")?.invoke(video)
    } catch (error: Exception) { null }

    private fun tracksOf(contract: Contract, model: Any?): List<Any> =
        (model?.let { contract.tracks.get(it) } as? List<*>)?.filterNotNull().orEmpty()

    private fun hasTrack(contract: Contract, model: Any?, id: Int): Boolean = id != -1 &&
        tracksOf(contract, model).any { contract.itemSubId.getInt(it) == id && usable(contract, it) }

    private fun usable(contract: Contract, item: Any): Boolean =
        !(contract.itemUrl.get(item) as? String).isNullOrBlank() ||
            (contract.itemUrls.get(item) as? List<*>)?.any { it is String && it.isNotBlank() } == true ||
            contract.itemRealtime.get(item) == true

    /** 先按原语言标识匹配字幕项，再按原语言代码匹配；两者都缺失则不改结果。 */
    private fun originalTrackId(contract: Contract, model: Any?): Int? {
        if (model == null) return null
        val language = contract.originalLanguage.get(model) ?: return null
        val tracks = tracksOf(contract, model).filter { usable(contract, it) && contract.itemSubId.getInt(it) != -1 }
        if (tracks.isEmpty()) return null
        val identity = contract.languageId.getLong(language)
        tracks.firstOrNull { contract.itemLanguageId.getLong(it) == identity }
            ?.let { return contract.itemSubId.getInt(it) }
        val code = contract.languageCode.get(language) as? String ?: return null
        return tracks.firstOrNull { code == contract.itemCode.get(it) }?.let { contract.itemSubId.getInt(it) }
    }

    /** 每个视频只记录一次完整判定输入，用于定位宿主为何给出空字幕。 */
    private fun explainOnce(contract: Contract, aweme: Any?, selected: Int, path: String, flags: String, model: Any?) {
        val aid = try {
            contract.aweme.adaptiveMethod("getAid").invoke(aweme) as? String ?: "?"
        } catch (error: Exception) { "?" }
        // 同一视频不同入口和状态变化各保留一条，不能让首个 trackId 查询吞掉显示诊断。
        val key = "$aid:$path:$selected:$flags"
        val first = synchronized(explained) {
            if (explained.containsKey(key)) false
            else {
                explained[key] = true
                while (explained.size > EXPLAINED_LIMIT) explained.remove(explained.keys.first())
                true
            }
        }
        if (first) HookRuntime.event(TAG, "trial-5 $path $flags " + describe(contract, aweme, selected, model))
    }

    private fun describe(contract: Contract, aweme: Any?, selected: Int, model: Any?): String {
        val language = model?.let { contract.originalLanguage.get(it) }
        val tracks = (model?.let { contract.tracks.get(it) } as? List<*>)?.filterNotNull().orEmpty()
        val texts = tracks.joinToString(",") { "${contract.itemCode.get(it)}#${contract.itemSubId.getInt(it)}:usable=${usable(contract, it)}:rt=${contract.itemRealtime.get(it)}" }
        return "aid=${aidText(contract, aweme)} selected=$selected original=${language?.let { contract.languageCode.get(it) }}" +
            " realtime=${language?.let { contract.realtime.get(it) }} tracks=[$texts] ${hostFlags(contract)}"
    }

    private fun aidText(contract: Contract, aweme: Any?): String = try {
        contract.aweme.adaptiveMethod("getAid").invoke(aweme) as? String ?: "?"
    } catch (error: Exception) { "?" }

    /** 只读探测宿主的翻译模式与目标语言；失败不影响任何行为。 */
    private fun hostFlags(contract: Contract): String {
        val reverse = probe { contract.loader.adaptiveClass("X.08a3").adaptiveDeclaredMethod("LIZ").invoke(null) }
        val target = probe {
            val holder = contract.loader.adaptiveClass("X.03hV")
            val instance = holder.adaptiveDeclaredField("LIZIZ").get(null)
            instance.javaClass.adaptiveMethod("LLILLIZIL").invoke(instance)
        }
        val toggles = probe {
            val service = contract.service?.invoke(null)
            "native=${contract.captionsForNative?.invoke(service)} foreign=${if (service != null) contract.captionsForForeign?.invoke(service) else null}"
        }
        val automatic = probe {
            val service = contract.loader.adaptiveClass("X.08aF").adaptiveDeclaredMethod("LIZ").invoke(null)
            contract.loader.adaptiveClass("com.ss.android.ugc.aweme.translation.service.ITranslationKevaService")
                .adaptiveMethod("LIZ").invoke(service)
        }
        return "reverseEffective=$reverse target=$target autoTranslate=$automatic captions[$toggles]"
    }

    private fun probe(block: () -> Any?): Any? = try { block() } catch (error: Exception) { "?" }
}
