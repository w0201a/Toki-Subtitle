package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** An explicit reply page runs in its own native coroutine scope, whose Job cancels Retrofit's suspended call. */
internal class HostCommentReplyNetwork(private val loader: ClassLoader) {
    private val reflected = lazy { Contract(loader) }
    fun fetch(context: Context, reader: HostOfflineComments, video: String, model: Any, root: String, cursor: Long,
        account: String, remote: LocalOnlyRequest, operation: CommentPageOperation, complete: (Result<Any>) -> Unit) {
        val once = AtomicBoolean(false)
        fun deliver(result: Result<Any>) { if (operation.active && once.compareAndSet(false, true)) complete(result) }
        try {
            check(operation.active && remote.permitsNetwork() && reader.accountMatches(account))
            check(CommentPageCodeGate.verify(context)) { "Unsupported native reply request code" }
            require(root.isNotBlank() && root.length <= 128 && cursor >= 0)
            val c = reflected.value
            require(c.aweme.isInstance(model) && c.videoId.invoke(model) == video)
            val scope = checkNotNull(c.scope.invoke(null, c.dispatcher.get(null)))
            operation.attach(AutoCloseable { c.cancelScope.invoke(null, scope, CancellationException("Toki reply request cancelled")) })
            check(operation.active && remote.permitsNetwork() && reader.accountMatches(account))
            val unit = c.unit.get(null)
            val suspended = c.suspended.invoke(null)
            fun result(value: Any?) {
                val checked = try {
                    c.checkFailure.invoke(null, value)
                    check(remote.permitsNetwork() && reader.accountMatches(account) && c.response.isInstance(value))
                    Result.success(checkNotNull(value))
                } catch (error: Exception) { Result.failure((error as? InvocationTargetException)?.targetException ?: error) }
                deliver(checked)
            }
            val block = Proxy.newProxyInstance(loader, arrayOf(c.function)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        val continuation = checkNotNull(args?.getOrNull(1))
                        val resumed = AtomicBoolean(false)
                        val bridge = Proxy.newProxyInstance(loader, arrayOf(c.continuation)) { continuationProxy, invoked, values ->
                            when (invoked.name) {
                                "getContext" -> c.getContext.invoke(continuation)
                                "resumeWith" -> {
                                    if (resumed.compareAndSet(false, true)) {
                                        try { result(values?.firstOrNull()) }
                                        finally {
                                            try { c.resume.invoke(continuation, unit) } catch (error: Exception) {
                                                HookRuntime.event("OfflineVideoHook", "reply continuation completion unavailable type=" + error.javaClass.simpleName)
                                            }
                                        }
                                    }
                                    null
                                }
                                "equals" -> continuationProxy === values?.firstOrNull()
                                "hashCode" -> System.identityHashCode(continuationProxy)
                                "toString" -> "TokiReplyContinuation"
                                else -> null
                            }
                        }
                        try {
                            check(operation.active && remote.permitsNetwork() && reader.accountMatches(account))
                            val service = c.service.get(null)
                            val response = c.request.invoke(service, root, cursor, 20, "", video, "", 0, "", false, "", 0,
                                "offline_mode_page", "", c.nonPersonalized.invoke(c.privacy.get(null)), null, null, null, -1L,
                                null, null, c.sceneValue, null, null, bridge)
                            if (response === suspended) suspended else { result(response); unit }
                        } catch (error: Exception) {
                            deliver(Result.failure((error as? InvocationTargetException)?.targetException ?: error)); unit
                        } catch (error: LinkageError) { deliver(Result.failure(error)); unit }
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TokiReplyPageBlock"
                    else -> null
                }
            }
            c.launch.invoke(null, scope, null, null, block, 3)
        } catch (error: Exception) { deliver(Result.failure(error)) }
          catch (error: LinkageError) { deliver(Result.failure(error)) }
    }

    private class Contract(private val loader: ClassLoader) {
        private fun type(name: String) = Class.forName(name, false, loader)
        val aweme = type("com.ss.android.ugc.aweme.feed.model.Aweme")
        val videoId = aweme.getMethod("getAid").apply { check(returnType == String::class.java) }
        val response = type("com.ss.android.ugc.aweme.comment.model.CommentItemList")
        val continuation = type("X.03Sp").apply { check(isInterface) }
        private val context = type("kotlin.coroutines.CoroutineContext")
        val getContext = continuation.getMethod("getContext").apply { check(returnType == context) }
        val resume = continuation.getMethod("resumeWith", Any::class.java).apply { check(returnType == Void.TYPE) }
        val function = type("kotlin.jvm.functions.Function2").apply { check(isInterface) }
        private val scopeType = type("X.03IS")
        val scope = type("X.03IR").getDeclaredMethod("LIZ", context).apply {
            check(returnType == type("X.03IT") && Modifier.isStatic(modifiers)); isAccessible = true
        }
        val cancelScope = type("X.03IR").getDeclaredMethod("LIZJ", scopeType, CancellationException::class.java).apply {
            check(returnType == Void.TYPE && Modifier.isStatic(modifiers)); isAccessible = true
        }
        val dispatcher = type("X.037l").getDeclaredField("LIZJ").apply { check(Modifier.isStatic(modifiers)); isAccessible = true }
        val launch = type("X.03T3").getDeclaredMethod("LIZLLL", scopeType, context, type("X.03Tr"), function, java.lang.Integer.TYPE).apply {
            check(returnType == type("X.03Ty") && Modifier.isStatic(modifiers)); isAccessible = true
        }
        val unit = type("kotlin.Unit").getDeclaredField("LIZ").apply { check(Modifier.isStatic(modifiers)); isAccessible = true }
        val suspended = type("X.03YT").getDeclaredMethod("LJFF").apply { check(returnType == Any::class.java); isAccessible = true }
        val checkFailure = type("X.03YW").getDeclaredMethod("LIZIZ", Any::class.java).apply { check(returnType == Void.TYPE); isAccessible = true }
        private val api = type("com.ss.android.ugc.aweme.commentv2.commentlist.CommentApiV2")
        val service = api.getDeclaredField("LIZIZ").apply { check(type == api && Modifier.isStatic(modifiers)); isAccessible = true }
        val request = api.getDeclaredMethod("LJIJJ", String::class.java, java.lang.Long.TYPE, java.lang.Integer.TYPE,
            String::class.java, String::class.java, String::class.java, java.lang.Integer.TYPE, String::class.java,
            java.lang.Boolean.TYPE, String::class.java, java.lang.Integer.TYPE, String::class.java, String::class.java,
            java.lang.Boolean.TYPE, java.lang.Integer::class.java, java.lang.Integer::class.java, java.lang.Float::class.java,
            java.lang.Long::class.java, String::class.java, String::class.java, java.lang.Integer::class.java,
            String::class.java, String::class.java, continuation).apply {
                check(returnType == Any::class.java && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        private val privacyType = type("X.0F2o")
        val privacy = privacyType.getDeclaredField("LIZIZ").apply {
            check(type == privacyType && Modifier.isStatic(modifiers)); isAccessible = true
        }
        val nonPersonalized = privacyType.getDeclaredMethod("LJ").apply {
            check(returnType == java.lang.Boolean.TYPE && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        private val scene = type("X.0F2U")
        val sceneValue = scene.getMethod("getValue").invoke(scene.enumConstants.single { (it as Enum<*>).name == "OFFLINE_COMMENT" }) as Int
    }
}
