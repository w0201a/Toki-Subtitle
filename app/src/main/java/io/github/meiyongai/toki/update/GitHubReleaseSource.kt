package io.github.meiyongai.toki.subtitle.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Unauthenticated HTTPS metadata request; no account/token, APK download, telemetry, or automatic installation. */
internal class GitHubReleaseSource(
    private val installedName: String,
    private val connect: () -> HttpURLConnection = { URL(ENDPOINT).openConnection() as HttpURLConnection },
) : ReleaseSource {
    override fun latest(): AppRelease? {
        val connection = connect()
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Toki/$installedName")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                HttpURLConnection.HTTP_OK -> Unit
                else -> throw IOException("Release metadata unavailable")
            }
            if (connection.contentLengthLong > MAX_RESPONSE) throw IOException("Release metadata too large")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RESPONSE) throw IOException("Release metadata too large")
                    bytes.write(buffer, 0, count)
                }
            }
            return AppRelease.fromApi(bytes.toString(Charsets.UTF_8.name()))
        } finally { connection.disconnect() }
    }
    companion object {
        const val ENDPOINT = "https://api.github.com/repos/w0201a/Toki-Subtitle/releases/latest"
        const val MAX_RESPONSE = 512 * 1024
    }
}
