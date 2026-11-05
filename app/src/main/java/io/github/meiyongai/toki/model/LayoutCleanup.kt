package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.R

/** 页面净化的三个独立配置区域；名称由资源系统本地化。 */
enum class LayoutGroup(val title: Int) {
    TOP(R.string.layout_top), BOTTOM(R.string.layout_bottom), SIDE(R.string.layout_side);

    val opacityKey = "layout_opacity_" + name.lowercase(java.util.Locale.ROOT)
    val opacityEnabledKey = "${opacityKey}_enabled"
}

/**
 * 页面控件的配置与展示目录，不保存宿主混淆类名或资源编号。
 * @param group 所属区域。
 * @param title 本地化名称。
 * @param tag 原生导航标识；非导航控件为 null。
 * Callers: ConfigSchema、LayoutCleanupSection、LayoutCleanupHook。
 */
enum class LayoutElement(val group: LayoutGroup, val title: Int, val tag: String? = null) {
    TOP_SEARCH(LayoutGroup.TOP, R.string.layout_search, "search"),
    TOP_LIVE(LayoutGroup.TOP, R.string.layout_live, "live"),
    MINIS(LayoutGroup.TOP, R.string.layout_minis),
    TOP_FRIENDS(LayoutGroup.TOP, R.string.layout_friends, "FRIENDS_FEED"),
    TOP_EXPLORE(LayoutGroup.TOP, R.string.layout_explore, "homepage_explore"),
    TOP_FOLLOWING(LayoutGroup.TOP, R.string.layout_following, "Following"),
    TOP_SHOP(LayoutGroup.TOP, R.string.layout_shop, "Shop"),
    TOP_FOR_YOU(LayoutGroup.TOP, R.string.layout_for_you, "For You"),
    TOP_NEARBY(LayoutGroup.TOP, R.string.layout_nearby, "Nearby"),
    TOP_STEM(LayoutGroup.TOP, R.string.layout_stem, "Stem"),
    TOP_SERIES(LayoutGroup.TOP, R.string.layout_series, "Drama"),
    BOTTOM_HOME(LayoutGroup.BOTTOM, R.string.layout_home, "HOME"),
    BOTTOM_FRIENDS(LayoutGroup.BOTTOM, R.string.layout_friends, "FRIENDS_TAB"),
    BOTTOM_SHOP(LayoutGroup.BOTTOM, R.string.layout_shop, "SHOP_MALL"),
    BOTTOM_CREATE(LayoutGroup.BOTTOM, R.string.layout_create, "PUBLISH"),
    BOTTOM_INBOX(LayoutGroup.BOTTOM, R.string.layout_inbox, "NOTIFICATION"),
    BOTTOM_PROFILE(LayoutGroup.BOTTOM, R.string.layout_profile, "USER"),
    BOTTOM_DISCOVER(LayoutGroup.BOTTOM, R.string.layout_discover, "DISCOVER"),
    BOTTOM_EXPLORE(LayoutGroup.BOTTOM, R.string.layout_explore, "homepage_explore"),
    BOTTOM_SEARCH_RECOMMENDATIONS(LayoutGroup.BOTTOM, R.string.layout_bottom_search_recommendations),
    AVATAR(LayoutGroup.SIDE, R.string.layout_avatar),
    FOLLOW(LayoutGroup.SIDE, R.string.layout_follow),
    LIKE(LayoutGroup.SIDE, R.string.layout_like),
    COMMENT(LayoutGroup.SIDE, R.string.layout_comment),
    FAVORITE(LayoutGroup.SIDE, R.string.layout_favorite),
    SHARE(LayoutGroup.SIDE, R.string.layout_share),
    MUSIC(LayoutGroup.SIDE, R.string.layout_music),
    AUTHOR(LayoutGroup.SIDE, R.string.layout_author),
    DESCRIPTION(LayoutGroup.SIDE, R.string.layout_description),
    MUSIC_TITLE(LayoutGroup.SIDE, R.string.layout_music_title),
    TAGS(LayoutGroup.SIDE, R.string.layout_tags),
    SEARCH_LABEL(LayoutGroup.SIDE, R.string.layout_search_label),
    AUTO_SCROLL_STOP(LayoutGroup.SIDE, R.string.layout_auto_scroll_stop),
    TAKO(LayoutGroup.SIDE, R.string.layout_tako),
    REWARDS(LayoutGroup.SIDE, R.string.layout_rewards),
    QUICK_COMMENT(LayoutGroup.SIDE, R.string.layout_quick_comment),
    PLAYLIST(LayoutGroup.SIDE, R.string.layout_playlist),
    CREATION_BUTTONS(LayoutGroup.SIDE, R.string.layout_creation_buttons),
    LIVE_NOTICE(LayoutGroup.SIDE, R.string.layout_live_notice),
    LANDSCAPE(LayoutGroup.SIDE, R.string.layout_landscape),
    PHOTO_INDICATORS(LayoutGroup.SIDE, R.string.layout_photo_indicators),
    DANMAKU_ENTRANCE(LayoutGroup.SIDE, R.string.layout_danmaku_entrance),
    VIDEO_TEMPLATE(LayoutGroup.SIDE, R.string.layout_video_template),
    DUET_BUTTON(LayoutGroup.SIDE, R.string.layout_duet_button),
    STITCH_BUTTON(LayoutGroup.SIDE, R.string.layout_stitch_button),
    ADD_YOURS_BUTTON(LayoutGroup.SIDE, R.string.layout_add_yours_button),
    STORY_BUTTON(LayoutGroup.SIDE, R.string.layout_story_button),
    COLLAB_BUTTON(LayoutGroup.SIDE, R.string.layout_collab_button),
    INSPIRATION_BUTTON(LayoutGroup.SIDE, R.string.layout_inspiration_button),
    REFERRAL_REWARD(LayoutGroup.SIDE, R.string.layout_referral_reward),
    GIFT_BAG(LayoutGroup.SIDE, R.string.layout_gift_bag),
    LIVE_TASK(LayoutGroup.SIDE, R.string.layout_live_task),
    AD_PRODUCT(LayoutGroup.SIDE, R.string.layout_ad_product),
    AD_INTERACTIVE(LayoutGroup.SIDE, R.string.layout_ad_interactive),
    AD_PLAYABLE(LayoutGroup.SIDE, R.string.layout_ad_playable),
    COMMODITY_CARD(LayoutGroup.SIDE, R.string.layout_commodity_card),
    AD_LIVE_SHOPPING(LayoutGroup.SIDE, R.string.layout_ad_live_shopping),
    PUSH_SURVEY(LayoutGroup.SIDE, R.string.layout_push_survey),
    FEEDBACK_SURVEY(LayoutGroup.SIDE, R.string.layout_feedback_survey),
    ANCHOR_EFFECT(LayoutGroup.SIDE, R.string.layout_anchor_effect),
    ANCHOR_CAPCUT(LayoutGroup.SIDE, R.string.layout_anchor_capcut),
    ANCHOR_AI(LayoutGroup.SIDE, R.string.layout_anchor_ai),
    ANCHOR_PHOTO(LayoutGroup.SIDE, R.string.layout_anchor_photo),
    ANCHOR_OTHER_TEMPLATE(LayoutGroup.SIDE, R.string.layout_anchor_other_template),
    SHARE_PANEL(LayoutGroup.SIDE, R.string.layout_share_panel),
    QUICK_MESSAGE(LayoutGroup.SIDE, R.string.layout_quick_message),
    USER_CARD(LayoutGroup.SIDE, R.string.layout_user_card),
    SURVEY(LayoutGroup.SIDE, R.string.layout_survey),
    AD_CARDS(LayoutGroup.SIDE, R.string.layout_ad_cards),
    BOTTOM_BANNERS(LayoutGroup.SIDE, R.string.layout_bottom_banners),
    ACTIVITIES(LayoutGroup.SIDE, R.string.layout_activities);

    val key: String = "layout_hide_" + name.lowercase(java.util.Locale.ROOT)


    /** A legacy aggregate still includes its inspected individual components. */
    val aggregate: LayoutElement? get() = when (this) {
        DUET_BUTTON -> CREATION_BUTTONS
        STITCH_BUTTON -> CREATION_BUTTONS
        ADD_YOURS_BUTTON -> CREATION_BUTTONS
        STORY_BUTTON -> CREATION_BUTTONS
        COLLAB_BUTTON -> CREATION_BUTTONS
        INSPIRATION_BUTTON -> CREATION_BUTTONS
        REFERRAL_REWARD -> REWARDS
        GIFT_BAG -> REWARDS
        LIVE_TASK -> REWARDS
        AD_PRODUCT -> AD_CARDS
        AD_INTERACTIVE -> AD_CARDS
        AD_PLAYABLE -> AD_CARDS
        COMMODITY_CARD -> AD_CARDS
        AD_LIVE_SHOPPING -> AD_CARDS
        PUSH_SURVEY -> SURVEY
        FEEDBACK_SURVEY -> SURVEY
        else -> null
    }

    companion object {
        /**
         * 将宿主工具栏的业务标识映射到现有净化开关。
         * @param tag 宿主工具栏 getTag 返回的稳定业务标识。
         * @return 对应开关；未列入目录的入口返回 null。
         * Callers: LayoutCleanupHook.init 工具栏绑定回调、LayoutComponentSelectorTest。
         */
        fun toolbar(tag: String): LayoutElement? = when (tag) {
            "tako" -> TAKO
            "coin" -> REWARDS
            "special_event" -> ACTIVITIES
            "dm_notice" -> QUICK_MESSAGE
            "publish", "story", "story_camera" -> CREATION_BUTTONS
            else -> navigation(LayoutGroup.TOP, tag)
        }

        /** @param group 区域。@param tag 宿主语义标识。@return 对应控件或 null。Callers: LayoutCleanupHook。 */
        fun navigation(group: LayoutGroup, tag: String): LayoutElement? =
            entries.singleOrNull { it.group == group && it.tag == tag } ?:
                if (group == LayoutGroup.TOP && tag == "Live") TOP_LIVE else null
    }
}
