package com.health.app

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        try { bridge.destroy() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}