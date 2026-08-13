package com.alphainventor.filemanager

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * A single tab's WebView, fully configured and wired up. Owns exactly one
 * platform [WebView] instance -- [TabManager]
 * decides when that's allowed to exist so the app never accumulates dozens
 * of live WebViews.
 */
class BrowserWebView(
    context: Context,
    val tabId: String,
    private val callbacks: TabCallbacks,
    private val proxyUsername: String? = null,
    private val proxyPassword: String? = null,
) {
    val webView: WebView = WebView(context)

    init {
        configureSettings(webView.settings)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = InnerWebViewClient()
        webView.webChromeClient = InnerWebChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            callbacks.onDownloadRequested(url, userAgent, contentDisposition, mimeType, contentLength)
        }
    }

    private fun configureSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationEnabled(true)

        // Hardening: no local file access, no bridging between file:// and
        // network content, no content:// access (uploads go through the
        // system file picker, not WebView file access).
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
    }

    fun loadUrl(url: String) = webView.loadUrl(url)
    fun goBack() = webView.goBack()
    fun goForward() = webView.goForward()
    fun canGoBack(): Boolean = webView.canGoBack()
    fun canGoForward(): Boolean = webView.canGoForward()
    fun reload() = webView.reload()
    fun stopLoading() = webView.stopLoading()
    fun currentUrl(): String? = webView.url

    /** Detaches and releases the platform WebView. Call before dropping this instance. */
    fun destroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
    }

    private inner class InnerWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            return callbacks.onExternalNavigation(uri)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            android.util.Log.d("BrowserAuth", "onPageStarted url=$url")
            callbacks.onPageStarted(tabId, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            android.util.Log.d("BrowserAuth", "onPageFinished url=$url")
            callbacks.onPageFinished(tabId, url)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never handler.proceed() here under any circumstance: a
            // certificate error always blocks the page.
            handler.cancel()
            callbacks.onError(tabId, BrowserErrorType.SSL_ERROR, error.toString())
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            android.util.Log.d(
                "BrowserAuth",
                "onReceivedError isMainFrame=${request.isForMainFrame} url=${request.url} " +
                    "code=${error.errorCode} desc=${error.description}",
            )
            if (!request.isForMainFrame) return
            val type = when (error.errorCode) {
                ERROR_HOST_LOOKUP -> BrowserErrorType.DNS_ERROR
                ERROR_CONNECT, ERROR_TIMEOUT -> BrowserErrorType.CONNECTION_TIMEOUT
                ERROR_PROXY_AUTHENTICATION -> BrowserErrorType.PROXY_AUTH_FAILED
                else -> BrowserErrorType.GENERIC
            }
            callbacks.onError(tabId, type, error.description?.toString() ?: "Unknown error")
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: android.webkit.HttpAuthHandler,
            host: String?,
            realm: String?,
        ) {
            android.util.Log.d(
                "BrowserAuth",
                "onReceivedHttpAuthRequest host=$host realm=$realm hasCreds=${proxyUsername != null}",
            )
            // This fires for both origin-server 401s and our own proxy's 407
            // (androidx.webkit routes both through the same callback). Since
            // every request in this app goes through our proxy, a challenge
            // reaching here is virtually always the proxy's -- supply its
            // credentials. Worst case for a real site using HTTP Basic Auth:
            // the wrong credentials are tried and it fails normally, same as
            // cancelling would have.
            if (proxyUsername != null && proxyPassword != null) {
                handler.proceed(proxyUsername, proxyPassword)
            } else {
                handler.cancel()
            }
        }
    }

    private inner class InnerWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            callbacks.onProgressChanged(tabId, newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            callbacks.onTitleChanged(tabId, title ?: view.url.orEmpty())
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            callbacks.onFaviconChanged(tabId, icon)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            callbacks.onGeolocationRequest(origin, callback)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            callbacks.onPermissionRequest(request)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture) return false
            val newWebView = callbacks.onCreateNewTabForWindow()
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = newWebView
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            callbacks.onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            callbacks.onHideCustomView()
        }

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: android.webkit.JsResult,
        ): Boolean {
            AlertDialog.Builder(view.context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: android.webkit.JsResult,
        ): Boolean {
            AlertDialog.Builder(view.context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: android.webkit.JsPromptResult,
        ): Boolean {
            val input = EditText(view.context).apply { setText(defaultValue) }
            AlertDialog.Builder(view.context)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private companion object {
        const val ERROR_HOST_LOOKUP = WebViewClient.ERROR_HOST_LOOKUP
        const val ERROR_CONNECT = WebViewClient.ERROR_CONNECT
        const val ERROR_TIMEOUT = WebViewClient.ERROR_TIMEOUT
        const val ERROR_PROXY_AUTHENTICATION = WebViewClient.ERROR_PROXY_AUTHENTICATION
    }
}
