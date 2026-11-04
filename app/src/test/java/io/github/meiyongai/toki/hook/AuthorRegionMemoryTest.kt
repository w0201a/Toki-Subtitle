package io.github.meiyongai.toki.subtitle.hook
import org.junit.Assert.*
import org.junit.Test
class AuthorRegionMemoryTest {
 @Test fun sparseProfileCanOnlyReuseTheExactSameAuthorsObservedRegion() {
  var now=0L;val memory=AuthorRegionMemory { now }
  assertEquals("US",memory.resolve("author-a","USA"))
  assertEquals("US",memory.resolve("author-a",null));assertNull(memory.resolve("author-b",null))
  assertEquals("JP",memory.resolve("author-a","JP"));assertEquals("JP",memory.resolve("author-a",""))
  now=30*60*1000L+1;assertNull(memory.resolve("author-a",null))
 }
 @Test fun unknownAndDeviceRegionsNeverBecomeAuthorEvidence() {
  val memory=AuthorRegionMemory { 0 }
  assertNull(memory.resolve("", "US"));assertNull(memory.resolve("a","not-a-country"))
  assertNull(memory.resolve("a",null));memory.resolve("a","JP");memory.clear();assertNull(memory.resolve("a",null))
 }
 @Test fun boundedMemoryEvictsOldAuthors() {
  val memory=AuthorRegionMemory { 0 };repeat(257){memory.resolve("id-$it","US")}
  assertNull(memory.resolve("id-0",null));assertEquals("US",memory.resolve("id-256",null))
 }
}
