package io.github.meiyongai.toki.subtitle.hook
import org.junit.Assert.*
import org.junit.Test
class ShareAdapterTypeTest {
 open class ObfuscatedAdapter
 class NativeRecycler { fun getAdapter(): ObfuscatedAdapter?=null }
 class InvalidRecycler { fun getAdapter(): Any?=null }
 @Test fun nativeReturnTypeWorksEvenWhenNoNamedNestedAdapterClassExists() {
  assertEquals(ObfuscatedAdapter::class.java,SharePanelCleanupHook.adapterType(NativeRecycler::class.java))
  assertThrows(IllegalStateException::class.java){SharePanelCleanupHook.adapterType(InvalidRecycler::class.java)}
 }
}
