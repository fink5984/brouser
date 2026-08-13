package com.alphainventor.filemanager

import android.content.Context
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.ProxyConfig as WebkitProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

/**
 * Owns the app-wide (not system-wide) WebView proxy override, and the
 * connectivity probe that makes the "fail closed, never fall back to a
 * direct connection" requirement real rather than aspirational: we verify
 * the proxy actually accepts our credentials *before* letting any page
 * load, using the exact same CONNECT-tunnel + Basic-Auth mechanism WebView
 * itself will use.
 */
class ProxyManager(private val context: Context) {

    sealed class ConnectivityResult {
        data object Ok : ConnectivityResult()
        data object AuthFailed : ConnectivityResult()
        data class Unreachable(val reason: String) : ConnectivityResult()
        data object UnsupportedOnDevice : ConnectivityResult()
    }

    /** Applies the WebView-level proxy override, then verifies it actually works. */
    suspend fun applyAndVerify(config: ProxyConfig): ConnectivityResult {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return ConnectivityResult.UnsupportedOnDevice
        }
        applyOverride(config)
        return probeConnect(config)
    }

    fun clearOverride() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(context)) {}
        }
    }

    private suspend fun applyOverride(config: ProxyConfig): Unit = suspendCancellableCoroutine { cont ->
        val webkitConfig = WebkitProxyConfig.Builder()
            .addProxyRule("${config.scheme}://${config.host}:${config.port}")
            // No bypass rules of any kind: every request, including ones
            // that look like localhost, still goes through the proxy. This
            // is deliberate -- see docs/architecture.md.
            .build()
        ProxyController.getInstance().setProxyOverride(
            webkitConfig,
            ContextCompat.getMainExecutor(context),
        ) { cont.resume(Unit) }
    }

    /**
     * Opens a raw CONNECT tunnel through the proxy exactly like WebView
     * would for an HTTPS site, presenting the same Basic-Auth credentials.
     * A 200 response means the proxy is reachable AND our credentials are
     * valid; anything else means the browser must not be allowed to load
     * pages yet.
     */
    private suspend fun probeConnect(config: ProxyConfig): ConnectivityResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS) {
                var socket: Socket? = null
                try {
                    val plain = Socket().apply {
                        connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS.toInt())
                        soTimeout = READ_TIMEOUT_MS.toInt()
                    }
                    socket = if (config.scheme == "https") {
                        (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(plain, config.host, config.port, true)
                            .apply { soTimeout = READ_TIMEOUT_MS.toInt() }
                    } else {
                        plain
                    }

                    val auth = Base64.encodeToString(
                        "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    )
                    val request = buildString {
                        append("CONNECT $PROBE_TARGET HTTP/1.1\r\n")
                        append("Host: $PROBE_TARGET\r\n")
                        append("Proxy-Authorization: Basic $auth\r\n")
                        append("Proxy-Connection: Keep-Alive\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()

                    val statusLine = socket.getInputStream().bufferedReader().readLine() ?: ""
                    when {
                        " 200 " in statusLine -> ConnectivityResult.Ok
                        " 407 " in statusLine -> ConnectivityResult.AuthFailed
                        statusLine.isBlank() -> ConnectivityResult.Unreachable("Empty response from proxy")
                        else -> ConnectivityResult.Unreachable("Proxy responded: $statusLine")
                    }
                } catch (e: Exception) {
                    ConnectivityResult.Unreachable(e.message ?: e.javaClass.simpleName)
                } finally {
                    runCatching { socket?.close() }
                }
            } ?: ConnectivityResult.Unreachable("Timed out waiting for the proxy")
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000L
        const val READ_TIMEOUT_MS = 6_000L
        const val PROBE_TARGET = "www.google.com:443"
    }
}
