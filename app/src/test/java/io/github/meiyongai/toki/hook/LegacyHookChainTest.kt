package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class LegacyHookChainTest {
    class Host { fun value(text: String) = text }
    private val method = Host::class.java.getDeclaredMethod("value",String::class.java)
    @Test fun allTokiInterceptorsRunInPriorityOrderAndOriginalRunsOnce() {
        val events=mutableListOf<String>();var count=0
        val chain=LegacyHookChain(method) { _,args -> count++;events.add("original");args[0] }
        chain.register(10,"low") { next -> events.add("low-before");val result=next.proceed();events.add("low-after");result }
        chain.register(20,"high") { next -> events.add("high-before");val result=next.proceed(arrayOf("translated"));events.add("high-after");result }
        assertEquals("translated",chain.invoke(Host(),arrayOf("source")))
        assertEquals(listOf("high-before","low-before","original","low-after","high-after"),events)
        assertEquals(1,count)
    }
    @Test fun originalHostThrowableIsPreservedAndNotRetried() {
        val hostError=IllegalArgumentException("host");var count=0
        val chain=LegacyHookChain(method) { _,_ -> count++;throw hostError }
        chain.register(50,null) { it.proceed() }
        assertSame(hostError,assertThrows(IllegalArgumentException::class.java) { chain.invoke(Host(),arrayOf("text")) })
        assertEquals(1,count)
    }
    @Test fun shortCircuitUnhookAndReplacementWorkWithoutDuplicateOriginal() {
        var count=0
        val chain=LegacyHookChain(method) { _,args -> count++;args[0] }
        val handle=chain.register(50,"translation") { "cached" }
        assertEquals("cached",chain.invoke(Host(),arrayOf("source")));assertEquals(0,count)
        handle.replaceHook { it.proceed(arrayOf("fresh")) }
        assertEquals("fresh",chain.invoke(Host(),arrayOf("source")));assertEquals(1,count)
        handle.unhook()
        assertEquals("source",chain.invoke(Host(),arrayOf("source")));assertEquals(2,count)
    }
    @Test fun replacementReceiverAndArgumentsReachRemainingInterceptors() {
        val target=Host()
        val chain=LegacyHookChain(method) { receiver,args -> assertSame(target,receiver);args[0] }
        chain.register(100,null) { it.proceedWith(target,arrayOf("new")) }
        chain.register(50,null) { assertSame(target,it.thisObject);assertEquals("new",it.getArg(0));it.proceed() }
        assertEquals("new",chain.invoke(Host(),arrayOf("old")))
    }
}
