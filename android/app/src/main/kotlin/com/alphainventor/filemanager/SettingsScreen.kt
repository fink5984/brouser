package com.alphainventor.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    homepage: String,
    searchEngine: String,
    maxTabs: Int,
    downloadsEnabled: Boolean,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onClearCookiesAndSiteData: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(headlineContent = { Text("Homepage") }, supportingContent = { Text(homepage) })
            ListItem(headlineContent = { Text("Search engine") }, supportingContent = { Text(searchEngine.replaceFirstChar { it.uppercase() }) })
            ListItem(headlineContent = { Text("Maximum tabs") }, supportingContent = { Text(maxTabs.toString()) })
            ListItem(headlineContent = { Text("Downloads") }, supportingContent = { Text(if (downloadsEnabled) "Enabled" else "Disabled") })
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Clear browsing history") },
                leadingContent = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                modifier = Modifier.clickable { onClearHistory() },
            )
            ListItem(
                headlineContent = { Text("Clear cookies and site data") },
                supportingContent = { Text("Signs you out of every site") },
                leadingContent = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                modifier = Modifier.clickable { onClearCookiesAndSiteData() },
            )
        }
    }
}
