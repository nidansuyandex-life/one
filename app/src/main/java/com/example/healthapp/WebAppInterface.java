package com.example.healthapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;

public class WebAppInterface {

    private final Context ctx;
    private final WebView webView;
    private final SharedPreferences prefs;
    private final WeatherHelper weather;
    private TextToSpeech tts;

    public WebAppInterface(Context ctx, WebView webView, WeatherHelper weather) {
        this.ctx = ctx;
        this.webView = webView;
        this.weather = weather;
        this.prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE);

        // 初始化 TTS（异步，完成后才可用）
        try {
            tts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    try { tts.setLanguage(Locale.CHINA); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    // ---------- 天气 ----------

    @JavascriptInterface
    public String getWeather() {
        return weather.getCachedWeather();
    }

    @JavascriptInterface
    public void refreshWeather() {
        weather.refresh();
    }

    // ---------- 存储桥接 ----------

    @JavascriptInterface
    public void save(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String load(String key) {
        return prefs.getString(key, null);
    }

    @JavascriptInterface
    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    // ---------- 语音：原生未接入，主动通知 H5 走手动输入 ----------

    @JavascriptInterface
    public void startVoice() {
        if (webView == null) return;
        webView.post(() -> {
            try {
                webView.evaluateJavascript(
                        "window.onNativeSpeechError && window.onNativeSpeechError('原生语音未接入，请使用手动输入')",
                        null);
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void stopVoice() { }

    // ---------- TTS 朗读 ----------

    @JavascriptInterface
    public void speak(String text) {
        if (tts == null || text == null) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "app_tts");
        } catch (Exception ignored) {}
    }

    // ---------- 震动 ----------

    @JavascriptInterface
    public void vibrate() {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(200);
            }
        } catch (Exception ignored) {}
    }

    // ---------- 设置开关 ----------

    @JavascriptInterface
    public void setSitReminder(boolean on) {
        prefs.edit().putBoolean("sit_reminder", on).apply();
    }

    @JavascriptInterface
    public void setBackupReminder(boolean on) {
        prefs.edit().putBoolean("backup_reminder", on).apply();
    }

    // ---------- 备份 ----------

    @JavascriptInterface
    public void saveBackup(String json) { }

    @JavascriptInterface
    public String listBackups() { return "[]"; }

    @JavascriptInterface
    public String readBackup(String date) { return null; }

    // ---------- AI ----------

    @JavascriptInterface
    public void sendChat(String payload) { }

    @JavascriptInterface
    public void sendStyling(String payload) { }
}
