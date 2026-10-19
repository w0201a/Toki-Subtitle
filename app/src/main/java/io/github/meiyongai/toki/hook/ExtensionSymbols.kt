package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Properties

/** Exact 47.4.3 compatibility table for the 2.0.15 implementation. No speculative matching. */
internal object ExtensionSymbols {
    private val profile: Properties by lazy {
        Properties().apply { checkNotNull(ExtensionSymbols::class.java.getResourceAsStream("/toki-4743-members.properties")).use(::load) }
    }
    @Volatile private var enabled = false
    @Volatile private var reverse = emptyMap<String, String>()
    fun initialize(paths: List<String>) = selectIdentity(HostDexIndex.identity(paths))
    internal fun selectIdentity(identity: String) {
        enabled = identity == profile.getProperty("host.identity")
        reverse = if (!enabled) emptyMap() else profile.stringPropertyNames().filter {
            it.startsWith("class.") && profile.getProperty(it) != "!unresolved"
        }.associate { profile.getProperty(it) to it.removePrefix("class.") }
    }
    private fun mapped(key: String): String? = if (!enabled) null else profile.getProperty(key)?.takeUnless { it == "!unresolved" }
    private fun baseline(type: Class<*>): String = reverse[type.name] ?: type.name
    fun type(loader: ClassLoader, name: String): Class<*> {
        val actual = if (!enabled || !profile.containsKey("class.$name")) name else
            checkNotNull(mapped("class.$name")) { "$name: not verified for TikTok 47.4.3" }
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
        val keys = (if (enabled) profile.stringPropertyNames() else emptySet<String>()).filter { it.startsWith(prefix) }
        val actual = if (keys.isEmpty()) name else {
            check(keys.size == 1) { "Ambiguous reference method: $prefix" }
            checkNotNull(mapped(keys.single())) { "$prefix: method structure unavailable" }.substringBefore('(')
        }
        return if (declared) type.getDeclaredMethod(actual, *parameters) else type.getMethod(actual, *parameters)
    }
    fun field(type: Class<*>, name: String, declared: Boolean): Field {
        val key = "${baseline(type)}:field.$name"
        val actual = if (!enabled || !profile.containsKey(key)) name else
            checkNotNull(mapped(key)) { "$key: field structure unavailable" }
        return if (declared) type.getDeclaredField(actual) else type.getField(actual)
    }
    fun methodNamed(method: Method, name: String): Boolean {
        val owner = baseline(method.declaringClass)
        val keys = (if (enabled) profile.stringPropertyNames() else emptySet<String>()).filter { it.startsWith("$owner:method.$name(") }
        return if (keys.isEmpty()) method.name == name else keys.any {
            mapped(it)?.substringBefore('(') == method.name
        }
    }
    fun fieldNamed(field: Field, name: String): Boolean {
        val key = "${baseline(field.declaringClass)}:field.$name"
        return if (!enabled || !profile.containsKey(key)) field.name == name else mapped(key) == field.name
    }
}

internal fun ClassLoader.adaptiveClass(name: String): Class<*> = ExtensionSymbols.type(this, name)
internal fun Class<*>.adaptiveDeclaredMethod(name: String, vararg parameters: Class<*>?): Method =
    ExtensionSymbols.method(this, name, parameters.map { requireNotNull(it) }.toTypedArray(), true)
internal fun Class<*>.adaptiveMethod(name: String, vararg parameters: Class<*>?): Method =
    ExtensionSymbols.method(this, name, parameters.map { requireNotNull(it) }.toTypedArray(), false)
internal fun Class<*>.adaptiveDeclaredField(name: String): Field = ExtensionSymbols.field(this, name, true)
internal fun Class<*>.adaptiveField(name: String): Field = ExtensionSymbols.field(this, name, false)
