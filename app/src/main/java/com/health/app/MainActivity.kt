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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST = 2001
    private val PERM_REQUEST = 1001

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ★ 崩溃捕获：必须在 super.onCreate 之前安装
        installCrashHandler()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 讯飞 SparkChain 初始化
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

        // 2. WebView 配置
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
            // ★ 禁用缓存：每次打开都加载最新 index.html
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = WebViewClient()

        // 3. WebChromeClient：alert/confirm/prompt + 文件选择
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView, url: String, msg: String, result: JsResult): Boolean {
                AlertDialog.Builder(v.context).setMessage(msg)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setCancelable(false).show()
                return true
            }
            override fun onJsConfirm(v: WebView, url: String, msg: String, result: JsResult): Boolean {
                AlertDialog.Builder(v.context).setMessage(msg)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false).show()
                return true
            }
            override fun onJsPrompt(v: WebView, url: String, msg: String,
                                    def: String?, result: JsPromptResult): Boolean {
                val edit = EditText(v.context).apply {
                    setText(def ?: ""); setSelection(text.length)
                }
                AlertDialog.Builder(v.context).setMessage(msg).setView(edit)
                    .setPositiveButton("确定") { _, _ -> result.confirm(edit.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false).show()
                return true
            }
            override fun onShowFileChooser(
                v: WebView,
                cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = cb
                return try {
                    val intent = params.createIntent()
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "file chooser failed", e)
                    fileChooserCallback = null; false
                }
            }
        }

        // 4. 注入原生桥接
        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // 5. 加载页面
        webView.loadUrl("file:///android_asset/index.html")

        // 6. 启动久坐提醒
        bridge.startSitReminder()

        // 7. 权限申请
        requestNeededPermissions()
    }

    /** ★ 崩溃捕获：把堆栈写进 SharedPreferences，下次启动时弹窗提示 */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val log = sw.toString().take(4000)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                val sp = getSharedPreferences("app_storage", MODE_PRIVATE)
                sp.edit().putString(
                    "last_crash",
                    "[$time] ${throwable.javaClass.simpleName}: ${throwable.message}\n$log"
                ).apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), PERM_REQUEST)
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
                        val n = data.clipData!!.itemCount
                        Array(n) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    else -> null
                }
                cb.onReceiveValue(uris)
            } else cb.onReceiveValue(null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "perm result: ${grantResults.joinToString()}")
    }

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