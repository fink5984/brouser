package com.alphainventor.filemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity(), BrowserActivityBridge {

    private lateinit var fileChooserHandler: FileChooserHandler
    private var pendingLocationCallback: ((Boolean) -> Unit)? = null
    private var fullscreenContainer: ViewGroup? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingLocationCallback?.invoke(granted)
            pendingLocationCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private val viewModel: BrowserViewModel by viewModels {
        BrowserViewModel.Factory(applicationContext, (application as ManagedBrowserApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        fileChooserHandler = FileChooserHandler(this)
        viewModel.bridge = this

        requestNotificationPermissionIfNeeded()

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.goBack()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent {
            ManagedBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.bridge = null
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // --- BrowserActivityBridge -------------------------------------------

    override fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean =
        fileChooserHandler.showChooser(callback, params)

    override fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun requestLocationPermission(onResult: (granted: Boolean) -> Unit) {
        pendingLocationCallback = onResult
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun launchExternalIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Nothing on the device can handle it; fail silently rather
            // than crash the browser.
        }
    }

    override fun shareUrl(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        val decorView = window.decorView as ViewGroup
        val container = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(view)
        }
        decorView.addView(container)
        fullscreenContainer = container
        WindowCompat.getInsetsController(window, decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun hideCustomView() {
        val decorView = window.decorView as ViewGroup
        fullscreenContainer?.let { decorView.removeView(it) }
        fullscreenContainer = null
        WindowCompat.getInsetsController(window, decorView).show(WindowInsetsCompat.Type.systemBars())
    }
}

