package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.util.Locale

/** Business keys read from the 47.6.3 action/channel contracts; never match translated UI labels. */
enum class SharePanelItem(val title: Int, val hostKeys: Set<String>) {
    RECIPIENTS(R.string.share_recipients, emptySet()),
    REPOST(R.string.share_repost, setOf("repost")),
    COPY_LINK(R.string.share_copy_link, setOf("copy")),
    FACEBOOK(R.string.share_facebook, setOf("facebook")),
    FACEBOOK_LITE(R.string.share_facebook_lite, setOf("facebook_lite")),
    FACEBOOK_GROUP(R.string.share_facebook_group, setOf("facebook_group")),
    INSTAGRAM(R.string.share_instagram, setOf("instagram")),
    INSTAGRAM_STORY(R.string.share_instagram_story, setOf("instagram_story")),
    MESSENGER(R.string.share_messenger, setOf("messenger")),
    MESSENGER_LITE(R.string.share_messenger_lite, setOf("messenger_lite")),
    SMS(R.string.share_sms, setOf("sms")),
    GOOGLE_MESSAGES(R.string.share_google_messages, setOf("google_messages")),
    EMAIL(R.string.share_email, setOf("email")),
    WHATSAPP(R.string.share_whatsapp, setOf("whatsapp")),
    WHATSAPP_STATUS(R.string.share_whatsapp_status, setOf("whatsapp_status")),
    WHATSAPP_BUSINESS(R.string.share_whatsapp_business, setOf("whatsapp_business")),
    TELEGRAM(R.string.share_telegram, setOf("telegram")),
    DISCORD(R.string.share_discord, setOf("discord")),
    SNAPCHAT(R.string.share_snapchat, setOf("snapchat")),
    TWITTER(R.string.share_twitter, setOf("twitter")),
    LINE(R.string.share_line, setOf("line")),
    VIBER(R.string.share_viber, setOf("viber")),
    VK(R.string.share_vk, setOf("vk")),
    REPORT(R.string.share_report, setOf("report")),
    DISLIKE(R.string.share_dislike, setOf("dislike")),
    DOWNLOAD(R.string.share_download, setOf("save", "save_photo", "save_image", "image_save", "now_save")),
    PROMOTE(R.string.share_promote, setOf("promote")),
    CREATE_GROUP(R.string.share_create_group, setOf("im_create_group")),
    DUET(R.string.share_duet, setOf("duet")),
    STITCH(R.string.share_stitch, setOf("stitch")),
    CAPTIONS(R.string.share_captions, setOf("captions")),
    PLAYBACK_SPEED(R.string.share_playback_speed, setOf("playback_speed")),
    CLEAR_SCREEN(R.string.share_clear_screen, setOf("clear_screen")),
    AUTO_SCROLL(R.string.share_auto_scroll, setOf("auto_scroll")),
    FAVORITE(R.string.share_favorite, setOf("favorite")),
    SHARE_TO_STORY(R.string.share_share_to_story, setOf("share_to_story")),
    WHY_THIS_VIDEO(R.string.share_why_this_video, setOf("why_this_video")),
    CREATE_STICKER(R.string.share_create_sticker, setOf("create_sticker")),
    MORE(R.string.share_more, setOf("more"));

    val key: String = "share_hide_" + name.lowercase(Locale.ROOT)
}

internal object SharePanelPolicy {
    const val CUSTOM_ENABLED = "share_custom_keys_enabled"
    const val CUSTOM_KEYS = "share_hidden_custom_keys"

    fun customKeys(raw: String): Set<String> {
        require(raw.length <= 4096)
        val values = raw.split('\n', ',').map(String::trim).filter(String::isNotEmpty)
        require(values.size <= 64 && values.all { it.matches(Regex("[A-Za-z0-9_.:/ -]{1,80}")) })
        return values.toSet()
    }

    fun hiddenKeys(snapshot: ConfigSnapshot?): Set<String> {
        if (snapshot == null) return emptySet()
        val selected = SharePanelItem.entries.filter { snapshot.boolean(it.key) }.flatMapTo(linkedSetOf()) { it.hostKeys }
        if (snapshot.boolean(CUSTOM_ENABLED)) selected.addAll(customKeys(snapshot.string(CUSTOM_KEYS, "").orEmpty()))
        return selected
    }

    /** Return the original list when unchanged; never modify host-owned data, order or callback identity. */
    fun <T> filter(source: List<T>, hidden: Set<String>, keyOf: (T) -> String?): List<T> {
        if (hidden.isEmpty()) return source
        val kept = source.filter { item -> keyOf(item)?.let { it !in hidden } ?: true }
        return if (kept.size == source.size) source else kept
    }
}
