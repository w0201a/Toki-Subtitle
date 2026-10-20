package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.CopyOnWriteArrayList

/** One legacy callback per member, with all of Toki's interceptors in order. */
internal class LegacyHookChain(private val executable: Executable,
    private val original: (Any?, Array<out Any?>) -> Any?
) {
    private class Entry(val priority: Int, val sequence: Long, val id: String?, var hooker: XposedInterface.Hooker)
    private val entries = CopyOnWriteArrayList<Entry>()
    @Volatile private var dispatch = emptyArray<XposedInterface.Hooker>()
    private fun rebuild() {
        dispatch = entries.sortedWith(compareByDescending<Entry> { it.priority }.thenBy { it.sequence })
            .map { it.hooker }.toTypedArray()
    }
    @Synchronized private fun remove(entry: Entry) { entries.remove(entry); rebuild() }
    @Synchronized private fun replace(entry: Entry, hooker: XposedInterface.Hooker) {
        check(entry in entries); entry.hooker = hooker; rebuild()
    }
    @Synchronized fun register(priority: Int, id: String?, hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
        val entry = Entry(priority, nextSequence++, id, hooker)
        entries.add(entry)
        rebuild()
        return object : XposedInterface.HookHandle {
            override fun getExecutable(): Executable = this@LegacyHookChain.executable
            override fun getId() = id
            override fun unhook() { remove(entry) }
            override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                replace(entry, hooker);return this
            }
        }
    }
    private var nextSequence = 0L
    fun invoke(receiver: Any?, arguments: Array<out Any?>): Any? {
        val snapshot = dispatch
        fun call(index: Int, target: Any?, args: Array<out Any?>): Any? {
            if(index == snapshot.size) return original(target,args)
            return snapshot[index].intercept(object : XposedInterface.Chain {
                private var argumentList: List<Any?>? = null
                override fun getExecutable(): Executable = this@LegacyHookChain.executable
                override fun getThisObject() = target
                override fun getArgs(): List<Any?> = argumentList ?: args.asList().also { argumentList = it }
                override fun getArg(index: Int): Any? = args[index]
                override fun proceed() = call(index+1,target,args)
                override fun proceed(args: Array<out Any?>) = call(index+1,target,args)
                override fun proceedWith(thisObject: Any) = call(index+1,thisObject,args)
                override fun proceedWith(thisObject: Any, args: Array<out Any?>) = call(index+1,thisObject,args)
            })
        }
        return call(0,receiver,arguments)
    }
}
