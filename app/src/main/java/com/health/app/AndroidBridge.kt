package com.health.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import java.util.Calendar

class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "VoiceBridge"
        private const val VOICE_DURATION_MS = 15_000L
        private const val PREFS = "app_storage"
        private const val SAMPLE_RATE = 16000

        // ★ 久坐提醒配置
        private const val SIT_INTERVAL_MS = 30 * 60 * 1000L   // 每 30 分钟
        private const val SIT_WINDOW_START = 8 * 60 + 30      // 8:30
        private const val SIT_WINDOW_END   = 17 * 60          // 17:00
        private const val SIT_NOTIFICATION_ID = 3001
        private const val SIT_CHANNEL_ID = "sit_reminder"
    }

    // ===== 语音识别变量 =====
    private val handler = Handler(Looper.getMainLooper())
    private var asr: ASR? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false
    private var autoStop: Runnable? = null
    private val resultBuffer = StringBuilder()

    // ===== ★ 久坐提醒变量 =====
    private val sitHandler = Handler(Looper.getMainLooper())
    private var sitRunnable: Runnable? = null
    @Volatile var isActivityForeground: Boolean = true   // MainActivity 会更新

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
                isRecording = false
                cancelAutoStop()
                stopAudioRecord()
                notifyError("识别失败($code): $msg")
            }
            override fun onBeginOfSpeech() { notifyState("started") }
            override fun onEndOfSpeech() {}
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
        a.language("zh_cn")
        a.domain("iat")
        a.accent("mandarin")
        a.vinfo(true)
        a.dwa("wpgs")
        val sessionId = System.currentTimeMillis().toString()
        val ret = a.start(sessionId)
        if (ret != 0) {
            notifyError("识别启动失败，错误码：$ret")
            return
        }
        if (!startAudioRecord()) {
            try { a.stop(true) } catch (_: Exception) {}
            return
        }
        autoStop = Runnable { doStop() }
        handler.postDelayed(autoStop!!, VOICE_DURATION_MS)
    }

    private fun doStop() {
        cancelAutoStop()
        if (!isRecording && audioRecord == null) return
        stopAudioRecord()
        try { asr?.stop(false) } catch (_: Exception) {}
    }

    private fun cancelAutoStop() {
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null
    }

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
                val buf = ByteArray(1280)
                while (isRecording) {
                    val read = ar.read(buf, 0, buf.size)
                    if (read > 0) {
                        val cur = asr ?: break
                        val ret = cur.write(buf.copyOf(read))
                        if (ret != 0) break
                    } else if (read < 0) break
                }
            }.also { it.start() }
            true
        } catch (e: Exception) {
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

    // ==================== ★ 久坐提醒 ====================

    /** MainActivity 在 onCreate 里调用，启动 30 分钟定时器 */
    fun startSitReminder() {
        stopSitReminder()
        sitRunnable = object : Runnable {
            override fun run() {
                try { checkAndRemind() } catch (e: Exception) {
                    Log.e(TAG, "sit check failed", e)
                }
                sitHandler.postDelayed(this, SIT_INTERVAL_MS)
            }
        }
        // 启动后先等 30 分钟再第一次触发（避免一进 App 就弹）
        sitHandler.postDelayed(sitRunnable!!, SIT_INTERVAL_MS)
        Log.d(TAG, "sit reminder started, interval = ${SIT_INTERVAL_MS / 60000} min")
    }

    fun stopSitReminder() {
        sitRunnable?.let { sitHandler.removeCallbacks(it) }
        sitRunnable = null
    }

    private fun checkAndRemind() {
        val cal = Calendar.getInstance()
        val totalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (totalMin < SIT_WINDOW_START || totalMin > SIT_WINDOW_END) {
            Log.d(TAG, "sit reminder: outside window, skip")
            return
        }

        if (isActivityForeground) {
            // 前台：调用 JS 弹出居中盒子
            webView.post {
                webView.evaluateJavascript(
                    "window.showCenterTimer && window.showCenterTimer(90)", null)
            }
            Log.d(TAG, "sit reminder: shown in foreground")
        } else {
            // 后台：发通知栏提醒
            showSitNotification()
            Log.d(TAG, "sit reminder: shown in notification")
        }
    }

    private fun showSitNotification() {
        try {
            val ctx = activity
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Android 8+ 需要通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    SIT_CHANNEL_ID, "久坐提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "每 30 分钟提醒站起来活动 90 秒" }
                nm.createNotificationChannel(ch)
            }

            // 点击通知打开 App
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
            }
            val pi = PendingIntent.getActivity(ctx, SIT_NOTIFICATION_ID, intent, piFlags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, SIT_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("该站起来活动了")
                .setContentText("已连续久坐 30 分钟，起来走一走，90 秒就好")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setDefaults(Notification.DEFAULT_ALL)
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_HIGH)

            nm.notify(SIT_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "showSitNotification failed", e)
        }
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
        s.replace("\\", "\\\\").replace("'", "\\'")
         .replace("\n", " ").replace("\r", " ")

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

    fun destroy() {
        cancelAutoStop()
        stopAudioRecord()
        try { asr?.stop(true) } catch (_: Exception) {}
        asr = null
        stopSitReminder()
    }
}