package io.github.meiyongai.toki.subtitle.hook

import java.lang.ref.WeakReference

/** Preparation of a neighbouring holder must never replace the selected playback owner. */
internal class CaptionPlaybackSelection {
    private var holder = WeakReference<Any>(null)
    var video: String = ""
        private set
    fun select(instance: Any, id: String) {
        holder = WeakReference(instance)
        video = id
    }
    fun owns(instance: Any?, id: String): Boolean = id.isNotBlank() && video == id && holder.get() === instance
    fun current(id: String): Boolean = video == id && holder.get() != null
    fun clear() { video = ""; holder.clear() }
}
