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

    // ★ 用于保存文件选择器的回调
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化讯飞 SparkChain
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

        // 2. WebView 基础设置
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

        // 3. WebViewClient
        webView.webViewClient = WebViewClient()

        // 4. ★★★ WebChromeClient：alert/confirm/prompt + 文件选择 ★★★
        webView.webChromeClient = object : WebChromeClient() {

            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String, message: String,
                defaultValue: String?, result: JsPromptResult
            ): Boolean {
                val edit = EditText(view.context).apply {
                    setText(defaultValue ?: "")
                    setSelection(text.length)
                }
                AlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setView(edit)
                    .setPositiveButton("确定") { _, _ -> result.confirm(edit.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }

            // ★★★ 关键：让 <input type="file"> 能打开系统选择器 ★★★
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // 先取消上一次未完成的回调，避免卡住
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                return try {
                    val intent = fileChooserParams.createIntent()
                    // 允许选择图片（accept="image/*" 已由 html 提供）
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (e: Exception) {
                    Log.e("WebView", "打开文件选择器失败", e)
                    fileChooserCallback = null
                    false
                }
            }
        }

        // 5. 注入原生桥接
        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // 6. 加载页面
        webView.loadUrl("file:///android_asset/index.html")

        // 7. 申请录音权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
            if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
        }
    }

    // ★★★ 文件选择结果回传给 WebView ★★★
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST) {
            val cb = fileChooserCallback ?: return
            fileChooserCallback = null

            if (resultCode == RESULT_OK && data != null) {
                // 返回用户选择的文件 URI
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
                // 用户取消
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

    override fun onDestroy() {
        try { fileChooserCallback?.onReceiveValue(null) } catch (_: Exception) {}
        fileChooserCallback = null
        try { SparkChain.getInst().unInit() } catch (_: Exception) {}
        try { bridge.destroy() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}