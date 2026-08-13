package com.alphainventor.filemanager

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ProxyStatus {
    data object Initializing : ProxyStatus()
    data object Ready : ProxyStatus()
    data class Unavailable(val reason: UnavailableReason, val detail: String) : ProxyStatus()
}

enum class UnavailableReason { NO_CONFIG, AUTH_FAILED, UNREACHABLE, UNSUPPORTED_DEVICE }

enum class Screen { BROWSER, HISTORY, BOOKMARKS, SETTINGS, ABOUT }

data class GeolocationRequest(val origin: String, val callback: GeolocationPermissions.Callback)

class BrowserViewModel(
    private val appContext: Context,
    private val configApi: ConfigApi,
    private val settingsDataStore: SettingsDataStore,
    private val proxyManager: ProxyManager,
    private val database: AppDatabase,
    private val downloader: ProxyAwareDownloader,
) : ViewModel() {

    var bridge: BrowserActivityBridge? = null

    val tabManager = TabManager(appContext)
    val tabs: StateFlow<List<Tab>> = tabManager.tabs
    val currentTabId: StateFlow<String?> = tabManager.currentTabId

    private val _proxyStatus = MutableStateFlow<ProxyStatus>(ProxyStatus.Initializing)
    val proxyStatus: StateFlow<ProxyStatus> = _proxyStatus

    private val _screen = MutableStateFlow(Screen.BROWSER)
    val screen: StateFlow<Screen> = _screen

    private val _currentError = MutableStateFlow<BrowserErrorType?>(null)
    val currentError: StateFlow<BrowserErrorType?> = _currentError

    private val _addressBarText = MutableStateFlow("")
    val addressBarText: StateFlow<String> = _addressBarText

    private val _browserSettings = MutableStateFlow(
        BrowserSettings(homepage = "https://www.google.com", searchEngine = "google", maxTabs = 10, downloadsEnabled = true),
    )
    val browserSettings: StateFlow<BrowserSettings> = _browserSettings

    private val _pendingExternalIntent = MutableStateFlow<ExternalIntentRequest?>(null)
    val pendingExternalIntent: StateFlow<ExternalIntentRequest?> = _pendingExternalIntent

    private val _pendingGeolocationRequest = MutableStateFlow<GeolocationRequest?>(null)
    val pendingGeolocationRequest: StateFlow<GeolocationRequest?> = _pendingGeolocationRequest

    private val _tabsOverviewVisible = MutableStateFlow(false)
    val tabsOverviewVisible: StateFlow<Boolean> = _tabsOverviewVisible

    val history: StateFlow<List<HistoryEntry>> =
        database.historyDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bookmarks: StateFlow<List<Bookmark>> =
        database.bookmarkDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var deviceConfig: DeviceConfig? = null

    init {
        wireTabManagerCallbacks()
        bootstrap()
    }

    private fun wireTabManagerCallbacks() {
        tabManager.onError = { _, type, _ -> _currentError.value = type }
        tabManager.onDownloadRequested = { url, ua, disposition, mime, _ ->
            val config = deviceConfig?.proxy
            if (config != null && _browserSettings.value.downloadsEnabled) {
                downloader.enqueue(url, ua, disposition, mime, config)
            }
        }
        tabManager.onShowFileChooser = { callback, params -> bridge?.showFileChooser(callback, params) ?: false }
        tabManager.onGeolocationRequest = { origin, callback -> handleGeolocationRequest(origin, callback) }
        tabManager.onPermissionRequest = { request -> handlePermissionRequest(request) }
        tabManager.onShowCustomView = { view, callback -> bridge?.showCustomView(view, callback) }
        tabManager.onHideCustomView = { bridge?.hideCustomView() }
        tabManager.onExternalNavigation = { uri -> handleExternalNavigation(uri) }
        tabManager.onPageVisited = { url, title -> recordVisit(url, title) }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val cached = settingsDataStore.load()
            if (cached != null) {
                applyConfig(cached)
                openInitialTabIfNeeded()
            }
            refreshConfigFromBackend(openTabAfter = cached == null)
        }
    }

    fun retryProxyConnection() {
        viewModelScope.launch { refreshConfigFromBackend(openTabAfter = tabs.value.isEmpty()) }
    }

    private suspend fun refreshConfigFromBackend(openTabAfter: Boolean) {
        _proxyStatus.value = ProxyStatus.Initializing
        when (val result = configApi.fetchConfig()) {
            is ConfigApi.Result.Success -> {
                settingsDataStore.save(result.config)
                applyConfig(result.config)
                if (openTabAfter) openInitialTabIfNeeded()
            }
            is ConfigApi.Result.Failure -> {
                if (deviceConfig == null) {
                    _proxyStatus.value = ProxyStatus.Unavailable(UnavailableReason.NO_CONFIG, result.reason)
                } else {
                    // We still have a previously-verified config; re-verify
                    // the proxy itself rather than giving up.
                    verifyProxy(deviceConfig!!)
                }
            }
        }
    }

    private suspend fun applyConfig(config: DeviceConfig) {
        deviceConfig = config
        _browserSettings.value = config.browser
        verifyProxy(config)
    }

    private suspend fun verifyProxy(config: DeviceConfig) {
        _proxyStatus.value = ProxyStatus.Initializing
        when (val result = proxyManager.applyAndVerify(config.proxy)) {
            ProxyManager.ConnectivityResult.Ok -> _proxyStatus.value = ProxyStatus.Ready
            ProxyManager.ConnectivityResult.AuthFailed ->
                _proxyStatus.value = ProxyStatus.Unavailable(UnavailableReason.AUTH_FAILED, "The proxy rejected this device's credentials")
            is ProxyManager.ConnectivityResult.Unreachable ->
                _proxyStatus.value = ProxyStatus.Unavailable(UnavailableReason.UNREACHABLE, result.reason)
            ProxyManager.ConnectivityResult.UnsupportedOnDevice ->
                _proxyStatus.value = ProxyStatus.Unavailable(UnavailableReason.UNSUPPORTED_DEVICE, "This device's WebView is too old to be proxied")
        }
    }

    private fun openInitialTabIfNeeded() {
        if (tabs.value.isEmpty()) {
            tabManager.createTab(_browserSettings.value.homepage)
        }
    }

    // --- Address bar / navigation --------------------------------------

    fun onAddressBarTextChanged(text: String) {
        _addressBarText.value = text
    }

    fun onAddressBarSubmit(text: String) {
        val engine = SearchEngine.fromId(_browserSettings.value.searchEngine)
        val target = when (val parsed = UrlParser.parse(text, engine)) {
            is UrlParser.Input.Url -> parsed.url
            is UrlParser.Input.Search -> if (parsed.query.isBlank()) return else engine.searchUrl(parsed.query)
        }
        navigateCurrentTab(target)
    }

    private fun navigateCurrentTab(url: String) {
        if (_proxyStatus.value !is ProxyStatus.Ready) return
        val activeId = currentTabId.value
        if (activeId == null) {
            tabManager.createTab(url)
        } else {
            tabManager.webViewFor(activeId)?.loadUrl(url) ?: tabManager.createTab(url)
        }
        _currentError.value = null
    }

    fun goBack(): Boolean {
        val webView = tabManager.activeWebView() ?: return false
        if (webView.canGoBack()) { webView.goBack(); return true }
        return false
    }

    fun goForward() {
        tabManager.activeWebView()?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        _currentError.value = null
        tabManager.activeWebView()?.reload()
    }

    fun stopLoading() {
        tabManager.activeWebView()?.stopLoading()
    }

    fun retryCurrentPage() {
        _currentError.value = null
        reload()
    }

    // --- Tabs ------------------------------------------------------------

    fun newTab() {
        if (tabs.value.size >= _browserSettings.value.maxTabs) return
        tabManager.createTab(_browserSettings.value.homepage)
        setTabsOverviewVisible(false)
    }

    fun closeTab(id: String) = tabManager.closeTab(id)
    fun switchTab(id: String) {
        tabManager.switchTo(id)
        setTabsOverviewVisible(false)
    }
    fun setTabsOverviewVisible(visible: Boolean) { _tabsOverviewVisible.value = visible }

    // --- Screens / menu ----------------------------------------------------

    fun navigateTo(screen: Screen) { _screen.value = screen }

    fun clearCookiesAndSiteData() {
        android.webkit.CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    // --- History & bookmarks ---------------------------------------------

    private fun recordVisit(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        viewModelScope.launch {
            database.historyDao().insert(HistoryEntry(url = url, title = title, visitedAt = System.currentTimeMillis()))
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) = viewModelScope.launch { database.historyDao().delete(entry) }
    fun clearHistory() = viewModelScope.launch { database.historyDao().clear() }

    fun toggleBookmark(url: String, title: String) = viewModelScope.launch {
        val existing = bookmarks.value.firstOrNull { it.url == url }
        if (existing != null) {
            database.bookmarkDao().delete(existing)
        } else {
            database.bookmarkDao().insert(Bookmark(url = url, title = title, createdAt = System.currentTimeMillis()))
        }
    }
    fun deleteBookmark(bookmark: Bookmark) = viewModelScope.launch { database.bookmarkDao().delete(bookmark) }

    // --- Permission-adjacent events ---------------------------------------

    private fun handleGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        val bridgeRef = bridge
        if (bridgeRef == null || !bridgeRef.hasLocationPermission()) {
            bridgeRef?.requestLocationPermission { granted ->
                if (granted) _pendingGeolocationRequest.value = GeolocationRequest(origin, callback)
                else callback.invoke(origin, false, false)
            } ?: callback.invoke(origin, false, false)
            return
        }
        _pendingGeolocationRequest.value = GeolocationRequest(origin, callback)
    }

    fun resolveGeolocationRequest(allow: Boolean) {
        val request = _pendingGeolocationRequest.value ?: return
        request.callback.invoke(request.origin, allow, false)
        _pendingGeolocationRequest.value = null
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        // Conservative default: deny web-page requests for camera/mic
        // (getUserMedia) and protected media. File uploads already cover
        // the "take a photo for this site" use case via the file chooser +
        // system camera app, without granting in-page camera/mic access.
        request.deny()
    }

    private fun handleExternalNavigation(uri: Uri): Boolean {
        val request = ExternalIntentResolver.resolve(appContext, uri) ?: return false
        _pendingExternalIntent.value = request
        return true
    }

    fun confirmExternalIntent() {
        val request = _pendingExternalIntent.value ?: return
        bridge?.launchExternalIntent(request.intent)
        _pendingExternalIntent.value = null
    }

    fun dismissExternalIntent() { _pendingExternalIntent.value = null }

    fun shareCurrentUrl() {
        val url = tabs.value.firstOrNull { it.id == currentTabId.value }?.url ?: return
        bridge?.shareUrl(url)
    }

    fun openDownloads() {
        bridge?.launchExternalIntent(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    }

    override fun onCleared() {
        tabManager.destroyAll()
        proxyManager.clearOverride()
    }

    class Factory(private val appContext: Context, private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BrowserViewModel(
                appContext = appContext,
                configApi = container.configApi,
                settingsDataStore = container.settingsDataStore,
                proxyManager = container.proxyManager,
                database = container.database,
                downloader = container.downloader,
            ) as T
        }
    }
}
