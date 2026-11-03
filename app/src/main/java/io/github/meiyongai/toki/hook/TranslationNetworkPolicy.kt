package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** Guards module-owned remote translation/caption fetches, NOT all TikTok network traffic. */
internal object TranslationNetworkPolicy {
    const val KEY = "subtitle_local_only_enabled"

    fun capture(): LocalOnlyRequest {
        val playbackRevision = OfflinePlaybackContext.state.revision
        return LocalOnlyRequest { OfflinePlaybackContext.state.revision == playbackRevision && remoteAllowed() }
    }

    private fun remoteAllowed(): Boolean {
        if (!ConfigClient.isReady || ConfigClient.getBoolean(KEY) || OfflinePlaybackContext.state.localOnly) return false
        val context = HookRuntime.hostContext ?: return false
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false } // Missing permission/capabilities never grants remote execution.
    }

    fun showMissingTranslation() = HostSettingsEntry.showMessage(R.string.local_translation_unavailable)
    fun showMissingCaption() = HostSettingsEntry.showMessage(R.string.local_caption_unavailable)
}
