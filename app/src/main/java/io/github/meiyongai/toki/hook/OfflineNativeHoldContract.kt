package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.graphics.Rect
import android.view.View
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Inspected matured native hold, not a global toast/menu override or a relaxed overlay hit test. */
internal class OfflineNativeHoldContract(runnable: Class<*>, private val surface: Class<*>) {
    constructor(loader: ClassLoader) : this(Class.forName("X.05pB", false, loader), Class.forName("X.05p5", false, loader))
    init { check(Runnable::class.java.isAssignableFrom(runnable) && View::class.java.isAssignableFrom(surface)) }
    val run = runnable.getDeclaredMethod("run").apply {
        check(returnType == Void.TYPE && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val owner = runnable.getDeclaredField("LL").apply {
        check(type == surface && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    fun surface(instance: Any): View? = (owner.get(instance) as? View)?.takeIf(surface::isInstance)

    companion object {
        /** Selected card identity + visible same-window descendant; adjacent/offscreen cards never qualify. */
        internal fun belongs(surface: View, selectedRoot: View, player: View, window: View): Boolean {
            if (surface === selectedRoot || !surface.isEnabled || !surface.isShown || !surface.isAttachedToWindow ||
                !selectedRoot.isShown || !selectedRoot.isAttachedToWindow || !player.isShown || !player.isAttachedToWindow ||
                surface.rootView !== window || selectedRoot.rootView !== window || player.rootView !== window) return false
            fun descendant(view: View): Boolean {
                var cursor: View? = view; var depth = 0
                while (cursor != null && cursor !== selectedRoot && depth++ < 64) cursor = cursor.parent as? View
                return cursor === selectedRoot
            }
            val rect = Rect()
            return descendant(surface) && descendant(player) && surface.getGlobalVisibleRect(rect) && !rect.isEmpty &&
                player.getGlobalVisibleRect(rect) && !rect.isEmpty
        }
        fun verify(context: Context): Boolean = try {
            val matched = mutableSetOf<String>()
            val wanted = mapOf("classes4.dex" to "2757ca393a37b513c91d4cbea971b9933e6bee17985a6cbb4d9f31c9b2f60c10",
                "classes5.dex" to "60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff")
            val info = context.applicationInfo
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path -> ZipFile(path).use { zip ->
                for ((name, expected) in wanted) {
                    val entry = zip.getEntry(name) ?: continue
                    if (name in matched || entry.size !in 1..32L * 1024 * 1024) return false
                    val hash = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        val bytes = ByteArray(128 * 1024); var total = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) return false
                            val n = input.read(bytes); if (n < 0) break
                            total += n; if (total > 32L * 1024 * 1024) return false
                            hash.update(bytes,0,n)
                        }
                    }
                    if (hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != expected) return false
                    matched.add(name)
                }
            } }
            matched == wanted.keys
        } catch (_: Exception) { false }
    }
}
