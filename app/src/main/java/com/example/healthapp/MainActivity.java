package com.example.healthapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private WebView webView;
    private IatWebSocketHelper iatHelper;
    private SparkChatHelper chatHelper;
    private BackupHelper backupHelper;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_RESULT_CODE = 100;
    private static final int REQ_PERMS = 200;

    private static final String PREFS = "health_app_settings";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 创建通知渠道
        NotificationHelper.createChannel(this);

        // 2. 请求权限
        requestPermsIfNeeded();

        // 3. 首次启动时默认打开久坐+备份提醒
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.contains("sit_reminder")) {
            sp.edit().putBoolean("sit_reminder", true).putBoolean("backup_reminder", true).apply();
        }
        if (sp.getBoolean("sit_reminder", true)) NotificationHelper.scheduleSitReminder(this);
        if (sp.getBoolean("backup_reminder", true)) NotificationHelper.scheduleBackupReminder(this);

        // 4. WebView
        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.setBackgroundColor(0xFFFFFFFF);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent intent = params.createIntent();
                try { startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE); }
                catch (Exception e) { filePathCallback = null; return false; }
                return true;
            }
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.grant(request.getResources());
                }
            }
        });

        iatHelper = new IatWebSocketHelper(webView);
        chatHelper = new SparkChatHelper(webView);
        backupHelper = new BackupHelper(this);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void startVoice() { iatHelper.start(); }

            @JavascriptInterface
            public void stopVoice() { iatHelper.stop(); }

            @JavascriptInterface
            public void sendChat(String question) { chatHelper.chat(question, "chat"); }

            @JavascriptInterface
            public void sendStyling(String question) { chatHelper.chat(question, "styling"); }

            @JavascriptInterface
            public void setSitReminder(boolean on) {
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                sp.edit().putBoolean("sit_reminder", on).apply();
                if (on) NotificationHelper.scheduleSitReminder(MainActivity.this);
                else    NotificationHelper.cancelSitReminder(MainActivity.this);
            }

            @JavascriptInterface
            public void setBackupReminder(boolean on) {
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                sp.edit().putBoolean("backup_reminder", on).apply();
                if (on) NotificationHelper.scheduleBackupReminder(MainActivity.this);
                else    NotificationHelper.cancelBackupReminder(MainActivity.this);
            }

            @JavascriptInterface
            public void showNotification(String title, String body) {
                runOnUiThread(() -> NotificationHelper.show(
                        MainActivity.this, (int)(System.currentTimeMillis() % 100000),
                        title == null ? "" : title, body == null ? "" : body));
            }

            @JavascriptInterface
            public void saveDailyBackup(String json) {
                if (backupHelper != null) backupHelper.saveDaily(json);
            }

            @JavascriptInterface
            public String listBackups() {
                return backupHelper != null ? backupHelper.list() : "[]";
            }

            @JavascriptInterface
            public String readBackup(String date) {
                return backupHelper != null ? backupHelper.read(date) : "";
            }

            /**
             * 手动导出备份：写入 exports/ 子目录，弹 Toast 提示路径。
             * 前端调用：AndroidBridge.saveBackup(jsonString)
             */
            @JavascriptInterface
            public void saveBackup(String json) {
                try {
                    File base = getExternalFilesDir(null);
                    if (base == null) base = getFilesDir();
                    File dir = new File(base, "exports");
                    if (!dir.exists()) dir.mkdirs();

                    String date = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
                    File f = new File(dir, "健康生活备份_" + date + ".json");

                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.close();

                    final String path = f.getAbsolutePath();
                    Log.d(TAG, "backup saved to " + path);
                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this,
                            "已保存到：\n" + path,
                            Toast.LENGTH_LONG
                    ).show());
                } catch (Exception e) {
                    Log.e(TAG, "saveBackup failed", e);
                    final String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this,
                            "保存失败：" + msg,
                            Toast.LENGTH_LONG
                    ).show());
                }
            }
        }, "AndroidBridge");

        container.addView(webView);
        setContentView(container);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestPermsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> need = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (iatHelper != null) iatHelper.destroy();
        if (chatHelper != null) chatHelper.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String s = data.getDataString();
                if (s != null) results = new Uri[]{ Uri.parse(s) };
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}