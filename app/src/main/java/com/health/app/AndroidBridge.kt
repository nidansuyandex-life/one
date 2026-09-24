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
    private val resultBuffer = StringBuilder()

    @JavascriptInterface
    fun isSpeechAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(activity)
    } catch (e: Exception) { false }

    @JavascriptInterface
    fun startVoice() = handler.post { doStart() }

    @JavascriptInterface
    fun stopVoice() = handler.post {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        cancelAutoStop()
        isRecognizing = false
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        r.setRecognitionListener(buildListener())
        recognizer = r
        return r
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { notifyState("started") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrEmpty()) {
                resultBuffer.setLength(0)
                resultBuffer.append(t)
            }
        }
        override fun onResults(results: Bundle?) {
            isRecognizing = false
            cancelAutoStop()
            val text = results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: resultBuffer.toString()
            if (text.isNullOrEmpty()) notifyError("没有识别到内容")
            else notifyResult(text)
        }
        override fun onError(error: Int) {
            isRecognizing = false
            cancelAutoStop()
            notifyError(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
                SpeechRecognizer.ERROR_CLIENT -> "语音连接异常，请再点一次"
                else -> "识别失败 ($error)"
            })
        }
    }

    private fun doStart() {
        if (isRecognizing) return

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

        try { recognizer?.cancel() } catch (_: Exception) {}

        handler.postDelayed({
            val rec = try { ensureRecognizer() } catch (e: Exception) {
                Log.e(TAG, "create failed", e)
                notifyError("语音初始化失败")
                return@postDelayed
            }
            resultBuffer.setLength(0)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                         RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                rec.startListening(intent)
                isRecognizing = true
                notifyState("started")
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                isRecognizing = false
                notifyError("启动失败，请再点一次")
                return@postDelayed
            }
            autoStop = Runnable {
                try { recognizer?.stopListening() } catch (_: Exception) {}
                isRecognizing = false
            }
            handler.postDelayed(autoStop!!, VOICE_DURATION_MS)
        }, 120)
    }

    private fun cancelAutoStop() {
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null
    }

    fun destroy() {
        cancelAutoStop()
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        isRecognizing = false
    }

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