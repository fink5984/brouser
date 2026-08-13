package com.alphainventor.filemanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun AppRoot(viewModel: BrowserViewModel) {
    val screen by viewModel.screen.collectAsState()
    val history by viewModel.history.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val browserSettings by viewModel.browserSettings.collectAsState()
    val proxyStatus by viewModel.proxyStatus.collectAsState()

    when (screen) {
        Screen.BROWSER -> BrowserScreen(viewModel)
        Screen.HISTORY -> HistoryScreen(
            entries = history,
            onBack = { viewModel.navigateTo(Screen.BROWSER) },
            onOpen = { url ->
                viewModel.navigateTo(Screen.BROWSER)
                viewModel.onAddressBarSubmit(url)
            },
            onDelete = { viewModel.deleteHistoryEntry(it) },
            onClearAll = { viewModel.clearHistory() },
        )
        Screen.BOOKMARKS -> BookmarksScreen(
            bookmarks = bookmarks,
            onBack = { viewModel.navigateTo(Screen.BROWSER) },
            onOpen = { url ->
                viewModel.navigateTo(Screen.BROWSER)
                viewModel.onAddressBarSubmit(url)
            },
            onDelete = { viewModel.deleteBookmark(it) },
        )
        Screen.SETTINGS -> SettingsScreen(
            homepage = browserSettings.homepage,
            searchEngine = browserSettings.searchEngine,
            maxTabs = browserSettings.maxTabs,
            downloadsEnabled = browserSettings.downloadsEnabled,
            onBack = { viewModel.navigateTo(Screen.BROWSER) },
            onClearHistory = { viewModel.clearHistory() },
            onClearCookiesAndSiteData = { viewModel.clearCookiesAndSiteData() },
        )
        Screen.ABOUT -> AboutScreen(
            proxyReady = proxyStatus is ProxyStatus.Ready,
            onBack = { viewModel.navigateTo(Screen.BROWSER) },
        )
    }
}
