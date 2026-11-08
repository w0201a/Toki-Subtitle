package io.github.meiyongai.toki.subtitle.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleaseSourceTest {
    private class Connection(val status: Int = 200, val body: ByteArray = "{\"tag_name\":\"v2.1.24\",\"draft\":false,\"prerelease\":false,\"body\":\"新增与修复\"}".toByteArray(), val length: Long = body.size.toLong()) : HttpURLConnection(URL(GitHubReleaseSource.ENDPOINT)) {
        var closed = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = length
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
    @Test fun officialStableMetadataUsesFiniteTimeoutsAndNoAuthentication() {
        val connection = Connection(); val release = GitHubReleaseSource("2.1.23") { connection }.latest()
        assertEquals("新增与修复", release?.notes); assertEquals(7_000, connection.connectTimeout); assertEquals(7_000, connection.readTimeout)
        assertEquals("GET", connection.requestMethod); assertFalse(connection.instanceFollowRedirects)
        assertEquals("Toki/2.1.23", connection.getRequestProperty("User-Agent")); assertNull(connection.getRequestProperty("Authorization"))
        assertTrue(connection.closed)
    }
    @Test fun noPublicStableReleaseIsNotAnUpdateAndConnectionIsClosed() {
        val connection = Connection(status = 404)
        assertNull(GitHubReleaseSource("2.1.23") { connection }.latest()); assertTrue(connection.closed)
    }
    @Test fun redirectsRateLimitAndServerErrorsNeverOpenOtherHostsOrBecomeUpdates() {
        for (code in listOf(301, 302, 403, 429, 500)) {
            val connection = Connection(status = code)
            assertThrows(IOException::class.java) { GitHubReleaseSource("2.1.23") { connection }.latest() }
            assertTrue(connection.closed); assertFalse(connection.instanceFollowRedirects)
        }
    }
    @Test fun declaredAndStreamingResponseLimitsAreBothEnforced() {
        for (declared in listOf(-1L, GitHubReleaseSource.MAX_RESPONSE + 1L)) {
            val connection = Connection(body = ByteArray(GitHubReleaseSource.MAX_RESPONSE + 1), length = declared)
            assertThrows(IOException::class.java) { GitHubReleaseSource("2.1.23") { connection }.latest() }
            assertTrue(connection.closed)
        }
    }
    @Test fun malformedResponseFailsAndDisconnectsWithoutReturningFakeLatestVersion() {
        val connection = Connection(body = "not json".toByteArray())
        assertThrows(Exception::class.java) { GitHubReleaseSource("2.1.23") { connection }.latest() }; assertTrue(connection.closed)
    }
}
