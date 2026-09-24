package com.health.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks

class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "VoiceBridge"
        private const val VOICE_DURATION_MS = 15_000L
        private const val PREFS = "app_storage"
        private const val SAMPLE_RATE = 16000
    }

    private val handler = Handler(Looper.getMainLooper())
    private var asr: ASR? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false
    private var autoStop: Runnable? = null
    private val resultBuffer = StringBuilder()

    // ==================== 语音识别 ====================

    @JavascriptInterface
    fun isSpeechAvailable(): Boolean = true

    @JavascriptInterface
    fun startVoice() = handler.post { doStart() }

    @JavascriptInterface
    fun stopVoice() = handler.post { doStop() }

    private fun ensureAsr(): ASR {
        asr?.let { return it }
        val a = ASR()
        a.registerCallbacks(object : AsrCallbacks {
            override fun onResult(asrResult: ASR.ASRResult?, o: Any?) {
                if (asrResult == null) return
                val status = asrResult.status
                val text = asrResult.bestMatchText
                Log.d(TAG, "onResult status=$status text=$text")

                if (!text.isNullOrEmpty()) {
                    resultBuffer.setLength(0)
                    resultBuffer.append(text)
                }

                if (status == 2) {
                    isRecording = false
                    cancelAutoStop()
                    stopAudioRecord()
                    val finalText = resultBuffer.toString().trim()
                    if (finalText.isNotEmpty()) notifyResult(finalText)
                    else notifyError("没有识别到内容")
                }
            }

            override fun onError(asrError: ASR.ASRError?, o: Any?) {
                val code = asrError?.code ?: -1
                val msg = asrError?.errMsg ?: "识别失败"
                Log.w(TAG, "onError code=$code msg=$msg")
                isRecording = false
                cancelAutoStop()
                stopAudioRecord()
                notifyError("识别失败($code): $msg")
            }

            override fun onBeginOfSpeech() {
                Log.d(TAG, "onBeginOfSpeech")
                notifyState("started")
            }

            override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
        })
        asr = a
        return a
    }

    private fun doStart() {
        if (isRecording) return

        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            notifyError("请先授予录音权限，授权后再次点击麦克风")
            return
        }

        resultBuffer.setLength(0)
        val a = ensureAsr()

        // 讯飞 ASR 参数（与 Sample 一致）
        a.language("zh_cn")
        a.domain("iat")
        a.accent("mandarin")
        a.vinfo(true)
        a.dwa("wpgs")

        val sessionId = System.currentTimeMillis().toString()
        val ret = a.start(sessionId)
        if (ret != 0) {
            Log.e(TAG, "asr.start failed, code=$ret")
            notifyError("识别启动失败，错误码：$ret")
            return
        }

        if (!startAudioRecord()) {
            try { a.stop(true) } catch (_: Exception) {}
            return
        }

        autoStop = Runnable {
            Log.d(TAG, "15s timeout, auto stop")
            doStop()
        }
        handler.postDelayed(autoStop!!, VOICE_DURATION_MS)
    }

    private fun doStop() {
        cancelAutoStop()
        if (!isRecording && audioRecord == null) return
        stopAudioRecord()
        try { asr?.stop(false) } catch (e: Exception) {
            Log.e(TAG, "asr.stop failed", e)
        }
    }

    private fun cancelAutoStop() {
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null
    }

    // ==================== 麦克风采集 ====================

    private fun startAudioRecord(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = if (minBuf < 4096) 4096 else minBuf

            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                notifyError("麦克风初始化失败，请检查权限")
                return false
            }
            audioRecord = ar
            ar.startRecording()
            isRecording = true

            recordThread = Thread {
                val buf = ByteArray(1280)   // 40ms 一帧
                while (isRecording) {
                    val read = ar.read(buf, 0, buf.size)
                    if (read > 0) {
                        val cur = asr ?: break
                        val ret = cur.write(buf.copyOf(read))
                        if (ret != 0) {
                            Log.w(TAG, "asr.write ret=$ret")
                            break
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            }.also { it.start() }

            true
        } catch (e: SecurityException) {
            Log.e(TAG, "no RECORD_AUDIO permission", e)
            notifyError("缺少录音权限")
            false
        } catch (e: Exception) {
            Log.e(TAG, "startAudioRecord failed", e)
            notifyError("录音启动失败：${e.message}")
            false
        }
    }

    private fun stopAudioRecord() {
        isRecording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        recordThread = null
    }

    fun destroy() {
        cancelAutoStop()
        stopAudioRecord()
        try { asr?.stop(true) } catch (_: Exception) {}
        asr = null
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