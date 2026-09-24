package com.health.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "VoiceBridge"
        private const val VOICE_DURATION_MS = 15_000L
        private const val PREFS = "app_storage"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var autoStop: Runnable? = null
    private var isRecognizing = false
    private var lastErrorCode = 0

    // ==================== 语音识别 ====================

    @JavascriptInterface
    fun isSpeechAvailable(): Boolean {
        val sysOk = try {
            SpeechRecognizer.isRecognitionAvailable(activity)
        } catch (e: Exception) { false }
        if (sysOk) return true
        return try {
            SpeechRecognizer.createSpeechRecognizer(activity).also { it.destroy() }
            true
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun startVoice() = handler.post { doStart() }

    @JavascriptInterface
    fun stopVoice() = handler.post {
        // ★ 用 cancel() 而不是 stopListening()，立即释放，避免状态残留
        try { recognizer?.cancel() } catch (_: Exception) {}
        cancelAutoStop()
        isRecognizing = false
    }

    /** ★ 整个 App 生命周期只创建一个 SpeechRecognizer */
    private fun ensureRecognizer(): SpeechRecognizer {
        val cur = recognizer
        if (cur != null) return cur
        val nr = SpeechRecognizer.createSpeechRecognizer(activity)
        nr.setRecognitionListener(buildListener())
        recognizer = nr
        return nr
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            notifyState("started")
        }
        override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            val t = partial?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrEmpty()) lastPartial = t
        }
        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults")
            isRecognizing = false
            cancelAutoStop()
            val text = results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: lastPartial
            lastPartial = ""
            if (text.isNullOrEmpty()) notifyError("没有识别到内容")
            else notifyResult(text)
        }
        override fun onError(error: Int) {
            Log.w(TAG, "onError: $error (${errorName(error)})")
            isRecognizing = false
            cancelAutoStop()
            lastErrorCode = error
            notifyError(errorName(error))
        }
    }

    private var lastPartial = ""

    private fun errorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容，请再说一遍"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话，请重试"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，请检查网络"
        SpeechRecognizer.ERROR_AUDIO -> "录音错误，请重试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请 1 秒后重试"
        // ★ 客户端错误：一般是上次会话未释放，给出明确提示
        SpeechRecognizer.ERROR_CLIENT -> "语音服务连接异常，已重置，请再点一次麦克风"
        SpeechRecognizer.ERROR_SERVER -> "服务端错误，请稍后重试"
        else -> "识别失败 (code=$error)"
    }

    private fun doStart() {
        if (isRecognizing) {
            Log.d(TAG, "已经在识别中，忽略")
            return
        }
        if (!isSpeechAvailable()) {
            notifyError("当前手机没有可用的语音识别服务，请使用下方【手动输入文字】")
            return
        }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            notifyError("请先授予录音权限，授权后再次点击麦克风")
            return
        }

        // ★ 无论上次是正常结束还是报错，都先 cancel 一次清状态
        try { recognizer?.cancel() } catch (_: Exception) {}

        // ★ 延迟 120ms 再启动，给系统/讯飞 释放上一个会话的时间
        handler.postDelayed({ doStartInternal() }, 120)
    }

    private fun doStartInternal() {
        val rec = try { ensureRecognizer() } catch (e: Exception) {
            Log.e(TAG, "createSpeechRecognizer failed", e)
            notifyError("语音服务初始化失败，请重启 App")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        }

        try {
            rec.startListening(intent)
            isRecognizing = true
            notifyState("started")
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw", e)
            // ★ 抛异常说明实例坏了，销毁重建
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            isRecognizing = false
            notifyError("启动失败，请再点一次麦克风")
            return
        }

        autoStop = Runnable {
            Log.d(TAG, "15s 到，自动停止")
            try { recognizer?.stopListening() } catch (_: Exception) {}
        }
        handler.postDelayed(autoStop!!, VOICE_DURATION_MS)
    }

    private fun cancelAutoStop() {
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null
    }

    /** Activity 销毁时清理 */
    fun destroy() {
        cancelAutoStop()
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        isRecognizing = false
    }

    // ==================== 持久化 ====================

    @JavascriptInterface
    fun save(key: String, value: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }
    @JavascriptInterface
    fun load(key: String): String? =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
    @JavascriptInterface
    fun remove(key: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
    }

    // ==================== 回调 JS ====================

    private fun esc(s: String): String =
        s.replace("\\", "\\\\")
         .replace("'", "\\'")
         .replace("\n", " ")
         .replace("\r", " ")

    private fun notifyResult(t: String) = webView.post {
        webView.evaluateJavascript(
            "window.onNativeSpeechResult && window.onNativeSpeechResult('${esc(t)}')", null)
    }
    private fun notifyError(m: String) = webView.post {
        webView.evaluateJavascript(
            "window.onNativeSpeechError && window.onNativeSpeechError('${esc(m)}')", null)
    }
    private fun notifyState(s: String) = webView.post {
        webView.evaluateJavascript(
            "window.onNativeSpeechState && window.onNativeSpeechState('$s')", null)
    }
}