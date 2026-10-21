package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test

class MinisCleanupHookTest {
    class OpenRequest
    interface Service {
        fun available(): Boolean
        fun allowed(activity: Activity): Boolean
        fun open(request: OpenRequest): Boolean
    }
    class Implementation : Service {
        override fun available() = true
        override fun allowed(activity: Activity) = true
        override fun open(request: OpenRequest) = true
        fun unrelated() {}
    }
    interface Missing { fun available(): Boolean }
    class Incomplete : Missing { override fun available() = true }
    @Test fun identifiesOnlyAvailabilityAndOpenMethodsWithRenamedMembers() {
        assertEquals(setOf("available", "allowed", "open"),
            MinisCleanupHook.methods(Implementation::class.java, Service::class.java).map { it.name }.toSet())
    }
    @Test fun rejectsIncompleteService() {
        assertThrows(IllegalStateException::class.java) {
            MinisCleanupHook.methods(Incomplete::class.java, Missing::class.java)
        }
    }
}
