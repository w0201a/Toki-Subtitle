package io.github.meiyongai.toki.subtitle.hook

import android.text.SpannableStringBuilder
import android.text.Spanned

/** A module-owned span identifies only our prefix; ordinary nickname text is never stripped by resemblance. */
internal object AuthorLabelPresentation {
    private class Prefix(val text: String)

    fun ownedPrefix(value: CharSequence): String? {
        val spanned = value as? Spanned ?: return null
        return spanned.getSpans(0, spanned.length, Prefix::class.java).firstOrNull {
            spanned.getSpanStart(it) == 0 && spanned.getSpanEnd(it) == it.text.length && value.toString().startsWith(it.text)
        }?.text
    }

    fun original(value: CharSequence): CharSequence {
        val spanned = value as? Spanned ?: return value
        val marker = spanned.getSpans(0, spanned.length, Prefix::class.java).firstOrNull {
            spanned.getSpanStart(it) == 0 && spanned.getSpanEnd(it) == it.text.length &&
                value.toString().startsWith(it.text)
        } ?: return value
        return SpannableStringBuilder(value).apply {
            removeSpan(marker)
            delete(0, marker.text.length)
        }
    }

    fun display(value: CharSequence, region: String?, enabled: Boolean): CharSequence {
        val source = original(value)
        if (!enabled || region.isNullOrBlank() || source.isBlank()) return source
        val prefix = AuthorLocationHook.formatAuthorWithLocation("", region)
        if (prefix.isEmpty()) return source
        return SpannableStringBuilder(prefix).apply {
            setSpan(Prefix(prefix), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(source)
        }
    }
}
