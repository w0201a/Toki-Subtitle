package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 投票按题目、选项逐条翻译；绑定代次隔离复用，原始模型只读。主线程持有状态。 */
internal class PollTranslationQueue(
    private val translate: (String, (String?) -> Unit) -> AutoCloseable,
    private val render: (Any, Int, String) -> Unit,
    private val report: (String) -> Unit = {},
    private val retryFailures: Boolean = false,
) : AutoCloseable {
    private class Binding(key: Any, val texts: List<String>) {
        val key = WeakReference(key)
        val results = mutableMapOf<Int, String>()
    }
    private class Task(val binding: Binding, val index: Int) {
        var retries = 0
        var subscription: AutoCloseable? = null
        var timeout: Runnable? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val bindings = WeakHashMap<Any, Binding>()
    private val cache = LinkedHashMap<String, String>(128, .75f, true)
    private val pending = ArrayDeque<Task>()
    private var active: Task? = null
    private var enabled = true
    private var closed = false
    private val pump = Runnable { next() }

    fun bind(key: Any, texts: List<String>) {
        if (closed) return
        val old = bindings[key]
        val binding = if (old != null && old.texts == texts) old else Binding(key, texts.toList()).also {
            bindings[key] = it
            pending.removeAll { task -> task.binding === old }
            if (active?.binding === old) release()
        }
        if (!enabled) return
        binding.texts.forEachIndexed { index, text ->
            val result = binding.results[index] ?: cache[text]
            if (result != null) {
                binding.results[index] = result
                renderSafely(key, index, result)
            } else if (text.length in 1..1000 && text.any(Char::isLetter) &&
                !(active?.let { it.binding === binding && it.index == index } ?: false) &&
                pending.none { it.binding === binding && it.index == index } && pending.size < 64) {
                pending += Task(binding, index)
            }
        }
        main.post(pump)
    }

    fun unbind(key: Any) {
        val binding = bindings.remove(key) ?: return
        pending.removeAll { it.binding === binding }
        if (active?.binding === binding) release()
        main.post(pump)
    }

    fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        if (!value) {
            main.removeCallbacks(pump)
            pending.clear()
            release()
            bindings.entries.toList().forEach { (key, binding) ->
                binding.texts.forEachIndexed { index, text -> renderSafely(key, index, text) }
            }
        } else bindings.entries.toList().forEach { (key, binding) -> bind(key, binding.texts) }
    }

    private fun renderSafely(key: Any, index: Int, text: String) {
        try { render(key, index, text) } catch (error: Exception) {
            report("display unavailable type=${error.javaClass.simpleName}")
            unbind(key)
        }
    }

    private fun current(task: Task): Any? = task.binding.key.get()?.takeIf {
        enabled && !closed && bindings[it] === task.binding
    }

    private fun next() {
        if (!enabled || closed || active != null) return
        while (pending.isNotEmpty()) {
            val task = pending.removeFirst()
            val key = current(task) ?: continue
            val text = task.binding.texts[task.index]
            cache[text]?.let {
                task.binding.results[task.index] = it
                renderSafely(key, task.index, it)
            } ?: run {
                active = task
                task.timeout = Runnable { finish(task, null) }.also { main.postDelayed(it, 6000) }
                report("request chars=${text.length} pending=${pending.size}")
                try {
                    val subscription = translate(text) { result -> main.post { finish(task, result) } }
                    if (active === task) task.subscription = subscription else subscription.close()
                } catch (_: Exception) { finish(task, null) }
                return
            }
        }
    }

    private fun finish(task: Task, result: String?) {
        if (active !== task || closed) return
        release()
        val text = result?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4000 }
        if (text != null) {
            cache[task.binding.texts[task.index]] = text
            while (cache.size > 128) cache.remove(cache.keys.first())
            current(task)?.let {
                task.binding.results[task.index] = text
                renderSafely(it, task.index, text)
            }
        }
        if (text == null && retryFailures && task.retries == 0 && current(task) != null) {
            task.retries++
            pending.addLast(task)
        }
        report(if (text == null) "unavailable; original retained" else "success")
        main.removeCallbacks(pump)
        main.postDelayed(pump, if (text == null) 1000 else 250)
    }

    private fun release() {
        val task = active ?: return
        active = null
        task.timeout?.let(main::removeCallbacks)
        try { task.subscription?.close() } catch (_: Exception) { }
    }

    override fun close() {
        setEnabled(false)
        closed = true
        bindings.clear()
        cache.clear()
    }
}
