package io.github.meiyongai.toki.subtitle.hook

/** Test fixtures opt into a fake transport without depending on Android connectivity or production config. */
internal fun fakeOnlineTranslator(enabled: (String) -> Boolean,
    translate: (String, (String?) -> Unit) -> AutoCloseable,
    report: (String) -> Unit = {},
): NativeSubtitleTranslator = NativeSubtitleTranslator(enabled, translate, report,
    access = { LocalOnlyRequest { true } })
