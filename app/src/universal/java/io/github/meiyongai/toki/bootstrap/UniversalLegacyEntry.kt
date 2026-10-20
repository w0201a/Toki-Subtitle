package io.github.meiyongai.toki.subtitle.bootstrap

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import android.app.Application
import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

/** Primary APK has modern entry and a legacy bootstrap, but no framework API definitions. */
class UniversalLegacyEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var path: String? = null
    private var started = false
    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) { path=param.modulePath }
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if(param.packageName !in setOf("com.zhiliaoapp.musically","com.ss.android.ugc.trill") || param.processName!=param.packageName) return
        // A modern framework owns this API; let its normal entry initialize once.
        if (runCatching { Class.forName("io.github.libxposed.api.XposedModule", false, javaClass.classLoader) }.isSuccess) {
            XposedBridge.log("Toki unified: modern framework available, legacy bootstrap skipped")
            return
        }
        val callback=object : XC_MethodHook() {
            override fun afterHookedMethod(hook: MethodHookParam) {
                val app=hook.thisObject as Application
                if(app.packageName==param.packageName) launch(app,param)
            }
        }
        XposedBridge.hookMethod(Application::class.java.getDeclaredMethod("attach",Context::class.java),callback)
        val current=runCatching { Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Application }.getOrNull()
        if(current?.packageName==param.packageName) launch(current,param)
    }
    @Synchronized private fun launch(app: Application,param: XC_LoadPackage.LoadPackageParam) {
        if(started) return
        started=true
        try {
            val modulePath=checkNotNull(path) { "Missing module path" }
            val dir=File(app.codeCacheDir,"toki-runtime").apply { check(isDirectory || mkdirs()) }
            val payload=ZipFile(modulePath).use { archive ->
                val entry=checkNotNull(archive.getEntry("assets/toki/legacy-payload.apk"))
                val file=File(dir,"legacy-${entry.crc}.apk")
                if(!file.isFile) {
                    file.outputStream().use { out ->
                        check(file.setReadOnly())
                        archive.getInputStream(entry).use { it.copyTo(out) }
                    }
                }
                check(file.length()==entry.size) { "Incomplete embedded runtime" }
                file
            }
            val loader=object : DexClassLoader(payload.absolutePath,dir.absolutePath,null,javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    // Inner and outer R8 outputs may assign the same short name to
                    // unrelated classes. Isolate all bundled dependencies, while
                    // sharing Android/JDK and the framework's traditional API.
                    if (!name.startsWith("java.") && !name.startsWith("javax.") &&
                        !name.startsWith("android.") && !name.startsWith("dalvik.") &&
                        !name.startsWith("de.robv.android.xposed.")) {
                        synchronized(this) {
                            val loaded=findLoadedClass(name) ?: try { findClass(name) }
                                catch (_: ClassNotFoundException) { return super.loadClass(name, resolve) }
                            if(resolve) resolveClass(loaded)
                            return loaded
                        }
                    }
                    return super.loadClass(name,resolve)
                }
            }
            val entry=loader.loadClass("io.github.meiyongai.toki.subtitle.hook.LegacyTokiEntry").getDeclaredConstructor().newInstance()
            val startup=IXposedHookZygoteInit.StartupParam::class.java.getDeclaredConstructor().apply { isAccessible=true }.newInstance()
            startup.modulePath=payload.absolutePath
            (entry as IXposedHookZygoteInit).initZygote(startup)
            (entry as IXposedHookLoadPackage).handleLoadPackage(param)
            XposedBridge.log("Toki unified legacy runtime initialized")
        } catch(error: Throwable) {
            XposedBridge.log(error)
            android.widget.Toast.makeText(app,"Toki 注入加载失败：${error.javaClass.simpleName}",android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
