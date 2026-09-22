package com.neoruaa.xhsdn.data.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import com.neoruaa.xhsdn.FileDownloader
import com.neoruaa.xhsdn.data.settings.DownloadOptions
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.OkHttpClient

/** Manual session data is encrypted and excluded from application backup. */
class SessionCredentials(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "web-session")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun set(host: String, value: String) {
        require(host in HOSTS)
        val values = readAll().apply { put(host, value.trim()) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(values.toString().toByteArray()), Base64.NO_WRAP)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(encoded.toByteArray()); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    @Synchronized fun get(host: String): String = readAll().optString(host)
    fun hasManualSession(): Boolean = HOSTS.any { get(it).isNotBlank() }
    private fun readAll(): org.json.JSONObject = runCatching {
        if (!file.exists()) return org.json.JSONObject()
        val data = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        org.json.JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size))))
    }.getOrElse { org.json.JSONObject() }
    companion object {
        private const val ALIAS = "xhsdn.web-session.v1"
        val HOSTS = setOf("www.xiaohongshu.com", "www.rednote.com")
    }
}

class XhsNetwork(private val credentials: SessionCredentials) {
    fun client(options: DownloadOptions, media: Boolean): OkHttpClient {
        val builder = FileDownloader.getSharedHttpClient().newBuilder()
            .readTimeout(options.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
            .connectTimeout(minOf(options.timeoutSeconds, 20).coerceAtLeast(5).toLong(), TimeUnit.SECONDS)
        if (options.proxy.isNotBlank() && (!media || options.proxyDownload)) {
            val uri = URI(options.proxy)
            require(uri.scheme in setOf("http", "socks5") && uri.host != null && uri.port in 1..65535 && uri.userInfo == null)
            builder.proxy(Proxy(if (uri.scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port)))
        }
        if (!media) builder.addNetworkInterceptor { chain ->
            val request = chain.request()
            val host = when (request.url.host) {
                "xiaohongshu.com", "www.xiaohongshu.com" -> "www.xiaohongshu.com"
                "rednote.com", "www.rednote.com" -> "www.rednote.com"
                else -> null
            }
            val cookie = host?.let { credentials.get(it).ifBlank {
                if (options.useWebSession) CookieManager.getInstance().getCookie("https://$it").orEmpty() else ""
            } }.orEmpty()
            val next = request.newBuilder().removeHeader("Cookie")
            if (cookie.isNotBlank() && request.url.isHttps) next.header("Cookie", cookie)
            chain.proceed(next.build())
        }
        return builder.build()
    }
}
