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
    @org.junit.Before fun newHostSession() {
        for (name in listOf("scopes", "resources", "handles")) {
            (HookRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        }
        (HookRuntime::class.java.getDeclaredField("features").apply { isAccessible = true }.get(null) as android.os.Bundle).clear()
    }
    private fun errors(): String {
        val snapshot = HookRuntime::class.java.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(HookRuntime) as android.os.Bundle
        return snapshot.getBundle("features")!!.keySet().joinToString("\n") { name ->
            "$name ${snapshot.getBundle("features")!!.getBundle(name)?.getString("error")}"
        }
    }
    class Language { companion object { @JvmStatic fun target(): String = "en" } }

    enum class FrameState { EXPANDED, HIDDEN }
    class Renderer { @JvmField val root: android.widget.FrameLayout? = null; @JvmField var video = "" }
    class Frame { @JvmField var text = ""; @JvmField var state = FrameState.EXPANDED }
    class ViewModel { fun getAwemeId() = "test-video" }
    class CaptionAssembly {
        @JvmField val renderer = Renderer()
        fun bindFrame(frame: Frame) {}
        fun viewModel() = ViewModel()
        fun onViewCreated(view: android.view.View) {}
    }

    @Test fun verifiedFramesInstallWithoutRendererMethodOrRedundantVideoFieldMapping() {
        HookRuntime.start("subtitle-frame-registration-test") { _, _ -> }
        val resolved = ExtensionSymbols::class.java.getDeclaredField("resolved").apply { isAccessible = true }
        val previous = resolved.get(null) as Properties
        val publish = ExtensionSymbols::class.java.getDeclaredMethod("publish", Properties::class.java).apply { isAccessible = true }
        val installed = mutableListOf<Executable>()
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
                                    if (handle.name == "unhook") { installed.remove(point); null } else throw AssertionError(handle.name)
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
        val module = object : XposedModule() {}; module.attachFramework(framework) {}
        val assem = "com.ss.android.ugc.aweme.autocaption.refactor.CLACaptionAssemV2"
        fun descriptor(type: Class<*>) = "L${type.name.replace('.', '/')};"
        publish.invoke(ExtensionSymbols, Properties().apply {
            setProperty("class.com.ss.android.ugc.aweme.splash.SplashActivity", android.app.Activity::class.java.name)
            setProperty("class.X.0B1g", Renderer::class.java.name)
            setProperty("class.X.0B14", Frame::class.java.name)
            setProperty("class.$assem", CaptionAssembly::class.java.name)
            setProperty("X.0B1g:field.LIZJ", "root"); setProperty("X.0B1g:field.LJI", "!unresolved")
            setProperty("X.0B14:field.LIZ", "text"); setProperty("X.0B14:field.LIZIZ", "state")
            setProperty("$assem:field.LLLLLILLIL", "renderer")
            setProperty("$assem:method.Sr()Lcom/ss/android/ugc/aweme/autocaption/refactor/CLACaptionViewModelV2;", "viewModel()${descriptor(ViewModel::class.java)}")
            setProperty("$assem:method.bs(LX/0B14;)V", "bindFrame(${descriptor(Frame::class.java)})V")
        })
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = if (name == "com.ss.android.ugc.aweme.splash.SplashActivity") android.app.Activity::class.java else super.loadClass(name)
        }
        try {
            HookRuntime.install("SubtitleFeature") { SubtitleFeature.install(module, loader, "SubtitleFeature") }
            assertTrue(errors(), HookRuntime.scope("SubtitleFeature").active)
            assertTrue(errors(), HookRuntime.scope("SubtitleCleanFrames").active)
            assertTrue(installed.any { it.declaringClass == CaptionAssembly::class.java && it.name == "bindFrame" })
            assertFalse(installed.any { it.declaringClass == Renderer::class.java })
        } finally { publish.invoke(ExtensionSymbols, previous) }
    }

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
