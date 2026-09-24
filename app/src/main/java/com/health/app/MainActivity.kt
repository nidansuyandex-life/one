package com.health.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ★ 1. 初始化讯飞 SparkChain SDK
        initSparkChain()

        // ★ 2. 配置 WebView
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

        // ★ 3. 注入桥接
        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.webViewClient = WebViewClient()

        // ★ 4. 加载页面
        webView.loadUrl("file:///android_asset/index.html")

        // ★ 5. 动态申请录音权限
        requestPermissionsIfNeeded()
    }

    private fun initSparkChain() {
        try {
            val config = SparkChainConfig.builder()
                .appID("4c627b59")
                .apiKey("2fbffaacd145309be7c024db99e9c7ef")
                .apiSecret("YzI1MDJmYWM0NTliNzNkMjI3NGIyM2Uz")
                .logLevel(3)   // 0:关闭 1:错误 2:警告 3:信息 4:调试

            val ret = SparkChain.getInst().init(this, config)
            Log.d("Iflytek", "SparkChain init result = $ret")
        } catch (e: Exception) {
            Log.e("Iflytek", "SparkChain init failed", e)
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.READ_PHONE_STATE
                    ),
                    1001
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty()) {
            Log.d("Iflytek", "Permission result: ${grantResults[0]}")
        }
    }

    override fun onDestroy() {
        try { bridge.destroy() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}