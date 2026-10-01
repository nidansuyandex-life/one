package com.example.healthapp;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 讯飞语音听写 · WebSocket 版
 */
public class IatWebSocketHelper {

    private static final String TAG = "IatWsHelper";

    private static final String APPID      = "4c627b59";
    private static final String API_KEY    = "2fbffaacd145309be7c024db99e9c7ef";
    private static final String API_SECRET = "YzI1MDJmYWM0NTliNzNkMjI3NGIyM2Uz";

    private static final String HOST = "iat-api.xfyun.cn";
    private static final String PATH = "/v2/iat";
    private static final int SAMPLE_RATE = 16000;

    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread recordThread;

    private volatile boolean isRecording = false;
    private final StringBuilder resultBuffer = new StringBuilder();

    public IatWebSocketHelper(WebView webView) {
        this.webView = webView;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void start() {
        if (isRecording) return;
        isRecording = true;
        resultBuffer.setLength(0);
        mainHandler.post(() -> callbackState("started"));
        try {
            String wsUrl = buildAuthUrl();
            Request request = new Request.Builder().url(wsUrl).build();
            webSocket = httpClient.newWebSocket(request, new WsListener());
        } catch (Exception e) {
            Log.e(TAG, "start error", e);
            isRecording = false;
            callbackError("启动失败：" + e.getMessage());
        }
    }

    public void stop() {
        if (!isRecording) return;
        isRecording = false;
        try {
            if (webSocket != null) {
                JSONObject frame = new JSONObject();
                JSONObject data = new JSONObject();
                data.put("status", 2);
                data.put("format", "audio/L16;rate=" + SAMPLE_RATE);
                data.put("encoding", "raw");
                data.put("audio", "");
                frame.put("data", data);
                webSocket.send(frame.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "stop send error", e);
        }
    }

    public void destroy() {
        stop();
        try { if (webSocket != null) webSocket.close(1000, "bye"); } catch (Exception ignored) {}
        webSocket = null;
        try {
            if (httpClient != null) {
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
            }
        } catch (Exception ignored) {}
    }

    private String buildAuthUrl() throws Exception {
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = fmt.format(new Date());

        String signatureOrigin = "host: " + HOST + "\n"
                + "date: " + date + "\n"
                + "GET " + PATH + " HTTP/1.1";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signBytes = mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.encodeToString(signBytes, Base64.NO_WRAP);

        String authOrigin = "api_key=\"" + API_KEY + "\", "
                + "algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", "
                + "signature=\"" + signature + "\"";

        String authorization = Base64.encodeToString(
                authOrigin.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        return "wss://" + HOST + PATH
                + "?authorization=" + URLEncoder.encode(authorization, "UTF-8")
                + "&date=" + URLEncoder.encode(date, "UTF-8")
                + "&host=" + HOST;
    }

    private class WsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.d(TAG, "WebSocket opened");
            try {
                JSONObject frame = new JSONObject();
                JSONObject common = new JSONObject();
                common.put("app_id", APPID);
                frame.put("common", common);

                JSONObject business = new JSONObject();
                business.put("language", "zh_cn");
                business.put("domain", "iat");
                business.put("accent", "mandarin");
                business.put("vad_eos", 2000);
                frame.put("business", business);

                JSONObject data = new JSONObject();
                data.put("status", 0);
                data.put("format", "audio/L16;rate=" + SAMPLE_RATE);
                data.put("encoding", "raw");
                data.put("audio", "");
                frame.put("data", data);

                ws.send(frame.toString());
                startRecording(ws);
            } catch (Exception e) {
                Log.e(TAG, "onOpen frame error", e);
                callbackError("发送参数帧失败：" + e.getMessage());
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject jo = new JSONObject(text);
                int code = jo.optInt("code", -1);
                if (code != 0) {
                    String msg = jo.optString("message", "识别错误 code=" + code);
                    callbackError(msg + " (code=" + code + ")");
                    return;
                }
                JSONObject data = jo.optJSONObject("data");
                if (data == null) return;
                int status = data.optInt("status", 1);

                JSONObject result = data.optJSONObject("result");
                if (result != null) {
                    JSONArray wsArr = result.optJSONArray("ws");
                    if (wsArr != null) {
                        for (int i = 0; i < wsArr.length(); i++) {
                            JSONArray cw = wsArr.getJSONObject(i).optJSONArray("cw");
                            if (cw != null && cw.length() > 0) {
                                resultBuffer.append(cw.getJSONObject(0).optString("w", ""));
                            }
                        }
                    }
                    callbackResult(resultBuffer.toString());
                }

                if (status == 2) {
                    String finalText = resultBuffer.toString().trim();
                    if (finalText.isEmpty()) callbackError("未识别到内容");
                    else callbackResult(finalText);
                    isRecording = false;
                    stopRecording();
                    try { ws.close(1000, "done"); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "onMessage parse error", e);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "WebSocket failure: " + t.getMessage());
            isRecording = false;
            stopRecording();
            callbackError("网络连接失败：" + t.getMessage());
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.d(TAG, "WebSocket closed: " + code + " " + reason);
            isRecording = false;
            stopRecording();
        }
    }

    @SuppressLint("MissingPermission")
    private void startRecording(final WebSocket ws) {
        int bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (bufSize <= 0) bufSize = 3200;

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord init failed", e);
            callbackError("麦克风初始化失败：" + e.getMessage());
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            callbackError("麦克风不可用");
            return;
        }

        audioRecord.startRecording();

        recordThread = new Thread(() -> {
            byte[] buffer = new byte[1280];
            while (isRecording && !Thread.currentThread().isInterrupted()) {
                int n = audioRecord.read(buffer, 0, buffer.length);
                if (n <= 0) continue;
                try {
                    byte[] chunk;
                    if (n == buffer.length) chunk = buffer;
                    else {
                        chunk = new byte[n];
                        System.arraycopy(buffer, 0, chunk, 0, n);
                    }
                    JSONObject frame = new JSONObject();
                    JSONObject data = new JSONObject();
                    data.put("status", 1);
                    data.put("format", "audio/L16;rate=" + SAMPLE_RATE);
                    data.put("encoding", "raw");
                    data.put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP));
                    frame.put("data", data);
                    ws.send(frame.toString());
                } catch (Exception e) {
                    Log.e(TAG, "send frame error", e);
                }
            }
        }, "IatRecordThread");
        recordThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (recordThread != null) {
            recordThread.interrupt();
            recordThread = null;
        }
        try {
            if (audioRecord != null) {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            }
        } catch (Exception ignored) {}
        audioRecord = null;
    }

    private void callbackResult(String text) {
        mainHandler.post(() -> evalJs("window.onNativeSpeechResult && window.onNativeSpeechResult(" + jsString(text) + ");"));
    }
    private void callbackError(String msg) {
        mainHandler.post(() -> evalJs("window.onNativeSpeechError && window.onNativeSpeechError(" + jsString(msg) + ");"));
    }
    private void callbackState(String state) {
        mainHandler.post(() -> evalJs("window.onNativeSpeechState && window.onNativeSpeechState(" + jsString(state) + ");"));
    }
    private void evalJs(String code) {
        try { if (webView != null) webView.evaluateJavascript(code, null); }
        catch (Exception e) { Log.e(TAG, "evalJs error", e); }
    }
    private static String jsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'";
    }
}