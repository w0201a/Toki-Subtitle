package io.github.meiyongai.toki.subtitle.provider

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DiagnosticOverviewTest {
    @Test fun environmentRevisionWaitingCannotBeMistakenForSuccessfulCurrentApplication() {
        val row = registered().apply { putString("state", "环境配置待应用"); putBoolean("appliedEnabled", true) }
        assertEquals("准备中／等待条件", DiagnosticOverview.featureState(row))
    }

    private fun registered() = Bundle().apply {
        putBoolean("requestedEnabled", true); putBoolean("appliedEnabled", true); putInt("registered", 4); putString("state", "已注册")
    }
    @Test fun missingReplyAndMissingFeatureNeverBecomeFalseDisabledOrWorking() {
        val text = DiagnosticOverview.render(Bundle(), 1000)
        assertTrue(text.contains("没有收到回复")); assertTrue(text.contains("不能给该宿主的功能下成功结论"))
        assertEquals("未收到该功能报告", DiagnosticOverview.featureState(null))
        assertEquals("请求状态未知", DiagnosticOverview.featureState(Bundle()))
    }
    @Test fun readinessIsDifferentFromBusinessSuccessAndRestartOrErrorsTakePrecedence() {
        val ready = registered()
        assertTrue(DiagnosticOverview.featureState(ready).contains("实际业务结果另看操作摘要"))
        ready.putBoolean("restartRequired", true)
        assertTrue(DiagnosticOverview.featureState(ready).contains("需重开"))
        ready.putString("errorStatus", "注册失败")
        assertTrue(DiagnosticOverview.featureState(ready).contains("异常"))
        ready.remove("errorStatus"); ready.putBoolean("restartRequired", false); ready.putBoolean("appliedEnabled", false)
        assertEquals("请求开启，但未实际应用", DiagnosticOverview.featureState(ready))
    }
    @Test fun revisionMismatchOldReportAndMissingOperationsAreSpelledOut() {
        val feature = registered().apply { putLong("appliedRevision", 2) }
        val host = Bundle().apply {
            putString("process", "com.zhiliaoapp.musically"); putLong("updated", 1000); putLong("configRevision", 5)
            putBundle("features", Bundle().apply { putBundle("OfflineVideoHook", feature) })
        }
        val text = DiagnosticOverview.render(Bundle().apply { putBundle("com.zhiliaoapp.musically", host) }, 100000)
        assertTrue(text.contains("配置版本不一致")); assertTrue(text.contains("快照超过60秒"))
        assertTrue(text.contains("宿主未提供新版操作摘要")); assertTrue(text.contains("离线视频导出"))
        assertTrue(text.contains("缺失不默认视为关闭"))
    }
    @Test fun detailsAreRedactedAndDisabledWithAppliedTrueIsNotClaimedClosed() {
        val row = registered().apply {
            putBoolean("requestedEnabled", false); putString("detail", "https://private.example/video token=hidden")
        }
        assertTrue(DiagnosticOverview.featureState(row).contains("需核查"))
        val report = Bundle().apply { putBundle("features", Bundle().apply { putBundle("OfflineVideoHook", row) }) }
        val text = DiagnosticOverview.render(Bundle().apply { putBundle("com.zhiliaoapp.musically", report) })
        assertFalse(text.contains("private.example"))
    }

    @Test fun disabledRequestWithoutAReleaseReceiptRemainsUnconfirmed() {
        val row = Bundle().apply { putBoolean("requestedEnabled", false); putString("state", "已注册") }
        assertTrue(DiagnosticOverview.featureState(row).contains("尚未收到关闭／恢复回执"))
    }
    @Test fun quickOverviewShowsOldExecutionAndKeepsItSeparateFromRegistration() {
        val receipt = ExecutionEvidence(ExecutionEvidence.Scope.MENU, ExecutionEvidence.Action.APPLY, ExecutionEvidence.Outcome.NEXT_READ, 2, true)
        val report = Bundle().apply {
            putLong("configRevision", 3)
            putBundle("features", Bundle().apply { putBundle("SpeedOptions", registered().apply { putBundle("execution", receipt.encode()) }) })
        }
        val text = DiagnosticOverview.render(Bundle().apply { putBundle("com.zhiliaoapp.musically", report) })
        assertTrue(text.indexOf("四项一眼状态") < text.indexOf("全部功能配置"))
        assertTrue(text.contains("旧配置执行证据")); assertTrue(text.contains("等待后续原生菜单读取"))
        assertTrue(text.contains("宿主未提供新版操作概览"))
    }
}
