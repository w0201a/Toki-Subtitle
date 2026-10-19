package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Properties

/** Independently cached extension contracts. Unknown versions require unique structural matches. */
internal object ExtensionSymbols {
    private const val MATCHER_REVISION = "relationships-v3"
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
    fun load(identity: String, directory: File): Boolean {
        publish(Properties())
        if (identity in known) {
            publish(ExtensionDexIndex.identity(reference))
            HookRuntime.event("ExtensionSymbols", "verified reference build; extension contracts ready")
            return true
        }
        val stored = HostSymbolCache.read(cache(directory))
        if (!cacheMatches(stored, identity, referenceHash)) return false
        publish(stored)
        report(stored, "cached")
        return true
    }
    internal fun cacheMatches(stored: Properties, identity: String, profile: String): Boolean =
        stored.getProperty("cache.identity") == identity && stored.getProperty("cache.profile") == profile &&
            stored.getProperty("cache.format") == ExtensionDexIndex.FORMAT &&
            stored.getProperty("cache.matcher") == MATCHER_REVISION && stored.getProperty("cache.complete") == "true"

    /** Called only on the existing background adaptation worker; never scans on a UI thread. */
    fun scan(paths: List<String>, identity: String, directory: File) {
        val result = try { ExtensionDexIndex.scan(paths, reference, HostNativeIndex.extensionCandidates(paths, reference)) } catch (error: Exception) {
            // A failed optional scanner must not prevent the core feature cache from being published.
            Properties().apply { setProperty("error.scanner", error.javaClass.simpleName) }
        }
        check(HostDexIndex.identity(paths) == identity) { "Host changed during extension discovery" }
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
