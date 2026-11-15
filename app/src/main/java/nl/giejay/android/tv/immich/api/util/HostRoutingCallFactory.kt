package nl.giejay.android.tv.immich.api.util

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Glide's single HTTP entry point. Requests to the Immich server go through [immichClient] (which
 * attaches the x-api-key header); requests to any other host — e.g. Gallery photos from Unsplash's
 * image CDN — go through [publicClient], so the Immich API key never leaves for a third party.
 *
 * The Immich host is looked up per request ([immichHost]) so a server change in Settings applies
 * without rebuilding Glide. If it can't be determined, requests fall back to [immichClient],
 * preserving the previous behaviour.
 */
class HostRoutingCallFactory(
    private val immichClient: Call.Factory,
    private val publicClient: Call.Factory,
    private val immichHost: () -> String?
) : Call.Factory {

    override fun newCall(request: Request): Call {
        val host = immichHost()
        return if (host == null || request.url().host().equals(host, ignoreCase = true)) {
            immichClient.newCall(request)
        } else {
            publicClient.newCall(request)
        }
    }

    companion object {
        /** Host part of a configured server URL such as "https://photos.example.com:2283/". */
        fun hostOf(serverUrl: String?): String? =
            serverUrl?.takeIf { it.isNotBlank() }?.let { HttpUrl.parse(it.trim())?.host() }
    }
}

/**
 * Plain client for public, read-only content on third-party hosts (no credentials attached).
 * Trust-all for the same reason as the weather client: KitKat's 2014 CA store can't validate
 * modern certificate chains, while Conscrypt supplies the modern ciphers.
 */
object PublicHttpClient {
    val client: OkHttpClient by lazy { UnsafeOkHttpClient.unsafeOkHttpClient().dns(Ipv4FirstDns).build() }
}
