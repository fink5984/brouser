package com.alphainventor.filemanager

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Hosts whichever WebView is currently active, re-parenting it as tabs switch. */
@Composable
fun WebViewHost(webView: WebView?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            android.widget.FrameLayout(context)
        },
        update = { container ->
            if (container.childCount == 1 && container.getChildAt(0) === webView) return@AndroidView
            container.removeAllViews()
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.let { container.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        },
    )
}
