package com.health.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.iflytek.sparkchain.core.recognizer.RecognizerConstant
import com.iflytek.sparkchain.core.recognizer.RecognizerListener
import com.iflytek.sparkchain.core.recognizer.RecognizerResult
import com.iflytek.sparkchain.core.recognizer.SpeechError
import com.iflytek.sparkchain.core.recognizer.SpeechRecognizer
import org.json.JSONObject

class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "VoiceBridge"
        private const val VOICE_DURATION_MS = 15_000L   // 15 秒自动停
        private const val PREFS = "app_storage"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var autoStop: Runnable? = null
    private var isRecognizing = false
    private val resultBuffer = StringBuilder()

    // ==================== 语音识别 ====================

    @JavascriptInterface
    fun isSpeechAvailable(): Boolean = true   // SparkChain 始终可用

    @JavascriptInterface
    fun startVoice() = handler.post { doStart() }

    @JavascriptInterface
    fun stopVoice() = handler.post {
        try { recognizer?.stop() } catch (_: Exception) {}
        cancelAutoStop()
        isRecognizing = false
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.create(activity)
        recognizer = r
        return r
    }

    private fun doStart() {
        if (isRecognizing) {
            Log.d(TAG, "already recognizing, skip")
            return
        }

        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            notifyError("请先授予录音权限，授权后再次点击麦克风")
            return
        }

        val rec = try { ensureRecognizer() } catch (e: Exception) {
            Log.e(TAG, "createRecognizer failed", e)
            notifyError("语音服务初始化失败，请重启 App")
            return
        }

        resultBuffer.setLength(0)

        // ★ SparkChain 参数设置
        rec.setParameter(RecognizerConstant.PARAMS, null)
        rec.setParameter(RecognizerConstant.LANGUAGE, "zh_cn")
        rec.setParameter(RecognizerConstant.ACCENT, "mandarin")
        rec.setParameter(RecognizerConstant.VAD_BOS, 5000)   // 前端点 5s
        rec.setParameter(RecognizerConstant.VAD_EOS, 2000)   // 后端点静音 2s
        rec.setParameter(RecognizerConstant.RESULT_TYPE, "json")
        rec.setParameter(RecognizerConstant.DWA, "wpgs")     // 开启动态修正

        val listener = object : RecognizerListener {
            override fun onVolumeChanged(volume: Int, data: ByteArray?) {}
            override fun onBeginOfSpeech() {
                Log.d(TAG, "onBeginOfSpeech")
                notifyState("started")
            }
            override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }

            override fun onResult(result: RecognizerResult?, isLast: Boolean) {
                val text = parseIatResult(result?.resultString)
                if (text.isNotEmpty()) resultBuffer.append(text)

                if (isLast) {
                    isRecognizing = false
                    cancelAutoStop()
                    val finalText = resultBuffer.toString().trim()
                    if (finalText.isNotEmpty()) notifyResult(finalText)
                    else notifyError("没有识别到内容")
                }
            }

            override fun onError(error: SpeechError?) {
                Log.w(TAG, "onError: ${error?.code} ${error?.message}")
                isRecognizing = false
                cancelAutoStop()
                notifyError(error?.message ?: "识别失败")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        try {
            rec.start(listener)   // ★ SparkChain 启动方法
            isRecognizing = true
            notifyState("started")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            isRecognizing = false
            notifyError("启动失败，请再点一次麦克风")
            return
        }

        // 15 秒自动停
        autoStop = Runnable {
            Log.d(TAG, "15s timeout, auto stop")
            try { recognizer?.stop() } catch (_: Exception) {}
            isRecognizing = false
        }
        handler.postDelayed(autoStop!!, VOICE_DURATION_MS)
    }

    private fun cancelAutoStop() {
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null
    }

    /** 解析 JSON（SparkChain 返回格式） */
    private fun parseIatResult(json: String?): String {
        if (json.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        try {
            val jo = JSONObject(json)
            if (jo.has("ws")) {
                val ws = jo.getJSONArray("ws")
                for (i in 0 until ws.length()) {
                    val cw = ws.getJSONObject(i).getJSONArray("cw")
                    if (cw.length() > 0) {
                        sb.append(cw.getJSONObject(0).getString("w"))
                    }
                }
            } else if (jo.has("text")) {
                sb.append(jo.getString("text"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseIatResult error", e)
        }
        return sb.toString()
    }

    fun destroy() {
        cancelAutoStop()
        try { recognizer?.stop() } catch (_: Exception) {}
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