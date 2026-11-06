package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Per-instance anchor business and rendered-root contract; shared schema class names never imply a template. */
internal class TemplateAnchorContract(val type: Class<*>, val bound: List<Method>, val released: List<Method>,
    private val read: (Any) -> TemplateRootBindings.Snapshot?) {
    fun current(owner: Any): TemplateRootBindings.Snapshot? = if (type.isInstance(owner)) read(owner) else null
    companion object {
        private val inspectedAnchorCodes = linkedMapOf(
            "CAPCUT" to 54, "PROP" to 28, "PROP_COMMERCIAL_EFFECT" to -28, "MV_TEMPLATE" to 29, "PHOTO_MV_ANCHOR" to 31,
            "ANCHOR_EDIT_EFFECT" to 64, "ANCHOR_UGC_TEMPLATE" to 65, "ANCHOR_AUTO_CUT" to 66, "ANCHOR_PUGC_TEMPLATE" to 78,
            "ANCHOR_PHOTO_MODE_TEMPLATE" to 87, "ANCHOR_TT_CC_TEMPLATE" to 110, "ANCHOR_AIGT_TEMPLATE" to 111,
            "ANCHOR_SGT_TEMPLATE" to 127, "LYRIC_EFFECT_ANCHOR" to 120)
        internal val allowedAnchorNames: Set<String> get() = inspectedAnchorCodes.keys
        private fun method(type: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
            type.getMethod(name, *args).apply { check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true }
        internal fun renderer(type: Class<*>, provider: Class<*>, anchor: Class<*>, aweme: Class<*>, allowed: Set<Int>): TemplateAnchorContract {
            val data = type.getDeclaredField("LLJJLIIIJLLLLLLLZ").apply {
                check(this.type == provider && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            val display = method(provider, "providerDisplayAnchor", anchor)
            val video = method(provider, "providerAweme", aweme)
            val kind = method(anchor, "getType", Int::class.javaPrimitiveType!!)
            val root = method(type, "rootView", View::class.java)
            val bind = method(type, "onBind", Void.TYPE)
            val release = listOf("unBind", "onDestroy").map { method(type, it, Void.TYPE) }
            return TemplateAnchorContract(type, listOf(bind, root), release) { owner ->
                val source = data.get(owner)
                val selected = source?.let { display.invoke(it) }
                val item = source?.let { video.invoke(it) }
                val view = root.invoke(owner) as? View
                if (selected == null || item == null || view == null) null else
                    (kind.invoke(selected) as Int).let { code ->
                        TemplateRootBindings.Snapshot(view, selected, item, code in allowed,
                            TemplateAnchorPolicy.element(code) ?: LayoutElement.VIDEO_TEMPLATE)
                    }
            }
        }
        internal fun specificButton(type: Class<*>): TemplateAnchorContract {
            val button = type.getDeclaredField("LLJLL").apply {
                check(View::class.java.isAssignableFrom(this.type) && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            val content = type.getMethod("getContentView").apply {
                check(View::class.java.isAssignableFrom(returnType) && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            val created = method(type, "onViewCreated", Void.TYPE, View::class.java)
            val bound = method(type, "i4", Void.TYPE, Any::class.java)
            val release = listOf("unBind", "onDestroyView").map { method(type, it, Void.TYPE) }
            return TemplateAnchorContract(type, listOf(created, bound), release) { owner ->
                val view = content.invoke(owner) as? View
                val child = button.get(owner) as? View
                var cursor: View? = child
                var depth = 0
                while (cursor != null && cursor !== view && depth++ < 32) cursor = cursor.parent as? View
                if (view == null || child == null || cursor !== view) null else
                    TemplateRootBindings.Snapshot(view, owner, owner, true, LayoutElement.ANCHOR_OTHER_TEMPLATE)
            }
        }
        internal fun capcutArea(type: Class<*>): TemplateAnchorContract {
            val child = type.getDeclaredField("LLJJJJJIL").apply {
                check(android.widget.RelativeLayout::class.java.isAssignableFrom(this.type) && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            val container = type.getMethod("getContainerView").apply { check(View::class.java.isAssignableFrom(returnType)) }
            val bound = listOf(method(type, "onViewCreated", Void.TYPE, View::class.java), method(type, "i4", Void.TYPE, Any::class.java))
            val release = listOf("unBind", "onDestroyView").map { method(type, it, Void.TYPE) }
            return TemplateAnchorContract(type, bound, release) { owner ->
                val root = container.invoke(owner) as? View
                val view = (child.get(owner) as? View) ?: root?.findViewById(2131367119)
                var cursor = view; var depth = 0
                while (cursor != null && cursor !== root && depth++ < 32) cursor = cursor.parent as? View
                if (view == null || root == null || cursor !== root) null else
                    TemplateRootBindings.Snapshot(view, owner, owner, true, LayoutElement.ANCHOR_CAPCUT)
            }
        }
        internal fun standardComponent(type: Class<*>, renderer: Class<*>, params: Class<*>, anchor: Class<*>, aweme: Class<*>, allowed: Set<Int>): TemplateAnchorContract {
            val rendered = type.getDeclaredField("LLJZ").apply {
                check(renderer.isAssignableFrom(this.type)); isAccessible = true
            }
            val model = renderer.getDeclaredField("LJIILL").apply { check(this.type == params); isAccessible = true }
            val root = renderer.getDeclaredField("LJIIJ").apply { check(View::class.java.isAssignableFrom(this.type)); isAccessible = true }
            val key = renderer.getDeclaredField("LJI").apply { check(this.type == String::class.java); isAccessible = true }
            val getVideo = method(params, "getAweme", aweme)
            val anchors = method(aweme, "getAnchors", List::class.java)
            val anchorKey = method(anchor, "getComponentKey", String::class.java)
            val kind = method(anchor, "getType", Int::class.javaPrimitiveType!!)
            val bound = listOf(method(type, "onViewCreated", Void.TYPE, View::class.java), method(type, "i4", Void.TYPE, Any::class.java))
            val release = listOf("unBind", "onDestroyView").map { method(type, it, Void.TYPE) }
            return TemplateAnchorContract(type, bound, release) { owner ->
                val drawing = rendered.get(owner)
                val item = drawing?.let { model.get(it) }?.let { getVideo.invoke(it) }
                val componentKey = drawing?.let { key.get(it) as? String }?.takeIf { it.isNotBlank() }
                val view = drawing?.let { root.get(it) as? View }
                val selected = if (item == null || componentKey == null) null else
                    (anchors.invoke(item) as? List<*>)?.filter { anchor.isInstance(it) && anchorKey.invoke(it) == componentKey }?.singleOrNull()
                if (selected == null || item == null || view == null) null else {
                    val code = kind.invoke(selected) as Int
                    TemplateRootBindings.Snapshot(view, selected, item, code in allowed,
                        TemplateAnchorPolicy.element(code) ?: LayoutElement.VIDEO_TEMPLATE)
                }
            }
        }
        internal fun legacyCoordinator(type: Class<*>, business: Class<*>, renderer: Class<*>, anchor: Class<*>, aweme: Class<*>, bindInput: Class<*>, bindOwner: Class<*>, allowed: Set<Int>): TemplateAnchorContract {
            val selectedRenderer = method(type, "LJ", renderer)
            val root = method(renderer, "rootView", View::class.java)
            val provider = type.getDeclaredField("LJI").apply { check(this.type == business); isAccessible = true }
            val video = type.getDeclaredField("LJII").apply { check(this.type == aweme); isAccessible = true }
            val anchors = method(business, "eb", List::class.java)
            val kind = method(anchor, "getType", Int::class.javaPrimitiveType!!)
            val bound = listOf(method(type, "LJII", Int::class.javaPrimitiveType!!, bindInput, bindOwner),
                method(type, "LIZJ", Void.TYPE), selectedRenderer)
            val release = listOf(method(type, "LJFF", Void.TYPE))
            return TemplateAnchorContract(type, bound, release) { owner ->
                val item = video.get(owner)
                val candidates = provider.get(owner)?.let { anchors.invoke(it) as? List<*> }
                // Never hide a multi-anchor area's shared root. A single inspected business anchor only.
                val selected = candidates?.singleOrNull()?.takeIf { anchor.isInstance(it) }
                val view = selectedRenderer.invoke(owner)?.let { root.invoke(it) as? View }
                if (item == null || selected == null || view == null) null else {
                    val code = kind.invoke(selected) as Int
                    TemplateRootBindings.Snapshot(view, selected, item, code in allowed,
                        TemplateAnchorPolicy.element(code) ?: LayoutElement.VIDEO_TEMPLATE)
                }
            }
        }
        fun resolve(loader: ClassLoader): List<TemplateAnchorContract> {
            fun load(name: String) = Class.forName(name, false, loader)
            val anchorEnum = load("X.09Un")
            val getType = method(anchorEnum, "getTYPE", Int::class.javaPrimitiveType!!)
            val codes = allowedAnchorNames.mapTo(linkedSetOf()) { name ->
                val constant = anchorEnum.getField(name).apply { check(this.type == anchorEnum && Modifier.isStatic(modifiers)) }.get(null)
                (getType.invoke(constant) as Int).also { check(it == inspectedAnchorCodes.getValue(name)) { "Anchor enum changed at runtime" } }
            }
            check(codes.size == allowedAnchorNames.size)
            val result = mutableListOf(
                renderer(load("X.0B4S"), load("com.ss.android.ugc.aweme.feed.model.banner.IStandardModelProvider"),
                    load("com.ss.android.ugc.aweme.feed.model.AnchorCommonStruct"), load("com.ss.android.ugc.aweme.feed.model.Aweme"), codes),
                specificButton(load("com.ss.android.ugc.aweme.feed.assem.pugc.VideoTemplateButtonAssem")))
            // Optional paths are validated independently; a missing variant cannot disable existing contracts.
            for (name in listOf(
                "com.ss.android.ugc.feed.platform.cell.interact.next.standardized.template.button.ui.FcpStandardTemplateAssem",
                "com.ss.android.ugc.feed.platform.cell.interact.next.standardized.template.button.template.FcpStandardButtonAssem",
                "com.ss.android.ugc.feed.platform.cell.interact.next.standardized.template.tag.template.FcpStandardTagAssem")) {
                try { result += standardComponent(load(name), load("X.1Qa6"), load("com.ss.android.ugc.aweme.feed.model.VideoItemParams"),
                    load("com.ss.android.ugc.aweme.feed.model.AnchorCommonStruct"), load("com.ss.android.ugc.aweme.feed.model.Aweme"), codes) }
                catch (_: ReflectiveOperationException) { }
                catch (_: IllegalStateException) { }
                catch (_: IllegalArgumentException) { }
            }
            try { result += legacyCoordinator(load("X.0B4Q"), load("X.07g2"), load("X.0B49"),
                load("com.ss.android.ugc.aweme.feed.model.AnchorCommonStruct"), load("com.ss.android.ugc.aweme.feed.model.Aweme"),
                load("X.0BKu"), load("X.0AqL"), codes) }
            catch (_: ReflectiveOperationException) { }
            try { result += capcutArea(load("com.ss.android.ugc.feed.platform.cell.template.CCTemplateAreaAssem")) }
            catch (_: ReflectiveOperationException) { }
            return result
        }
    }
}
