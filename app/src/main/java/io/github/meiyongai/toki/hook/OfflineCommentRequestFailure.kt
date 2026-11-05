package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.IdentityHashMap

/** Numeric network status and cause types only. Never records exception messages, URLs, request logs or headers. */
internal object OfflineCommentRequestFailure {
    fun describe(stage: String, error: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val kinds = mutableListOf<String>(); var value: Throwable? = error; var http: Int? = null; var network: Int? = null
        while (value != null && seen.add(value) && kinds.size < 8) {
            val current = value
            kinds += current.javaClass.simpleName.take(64)
            fun code(name: String): Int? = try {
                current.javaClass.getMethod(name).takeIf { it.returnType == Int::class.javaPrimitiveType && it.parameterCount == 0 }
                    ?.invoke(current) as? Int
            } catch (_: ReflectiveOperationException) { null }
            code("getStatusCode")?.let { http = it }
            code("getCronetInternalErrorCode")?.let { network = it }
            value = if (current is InvocationTargetException) current.targetException else current.cause
        }
        return "stage=" + stage + " cause=" + kinds.joinToString(">") +
            (http?.let { " http=$it" } ?: "") + (network?.let { " network=$it" } ?: "")
    }
}
