package com.alphainventor.filemanager

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the proxy credentials at rest using AES-256-GCM via Android's
 * Keystore-backed MasterKey, not plain DataStore. This is the one place in
 * the app that ever sees the proxy password after it arrives from the
 * backend over TLS.
 */
class SecureCredentialStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_proxy_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveProxyCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun proxyUsername(): String? = prefs.getString(KEY_USERNAME, null)
    fun proxyPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "proxy_username"
        const val KEY_PASSWORD = "proxy_password"
    }
}
