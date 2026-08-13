package com.alphainventor.filemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-browser blocking screen shown while the proxy is not verified as
 * reachable and authenticated. There is deliberately no path from this
 * screen to loading a page directly -- only Retry, which re-runs the same
 * verification the app does at startup.
 */
@Composable
fun ProxyUnavailableScreen(detail: String, onRetry: () -> Unit) {
    FullScreenMessage(
        icon = Icons.Filled.CloudOff,
        title = "Can't connect to the browsing service",
        body = "Check your connection and try again.\n\n$detail",
        actionLabel = "Retry",
        onAction = onRetry,
    )
}

@Composable
fun PageErrorScreen(type: BrowserErrorType, detail: String, onRetry: () -> Unit) {
    val (icon, title) = when (type) {
        BrowserErrorType.SSL_ERROR -> Icons.Filled.GppMaybe to "This connection is not private"
        BrowserErrorType.DNS_ERROR -> Icons.Filled.SearchOff to "Site can't be reached"
        BrowserErrorType.CONNECTION_TIMEOUT -> Icons.Filled.CloudOff to "Connection timed out"
        BrowserErrorType.PROXY_AUTH_FAILED -> Icons.Filled.GppMaybe to "Browsing service rejected this device"
        BrowserErrorType.GENERIC, BrowserErrorType.PROXY_UNAVAILABLE -> Icons.Filled.WarningAmber to "Something went wrong"
    }
    val body = when (type) {
        BrowserErrorType.SSL_ERROR -> "The site's security certificate could not be verified. For your safety, this page has been blocked."
        else -> detail.ifBlank { "The page could not be loaded." }
    }
    FullScreenMessage(icon = icon, title = title, body = body, actionLabel = "Retry", onAction = onRetry)
}

@Composable
private fun FullScreenMessage(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun ConnectingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
        Text("Connecting to the browsing service...", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
