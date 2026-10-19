package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class DescriptionTranslationTest {
    @Test fun longDescriptionsRemainEligibleWithoutTruncationLimit() {
        assertTrue(DescriptionTranslation.eligible("A long description. ".repeat(300), "en"))
    }
    @Test fun chineseLanguagesAndChineseOnlyDescriptionsAreSkipped() {
        for (language in listOf("zh", "zh-Hans", "ZH_tw"))
            assertFalse(DescriptionTranslation.eligible("中文 #topic", language))
        assertFalse(DescriptionTranslation.eligible("这是中文标题", null))
    }
    @Test fun supportsForeignScriptsAndMissingLanguageMetadata() {
        assertTrue(DescriptionTranslation.eligible("これは日本語です", "ja"))
        assertTrue(DescriptionTranslation.eligible("외국어 제목", "ko"))
        assertTrue(DescriptionTranslation.eligible("An untagged title #video", null))
        assertTrue(DescriptionTranslation.eligible("Заголовок", "ru"))
    }
    @Test fun blankAndSymbolOnlyTitlesDoNotProduceTranslationRequests() {
        assertFalse(DescriptionTranslation.eligible(null, "en"))
        assertFalse(DescriptionTranslation.eligible("  ", null))
        assertFalse(DescriptionTranslation.eligible("12345 🎉", "en"))
    }
}
