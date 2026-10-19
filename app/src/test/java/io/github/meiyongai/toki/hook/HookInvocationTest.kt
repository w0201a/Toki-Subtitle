package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

/** 拦截边界的异常身份与原始返回值测试，不依赖 Android 或模拟异常堆栈。 */
class HookInvocationTest {
    @Test fun callbackFailureAfterHostSuccessDoesNotRunHostTwice() {
        val call = HookInvocation()
        var executions = 0
        val original = Any()
        call.proceed { executions++; original }
        assertSame(original, call.recover(IllegalStateException("module")) { executions++; Any() })
        assertEquals(1, executions)
    }

    @Test fun callbackFailureBeforeHostInvocationFallsBackExactlyOnce() {
        val call = HookInvocation()
        var executions = 0
        assertEquals(7, call.recover(IllegalStateException("module")) { executions++; 7 })
        assertEquals(1, executions)
    }

    @Test fun wrappedHostFailureCannotTriggerASecondInvocation() {
        val call = HookInvocation()
        val host = IllegalStateException("host")
        assertThrows(IllegalStateException::class.java) { call.proceed { throw host } }
        val wrapper = RuntimeException("module", host)
        assertSame(wrapper, assertThrows(RuntimeException::class.java) { call.recover(wrapper) { fail("must not rerun host") } })
    }

    @Test fun aLaterHostFailureCannotReturnAnEarlierSuccessfulResult() {
        val call = HookInvocation()
        assertEquals(7, call.proceed { 7 })
        assertThrows(IllegalStateException::class.java) { call.proceed { throw IllegalStateException("host") } }
        val failure = RuntimeException("wrapper")
        assertSame(failure, assertThrows(RuntimeException::class.java) { call.recover(failure) { fail("no retry") } })
    }

    /** 验证返回值与 null 均原样返回。@return Unit。Callers: JUnit。 */
    @Test fun returnValuesAreUnchanged() {
        val call = HookInvocation()
        val result = Any()
        assertSame(result, call.proceed { result })
        assertNull(call.proceed { null })
    }

    /** 验证宿主失败不被识别为模块异常且保持对象身份。@return Unit。Callers: JUnit。 */
    @Test fun downstreamFailureIsRethrownAndAttributed() {
        val call = HookInvocation()
        val original = IllegalStateException("host")
        assertSame(original, assertThrows(IllegalStateException::class.java) { call.proceed { throw original } })
        assertTrue(call.fromDownstream(original))
    }

    /** 验证具有相同类型和消息的模块异常不会混同宿主异常。@return Unit。Callers: JUnit。 */
    @Test fun identityNotMessageDeterminesOrigin() {
        val call = HookInvocation()
        val host = IllegalStateException("same")
        assertThrows(IllegalStateException::class.java) { call.proceed { throw host } }
        assertFalse(call.fromDownstream(IllegalStateException("same")))
    }

    /** 验证模块主动包装宿主异常仍归属模块。@return Unit。Callers: JUnit。 */
    @Test fun wrapperIsAModuleFailure() {
        val call = HookInvocation()
        val host = IllegalStateException("host")
        assertThrows(IllegalStateException::class.java) { call.proceed { throw host } }
        assertFalse(call.fromDownstream(RuntimeException("module", host)))
    }

    /** 验证不同调用不共享异常记录。@return Unit。Callers: JUnit。 */
    @Test fun invocationsAreIndependent() {
        val call = HookInvocation()
        val error = IllegalStateException("host")
        assertThrows(IllegalStateException::class.java) { call.proceed { throw error } }
        assertFalse(HookInvocation().fromDownstream(error))
    }

    /** 验证虚拟机级错误也不被吞没。@return Unit。Callers: JUnit。 */
    @Test fun errorsArePropagatedWithoutRecovery() {
        val call = HookInvocation()
        val error = LinkageError("test")
        assertSame(error, assertThrows(LinkageError::class.java) { call.proceed { throw error } })
        assertTrue(call.fromDownstream(error))
    }

    /** 验证一个拦截器多次调用 proceed 时所有原异常仍可识别。@return Unit。Callers: JUnit。 */
    @Test fun multipleProceedCallsKeepTheirOrigin() {
        val call = HookInvocation()
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        for (error in listOf(first, second)) assertThrows(IllegalStateException::class.java) { call.proceed { throw error } }
        assertTrue(call.fromDownstream(first))
        assertTrue(call.fromDownstream(second))
    }
}
