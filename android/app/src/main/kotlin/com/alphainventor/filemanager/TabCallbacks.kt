package com.alphainventor.filemanager

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** Events a [BrowserWebView] reports back up to whoever owns the tab. */
interface TabCallbacks {
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleChanged(tabId: String, title: String)
    fun onFaviconChanged(tabId: String, favicon: Bitmap?)
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String)
    fun onError(tabId: String, type: BrowserErrorType, description: String)
    fun onDownloadRequested(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
    )
    fun onShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean
    fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback)
    fun onPermissionRequest(request: PermissionRequest)
    /** Creates a brand-new tab for a window.open()/target=_blank request and returns its live WebView. */
    fun onCreateNewTabForWindow(): WebView
    fun onShowCustomView(view: android.view.View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun onExternalNavigation(uri: Uri): Boolean
}
