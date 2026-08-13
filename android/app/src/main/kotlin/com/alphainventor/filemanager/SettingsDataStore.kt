package com.alphainventor.filemanager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "browser_settings")

/**
 * Caches the last-known-good device config on disk so the app doesn't need
 * a round trip to the backend on every cold start. This does not weaken the
 * fail-closed proxy guarantee: it only remembers *which* proxy to use, it
 * never causes traffic to bypass the proxy. The proxy password itself is
 * never stored here -- see [SecureCredentialStore] for that.
 */
class SettingsDataStore(
    context: Context,
    private val secureCredentialStore: SecureCredentialStore,
) {
    private val appContext = context

    private object Keys {
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_SCHEME = stringPreferencesKey("proxy_scheme")
        val HOMEPAGE = stringPreferencesKey("homepage")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val MAX_TABS = intPreferencesKey("max_tabs")
        val DOWNLOADS_ENABLED = booleanPreferencesKey("downloads_enabled")
    }

    suspend fun save(config: DeviceConfig) {
        secureCredentialStore.saveProxyCredentials(config.proxy.username, config.proxy.password)
        appContext.dataStore.edit { prefs ->
            prefs[Keys.PROXY_HOST] = config.proxy.host
            prefs[Keys.PROXY_PORT] = config.proxy.port
            prefs[Keys.PROXY_SCHEME] = config.proxy.scheme
            prefs[Keys.HOMEPAGE] = config.browser.homepage
            prefs[Keys.SEARCH_ENGINE] = config.browser.searchEngine
            prefs[Keys.MAX_TABS] = config.browser.maxTabs
            prefs[Keys.DOWNLOADS_ENABLED] = config.browser.downloadsEnabled
        }
    }

    suspend fun load(): DeviceConfig? {
        val prefs = appContext.dataStore.data.first()
        val host = prefs[Keys.PROXY_HOST] ?: return null
        val port = prefs[Keys.PROXY_PORT] ?: return null
        val scheme = prefs[Keys.PROXY_SCHEME] ?: "https"
        val username = secureCredentialStore.proxyUsername() ?: return null
        val password = secureCredentialStore.proxyPassword() ?: return null
        return DeviceConfig(
            proxy = ProxyConfig(host, port, scheme, username, password),
            browser = BrowserSettings(
                homepage = prefs[Keys.HOMEPAGE] ?: "https://www.google.com",
                searchEngine = prefs[Keys.SEARCH_ENGINE] ?: "google",
                maxTabs = prefs[Keys.MAX_TABS] ?: 10,
                downloadsEnabled = prefs[Keys.DOWNLOADS_ENABLED] ?: true,
            ),
        )
    }
}
