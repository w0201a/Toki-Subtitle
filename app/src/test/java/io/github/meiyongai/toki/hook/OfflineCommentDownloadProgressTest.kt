package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot

class OfflineCommentDownloadProgressTest {
    private fun row()=OfflineCommentData.Row("c","a","h","u","original","译文","zh-hans",0,0)
    private fun page()=OfflineCommentData.Snapshot("a".repeat(64),"v",1,listOf(row()),1,true,false,20)
    @Test fun onlyAdvancingCursorCanContinueWithoutArbitraryTotalPageCap() {
        assertEquals(20L,OfflineCommentDownloadProgress.advance(0,page()))
        assertThrows(IllegalArgumentException::class.java){OfflineCommentDownloadProgress.advance(20,page())}
        assertNull(OfflineCommentDownloadProgress.advance(0,page().copy(hasMore=false)))
        assertThrows(IllegalArgumentException::class.java){OfflineCommentDownloadProgress.advance(0,page().copy(rows=listOf(row().copy(sourceTruncated=true))))}
    }
    @Test fun missingFailedOrWrongTargetTranslationIsNotComplete() {
        assertTrue(OfflineCommentDownloadProgress.translated(page(),"zh-hans"))
        assertFalse(OfflineCommentDownloadProgress.translated(page(),"ja"))
        assertFalse(OfflineCommentDownloadProgress.translated(page().copy(rows=listOf(row().copy(translation=null))),"zh-hans"))
        assertFalse(OfflineCommentDownloadProgress.complete(2,1,emptyMap(),emptyMap()))
        assertFalse(OfflineCommentDownloadProgress.complete(1,1,mapOf("c" to 2),mapOf("c" to 1)))
        assertTrue(OfflineCommentDownloadProgress.complete(1,1,mapOf("c" to 2),mapOf("c" to 2)))
    }
    @Test fun downloadPermissionIsOptInAndIndependentFromVisiblePlaybackOrTranslationToggle() {
        assertFalse(ConfigSchema.booleanDefault(OfflineCommentDownloadHook.KEY))
        val requested=ConfigSnapshot(mapOf(OfflineCommentDownloadHook.KEY to true,OfflineCommentDownloadHook.BACKFILL to true))
        ConfigSchema.validate(requested.configuration())
        val plan=HostFeaturePlan(requested)
        assertEquals(setOf("OfflineCommentDownloadHook"),plan.adaptedFeatures)
        assertEquals(setOf(HostSymbol.COMMENT_TRANSLATION),plan.symbols)
        assertTrue(plan.requiresScan)
        assertFalse(requested.boolean(CachedCommentTranslateHook.KEY))
    }
}
