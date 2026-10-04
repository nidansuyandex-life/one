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

    // ---------- 存储桥接（HTML 里的 Store / __appStorage* 依赖这些） ----------

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

    // ---------- 语音（HTML 里有对应调用，保留占位） ----------

    @JavascriptInterface
    public void startVoice() {
        // 若接入原生语音识别，实现在这里，完成后回调：
        // webView.evaluateJavascript("window.onNativeSpeechResult('识别文本')", null);
    }

    @JavascriptInterface
    public void stopVoice() {
        // 停止录音/识别
    }

    // ---------- 提示音 / 震动 / 朗读 ----------

    @JavascriptInterface
    public void speak(String text) {
        // 可接入 TextToSpeech
    }

    @JavascriptInterface
    public void vibrate() {
        // 可接入 Vibrator
    }

    // ---------- 通知开关 ----------

    @JavascriptInterface
    public void setSitReminder(boolean on) {
        prefs.edit().putBoolean("sit_reminder", on).apply();
    }

    @JavascriptInterface
    public void setBackupReminder(boolean on) {
        prefs.edit().putBoolean("backup_reminder", on).apply();
    }

    @JavascriptInterface
    public void saveBackup(String json) {
        // 可选：保存备份到文件
    }

    // ---------- AI 聊天（HTML 里有对应调用，保留占位） ----------

    @JavascriptInterface
    public void sendChat(String payload) {
        // 接入你自己的大模型 API，流式结果通过 webView 回调：
        // webView.evaluateJavascript("window.onNativeChatResult('chunk','chat')", null);
        // 结束：window.onNativeChatState('finished','chat')
    }

    @JavascriptInterface
    public void sendStyling(String payload) {
        // 同上，tag = "styling"
    }

    // ---------- 历史备份（HTML 里有对应调用，保留占位） ----------

    @JavascriptInterface
    public String listBackups() {
        return "[]";
    }

    @JavascriptInterface
    public String readBackup(String date) {
        return null;
    }

    // ---------- 每日备份（HTML 里有对应调用，保留占位） ----------

    @JavascriptInterface
    public void saveDailyBackup(String json) {
        // 可选：保存每日备份
    }
}
