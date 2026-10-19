package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Properties

/** Independently cached extension contracts. Unknown versions require unique structural matches. */
internal object ExtensionSymbols {
    private const val MATCHER_REVISION = "relationships-v8"
    private val reference: Properties by lazy {
        Properties().apply { checkNotNull(ExtensionSymbols::class.java.getResourceAsStream("/toki-extension-profile.properties")).use(::load) }
    }
    private val referenceHash by lazy { profileHash(reference) }
    internal fun profileHash(profile: Properties): String =
        HostDexIndex.digest(profile.stringPropertyNames().filterNot { it.startsWith("native.") }
            .sorted().joinToString("\n") { "$it=${profile.getProperty(it)}" })
    private val known = setOf(
        "0259ca5f53357042b72fe6502ca22875aa3035506bfc458fb645146abb19dc16",
        "a104c3b28e2f4ee94292a0dcc464ff114e63f430e760bfd093c434cc6298a129")
    @Volatile private var resolved = Properties()
    @Volatile private var reverse = emptyMap<String, String>()
    private fun publish(value: Properties) {
        reverse = value.stringPropertyNames().filter { it.startsWith("class.") }.associate {
            value.getProperty(it) to it.removePrefix("class.")
        }
        resolved = value
    }
    private fun cache(directory: File) = File(directory, "subtitle-extension-symbols.properties")
    fun load(identity: String, directory: File, retryToken: String = ""): Boolean {
        publish(Properties())
        if (identity in known && retryToken.isEmpty()) {
            publish(ExtensionDexIndex.identity(reference))
            HookRuntime.event("ExtensionSymbols", "verified reference build; extension contracts ready")
            return true
        }
        val stored = HostSymbolCache.read(cache(directory))
        if (!cacheMatches(stored, identity, referenceHash, retryToken)) return false
        publish(stored)
        report(stored, "cached")
        return true
    }
    internal fun cacheMatches(stored: Properties, identity: String, profile: String, retryToken: String = ""): Boolean =
        stored.getProperty("cache.retryToken", "") == retryToken &&
        stored.getProperty("cache.identity") == identity && stored.getProperty("cache.profile") == profile &&
            stored.getProperty("cache.format") == ExtensionDexIndex.FORMAT &&
            stored.getProperty("cache.matcher") == MATCHER_REVISION && stored.getProperty("cache.complete") == "true"

    /** Called only on the existing background adaptation worker; never scans on a UI thread. */
    fun scan(paths: List<String>, identity: String, directory: File, retryToken: String = "") {
        val session = HostScanController.session
        val showProgress = session.status.phase == HostScanPhase.EXTENSIONS
        if (showProgress) session.extensionProgress("native", 0, 0)
        // The native prefilter is an optimization, not a dependency of local analysis.
        val candidates = try { HostNativeIndex.extensionCandidates(paths, reference) } catch (error: Exception) {
            HookRuntime.event("ExtensionSymbols", "native prefilter unavailable=${error.javaClass.simpleName}; using independent local DEX analysis")
            null
        } catch (error: OutOfMemoryError) {
            HookRuntime.event("ExtensionSymbols", "native prefilter exhausted its memory budget; using local DEX analysis")
            null
        } catch (error: LinkageError) {
            HookRuntime.event("ExtensionSymbols", "native prefilter unavailable=${error.javaClass.simpleName}; using independent local DEX analysis")
            null
        }
        val result = try { ExtensionDexIndex.scan(paths, reference, candidates) { stage, completed, total ->
            if (showProgress) session.extensionProgress(stage, completed, total)
        } } catch (error: Exception) {
            // A failed optional scanner must not prevent the core feature cache from being published.
            Properties().apply { setProperty("error.scanner", error.javaClass.simpleName) }
        } catch (error: OutOfMemoryError) {
            // A bounded scanner allocation failure must terminate the scan UI instead
            // of leaving it forever on EXTENSIONS. Do not catch unrelated VM errors.
            Properties().apply { setProperty("error.scanner", "memory-budget-exceeded") }
        }
        check(HostDexIndex.identity(paths) == identity) { "Host changed during extension discovery" }
        result.setProperty("cache.retryToken", retryToken)
        result.setProperty("cache.identity", identity)
        result.setProperty("cache.profile", referenceHash)
        result.setProperty("cache.format", ExtensionDexIndex.FORMAT)
        result.setProperty("cache.matcher", MATCHER_REVISION)
        result.setProperty("cache.complete", "true")
        HostSymbolCache.write(cache(directory), result)
        publish(result)
        report(result, "discovered")
    }
    private fun report(result: Properties, stage: String) {
        val classes = result.stringPropertyNames().count { it.startsWith("class.") }
        val failures = result.stringPropertyNames().count { it.startsWith("error.") }
        HookRuntime.event("ExtensionSymbols", "$stage classes=$classes unresolvedContracts=$failures; unique structural contracts only")
    }
    fun captionOriginalVisibility(loader: ClassLoader): Method? {
        val value = resolved.getProperty("captionLanguage.originalVisibility") ?: return null
        val owner = value.substringBefore("->")
        val type = Class.forName(owner.substring(1, owner.length - 1).replace('/', '.'), false, loader)
        val aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader)
        return type.getDeclaredMethod(value.substringAfter("->"), aweme, java.lang.Boolean::class.java).apply {
            check(java.lang.reflect.Modifier.isStatic(modifiers) && returnType == Boolean::class.javaPrimitiveType)
            isAccessible = true
        }
    }
    fun captionLanguage(loader: ClassLoader, target: Boolean): Method? {
        val encoded = resolved.getProperty("captionLanguage.${if (target) "target" else "excluded"}") ?: return null
        val descriptor = encoded.substringBefore("->")
        check(descriptor.startsWith("L") && descriptor.endsWith(";"))
        val type = Class.forName(descriptor.substring(1, descriptor.length - 1).replace('/', '.'), false, loader)
        return type.getDeclaredMethod(encoded.substringAfter("->")).apply {
            check(java.lang.reflect.Modifier.isStatic(modifiers) && parameterCount == 0 &&
                returnType == if (target) String::class.java else Array<String>::class.java)
            isAccessible = true
        }
    }
    fun commentGate(loader: ClassLoader): List<Method>? {
        val owner = resolved.getProperty("commentGate.class") ?: return null
        val type = Class.forName(owner, false, loader)
        val methods = checkNotNull(resolved.getProperty("commentGate.methods")).split('\n').map { encoded ->
            val signature = encoded.substringAfter('(')
            val parameters = when (signature) {
                ")Z" -> emptyArray<Class<*>>()
                "Z)Z" -> arrayOf(Boolean::class.javaPrimitiveType!!)
                else -> error("Invalid comment gate signature")
            }
            type.getDeclaredMethod(encoded.substringBefore('('), *parameters).apply {
                check(java.lang.reflect.Modifier.isStatic(modifiers) && returnType == Boolean::class.javaPrimitiveType)
                isAccessible = true
            }
        }
        check(methods.size >= 2 && methods.distinct().size == methods.size)
        return methods
    }
    private fun baseline(type: Class<*>): String = reverse[type.name] ?: type.name

    fun type(loader: ClassLoader, name: String): Class<*> {
        if (!reference.containsKey("class.$name")) return Class.forName(name, false, loader)
        val actual = checkNotNull(resolved.getProperty("class.$name")) { "$name: no unique structural adaptation" }
        return Class.forName(actual, false, loader)
    }
    private fun descriptor(type: Class<*>): String = when {
        type.isArray -> "[" + descriptor(type.componentType!!)
        type.isPrimitive -> mapOf(java.lang.Boolean.TYPE to "Z", java.lang.Byte.TYPE to "B", java.lang.Character.TYPE to "C",
            java.lang.Short.TYPE to "S", Integer.TYPE to "I", java.lang.Long.TYPE to "J", java.lang.Float.TYPE to "F",
            java.lang.Double.TYPE to "D", Void.TYPE to "V").getValue(type)
        else -> "L${baseline(type).replace('.', '/')};"
    }
    /** Resolve the complete verified descriptor without requiring separately mapped parameter classes. */
    fun methodByRole(type: Class<*>, name: String): Method {
        val prefix = "${baseline(type)}:method.$name("
        val roles = reference.stringPropertyNames().filter { it.startsWith(prefix) }
        check(roles.size == 1) { "Ambiguous or missing method role: $prefix" }
        val actual = checkNotNull(resolved.getProperty(roles.single())) { "$prefix: method structure unavailable" }
        fun actualDescriptor(value: Class<*>): String = when {
            value.isArray -> value.name.replace('.', '/')
            value.isPrimitive -> descriptor(value)
            else -> "L${value.name.replace('.', '/')};"
        }
        return type.declaredMethods.single { method ->
            method.name + method.parameterTypes.joinToString("", "(", ")") { actualDescriptor(it) } + actualDescriptor(method.returnType) == actual
        }.apply { isAccessible = true }
    }
    fun method(type: Class<*>, name: String, parameters: Array<out Class<*>>, declared: Boolean): Method {
        val owner = baseline(type)
        val prefix = "$owner:method.$name(${parameters.joinToString("") { descriptor(it) }})"
        val keys = reference.stringPropertyNames().filter { it.startsWith(prefix) }
        val actual = if (keys.isEmpty()) name else {
            check(keys.size == 1) { "Ambiguous reference method: $prefix" }
            checkNotNull(resolved.getProperty(keys.single())) { "$prefix: method structure unavailable" }.substringBefore('(')
        }
        return if (declared) type.getDeclaredMethod(actual, *parameters) else type.getMethod(actual, *parameters)
    }
    fun field(type: Class<*>, name: String, declared: Boolean): Field {
        val key = "${baseline(type)}:field.$name"
        val actual = if (!reference.containsKey(key)) name else
            checkNotNull(resolved.getProperty(key)) { "$key: field structure unavailable" }
        return if (declared) type.getDeclaredField(actual) else type.getField(actual)
    }
    fun methodNamed(method: Method, name: String): Boolean {
        val owner = baseline(method.declaringClass)
        val keys = reference.stringPropertyNames().filter { it.startsWith("$owner:method.$name(") }
        return if (keys.isEmpty()) method.name == name else keys.any {
            resolved.getProperty(it)?.substringBefore('(') == method.name
        }
    }
    fun fieldNamed(field: Field, name: String): Boolean {
        val key = "${baseline(field.declaringClass)}:field.$name"
        return if (!reference.containsKey(key)) field.name == name else resolved.getProperty(key) == field.name
    }
}

internal fun ClassLoader.adaptiveClass(name: String): Class<*> = ExtensionSymbols.type(this, name)
internal fun Class<*>.adaptiveDeclaredMethod(name: String, vararg parameters: Class<*>?): Method =
    ExtensionSymbols.method(this, name, parameters.map { requireNotNull(it) }.toTypedArray(), true)
internal fun Class<*>.adaptiveMethod(name: String, vararg parameters: Class<*>?): Method =
    ExtensionSymbols.method(this, name, parameters.map { requireNotNull(it) }.toTypedArray(), false)
internal fun Class<*>.adaptiveDeclaredField(name: String): Field = ExtensionSymbols.field(this, name, true)
internal fun Class<*>.adaptiveField(name: String): Field = ExtensionSymbols.field(this, name, false)
