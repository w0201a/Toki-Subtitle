package io.github.meiyongai.toki.subtitle.hook

import android.app.AlertDialog
import android.os.Looper
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OfflineCommentDefaultsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun quotas(snapshot:ConfigSnapshot)=OfflineCommentBudget.Quotas(
        OfflineCommentPreferences.limit(snapshot.string(OfflineCommentPreferences.LIMIT)),
        OfflineCommentPreferences.replies(snapshot.string(OfflineCommentPreferences.REPLY_LIMIT)),
        OfflineCommentPreferences.threadReplies(snapshot.string(OfflineCommentPreferences.THREAD_LIMIT)))

    @Test fun absentOrInvalidSettingsUseIndependent200100015Defaults() {
        val expected=OfflineCommentBudget.Quotas(200,1000,15)
        assertEquals(expected,quotas(ConfigSnapshot(emptyMap())))
        assertEquals(expected,OfflineCommentBudget.Quotas(200))
        for(invalid in listOf("", "bad", "-1", "2501")) {
            assertEquals(200,OfflineCommentPreferences.limit(invalid))
            assertEquals(1000,OfflineCommentPreferences.replies(invalid))
            assertEquals(15,OfflineCommentPreferences.threadReplies(invalid))
        }
        assertEquals(200,OfflineCommentPreferences.limit("0"))
    }
    @Test fun explicit333AndLargerHistoricalLimitsAndZeroRepliesSurviveRoundTrip() {
        for((roots,total,thread) in listOf(Triple(333,1000,15),Triple(2500,2500,50),Triple(1,0,0))) {
            val values=mapOf(OfflineCommentPreferences.LIMIT to "$roots",OfflineCommentPreferences.REPLY_LIMIT to "$total",OfflineCommentPreferences.THREAD_LIMIT to "$thread")
            ConfigSchema.validate(values)
            assertEquals(OfflineCommentBudget.Quotas(roots,total,thread),quotas(ConfigSnapshot(ConfigSnapshot(values).configuration())))
        }
    }
    @Test fun loweredDefaultAndReopenPreserveExplicitVideoOverrideAndAllStoredRows() {
        val scope=HostOfflineComments.LOCAL_SCOPE;val video="defaults-${UUID.randomUUID()}"
        val file=File(System.getProperty("java.io.tmpdir"),"toki-v3-defaults/${UUID.randomUUID()}.sqlite")
        OfflineCommentLimitOverrides.set(app,scope,video,333)
        val rows=(1..350).map { OfflineCommentData.Row("c$it","a","h","u","",null,"",0,0,mediaKnown=true) }
        CommentRepository(app,file).use { repo ->
            repo.register(scope,video,"model",OfflineCommentBudget.Quotas(350,0,0),"zh-hans")
            repo.importPage(OfflineCommentData.Snapshot(scope,video,0,rows,350,false,false))
        }
        CommentRepository(app,file).use { repo ->
            val configured=OfflineCommentLimitOverrides.effective(app,scope,video,OfflineCommentPreferences.limit(null))
            assertEquals(333,configured)
            repo.register(scope,video,"",OfflineCommentBudget.Quotas(configured,1000,15),"zh-hans")
            assertEquals(333,repo.readVideo(scope,video)!!.snapshot.rows.size)
            assertEquals(350,repo.readVideo(scope,video)!!.storedRoots)
            OfflineCommentLimitOverrides.set(app,scope,video,null)
            val inherited=OfflineCommentLimitOverrides.effective(app,scope,video,OfflineCommentPreferences.limit(null))
            assertEquals(200,inherited)
            assertEquals(333,OfflineCommentLimitOverrides.effective(app,scope,video,333))
            repo.register(scope,video,"",OfflineCommentBudget.Quotas(inherited),"zh-hans")
            assertEquals(200,repo.readVideo(scope,video)!!.snapshot.rows.size)
            assertEquals(350,repo.readVideo(scope,video)!!.storedRoots)
        }
        CommentRepository(app,file).use { repo ->
            assertEquals(350,repo.readVideo(scope,video)!!.storedRoots)
            assertNull(OfflineCommentLimitOverrides.savedLimit(app,scope,video))
        }
    }
    @Test fun globalCountResetReturns200ButVideoInheritanceRemainsNull() {
        var result:Int?=-1
        OfflineCommentLimitDialog.show(app,333) { result=it }
        var dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(200,result)
        OfflineCommentLimitDialog.show(app,333,inherited=true) { result=it }
        dialog=ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(Looper.getMainLooper()).idle()
        assertNull(result)
    }
    @Test fun replyResetReturns1000And15WithoutApplyingBeforeUserClicks() {
        var result:Pair<Int,Int>?=null
        OfflineCommentLimitDialog.replies(app,2000,50) { total,thread -> result=total to thread }
        assertNull(result)
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1000 to 15,result)
    }
}
