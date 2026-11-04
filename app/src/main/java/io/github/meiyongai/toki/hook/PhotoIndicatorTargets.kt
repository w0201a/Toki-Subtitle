package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import java.lang.reflect.Modifier

/** Photo indicator lifecycle roots can be the whole carousel: only inspected indicator children may be hidden. */
internal class PhotoIndicatorTargets(private val contracts: List<Contract>) {
    internal class Contract(val type: Class<*>, names: List<String>) {
        private val fields = names.map { name -> type.getDeclaredField(name).apply {
            check(View::class.java.isAssignableFrom(this.type) && !Modifier.isStatic(modifiers))
            isAccessible = true
        } }
        fun read(owner: Any): List<View> = fields.mapNotNull { it.get(owner) as? View }
    }

    fun targets(owner: Any, lifecycleRoot: View): Set<View> {
        val result = linkedSetOf<View>()
        for (contract in contracts) {
            if (!contract.type.isInstance(owner)) continue
            for (candidate in contract.read(owner)) {
                // No fallback to the callback root, even if a changed host aliases a field to it.
                if (candidate === lifecycleRoot) continue
                var cursor: View? = candidate
                var depth = 0
                while (cursor != null && cursor !== lifecycleRoot && depth++ < 64) cursor = cursor.parent as? View
                if (cursor === lifecycleRoot) result.add(candidate)
            }
        }
        return result
    }

    companion object {
        fun resolve(loader: ClassLoader, unavailable: (String, Exception) -> Unit): PhotoIndicatorTargets {
            val prefix = "com.ss.android.ugc.aweme.ui.feed.photos.assem."
            val contracts = mapOf(
                "PhotoNumIndicatorAssem" to listOf("LLJJLIIIJLLLLLLLZ"),
                "AbsPhotosDotIndicatorAssem" to listOf("LLLF", "LLLILZLLLI"),
            ).mapNotNull { (name, fields) ->
                try { Contract(Class.forName(prefix + name, false, loader), fields) }
                catch (error: ReflectiveOperationException) { unavailable(name, error); null }
                catch (error: IllegalStateException) { unavailable(name, error); null }
            }
            // PhotoSwipeHintAssem owns pager listeners/scroller, not a hideable indicator view.
            return PhotoIndicatorTargets(contracts)
        }
    }
}
