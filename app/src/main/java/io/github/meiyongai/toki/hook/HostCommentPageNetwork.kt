package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit native authenticated comment-list request; no offline cache manager or cache-clear job is called. */
internal class HostCommentPageNetwork(private val loader: ClassLoader) {
    private val reflected = lazy { Contract(loader) }
    fun fetch(context: Context, reader: HostOfflineComments, video: String, model: Any, cursor: Long,
        account: String, remote: LocalOnlyRequest, operation: CommentPageOperation,
        complete: (Result<Any>) -> Unit) {
        val once = AtomicBoolean(false)
        fun deliver(result: Result<Any>) {
            if (once.compareAndSet(false, true) && operation.active) complete(result)
        }
        try {
            check(operation.active && remote.permitsNetwork())
            check(CommentPageCodeGate.verify(context)) { "Unsupported native comment request code" }
            check(reader.accountMatches(account)) { "Account changed before comment request" }
            require(cursor >= 0)
            val contract = reflected.value
            require(contract.aweme.isInstance(model) && contract.id.invoke(model) == video)
            fun consumer(action: (Any?) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(contract.consumer)) { proxy, method, args ->
                when (method.name) {
                    "accept" -> { action(args?.firstOrNull()); null }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TokiCommentPageConsumer"
                    else -> null
                }
            }
            check(operation.active && remote.permitsNetwork() && reader.accountMatches(account))
            val observable = checkNotNull(contract.request.invoke(null, video, cursor, 20, "", 0, 0, model, null,
                arrayListOf<String>(), null, -1L, contract.sceneValue, null, false, null, false, null, null))
            check(operation.active && remote.permitsNetwork() && reader.accountMatches(account))
            val subscription = contract.subscribe.invoke(observable,
                consumer { response ->
                    if (!operation.active || !remote.permitsNetwork()) return@consumer
                    val result = try {
                        check(reader.accountMatches(account) && contract.response.isInstance(response))
                        Result.success(checkNotNull(response))
                    } catch (error: Exception) { Result.failure(error) }
                    deliver(result)
                }, consumer { error ->
                    deliver(Result.failure((error as? Throwable) ?: IllegalStateException("Comment request failed")))
                })
            operation.attach(AutoCloseable { if (subscription != null) contract.dispose.invoke(subscription) })
        } catch (error: Exception) { deliver(Result.failure(error)) }
        catch (error: LinkageError) { deliver(Result.failure(error)) }
    }

    private class Contract(private val loader: ClassLoader) {
        private fun type(name: String) = Class.forName(name, false, loader)
        val aweme = type("com.ss.android.ugc.aweme.feed.model.Aweme")
        val id = aweme.getMethod("getAid").apply { check(returnType == String::class.java) }
        val response = type("com.ss.android.ugc.aweme.comment.model.CommentItemList")
        private val observable = type("X.02tZ")
        val consumer = type("X.02tr").apply { check(isInterface) }
        private val disposable = type("X.02ti").apply { check(isInterface) }
        val dispose = disposable.getMethod("dispose").apply { check(returnType == Void.TYPE) }
        val subscribe = observable.getMethod("LJJZZI", consumer, consumer).apply {
            check(returnType == disposable && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        private val scene = type("X.0F2U")
        val sceneValue: Int = scene.getMethod("getValue").invoke(
            scene.enumConstants.single { (it as Enum<*>).name == "DEFAULT" }) as Int
        val request = type("X.0F2w").getDeclaredMethod("LIZ", String::class.java, java.lang.Long.TYPE,
            java.lang.Integer.TYPE, String::class.java, java.lang.Integer.TYPE, java.lang.Integer.TYPE, aweme,
            String::class.java, List::class.java, String::class.java, java.lang.Long::class.java,
            java.lang.Integer::class.java, type("kotlin.jvm.functions.Function0"), java.lang.Boolean.TYPE,
            java.lang.Integer::class.java, java.lang.Boolean.TYPE, String::class.java, java.lang.Long::class.java).apply {
                check(returnType == observable && Modifier.isStatic(modifiers)); isAccessible = true
            }
    }
}
