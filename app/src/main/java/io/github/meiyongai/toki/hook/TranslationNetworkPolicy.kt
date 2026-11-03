package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** Cache-first hybrid mode by default; explicit local-only remains optional, not implied by an offline entry. */
internal object TranslationNetworkPolicy {
    const val KEY = "subtitle_local_only_enabled"

    fun capture(): LocalOnlyRequest {
        val playbackRevision = OfflinePlaybackContext.state.revision
        return LocalOnlyRequest { OfflinePlaybackContext.state.revision == playbackRevision && remoteAllowed() }
    }

    internal fun allowsRemote(configurationReady: Boolean, localOnly: Boolean, validatedNetwork: Boolean): Boolean =
        configurationReady && !localOnly && validatedNetwork

    internal fun remoteAvailable(): Boolean = remoteAllowed()

    private fun remoteAllowed(): Boolean {
        if (!ConfigClient.isReady || ConfigClient.getBoolean(KEY)) return false
        val context = HookRuntime.hostContext ?: return false
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            allowsRemote(ConfigClient.isReady, ConfigClient.getBoolean(KEY),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        } catch (_: Exception) { false } // Missing permission/capabilities never grants remote execution.
    }

    fun showMissingTranslation() = HostSettingsEntry.showMessage(R.string.local_translation_unavailable)
    fun showMissingCaption() = HostSettingsEntry.showMessage(R.string.local_caption_unavailable)
}
