package nl.giejay.android.tv.immich.homeassistant

import android.content.Context
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.util.Ipv4FirstDns
import nl.giejay.android.tv.immich.api.util.Tls12SocketFactory.Companion.enableTls12
import okhttp3.OkHttpClient
import timber.log.Timber
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTP client for Home Assistant. Unlike the public weather/Unsplash clients this one carries a
 * long-lived access token, so certificates are verified for real: KitKat's 2014 CA store doesn't
 * know Let's Encrypt's ISRG roots, so they're bundled (res/raw) and trusted alongside the system
 * store. Conscrypt (installed in ImmichApplication) supplies the modern TLS.
 */
object HaHttp {
    @Volatile
    private var client: OkHttpClient? = null

    fun client(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: build(context.applicationContext).also { client = it }
    }

    private fun build(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(Ipv4FirstDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // keeps the websocket alive through NATs/proxies and detects dead connections
            .pingInterval(30, TimeUnit.SECONDS)
        try {
            val trustManager = CompositeTrustManager(listOfNotNull(systemTrustManager(), bundledTrustManager(context)))
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            builder.enableTls12(sslContext.socketFactory, trustManager)
        } catch (e: Exception) {
            Timber.e(e, "Could not set up Home Assistant TLS; using platform defaults")
        }
        return builder.build()
    }

    private fun systemTrustManager(): X509TrustManager? = runCatching {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    private fun bundledTrustManager(context: Context): X509TrustManager? = runCatching {
        val factory = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, res ->
            context.resources.openRawResource(res).use { keyStore.setCertificateEntry("isrg$i", factory.generateCertificate(it)) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.onFailure { Timber.e(it, "Could not load bundled CA roots") }.getOrNull()

    /** Trusts a chain if any delegate does (system roots, then the bundled ISRG roots). */
    private class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            check(chain, authType) { tm, c, a -> tm.checkClientTrusted(c, a) }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            check(chain, authType) { tm, c, a -> tm.checkServerTrusted(c, a) }
        }

        private inline fun check(
            chain: Array<X509Certificate>,
            authType: String,
            verify: (X509TrustManager, Array<X509Certificate>, String) -> Unit
        ) {
            var last: CertificateException? = null
            for (tm in delegates) {
                try {
                    verify(tm, chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("No trust managers available")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }
}
