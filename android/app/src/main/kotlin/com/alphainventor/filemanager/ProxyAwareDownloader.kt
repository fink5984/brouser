package com.alphainventor.filemanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

/**
 * Downloads files through the exact same proxy WebView uses. The platform
 * DownloadManager service runs outside the app's process and has no
 * knowledge of our WebView-scoped proxy override, so using it would let
 * downloads silently bypass the proxy -- this class exists specifically to
 * avoid that.
 */
class ProxyAwareDownloader(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var installedProxyAuth: ProxyConfig? = null

    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        proxy: ProxyConfig,
    ) {
        ensureChannel()
        installProxyAuthenticator(proxy)

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val notificationId = fileName.hashCode()

        scope.launch {
            runCatching { download(url, userAgent, mimeType, fileName, notificationId) }
                .onFailure { notifyFailed(fileName, notificationId) }
        }
    }

    private fun download(
        url: String,
        userAgent: String?,
        mimeType: String?,
        fileName: String,
        notificationId: Int,
    ) {
        notifyProgress(fileName, notificationId, indeterminate = true, percent = 0)

        val connection = (URL(url).openConnection(systemProxy()) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            userAgent?.let { setRequestProperty("User-Agent", it) }
        }

        connection.connect()
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            notifyFailed(fileName, notificationId)
            return
        }

        val resolvedMime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"
        val totalBytes = connection.contentLengthLong

        val (outputStream, publicUri) = openDownloadTarget(fileName, resolvedMime)
        outputStream.use { out ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesCopied = 0L
                var lastNotifiedPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    bytesCopied += read
                    if (totalBytes > 0) {
                        val percent = (bytesCopied * 100 / totalBytes).toInt()
                        if (percent != lastNotifiedPercent) {
                            notifyProgress(fileName, notificationId, indeterminate = false, percent = percent)
                            lastNotifiedPercent = percent
                        }
                    }
                }
            }
        }
        connection.disconnect()
        notifyComplete(fileName, notificationId, publicUri)
    }

    private fun systemProxy(): Proxy =
        installedProxyAuth?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(it.host, it.port)) }
            ?: Proxy.NO_PROXY

    /**
     * java.net has no per-request proxy credential API for CONNECT tunnels;
     * proxy auth is supplied via a process-wide Authenticator. That's an
     * acceptable trade-off here because this app only ever talks to one
     * proxy with one credential set -- there is nothing else it could leak
     * credentials to.
     */
    private fun installProxyAuthenticator(proxy: ProxyConfig) {
        if (installedProxyAuth == proxy) return
        installedProxyAuth = proxy
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType == RequestorType.PROXY) {
                    return PasswordAuthentication(proxy.username, proxy.password.toCharArray())
                }
                return null
            }
        })
    }

    private fun openDownloadTarget(fileName: String, mimeType: String): Pair<OutputStream, Uri?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create download entry")
            val stream = resolver.openOutputStream(uri) ?: error("Unable to open download stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return stream to uri
        }

        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = java.io.File(downloadsDir, fileName)
        return file.outputStream() to Uri.fromFile(file)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun notifyProgress(fileName: String, id: Int, indeterminate: Boolean, percent: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(if (indeterminate) "Downloading..." else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .build()
        NotificationManagerCompat.from(context).notifySafely(id, notification)
    }

    private fun notifyComplete(fileName: String, id: Int, uri: Uri?) {
        val viewIntent = uri?.let {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it, MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "")))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val pendingIntent = viewIntent?.let {
            android.app.PendingIntent.getActivity(
                context, id, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText("Download complete")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notifySafely(id, notification)
    }

    private fun notifyFailed(fileName: String, id: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(fileName)
            .setContentText("Download failed")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notifySafely(id, notification)
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
    }
}

/** Swallows the SecurityException that fires when POST_NOTIFICATIONS hasn't been granted yet. */
private fun NotificationManagerCompat.notifySafely(id: Int, notification: android.app.Notification) {
    try {
        notify(id, notification)
    } catch (_: SecurityException) {
        // Notification permission not granted; the download itself still
        // succeeds, the user just won't see progress/completion toasts.
    }
}
