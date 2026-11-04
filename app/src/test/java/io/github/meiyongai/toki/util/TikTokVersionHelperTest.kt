package io.github.meiyongai.toki.subtitle.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TikTokVersionHelperTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun install(name: String, version: String, code: Long) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name; versionName = version; longVersionCode = code
            applicationInfo = ApplicationInfo().apply { packageName = name }
        })
    }
    @Test fun missingHostsRetainPackageIdentity() {
        val hosts = TikTokVersionHelper.getInstalledTikTokStatuses(context)
        assertEquals(listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), hosts.map { it.packageName })
        assertTrue(hosts.none { it.isInstalled })
        assertFalse(TikTokVersionHelper.getInstalledTikTokStatus(context).isInstalled)
    }
    @Test fun bothInstalledHostsKeepTheirActualVersions() {
        install("com.zhiliaoapp.musically", "47.6.3", 476030L)
        install("com.ss.android.ugc.trill", "47.5.3", 475030L)
        val hosts = TikTokVersionHelper.getInstalledTikTokStatuses(context)
        assertTrue(hosts.all { it.isInstalled })
        assertEquals(listOf("47.6.3", "47.5.3"), hosts.map { it.versionName })
        assertEquals(listOf(476030L, 475030L), hosts.map { it.versionCode })
        assertEquals(hosts[0], TikTokVersionHelper.getInstalledTikTokStatus(context))
    }
    @Test fun trillAloneRemainsSelectableWithoutPretendingGlobalIsInstalled() {
        install("com.ss.android.ugc.trill", "47.6.3", 476030L)
        val hosts = TikTokVersionHelper.getInstalledTikTokStatuses(context)
        assertFalse(hosts[0].isInstalled); assertTrue(hosts[1].isInstalled)
        assertEquals(hosts[1], TikTokVersionHelper.getInstalledTikTokStatus(context))
    }
    @Test fun unrelatedPackagesAreNeverListed() {
        install("example.unrelated", "47.6.3", 1L)
        assertEquals(2, TikTokVersionHelper.getInstalledTikTokStatuses(context).size)
        assertTrue(TikTokVersionHelper.getInstalledTikTokStatuses(context).none { it.isInstalled })
    }
}
