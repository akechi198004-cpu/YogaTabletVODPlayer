package com.hm.video.util

import android.os.Build
import android.util.Log
import com.hm.video.HMVideoApplication
import com.hm.video.R
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 4.4 (API 19) ships an OpenSSL stack that supports TLS 1.2 but leaves it
 * disabled: sockets only negotiate TLS 1.0, and most HTTPS origins now answer that
 * with a "protocol version" alert. Its root store also predates Let's Encrypt and
 * the current Amazon / DigiCert roots.
 *
 * Both gaps are fixed here for the two transports the app actually uses:
 * OkHttp (page fetches, image downloads, HLS proxy) and HttpsURLConnection
 * (Media3's DefaultHttpDataSource for direct playback).
 *
 * Nothing is bypassed: the platform trust manager still validates every chain and
 * the extra roots are only consulted after it rejects a chain. On API 24+ this is
 * a no-op because the platform already enables TLS 1.2 and ships the roots.
 */
object LegacyTls {

    private const val TAG = "HMTls"

    @Volatile
    private var loggedProtocols = false

    /** Order matters: offer the newest protocol first, keep 1.0 as a last resort. */
    private val PREFERRED_PROTOCOLS = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")

    private class Configuration(val context: SSLContext, val trust: X509TrustManager)

    private val configuration: Configuration? by lazy {
        if (Build.VERSION.SDK_INT >= 24) return@lazy null
        try {
            val platformTrust = trustManagerFor(null)
            val merged = merge(platformTrust, trustManagerFor(extraRoots()))
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(merged), SecureRandom())
            }
            Configuration(context, merged)
        } catch (error: Throwable) {
            Log.w(TAG, "could not install TLS 1.2 / extra roots", error)
            null
        }
    }

    /** OkHttp path: replaces the socket factory of a builder that is still editable. */
    fun configure(builder: OkHttpClient.Builder) {
        val installed = configuration
        Log.i(TAG, if (installed == null) "using platform TLS defaults" else "installed TLS 1.2 socket factory + extra roots")
        if (installed == null) return
        builder.sslSocketFactory(forceProtocols(installed.context.socketFactory), installed.trust)
        // OkHttp's own spec list is a trap here: MODERN_TLS only lists 1.3/1.2, so on a
        // KitKat socket whose enabled protocols are TLS 1.0 the intersection is empty, while
        // COMPATIBLE_TLS is TLS 1.0 only and would downgrade us straight back. Ask for all
        // three explicitly and let the server pick the highest one it accepts.
        builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .build(),
                ConnectionSpec.CLEARTEXT
            )
        )
    }

    /** Media3 path: DefaultHttpDataSource uses HttpsURLConnection rather than OkHttp. */
    fun configurePlatformHttps() {
        configuration?.let {
            HttpsURLConnection.setDefaultSSLSocketFactory(forceProtocols(it.context.socketFactory))
            Log.i(TAG, "TLS 1.2 enabled, extra roots merged (SDK ${Build.VERSION.SDK_INT})")
        }
    }

    /**
     * Wraps a platform factory so every socket enables TLS 1.2 first. Android 4.4's
     * default enabledProtocols only lists SSLv3/TLSv1, no matter what SSLContext built
     * the socket, and OkHttp 3.x does not raise that ceiling for us.
     */
    private fun forceProtocols(base: SSLSocketFactory): SSLSocketFactory = object : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            upgrade(base.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            upgrade(base.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            upgrade(base.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            upgrade(base.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            upgrade(base.createSocket(address, port, localAddress, localPort))
    }

    private fun upgrade(socket: Socket): Socket {
        if (socket !is SSLSocket) return socket
        return try {
            val supported = socket.supportedProtocols
            val wanted = PREFERRED_PROTOCOLS.filter { want -> supported.any { it.equals(want, true) } }
            if (wanted.isNotEmpty()) socket.enabledProtocols = wanted.toTypedArray()
            socket.useClientMode = true
            if (!loggedProtocols) {
                loggedProtocols = true
                Log.i(TAG, "protocols supported=${supported.joinToString(",")} enabled=${socket.enabledProtocols.joinToString(",")}")
            }
            socket
        } catch (error: Throwable) {
            // Keep the platform defaults rather than failing the connection outright.
            Log.w(TAG, "could not enable TLS 1.2 on socket", error)
            socket
        }
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Extra roots bundled in res/raw so old devices can verify modern chains. */
    private fun extraRoots(): KeyStore {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        HMVideoApplication.instance.resources.openRawResource(R.raw.extra_roots).use { stream ->
            val certificates: Collection<Certificate> =
                CertificateFactory.getInstance("X.509").generateCertificates(stream)
            certificates.forEachIndexed { index, certificate ->
                store.setCertificateEntry("extra-$index", certificate)
            }
        }
        return store
    }

    /** Platform store first; the extra store is only a fallback for unknown roots. */
    private fun merge(platform: X509TrustManager, extra: X509TrustManager): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                platform.checkClientTrusted(chain, authType)
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    platform.checkServerTrusted(chain, authType)
                } catch (platformRejection: CertificateException) {
                    try {
                        extra.checkServerTrusted(chain, authType)
                    } catch (extraRejection: CertificateException) {
                        platformRejection.addSuppressed(extraRejection)
                        throw platformRejection
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
        }
}
