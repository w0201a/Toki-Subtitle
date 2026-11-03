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
}
