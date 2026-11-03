package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Per-instance anchor business and rendered-root contract; shared schema class names never imply a template. */
internal class TemplateAnchorContract(val type: Class<*>, val bound: List<Method>, val released: List<Method>,
    private val read: (Any) -> TemplateRootBindings.Snapshot?) {
    fun current(owner: Any): TemplateRootBindings.Snapshot? = if (type.isInstance(owner)) read(owner) else null
    companion object {
        private val inspectedAnchorCodes = linkedMapOf(
            "PROP" to 28, "PROP_COMMERCIAL_EFFECT" to -28, "MV_TEMPLATE" to 29, "PHOTO_MV_ANCHOR" to 31,
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
                    TemplateRootBindings.Snapshot(view, selected, item, (kind.invoke(selected) as Int) in allowed)
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
                    TemplateRootBindings.Snapshot(view, owner, owner, true)
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
            return listOf(
                renderer(load("X.0B4S"), load("com.ss.android.ugc.aweme.feed.model.banner.IStandardModelProvider"),
                    load("com.ss.android.ugc.aweme.feed.model.AnchorCommonStruct"), load("com.ss.android.ugc.aweme.feed.model.Aweme"), codes),
                specificButton(load("com.ss.android.ugc.aweme.feed.assem.pugc.VideoTemplateButtonAssem")))
        }
    }
}
