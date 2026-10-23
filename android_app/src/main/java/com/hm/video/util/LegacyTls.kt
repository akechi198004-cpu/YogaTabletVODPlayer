package com.hm.video.util

import android.os.Build
import com.hm.video.HMVideoApplication
import com.hm.video.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Android 6 predates ISRG Root X1. Extend its CA store without bypassing validation. */
object LegacyTls {
    private val configuration: Pair<SSLContext, X509TrustManager>? by lazy {
        if (Build.VERSION.SDK_INT >= 24) null else {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val platformTrust = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                platformTrust.acceptedIssuers.forEachIndexed { index, cert ->
                    setCertificateEntry("system-$index", cert)
                }
                HMVideoApplication.instance.resources.openRawResource(R.raw.isrg_root_x1).use {
                    setCertificateEntry("isrg-root-x1", CertificateFactory.getInstance("X.509").generateCertificate(it))
                }
            }
            factory.init(store)
            val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            context to trust
        }
    }

    fun configure(builder: OkHttpClient.Builder) {
        configuration?.let { (context, trust) -> builder.sslSocketFactory(context.socketFactory, trust) }
    }

    fun configurePlatformHttps() {
        // Media3's DefaultHttpDataSource uses HttpsURLConnection rather than OkHttp.
        configuration?.let { (context, _) -> HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory) }
    }
}
