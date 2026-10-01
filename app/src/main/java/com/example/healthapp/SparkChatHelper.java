package com.example.healthapp;

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
 * 讯飞星火大模型 · WebSocket 版（无需 SparkChain.aar / Codec.aar）
 */
public class SparkChatHelper {

    private static final String TAG = "SparkChatHelper";

    // ====== 讯飞账号 ======
    private static final String APPID      = "4c627b59";
    private static final String API_KEY    = "2fbffaacd145309be7c024db99e9c7ef";
    private static final String API_SECRET = "YzI1MDJmYWM0NTliNzNkMjI3NGIyM2Uz";

    // ====== 端点与模型 ======
    // 4.0Ultra   → wss://spark-api.xf-yun.com/v4.0/chat,  domain=4.0Ultra
    // 3.5 Max    → wss://spark-api.xf-yun.com/v3.5/chat,  domain=generalv3.5
    // 3.0        → wss://spark-api.xf-yun.com/v3.1/chat,  domain=generalv3
    private static final String HOST   = "spark-api.xf-yun.com";
    private static final String PATH   = "/v4.0/chat";
    private static final String DOMAIN = "4.0Ultra";

    // ====== 系统提示词 ======
    private static final String SYS_PROMPT_CHAT =
        "你是用户的私人健康生活助手。用户会提供【用户当前数据】和【用户问题】两部分内容。" +
        "请结合数据回答，给具体、可执行的建议。语气友好、简洁，不要泛泛而谈。" +
        "中文回答，不超过 250 字。涉及医疗、用药、严重皮肤问题时提醒用户就医，不要给出诊断。";

    private static final String SYS_PROMPT_STYLING =
        "你是专业时尚搭配师。用户会提供衣橱清单和场景要求。" +
        "严格按要求只返回 JSON，不要加任何解释、代码块标记或 markdown。" +
        "JSON 格式必须是：{\"reason\":\"一句话理由\",\"items\":[{\"cat\":\"top\",\"name\":\"单品名\"}]}。" +
        "cat 只能取 top/pants/dress/coat/shoes/bag/acc；name 必须与用户给的清单里完全一致。";

    private final WebView webView;
    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SparkChatHelper(WebView webView) {
        this.webView = webView;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void chat(String question, String tag) {
        final String ftag = (tag == null || tag.isEmpty()) ? "chat" : tag;
        String sysPrompt = "styling".equals(ftag) ? SYS_PROMPT_STYLING : SYS_PROMPT_CHAT;
        String fullPrompt = "【系统指令】" + sysPrompt + "\n\n" + question;

        try {
            String wsUrl = buildAuthUrl();
            Log.d(TAG, "WS URL = " + wsUrl);
            Request request = new Request.Builder().url(wsUrl).build();
            httpClient.newWebSocket(request, new WsListener(fullPrompt, ftag));
        } catch (Exception e) {
            Log.e(TAG, "start error", e);
            callbackError("启动失败：" + e.getMessage(), ftag);
        }
    }

    public void destroy() {
        try {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
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
        private final String prompt;
        private final String tag;
        private boolean finished = false;

        WsListener(String prompt, String tag) {
            this.prompt = prompt;
            this.tag = tag;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.d(TAG, "WS opened");
            try {
                JSONObject frame = new JSONObject();

                JSONObject header = new JSONObject();
                header.put("app_id", APPID);
                header.put("uid", "health_app_user");
                frame.put("header", header);

                JSONObject parameter = new JSONObject();
                JSONObject chat = new JSONObject();
                chat.put("domain", DOMAIN);
                chat.put("temperature", 0.5);
                chat.put("max_tokens", 4096);
                parameter.put("chat", chat);
                frame.put("parameter", parameter);

                JSONObject payload = new JSONObject();
                JSONObject message = new JSONObject();
                JSONArray textArr = new JSONArray();
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", prompt);
                textArr.put(userMsg);
                message.put("text", textArr);
                payload.put("message", message);
                frame.put("payload", payload);

                ws.send(frame.toString());
            } catch (Exception e) {
                Log.e(TAG, "onOpen error", e);
                callbackError("发送请求失败：" + e.getMessage(), tag);
                finished = true;
                try { ws.close(1000, "error"); } catch (Exception ignored) {}
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject jo = new JSONObject(text);
                JSONObject header = jo.optJSONObject("header");
                int code = header != null ? header.optInt("code", 0) : 0;
                int status = header != null ? header.optInt("status", 1) : 1;

                if (code != 0) {
                    String msg = header != null ? header.optString("message", "错误 code=" + code) : ("错误 code=" + code);
                    callbackError(msg + " (code=" + code + ")", tag);
                    finished = true;
                    try { ws.close(1000, "err"); } catch (Exception ignored) {}
                    return;
                }

                JSONObject payload = jo.optJSONObject("payload");
                if (payload != null) {
                    JSONObject choices = payload.optJSONObject("choices");
                    if (choices != null) {
                        JSONArray textArr = choices.optJSONArray("text");
                        if (textArr != null) {
                            for (int i = 0; i < textArr.length(); i++) {
                                JSONObject item = textArr.getJSONObject(i);
                                String content = item.optString("content", "");
                                if (content != null && !content.isEmpty()) {
                                    callbackResult(content, tag);
                                }
                            }
                        }
                    }
                }

                if (status == 2) {
                    finished = true;
                    callbackState("finished", tag);
                    try { ws.close(1000, "done"); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "onMessage parse error", e);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "WS failure: " + t.getMessage());
            if (!finished) {
                finished = true;
                callbackError("网络错误：" + t.getMessage(), tag);
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.d(TAG, "WS closed: " + code + " " + reason);
            if (!finished) {
                finished = true;
                callbackState("finished", tag);
            }
        }
    }

    private void callbackResult(String text, String tag) {
        mainHandler.post(() -> evalJs("window.onNativeChatResult && window.onNativeChatResult(" + jsString(text) + ", " + jsString(tag) + ");"));
    }
    private void callbackError(String msg, String tag) {
        mainHandler.post(() -> evalJs("window.onNativeChatError && window.onNativeChatError(" + jsString(msg) + ", " + jsString(tag) + ");"));
    }
    private void callbackState(String state, String tag) {
        mainHandler.post(() -> evalJs("window.onNativeChatState && window.onNativeChatState(" + jsString(state) + ", " + jsString(tag) + ");"));
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