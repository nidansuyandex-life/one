package com.health.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST = 2001
    private val PERM_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 讯飞初始化
        try {
            val config = SparkChainConfig.builder()
            config.appID("4c627b59")
                  .apiKey("2fbffaacd145309be7c024db99e9c7ef")
                  .apiSecret("YzI1MDJmYWM0NTliNzNkMjI3NGIyM2Uz")
            config.logLevel(LogLvl.VERBOSE.value)
            val ret = SparkChain.getInst().init(applicationContext, config)
            Log.d("Iflytek", "SparkChain init result = $ret")
        } catch (e: Exception) {
            Log.e("Iflytek", "SparkChain init failed", e)
        }

        // 2. WebView
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
        webView.webViewClient = WebViewClient()

        // 3. WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setCancelable(false).show()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false).show()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String, message: String,
                                    defaultValue: String?, result: JsPromptResult): Boolean {
                val edit = EditText(view.context).apply {
                    setText(defaultValue ?: ""); setSelection(text.length)
                }
                AlertDialog.Builder(view.context)
                    .setMessage(message).setView(edit)
                    .setPositiveButton("确定") { _, _ -> result.confirm(edit.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false).show()
                return true
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    val intent = fileChooserParams.createIntent()
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (e: Exception) {
                    Log.e("WebView", "file chooser failed", e)
                    fileChooserCallback = null; false
                }
            }
        }

        // 4. 桥接
        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // 5. 加载页面
        webView.loadUrl("file:///android_asset/index.html")

        // 6. ★ 启动久坐提醒
        bridge.startSitReminder()

        // 7. 权限申请
        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), PERM_REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val cb = fileChooserCallback ?: return
            fileChooserCallback = null
            if (resultCode == RESULT_OK && data != null) {
                val uris: Array<Uri>? = when {
                    data.data != null -> arrayOf(data.data!!)
                    data.clipData != null -> {
                        val count = data.clipData!!.itemCount
                        Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    else -> null
                }
                cb.onReceiveValue(uris)
            } else {
                cb.onReceiveValue(null)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("App", "perm result: ${grantResults.joinToString()}")
    }

    // ★ 更新前台/后台状态
    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized) bridge.isActivityForeground = true
    }

    override fun onPause() {
        if (::bridge.isInitialized) bridge.isActivityForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        try { fileChooserCallback?.onReceiveValue(null) } catch (_: Exception) {}
        fileChooserCallback = null
        try { SparkChain.getInst().unInit() } catch (_: Exception) {}
        try { bridge.destroy() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}