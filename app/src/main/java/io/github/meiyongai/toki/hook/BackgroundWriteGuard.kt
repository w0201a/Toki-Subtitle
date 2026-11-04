package io.github.meiyongai.toki.subtitle.hook

import android.graphics.drawable.Drawable
import android.view.View
import java.util.IdentityHashMap

/** setBackground delegates to setBackgroundDrawable; capture the outer native intent exactly once per view. */
internal class BackgroundWriteGuard {
    private val active = IdentityHashMap<View, Unit>()
    fun apply(view: View, input: Drawable?, transform: (Drawable?) -> Drawable?,
        proceed: (Drawable?) -> Any?): Any? {
        if (active.containsKey(view)) return proceed(input)
        active[view] = Unit
        try { return proceed(transform(input)) } finally { active.remove(view) }
    }
}
