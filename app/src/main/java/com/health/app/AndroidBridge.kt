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
        private const val VOICE_DURATION_MS = 15_000L   // 15 秒自动停
        private const val PREFS = "app_storage"
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoStop: Runnable? = null
    private var isRecognizing = false

    // ==================== 语音识别 ====================

    @JavascriptInterface
    fun isSpeechAvailable(): Boolean {
        // 官方 API
        val sysOk = try {
            SpeechRecognizer.isRecognitionAvailable(activity)
        } catch (e: Exception) {
            Log.w(TAG, "isRecognitionAvailable error", e); false
        }
        if (sysOk) return true

        // 双保险：试建实例（部分 ROM 靠这步兜住）
        return try {
            val t = SpeechRecognizer.createSpeechRecognizer(activity)
            t.destroy()
            Log.d(TAG, "isSpeechAvailable: 官方API返回false，但试建实例成功")
            true
        } catch (e: Exception) {
            Log.w(TAG, "试建实例也失败", e); false
        }
    }

    @JavascriptInterface
    fun startVoice() = handler.post { doStart() }

    @JavascriptInterface
    fun stopVoice() = handler.post {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        cancelAutoStop()
    }

    private fun doStart() {
        if (isRecognizing) {
            Log.d(TAG, "已经在识别中，忽略本次")
            return
        }

        if (!isSpeechAvailable()) {
            Log.w(TAG, "没有可用的语音识别服务")
            notifyError("当前手机没有可用的语音识别服务，请使用下方【手动输入文字】")
            return
        }

        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            notifyError("请先授予录音权限，授权后再次点击麦克风")
            return
        }

        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity)

        val sb = StringBuilder()

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                notifyState("started")
            }
            override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrEmpty()) { sb.clear(); sb.append(t) }
            }

            override fun onResults(results: Bundle?) {
                Log.d(TAG, "onResults")
                isRecognizing = false
                cancelAutoStop()
                val text = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?: sb.toString()
                if (text.isNullOrEmpty()) notifyError("没有识别到内容")
                else notifyResult(text)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "onError: $error")
                isRecognizing = false
                cancelAutoStop()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误"
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                    else -> "识别失败 (code=$error)"
                }
                notifyError(msg)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        }

        try {
            recognizer?.startListening(intent)
            isRecognizing = true
            notifyState("started")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isRecognizing = false
            notifyError("启动失败：" + (e.message ?: "未知错误"))
            return
        }

        // ★ 15 秒自动停，与前端窗口保持一致
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

    // ==================== 持久化（给页面 localStorage 兜底） ====================

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