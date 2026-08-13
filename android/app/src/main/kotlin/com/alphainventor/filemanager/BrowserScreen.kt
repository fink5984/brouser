package com.alphainventor.filemanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun BrowserScreen(viewModel: BrowserViewModel) {
    val tabs by viewModel.tabs.collectAsState()
    val currentTabId by viewModel.currentTabId.collectAsState()
    val proxyStatus by viewModel.proxyStatus.collectAsState()
    val currentError by viewModel.currentError.collectAsState()
    val tabsOverviewVisible by viewModel.tabsOverviewVisible.collectAsState()
    val pendingExternalIntent by viewModel.pendingExternalIntent.collectAsState()
    val pendingGeolocation by viewModel.pendingGeolocationRequest.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    val currentTab = tabs.firstOrNull { it.id == currentTabId }
    val isBookmarked = currentTab != null && bookmarks.any { it.url == currentTab.url }

    Scaffold(
        topBar = {
            Box {
                BrowserToolbar(
                    url = currentTab?.url.orEmpty(),
                    isLoading = currentTab?.isLoading == true,
                    progress = currentTab?.progress ?: 0,
                    canGoBack = viewModel.tabManager.activeWebView()?.canGoBack() == true,
                    canGoForward = viewModel.tabManager.activeWebView()?.canGoForward() == true,
                    tabCount = tabs.size,
                    onBack = { viewModel.goBack() },
                    onForward = { viewModel.goForward() },
                    onReloadOrStop = { if (currentTab?.isLoading == true) viewModel.stopLoading() else viewModel.reload() },
                    onAddressSubmit = { viewModel.onAddressBarSubmit(it) },
                    onTabsClick = { viewModel.setTabsOverviewVisible(true) },
                    onMenuClick = { menuExpanded = true },
                )
                BrowserMenu(
                    expanded = menuExpanded,
                    isCurrentPageBookmarked = isBookmarked,
                    onDismiss = { menuExpanded = false },
                    onNewTab = { viewModel.newTab() },
                    onToggleBookmark = {
                        currentTab?.let { viewModel.toggleBookmark(it.url, it.title) }
                    },
                    onHistory = { viewModel.navigateTo(Screen.HISTORY) },
                    onBookmarks = { viewModel.navigateTo(Screen.BOOKMARKS) },
                    onDownloads = { viewModel.openDownloads() },
                    onSettings = { viewModel.navigateTo(Screen.SETTINGS) },
                    onAbout = { viewModel.navigateTo(Screen.ABOUT) },
                    onShare = { viewModel.shareCurrentUrl() },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val status = proxyStatus
            when {
                status is ProxyStatus.Initializing -> ConnectingScreen()
                status is ProxyStatus.Unavailable ->
                    ProxyUnavailableScreen(detail = status.detail, onRetry = { viewModel.retryProxyConnection() })
                currentError != null ->
                    PageErrorScreen(type = currentError!!, detail = "", onRetry = { viewModel.retryCurrentPage() })
                else -> WebViewHost(webView = viewModel.tabManager.activeWebView()?.webView)
            }
        }
    }

    if (tabsOverviewVisible) {
        TabsOverviewSheet(
            tabs = tabs,
            currentTabId = currentTabId,
            onSelect = { viewModel.switchTab(it) },
            onClose = { viewModel.closeTab(it) },
            onNewTab = { viewModel.newTab() },
            onDismiss = { viewModel.setTabsOverviewVisible(false) },
        )
    }

    pendingExternalIntent?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissExternalIntent() },
            title = { Text("Open in another app?") },
            text = { Text(request.description) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmExternalIntent() }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissExternalIntent() }) { Text("Cancel") }
            },
        )
    }

    pendingGeolocation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveGeolocationRequest(false) },
            title = { Text("Allow location access?") },
            text = { Text("${request.origin} wants to know your location.") },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveGeolocationRequest(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveGeolocationRequest(false) }) { Text("Block") }
            },
        )
    }
}
