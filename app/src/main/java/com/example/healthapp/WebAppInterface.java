package com.example.healthapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

public class WebAppInterface {

    private final Context ctx;
    private final SharedPreferences prefs;
    private final WeatherHelper weather;

    public WebAppInterface(Context ctx, WeatherHelper weather) {
        this.ctx = ctx;
        this.weather = weather;
        this.prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE);
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

    // ---------- 语音（HTML 里会调，暂留空实现，界面自动走浏览器识别兜底） ----------

    @JavascriptInterface
    public void startVoice() {
        // 如需接入原生语音识别，完成后调用：
        // webView.evaluateJavascript("window.onNativeSpeechResult('识别文本')", null);
        // 出错时：window.onNativeSpeechError('错误信息')
    }

    @JavascriptInterface
    public void stopVoice() { }

    // ---------- 声音 / 振动 ----------

    @JavascriptInterface
    public void speak(String text) { }

    @JavascriptInterface
    public void vibrate() { }

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

    // ---------- AI（HTML 里会调，暂留空，界面会给出提示） ----------

    @JavascriptInterface
    public void sendChat(String payload) { }

    @JavascriptInterface
    public void sendStyling(String payload) { }
}
