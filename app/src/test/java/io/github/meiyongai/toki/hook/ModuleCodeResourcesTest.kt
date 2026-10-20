package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleCodeResourcesTest {
    @Test fun memoryLoadedDexCanReadProfileFromModuleArchive() {
        val archive=File.createTempFile("toki-module-profile", ".zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("unique-test-profile.tsv"));out.write("verified profile".toByteArray());out.closeEntry()
        }
        try {
            ModuleCodeResources.apkPath=archive.absolutePath
            assertEquals("verified profile",ModuleCodeResources.open(javaClass,"/unique-test-profile.tsv")!!.bufferedReader().use { it.readText() })
            assertNull(ModuleCodeResources.open(javaClass,"/absent-resource.tsv"))
        } finally { ModuleCodeResources.apkPath=null }
    }
}
