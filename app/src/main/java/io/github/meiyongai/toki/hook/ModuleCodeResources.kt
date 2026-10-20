package io.github.meiyongai.toki.subtitle.hook

import java.io.InputStream
import java.util.zip.ZipFile

/** LSPatch loads DEX from shared memory; Java resources need the original module APK. */
internal object ModuleCodeResources {
    @Volatile var apkPath: String? = null
    fun open(owner: Class<*>, name: String): InputStream? {
        owner.getResourceAsStream(name)?.let { return it }
        val path = apkPath ?: return null
        ZipFile(path).use { archive ->
            val entry = archive.getEntry(name.removePrefix("/")) ?: return null
            require(entry.size in 0..8_388_608) { "Module resource exceeds budget" }
            return archive.getInputStream(entry).use { it.readBytes().inputStream() }
        }
    }
}
