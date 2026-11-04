package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The obfuscated outer gate/selector are admitted only for the inspected original player DEX. */
internal class HdrSelectionContract(loader: ClassLoader) {
    private val request = Class.forName("X.06yB", false, loader)
    private val model = Class.forName("com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel", false, loader)
    private val bitrate = Class.forName("com.ss.android.ugc.playerkit.simapicommon.model.SimBitRate", false, loader)
    val select = Class.forName("X.070B", false, loader).getDeclaredMethod("LJFF", request).apply {
        check(!Modifier.isStatic(modifiers) && returnType.name == "X.070U"); isAccessible = true
    }
    val gate = Class.forName("X.03CW", false, loader).getDeclaredMethod("LJJJJJ").apply {
        check(Modifier.isStatic(modifiers) && returnType == Boolean::class.javaPrimitiveType && parameterCount == 0)
        isAccessible = true
    }
    private val video = request.getDeclaredField("LIZ").apply {
        check(type == model && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    private val rates = model.getMethod("getBitRate").apply { check(List::class.java.isAssignableFrom(returnType)) }
    private val hdr = bitrate.getMethod("getHdrType").apply { check(returnType == Int::class.javaPrimitiveType) }
    data class Counts(val sdr: Int, val hdr: Int, val unknown: Int)
    fun counts(value: Any?): Counts {
        if (value == null || !request.isInstance(value)) return Counts(0, 0, 0)
        val source = video.get(value) ?: return Counts(0, 0, 0)
        val list = rates.invoke(source) as? List<*> ?: return Counts(0, 0, 0)
        var sdr = 0; var high = 0; var unknown = 0
        for (entry in list) {
            if (entry == null || !bitrate.isInstance(entry)) { unknown++; continue }
            val kind = hdr.invoke(entry) as Int
            if (kind == 0) sdr++ else if (kind > 0) high++ else unknown++
        }
        return Counts(sdr, high, unknown)
    }

    companion object {
        private val expected = mapOf(
            "classes2.dex" to "ae46f96919190b1a2d99757ef2b72fd32bcfafc016ce7ff06f1101784dbb5dd1",
            "classes4.dex" to "2757ca393a37b513c91d4cbea971b9933e6bee17985a6cbb4d9f31c9b2f60c10")
        fun verify(context: Context): Boolean = verifyPaths(listOf(context.applicationInfo.sourceDir) +
            context.applicationInfo.splitSourceDirs.orEmpty(), expected)
        internal fun verifyPaths(paths: List<String>, hashes: Map<String, String>): Boolean {
            if (hashes.isEmpty()) return false
            return try {
                val matched = mutableSetOf<String>()
                for (path in paths) ZipFile(path).use { zip ->
                    for ((name, expectedHash) in hashes) {
                        val entry = zip.getEntry(name) ?: continue
                        if (name in matched || entry.size !in 1..32L * 1024 * 1024) return false
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            var total = 0L; val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) return false
                                val n = input.read(buffer); if (n < 0) break
                                total += n; if (total > 32L * 1024 * 1024) return false
                                digest.update(buffer, 0, n)
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        if (actual != expectedHash) return false
                        matched.add(name)
                    }
                }
                matched == hashes.keys
            } catch (_: Exception) { false }
        }
    }
}
