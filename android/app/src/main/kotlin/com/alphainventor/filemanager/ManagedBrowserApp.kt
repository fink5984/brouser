package com.alphainventor.filemanager

import android.app.Application
import android.webkit.WebView

class ManagedBrowserApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Never enabled in release builds: exposes the WebView's contents
        // to chrome://inspect on the connected host machine.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
