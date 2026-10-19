package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SubtitleRegistrationTest {
    class Language { companion object { @JvmStatic fun target(): String = "en" } }

    @Test fun missingExcludedListAndDisplayContractsDoNotRollBackTheValidTargetLanguage() {
        HookRuntime.start("subtitle-registration-test") { _, _ -> }
        val resolved = ExtensionSymbols::class.java.getDeclaredField("resolved").apply { isAccessible = true }
        val previous = resolved.get(null)
        val installed = mutableListOf<Executable>()
        var unhooks = 0
        val framework = Proxy.newProxyInstance(XposedInterface::class.java.classLoader, arrayOf(XposedInterface::class.java)) { _, method, args ->
            when (method.name) {
                "hook" -> {
                    val point = args!![0] as Executable
                    Proxy.newProxyInstance(method.returnType.classLoader, arrayOf(method.returnType)) { builder, call, _ ->
                        when (call.name) {
                            "setExceptionMode", "setPriority" -> builder
                            "intercept" -> {
                                installed += point
                                Proxy.newProxyInstance(call.returnType.classLoader, arrayOf(call.returnType)) { _, handle, _ ->
                                    if (handle.name == "unhook") { unhooks++; null } else throw AssertionError(handle.name)
                                }
                            }
                            else -> throw AssertionError(call.name)
                        }
                    }
                }
                "log" -> null
                else -> throw AssertionError(method.name)
            }
        } as XposedInterface
        val module = object : XposedModule() {}
        module.attachFramework(framework) {}
        resolved.set(null, Properties().apply {
            val descriptor = "L${Language::class.java.name.replace('.', '/')};"
            setProperty("captionLanguage.target", "$descriptor->target")
            setProperty("captionLanguage.excluded", "Lmissing/ExcludedLanguages;->read")
        })
        try {
            HookRuntime.install("SubtitleFeature") { SubtitleFeature.install(module, javaClass.classLoader!!, "SubtitleFeature") }
            assertEquals(listOf(Language::class.java.getDeclaredMethod("target")), installed)
            assertEquals(0, unhooks)
            assertTrue(HookRuntime.scope("SubtitleLanguage").active)
            assertFalse(HookRuntime.scope("SubtitleExcludedLanguages").active)
            assertFalse(HookRuntime.scope("SubtitleFeature").active)
            // The menu was independently attempted even though no renderer exists.
            assertFalse(HookRuntime.scope("SubtitleMenu").active)
        } finally { resolved.set(null, previous) }
    }
}
