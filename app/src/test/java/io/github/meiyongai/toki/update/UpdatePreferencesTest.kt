package io.github.meiyongai.toki.subtitle.update

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [29, 35])
class UpdatePreferencesTest {
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
    @Test fun fullStateAndUnicodeNotesSurviveNewStoreInstance() {
        val prefs = prefs(); val release = AppRelease(ReleaseVersion(2, 1, 24), "v2.1.24", "新功能\n修复 Bug")
        val state = UpdateState(99_000, release, ReleaseVersion(2, 1, 24))
        UpdatePreferences(prefs).write(state); assertEquals(state, UpdatePreferences(prefs).read())
        assertSame(UpdatePreferences(prefs).lock, UpdatePreferences(prefs).lock)
    }
    @Test fun updateStateNeverTouchesFeatureSettingsOrOtherPreferenceKeys() {
        val prefs = prefs(); prefs.edit().putString("existing", "preserved").commit()
        val feature = RuntimeEnvironment.getApplication().getSharedPreferences("feature-private-test", Context.MODE_PRIVATE)
        feature.edit().putBoolean("sim_spoof_enabled", true).putInt("comment_limit", 333).commit()
        val before = feature.all.toMap(); UpdatePreferences(prefs).write(UpdateState(123_000))
        assertEquals("preserved", prefs.getString("existing", null)); assertEquals(before, feature.all)
    }
    @Test fun absentStateIsFreshButCorruptionIsNotSilentlyOverwritten() {
        val prefs = prefs(); assertEquals(UpdateState(), UpdatePreferences(prefs).read())
        prefs.edit().putString("state", "broken original").commit()
        val engine = UpdateEngine(ReleaseVersion(2, 1, 23), UpdatePreferences(prefs), ReleaseSource { error("not allowed") })
        assertEquals(UpdateResult.Failed, engine.check(false)); assertEquals("broken original", prefs.getString("state", null))
    }
    @Test fun failedCommitThrowsInsteadOfPretendingThrottleWasSaved() {
        val prefs = prefs()
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { UpdatePreferences(failing).write(UpdateState(123)) }
    }
}
