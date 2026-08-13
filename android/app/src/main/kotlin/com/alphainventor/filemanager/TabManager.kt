package com.alphainventor.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns every open [Tab] and lazily creates/evicts the [BrowserWebView] that
 * backs it. Only a bounded number of WebViews are ever alive at once --
 * background tabs beyond that cap are torn down (a fresh WebView reloads
 * the tab's last URL when it's revisited) so a session with many tabs open
 * doesn't accumulate unbounded native memory.
 */
class TabManager(
    private val context: Context,
    private val maxLiveWebViews: Int = 4,
) : TabCallbacks {

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs

    private val _currentTabId = MutableStateFlow<String?>(null)
    val currentTabId: StateFlow<String?> = _currentTabId

    private val liveWebViews = LinkedHashMap<String, BrowserWebView>(16, 0.75f, true)

    var onError: ((tabId: String, BrowserErrorType, String) -> Unit)? = null
    var onDownloadRequested: ((url: String, ua: String, disposition: String, mime: String, length: Long) -> Unit)? = null
    var onShowFileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    var onGeolocationRequest: ((origin: String, GeolocationPermissions.Callback) -> Unit)? = null
    var onPermissionRequest: ((PermissionRequest) -> Unit)? = null
    var onShowCustomView: ((android.view.View, WebChromeClient.CustomViewCallback) -> Unit)? = null
    var onHideCustomView: (() -> Unit)? = null
    var onExternalNavigation: ((Uri) -> Boolean)? = null
    var onPageVisited: ((url: String, title: String) -> Unit)? = null

    fun createTab(url: String?, activate: Boolean = true): Tab {
        val tab = Tab(url = url.orEmpty())
        _tabs.update { it + tab }
        getOrCreateWebView(tab.id)
        if (url != null) webViewFor(tab.id)?.loadUrl(url)
        if (activate) switchTo(tab.id)
        return tab
    }

    fun closeTab(id: String) {
        liveWebViews.remove(id)?.destroy()
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_currentTabId.value == id) {
            val next = remaining.maxByOrNull { it.lastUsedAt }
            if (next != null) switchTo(next.id) else _currentTabId.value = null
        }
    }

    fun switchTo(id: String) {
        if (_tabs.value.none { it.id == id }) return
        getOrCreateWebView(id)
        _currentTabId.value = id
        touch(id)
        evictIfNeeded()
    }

    fun activeWebView(): BrowserWebView? = _currentTabId.value?.let { liveWebViews[it] }
    fun webViewFor(id: String): BrowserWebView? = liveWebViews[id]

    fun restoreTabs(saved: List<Tab>) {
        if (saved.isEmpty()) return
        _tabs.value = saved
        saved.maxByOrNull { it.lastUsedAt }?.let { switchTo(it.id) }
    }

    fun destroyAll() {
        liveWebViews.values.forEach { it.destroy() }
        liveWebViews.clear()
    }

    private fun getOrCreateWebView(tabId: String): BrowserWebView {
        liveWebViews[tabId]?.let { return it }
        val created = BrowserWebView(context, tabId, this)
        liveWebViews[tabId] = created
        evictIfNeeded()
        return created
    }

    private fun evictIfNeeded() {
        val iterator = liveWebViews.entries.iterator()
        while (liveWebViews.size > maxLiveWebViews && iterator.hasNext()) {
            val (id, webView) = iterator.next()
            if (id == _currentTabId.value) continue
            iterator.remove()
            webView.destroy()
        }
    }

    private fun touch(id: String) {
        _tabs.update { list -> list.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it } }
    }

    private fun updateTab(id: String, transform: (Tab) -> Tab) {
        _tabs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    // --- TabCallbacks -------------------------------------------------

    override fun onProgressChanged(tabId: String, progress: Int) {
        updateTab(tabId) { it.copy(progress = progress, isLoading = progress in 1..99) }
    }

    override fun onTitleChanged(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title) }
    }

    override fun onFaviconChanged(tabId: String, favicon: Bitmap?) {
        updateTab(tabId) { it.copy(favicon = favicon) }
    }

    override fun onPageStarted(tabId: String, url: String) {
        updateTab(tabId) { it.copy(url = url, isLoading = true, progress = 5) }
    }

    override fun onPageFinished(tabId: String, url: String) {
        val title = _tabs.value.firstOrNull { it.id == tabId }?.title ?: url
        updateTab(tabId) { it.copy(url = url, isLoading = false, progress = 100) }
        onPageVisited?.invoke(url, title)
    }

    override fun onError(tabId: String, type: BrowserErrorType, description: String) {
        updateTab(tabId) { it.copy(isLoading = false) }
        onError?.invoke(tabId, type, description)
    }

    override fun onDownloadRequested(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
    ) {
        onDownloadRequested?.invoke(url, userAgent, contentDisposition, mimeType, contentLength)
    }

    override fun onShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = onShowFileChooser?.invoke(filePathCallback, params) ?: false

    override fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        onGeolocationRequest?.invoke(origin, callback) ?: callback.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        onPermissionRequest?.invoke(request) ?: request.deny()
    }

    override fun onCreateNewTabForWindow(): WebView {
        val tab = Tab()
        _tabs.update { it + tab }
        return getOrCreateWebView(tab.id).webView.also {
            switchTo(tab.id)
        }
    }

    override fun onShowCustomView(view: android.view.View, callback: WebChromeClient.CustomViewCallback) {
        onShowCustomView?.invoke(view, callback)
    }

    override fun onHideCustomView() {
        onHideCustomView?.invoke()
    }

    override fun onExternalNavigation(uri: Uri): Boolean = onExternalNavigation?.invoke(uri) ?: false
}
