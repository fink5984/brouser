package com.alphainventor.filemanager

import android.content.Intent
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * Everything about handling a tab event that requires an Activity (file
 * pickers, permission prompts, launching other apps). Implemented by
 * MainActivity and attached to the ViewModel after creation so the
 * ViewModel itself never holds an Activity reference.
 */
interface BrowserActivityBridge {
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun hasLocationPermission(): Boolean
    fun requestLocationPermission(onResult: (granted: Boolean) -> Unit)
    fun launchExternalIntent(intent: Intent)
    fun shareUrl(url: String)
    fun showCustomView(view: android.view.View, callback: WebChromeClient.CustomViewCallback)
    fun hideCustomView()
}
