package com.alphainventor.filemanager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun BrowserMenu(
    expanded: Boolean,
    isCurrentPageBookmarked: Boolean,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onHistory: () -> Unit,
    onBookmarks: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onShare: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("New tab") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { onDismiss(); onNewTab() },
        )
        DropdownMenuItem(
            text = { Text(if (isCurrentPageBookmarked) "Remove bookmark" else "Add bookmark") },
            leadingIcon = { Icon(if (isCurrentPageBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, contentDescription = null) },
            onClick = { onDismiss(); onToggleBookmark() },
        )
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = { onDismiss(); onShare() },
        )
        DropdownMenuItem(
            text = { Text("History") },
            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
            onClick = { onDismiss(); onHistory() },
        )
        DropdownMenuItem(
            text = { Text("Bookmarks") },
            leadingIcon = { Icon(Icons.Filled.BookmarkBorder, contentDescription = null) },
            onClick = { onDismiss(); onBookmarks() },
        )
        DropdownMenuItem(
            text = { Text("Downloads") },
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            onClick = { onDismiss(); onDownloads() },
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { onDismiss(); onSettings() },
        )
        DropdownMenuItem(
            text = { Text("About") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = { onDismiss(); onAbout() },
        )
    }
}
