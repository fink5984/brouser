package com.alphainventor.filemanager

import android.content.Context

/** Minimal hand-rolled DI container: one instance of each shared dependency, built once at app startup. */
class AppContainer(context: Context) {
    val configApi = ConfigApi(baseUrl = BuildConfig.CONFIG_BASE_URL, deviceToken = BuildConfig.DEVICE_TOKEN)
    val secureCredentialStore = SecureCredentialStore(context)
    val settingsDataStore = SettingsDataStore(context, secureCredentialStore)
    val proxyManager = ProxyManager(context)
    val database = AppDatabase.get(context)
    val downloader = ProxyAwareDownloader(context)
}
