package io.github.meiyongai.toki.subtitle.ui

import android.graphics.Typeface
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SubtitleFontResolverTest {
    @Test fun repeatedFramesShareOneDeferredLoadAndUpdateAllCurrentViews() {
        val work = arrayListOf<Runnable>(); val posts = arrayListOf<Runnable>(); var reads = 0
        val resolver = SubtitleFontResolver({ _, _, _ -> reads++; Typeface.MONOSPACE }, { work += it }, { posts += it }, { 0 })
        val first = TextView(RuntimeEnvironment.getApplication()); val second = TextView(first.context)
        repeat(100) { first.typeface = resolver.resolve(first, "sans-serif", 0, "a".repeat(64)) }
        second.typeface = resolver.resolve(second, "sans-serif", 0, "a".repeat(64))
        assertEquals(1, work.size); assertEquals(0, reads)
        work.removeAt(0).run(); assertEquals(1, reads); assertEquals(1, posts.size)
        posts.removeAt(0).run()
        assertSame(Typeface.MONOSPACE, first.typeface); assertSame(Typeface.MONOSPACE, second.typeface)
        assertSame(Typeface.MONOSPACE, resolver.resolve(first, "sans-serif", 0, "a".repeat(64)))
        assertTrue(work.isEmpty())
    }
    @Test fun lateCompletionCannotOverwriteAChangedAssetOrSystemSelection() {
        val work = arrayListOf<Runnable>(); val posts = arrayListOf<Runnable>()
        val resolver = SubtitleFontResolver({ _, id, _ -> if (id.startsWith('a')) Typeface.SERIF else Typeface.MONOSPACE }, { work += it }, { posts += it }, { 0 })
        val view = TextView(RuntimeEnvironment.getApplication())
        view.typeface = resolver.resolve(view, "sans-serif", 0, "a".repeat(64))
        val system = resolver.resolve(view, "sans-serif", 0, "b".repeat(64)); view.typeface = system
        work.removeAt(0).run(); posts.removeAt(0).run(); assertSame(system, view.typeface)
        view.typeface = resolver.resolve(view, "serif", 0, "")
        val chosen = view.typeface
        work.removeAt(0).run(); posts.removeAt(0).run(); assertSame(chosen, view.typeface)
    }
    @Test fun failedFileAccessFallsBackAndRetriesOnlyAfterTheBoundedDelay() {
        val work = arrayListOf<Runnable>(); val posts = arrayListOf<Runnable>(); var now = 0L; var failing = true
        val resolver = SubtitleFontResolver({ _, _, _ -> if (failing) error("Temporarily unavailable") else Typeface.MONOSPACE }, { work += it }, { posts += it }, { now })
        val view = TextView(RuntimeEnvironment.getApplication())
        view.typeface = resolver.resolve(view, "sans-serif", 0, "a".repeat(64))
        work.removeAt(0).run(); posts.removeAt(0).run()
        now = 4999; resolver.resolve(view, "sans-serif", 0, "a".repeat(64)); assertTrue(work.isEmpty())
        now = 5000; failing = false; resolver.resolve(view, "sans-serif", 0, "a".repeat(64))
        assertEquals(1, work.size); work.removeAt(0).run(); posts.removeAt(0).run()
        assertSame(Typeface.MONOSPACE, view.typeface)
    }
    @Test fun arbitraryPathValuesNeverReachTheFontLoader() {
        var loads = 0
        val resolver = SubtitleFontResolver({ _, _, _ -> loads++; Typeface.DEFAULT }, { it.run() }, { it.run() }, { 0 })
        val view = TextView(RuntimeEnvironment.getApplication())
        resolver.resolve(view, "sans-serif", 0, "../other.ttf")
        assertEquals(0, loads)
    }
}
