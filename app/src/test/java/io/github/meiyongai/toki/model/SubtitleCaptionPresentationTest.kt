package io.github.meiyongai.toki.subtitle.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCaptionPresentationTest {
    @Test fun acceptedOrdersExposeBothLayouts() {
        assertEquals(listOf("original_first", "translated_first"), SubtitleCaptionPresentation.orders)
    }

    @Test fun existingThreeArgumentCallsKeepOriginalFirst() {
        assertEquals("原文\nTranslation", SubtitleCaptionPresentation.display("原文", "Translation", "bilingual"))
    }

    @Test fun bilingualOrderCanBeReversedAndRestoredForTheSameCue() {
        assertEquals("Translation\n原文", SubtitleCaptionPresentation.display("原文", "Translation", "bilingual", "translated_first"))
        assertEquals("原文\nTranslation", SubtitleCaptionPresentation.display("原文", "Translation", "bilingual", "original_first"))
    }

    @Test fun unknownOrderFallsBackToOriginalFirst() {
        for (order in listOf("", "unsupported", "TRANSLATED_FIRST", " translated_first ")) {
            assertEquals("原文\nTranslation", SubtitleCaptionPresentation.display("原文", "Translation", "bilingual", order))
        }
    }

    @Test fun originalOnlyIgnoresOrderAndTranslation() {
        for (order in SubtitleCaptionPresentation.orders + "unsupported") {
            assertEquals(" 原文 ", SubtitleCaptionPresentation.display(" 原文 ", "Translation", "original", order))
        }
    }

    @Test fun translatedOnlyIgnoresOrderAndPreservesTranslationFormatting() {
        for (order in SubtitleCaptionPresentation.orders + "unsupported") {
            assertEquals(" Translation ", SubtitleCaptionPresentation.display("原文", " Translation ", "translated", order))
        }
    }

    @Test fun absentOrBlankTranslationRetainsSourceInEveryModeAndOrder() {
        for (mode in listOf("original", "translated", "bilingual", "unsupported")) {
            for (order in SubtitleCaptionPresentation.orders + "unsupported") {
                for (translation in listOf(null, "", " ", "\t\n")) {
                    assertEquals(" 原文\n第二行 ", SubtitleCaptionPresentation.display(" 原文\n第二行 ", translation, mode, order))
                }
            }
        }
    }

    @Test fun matchingBilingualTextIsNotDuplicatedInEitherOrder() {
        for (order in SubtitleCaptionPresentation.orders + "unsupported") {
            assertEquals("same", SubtitleCaptionPresentation.display("same", "same", "bilingual", order))
            assertEquals(" same ", SubtitleCaptionPresentation.display("\tsame\n", " same ", "bilingual", order))
        }
    }

    @Test fun multilineCaptionsKeepTheirInternalLineBreaksWhenReordered() {
        val original = "原文第一行\n原文第二行"
        val translated = "First translated line\nSecond translated line"
        assertEquals(original + "\n" + translated, SubtitleCaptionPresentation.display(original, translated, "bilingual", "original_first"))
        assertEquals(translated + "\n" + original, SubtitleCaptionPresentation.display(original, translated, "bilingual", "translated_first"))
    }

    @Test fun unknownModeKeepsExistingTranslatedOnlyFallback() {
        for (order in SubtitleCaptionPresentation.orders + "unsupported") {
            assertEquals("Translation", SubtitleCaptionPresentation.display("原文", "Translation", "unsupported", order))
        }
    }
}
