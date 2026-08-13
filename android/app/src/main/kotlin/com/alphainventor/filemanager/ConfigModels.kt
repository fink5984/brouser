package com.alphainventor.filemanager

/** Connection info for the forward proxy all WebView traffic must use. */
data class ProxyConfig(
    val host: String,
    val port: Int,
    /** "http" for a plain proxy connection, "https" for a TLS-secured one. */
    val scheme: String,
    val username: String,
    val password: String,
)

data class BrowserSettings(
    val homepage: String,
    val searchEngine: String,
    val maxTabs: Int,
    val downloadsEnabled: Boolean,
)

data class DeviceConfig(
    val proxy: ProxyConfig,
    val browser: BrowserSettings,
)
