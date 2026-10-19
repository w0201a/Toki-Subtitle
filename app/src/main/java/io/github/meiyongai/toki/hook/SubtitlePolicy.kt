package io.github.meiyongai.toki.subtitle.hook

internal object SubtitlePolicy {
    fun includeEnglish(excluded: List<String>): Array<String> = excluded.filterNot {
        it.substringBefore('-').substringBefore('_').equals("en", true)
    }.toTypedArray()
}
