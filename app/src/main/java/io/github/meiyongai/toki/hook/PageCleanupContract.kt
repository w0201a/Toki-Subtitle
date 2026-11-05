package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.view.ViewGroup
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Roots are taken from inspected inflated fields/widget getters/holder returns, never guessed from labels or parents. */
internal class PageCleanupContract(val item: PageCleanupItem, val type: Class<*>,
    val bound: List<Method>, val released: List<Method>,
    private val read: (Any, Array<out Any?>, Any?) -> View?) {
    fun root(instance: Any, arguments: Array<out Any?>, result: Any?): View? =
        if (type.isInstance(instance)) read(instance, arguments, result) else null

    companion object {
        private fun method(type: Class<*>, name: String, result: Class<*>, vararg arguments: Class<*>): Method =
            type.getMethod(name, *arguments).apply {
                check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        private fun viewField(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply {
            check(View::class.java.isAssignableFrom(this.type) && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        internal fun inflated(item: PageCleanupItem, type: Class<*>, field: String): PageCleanupContract {
            val created = method(type, "onViewCreated", Void.TYPE, View::class.java)
            val view = viewField(type, field)
            val destroyed = try { type.getDeclaredMethod("onDestroy").apply {
                check(returnType == Void.TYPE && !Modifier.isStatic(modifiers)); isAccessible = true
            } } catch (_: NoSuchMethodException) { null }
            return PageCleanupContract(item, type, listOf(created), listOfNotNull(destroyed)) { instance, _, _ -> view.get(instance) as? View }
        }
        internal fun widget(item: PageCleanupItem, type: Class<*>, source: Class<*>?): PageCleanupContract {
            val load = if (source == null) method(type, "onLoad", Void.TYPE, Array<Any>::class.java)
                else method(type, "onLoad", Void.TYPE, source, Array<Any>::class.java)
            val unload = if (source == null) method(type, "onUnload", Void.TYPE)
                else method(type, "onUnload", Void.TYPE, source)
            val show = try { method(type, "show", Void.TYPE) } catch (_: NoSuchMethodException) { null }
            val onShow = try { method(type, "onShow", Void.TYPE) } catch (_: NoSuchMethodException) { null }
            val root = type.getMethod("getView").apply {
                check(View::class.java.isAssignableFrom(returnType) && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            val content = type.getField("contentView").apply {
                check(this.type == View::class.java && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            return PageCleanupContract(item, type, listOfNotNull(load, show, onShow).distinct(), listOf(unload)) { instance, _, _ ->
                val actual = content.get(instance) as? View
                actual?.takeIf { root.invoke(instance) === it }
            }
        }
        internal fun inbox(item: PageCleanupItem, type: Class<*>, holder: Class<*>, model: Class<*>): PageCleanupContract {
            val created = method(type, "Kp", holder, ViewGroup::class.java, model)
            val bound = type.methods.single { it.name == "Ip" && it.parameterCount == 3 &&
                it.parameterTypes[0] == holder && it.parameterTypes[2] == java.lang.Integer.TYPE && it.returnType == Void.TYPE }.apply {
                check(!Modifier.isStatic(modifiers)); isAccessible = true
            }
            val root = holder.getField("itemView").apply { check(this.type == View::class.java); isAccessible = true }
            val destroyed = method(type, "onDestroy", Void.TYPE)
            return PageCleanupContract(item, type, listOf(created, bound), listOf(destroyed)) { _, arguments, result ->
                val owner = if (holder.isInstance(result)) result else arguments.firstOrNull()?.takeIf(holder::isInstance)
                owner?.let { root.get(it) as? View }
            }
        }
        fun resolve(item: PageCleanupItem, loader: ClassLoader): PageCleanupContract {
            fun type(name: String) = Class.forName(name, false, loader)
            return when (item) {
                PageCleanupItem.SEARCH_VOICE -> inflated(item,
                    type("com.ss.android.ugc.aweme.search.pages.middlepage.voiceentrance.VoiceFloatingButtonAssem"), "LLJZ")
                PageCleanupItem.SEARCH_TAKO -> inflated(item,
                    type("com.ss.android.ugc.aweme.search.pages.middlepage.takoentrance.ui.BaseTakoFloatingButtonAssem"), "LLJLIL")
                PageCleanupItem.INBOX_SHOP_MIGRATION -> inbox(item,
                    type("com.ss.android.ugc.aweme.inbox.shop.ShopFullMigrationBannerAssem"),
                    type("androidx.recyclerview.widget.RecyclerView$" + "ViewHolder"), type("X.02qa"))
                PageCleanupItem.LIVE_FOLLOW_BOTTOM -> widget(item,
                    type("com.bytedance.android.livesdk.chatroom.widget.followguide.FollowGuideBottomCardWidget"),
                    type("com.bytedance.ies.sdk.widgets.RecycleSource"))
                PageCleanupItem.LIVE_FOLLOW_PIN -> widget(item,
                    type("com.bytedance.android.livesdk.chatroom.widget.followguide.FollowGuidePinCardWidget"),
                    type("com.bytedance.ies.sdk.widgets.RecycleSource"))
                PageCleanupItem.LIVE_GIFT_TRAY -> widget(item,
                    type("com.bytedance.android.livesdk.gift.base.platform.business.tray.LiveGiftTrayWidgetV2"), null)
                PageCleanupItem.LIVE_EXTENDED_GIFT_TRAY -> widget(item,
                    type("com.bytedance.android.livesdk.gift.base.platform.business.tray.ExtendScreenGiftTrayWidget"), null)
                PageCleanupItem.LIVE_GIFT_GUIDE -> widget(item,
                    type("com.bytedance.android.livesdk.guide.GiftGuideWidget"), type("com.bytedance.ies.sdk.widgets.RecycleSource"))
            }
        }
    }
}
