package io.github.meiyongai.toki.subtitle.provider

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.ParcelFileDescriptor
import io.github.meiyongai.toki.subtitle.model.FontTestFixture
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SubtitleFontProviderTest {
    private lateinit var provider: SubtitleFontProvider
    private lateinit var asset: SubtitleFontStore.Asset
    @Before fun prepare() {
        provider = Robolectric.buildContentProvider(SubtitleFontProvider::class.java).create().get()
        val context = checkNotNull(provider.context)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.zhiliaoapp.musically"
            applicationInfo = ApplicationInfo().apply { packageName = "com.zhiliaoapp.musically"; uid = 12051 }
        })
        ShadowBinder.setCallingUid(android.os.Process.myUid())
        asset = SubtitleFontStore.importFont(context, "Private.ttf", ByteArrayInputStream(FontTestFixture.font())) {}
    }
    @After fun finish() { ShadowBinder.setCallingUid(android.os.Process.myUid()) }
    @Test fun managerAndKnownHostCanReadTheSameRegisteredBytesWithIdentityRestored() {
        for (uid in listOf(android.os.Process.myUid(), 12051)) {
            ShadowBinder.setCallingUid(uid)
            ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(SubtitleFontStore.uri(asset.id), "r")).use {
                assertArrayEquals(FontTestFixture.font(), it.readBytes())
            }
            assertEquals(uid, Binder.getCallingUid())
            assertEquals("font/ttf", provider.getType(SubtitleFontStore.uri(asset.id)))
            val recent = checkNotNull(provider.call("recent", null, null).getString("fonts"))
            assertTrue(recent.contains(asset.id)); assertFalse(recent.contains(asset.file.absolutePath))
        }
    }
    @Test fun unrelatedUidCannotReadOrEnumerateFonts() {
        ShadowBinder.setCallingUid(22052)
        assertThrows(SecurityException::class.java) { provider.openFile(SubtitleFontStore.uri(asset.id), "r") }
        assertThrows(SecurityException::class.java) { provider.getType(SubtitleFontStore.uri(asset.id)) }
        assertThrows(SecurityException::class.java) { provider.call("recent", null, null) }
    }
    @Test fun noCallerGetsWriteOrDeleteAccess() {
        val before = asset.file.readBytes(); val uri = SubtitleFontStore.uri(asset.id)
        for (mode in listOf("w", "rw", "wa", "rwt", "")) assertThrows(SecurityException::class.java) { provider.openFile(uri, mode) }
        assertThrows(SecurityException::class.java) { provider.insert(uri, null) }
        assertThrows(SecurityException::class.java) { provider.update(uri, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.delete(uri, null, null) }
        assertArrayEquals(before, asset.file.readBytes())
    }
    @Test fun unknownIdentifiersOrMutatedPrivateCopiesAreNotPublished() {
        assertThrows(java.io.FileNotFoundException::class.java) { provider.openFile(SubtitleFontStore.uri("b".repeat(64)), "r") }
        asset.file.appendBytes(byteArrayOf(0))
        assertThrows(IllegalStateException::class.java) { provider.openFile(SubtitleFontStore.uri(asset.id), "r") }
    }
}
