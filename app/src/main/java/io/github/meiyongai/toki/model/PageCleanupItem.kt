package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.R

/** Page-internal presentation controls, separate from feed navigation hiding and data filtering. */
enum class PageCleanupGroup(val title: Int) {
    SEARCH(R.string.page_cleanup_search), INBOX(R.string.page_cleanup_inbox), LIVE(R.string.page_cleanup_live)
}

enum class PageCleanupItem(val group: PageCleanupGroup, val title: Int, val summary: Int) {
    SEARCH_VOICE(PageCleanupGroup.SEARCH, R.string.page_hide_search_voice, R.string.page_hide_search_voice_hint),
    SEARCH_TAKO(PageCleanupGroup.SEARCH, R.string.page_hide_search_tako, R.string.page_hide_search_tako_hint),
    INBOX_SHOP_MIGRATION(PageCleanupGroup.INBOX, R.string.page_hide_inbox_shop_migration, R.string.page_hide_inbox_shop_migration_hint),
    LIVE_FOLLOW_BOTTOM(PageCleanupGroup.LIVE, R.string.page_hide_live_follow_bottom, R.string.page_hide_live_follow_bottom_hint),
    LIVE_FOLLOW_PIN(PageCleanupGroup.LIVE, R.string.page_hide_live_follow_pin, R.string.page_hide_live_follow_pin_hint),
    LIVE_GIFT_GUIDE(PageCleanupGroup.LIVE, R.string.page_hide_live_gift_guide, R.string.page_hide_live_gift_guide_hint),
    LIVE_GIFT_TRAY(PageCleanupGroup.LIVE, R.string.page_hide_live_gift_tray, R.string.page_hide_live_gift_tray_hint),
    LIVE_EXTENDED_GIFT_TRAY(PageCleanupGroup.LIVE, R.string.page_hide_live_extended_gift_tray, R.string.page_hide_live_extended_gift_tray_hint);

    val key: String = "page_hide_" + name.lowercase(java.util.Locale.ROOT)
}
