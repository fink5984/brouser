package com.alphainventor.filemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the backend's own config endpoint. This is plain app networking,
 * not WebView traffic -- it is intentionally NOT sent through the managed
 * proxy, since the proxy's own connection details are what it returns.
 */
class ConfigApi(
    private val baseUrl: String,
    private val deviceToken: String,
) {
    sealed class Result {
        data class Success(val config: DeviceConfig) : Result()
        data class Failure(val reason: String) : Result()
    }

    suspend fun fetchConfig(): Result = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/api/v1/device/config")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $deviceToken")
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext Result.Failure("Config request failed with HTTP $code")
            }

            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            Result.Success(parse(body))
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Unknown network error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String): DeviceConfig {
        val json = JSONObject(body)
        val proxyJson = json.getJSONObject("proxy")
        val browserJson = json.getJSONObject("browser")
        return DeviceConfig(
            proxy = ProxyConfig(
                host = proxyJson.getString("host"),
                port = proxyJson.getInt("port"),
                scheme = proxyJson.optString("scheme", "https"),
                username = proxyJson.getString("username"),
                password = proxyJson.getString("password"),
            ),
            browser = BrowserSettings(
                homepage = browserJson.getString("homepage"),
                searchEngine = browserJson.getString("searchEngine"),
                maxTabs = browserJson.getInt("maxTabs"),
                downloadsEnabled = browserJson.getBoolean("downloadsEnabled"),
            ),
        )
    }
}
