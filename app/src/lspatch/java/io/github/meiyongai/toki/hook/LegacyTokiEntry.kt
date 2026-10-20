package io.github.meiyongai.toki.subtitle.hook

import android.app.Application
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Traditional entry used by LSPatch integrated modules; modern builds omit it. */
class LegacyTokiEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var modulePath: String? = null
    private val loaded = ConcurrentHashMap.newKeySet<String>()
    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) { modulePath=param.modulePath }
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if(param.packageName !in setOf(TokiModule.PKG_TIKTOK_GLOBAL,TokiModule.PKG_TIKTOK_TRILL) ||
            param.processName != param.packageName || !loaded.add(param.processName)) return
        installPreloaderWait(param)
        android.util.Log.i("TokiLegacy", "entry callback received")
        XposedBridge.hookMethod(Application::class.java.getDeclaredMethod("attach", android.content.Context::class.java),
            object : XC_MethodHook() {
                override fun afterHookedMethod(hook: MethodHookParam) {
                    val app = hook.thisObject as? Application ?: return
                    if (app.packageName == param.packageName) start(app, param)
                }
            })
        // LSPatch loads modules after Application.attach. Do not wait on an
        // Instrumentation callback which its loader may already have passed.
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(hook: MethodHookParam) {
                val app=hook.args[0] as? Application ?: return
                if(app.packageName == param.packageName) start(app,param)
            }
        }
        XposedBridge.hookMethod(Instrumentation::class.java.getMethod("callApplicationOnCreate",Application::class.java),callback)
        // Also cover an injector attaching after Application creation.
        val current = runCatching { Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Application }.getOrNull()
        if(current?.packageName == param.packageName) start(current,param)
        XposedBridge.log("Toki legacy: entry loaded; waiting for host Application")
    }
    /** Exact-version recovery for the verified two-condition busy wait. */
    private fun installPreloaderWait(param: XC_LoadPackage.LoadPackageParam) {
        try {
            val verified = LegacyPreloaderContract.verify(param.appInfo.sourceDir) ?: return
            val owner = Class.forName(verified.owner, false, param.classLoader)
            val markerOwner = Class.forName(verified.markerOwner, false, param.classLoader)
            val marker = markerOwner.getDeclaredField(verified.marker).apply {
                check(type == java.lang.Long.TYPE && java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
            }
            // Class-loader preloading can enter the wait before modules are loaded.
            // Existing invocations cannot be replaced; yield at their repeated
            // Application.getBaseContext checkpoint until the same marker is ready.
            XposedBridge.hookMethod(android.content.ContextWrapper::class.java.getMethod("getBaseContext"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(hook: MethodHookParam) {
                        if (hook.thisObject !is Application || !Thread.currentThread().name.startsWith("AsyncTask")) return
                        if (hook.result != null && marker.getLong(null) != 0L) return
                        val waiting = Thread.currentThread().stackTrace.any {
                            it.className == verified.owner && it.methodName == verified.method
                        }
                        if (waiting) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        }
                    }
                })
            val method = owner.getDeclaredMethod(verified.method, Application::class.java)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(hook: MethodHookParam) {
                    val app = hook.args[0] as Application
                    var interrupted = false
                    try {
                        while (app.baseContext == null || marker.getLong(null) == 0L) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { interrupted = true }
                        }
                        hook.result = null
                    } finally { if (interrupted) Thread.currentThread().interrupt() }
                }
            })
            XposedBridge.log("Toki preloader: verified busy wait replaced with yielding wait")
        } catch (error: Throwable) { XposedBridge.log("Toki preloader guard unavailable: " + error.javaClass.simpleName) }
    }

    private var started=false
    // This build implements a framework facade, rather than attaching to a
    // framework-owned modern API. The API facade is bundled only in this variant.
    @android.annotation.SuppressLint("XposedInternalApi")
    @Synchronized private fun start(app: Application,param: XC_LoadPackage.LoadPackageParam) {
        if(started) return
        started=true
        try {
            val path=modulePath ?: javaClass.protectionDomain?.codeSource?.location?.path
                ?: error("LSPatch did not provide module APK path")
            ModuleCodeResources.apkPath = path
            val moduleInfo=checkNotNull(app.packageManager.getPackageArchiveInfo(path,0)?.applicationInfo) {
                "Cannot read integrated module APK"
            }.apply { sourceDir=path;publicSourceDir=path }
            val backend=LegacyFramework(moduleInfo)
            val module=TokiModule()
            module.attachFramework(backend.api) {}
            module.onModuleLoaded(object : XposedModuleInterface.ModuleLoadedParam {
                override fun isSystemServer()=false
                override fun getProcessName()=param.processName
            })
            val host=object : XposedModuleInterface.PackageReadyParam {
                override fun getPackageName()=param.packageName
                override fun getApplicationInfo()=param.appInfo
                override fun isFirstPackage()=true
                override fun getDefaultClassLoader()=param.classLoader
                override fun getClassLoader()=app.classLoader
                override fun getAppComponentFactory(): android.app.AppComponentFactory = android.app.AppComponentFactory()
            }
            // Explicitly bind an already-created Application; instrumentation is
            // currently executing, so registering that same hook would be too late.
            module.onLegacyApplication(app)
            module.onPackageReady(host)
            android.util.Log.i("TokiLegacy","LSPatch host initialized: "+param.packageName)
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var notified = false
                override fun onActivityResumed(activity: android.app.Activity) {
                    if (!notified) {
                        notified = true
                        android.widget.Toast.makeText(activity, "Toki 2.0.37 已加载 · 长按首页打开设置", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                override fun onActivityCreated(a: android.app.Activity,b: android.os.Bundle?) = Unit
                override fun onActivityStarted(a: android.app.Activity) = Unit
                override fun onActivityPaused(a: android.app.Activity) = Unit
                override fun onActivityStopped(a: android.app.Activity) = Unit
                override fun onActivityDestroyed(a: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(a: android.app.Activity,b: android.os.Bundle) = Unit
            })
        } catch(error: Throwable) {
            XposedBridge.log("Toki legacy initialization failed");XposedBridge.log(error)
            android.widget.Toast.makeText(app,"Toki 加载失败："+error.javaClass.simpleName,android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

internal class LegacyFramework(private val info: ApplicationInfo) {
    private val hooks=ConcurrentHashMap<Executable,LegacyHookChain>()
    val api: XposedInterface = Proxy.newProxyInstance(XposedInterface::class.java.classLoader,arrayOf(XposedInterface::class.java)) { _,method,args ->
        when(method.name) {
            "getModuleApplicationInfo" -> info
            "getRemotePreferences" -> throw UnsupportedOperationException("LSPatch uses host-private Toki settings")
            "getFrameworkName" -> "LSPatch legacy bridge"
            "getFrameworkVersion" -> "82"
            "getFrameworkVersionCode" -> 82L
            "getFrameworkProperties" -> 0L
            "deoptimize" -> false
            "log" -> { XposedBridge.log("Toki "+args?.getOrNull(1)+": "+args?.getOrNull(2));null }
            "hook" -> builder(args!![0] as Executable)
            else -> throw UnsupportedOperationException("Unsupported LSPatch bridge operation: "+method.name)
        }
    } as XposedInterface
    private fun builder(executable: Executable): XposedInterface.HookBuilder = object : XposedInterface.HookBuilder {
        private var priority=XposedInterface.PRIORITY_DEFAULT
        private var id: String?=null
        override fun setPriority(priority: Int)=apply { this.priority=priority }
        override fun setExceptionMode(mode: XposedInterface.ExceptionMode)=apply {
            require(mode==XposedInterface.ExceptionMode.PASSTHROUGH) { "Only passthrough is supported" }
        }
        override fun setId(id: String?)=apply { this.id=id }
        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            val chain=hooks.computeIfAbsent(executable) { member ->
                val chain=LegacyHookChain(member) { receiver,arguments ->
                    try { XposedBridge.invokeOriginalMethod(member,receiver,arguments) }
                    catch(error: InvocationTargetException) { throw error.targetException }
                }
                XposedBridge.hookMethod(member,object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try { param.result=chain.invoke(param.thisObject,param.args) }
                        catch(error: Throwable) { param.throwable=error }
                    }
                })
                chain
            }
            return chain.register(priority,id,hooker)
        }
    }
}
