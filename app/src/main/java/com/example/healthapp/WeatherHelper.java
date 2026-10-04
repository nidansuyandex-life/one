package com.example.healthapp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class WeatherHelper {

    private static final String TAG = "WeatherHelper";
    private static final int REQ_LOCATION = 1001;

    /** 去 https://dev.qweather.com 免费申请，替换成你自己的 Key */
    private static final String QWEATHER_KEY = "把你的和风天气KEY填这里";

    private final Context ctx;
    private final WebView webView;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WeatherHelper(Context ctx, WebView webView) {
        this.ctx = ctx;
        this.webView = webView;
        this.prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE);
    }

    /** H5 同步调用：直接返回上次缓存 */
    public String getCachedWeather() {
        return prefs.getString("weather_json", null);
    }

    /** 主动刷新天气（H5 可调，Activity 也能调） */
    public void refresh() {
        if (hasLocationPermission()) {
            requestByLocation();
        } else {
            // 没权限：先用 IP 定位兜底，同时弹框请求权限
            requestByIp();
            requestLocationPermission();
        }
    }

    // ---------------- 系统定位 ----------------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        if (ctx instanceof Activity) {
            ActivityCompat.requestPermissions((Activity) ctx,
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, REQ_LOCATION);
        }
    }

    /** Activity 里复写 onRequestPermissionsResult 后调这个方法 */
    public void onPermissionResult(boolean granted) {
        if (granted) requestByLocation();
        else requestByIp();
    }

    private void requestByLocation() {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { requestByIp(); return; }

        try {
            // 1) 先看有没有上次的位置，秒回
            Location last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) fetchWeatherByCoord(last.getLongitude(), last.getLatitude());

            // 2) 再请求一次单次定位
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    fetchWeatherByCoord(location.getLongitude(), location.getLatitude());
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {
                    requestByIp();  // 定位被关了，回退到 IP
                }
            }, main);

        } catch (SecurityException e) {
            Log.w(TAG, "location security exception", e);
            requestByIp();
        } catch (Exception e) {
            Log.w(TAG, "location error", e);
            requestByIp();
        }
    }

    // ---------------- IP 定位兜底 ----------------

    private void requestByIp() {
        new Thread(() -> {
            try {
                // ipapi.co 免费、无需 key，返回 lat/lon/city
                String ipUrl = "https://ipapi.co/json/";
                String body = httpGet(ipUrl);
                if (body == null) return;
                JSONObject obj = new JSONObject(body);
                double lat = obj.optDouble("latitude", Double.NaN);
                double lon = obj.optDouble("longitude", Double.NaN);
                String city = obj.optString("city", "");
                if (Double.isNaN(lat) || Double.isNaN(lon)) return;
                fetchWeatherByCoord(lon, lat, city);
            } catch (Exception e) {
                Log.w(TAG, "ip locate failed", e);
            }
        }).start();
    }

    // ---------------- 拉天气 ----------------

    private void fetchWeatherByCoord(double lon, double lat) {
        fetchWeatherByCoord(lon, lat, "");
    }

    private void fetchWeatherByCoord(double lon, double lat, String cityHint) {
        new Thread(() -> {
            try {
                String url = "https://devapi.qweather.com/v7/weather/now?location="
                        + lon + "," + lat + "&key=" + QWEATHER_KEY;
                String body = httpGet(url);
                if (body == null) return;

                JSONObject root = new JSONObject(body);
                if (!"200".equals(root.optString("code"))) {
                    Log.w(TAG, "qweather code = " + root.optString("code"));
                    return;
                }
                JSONObject now = root.getJSONObject("now");
                int temp = (int) Math.round(now.optDouble("temp", 0));
                String desc = now.optString("text", "");
                String iconCode = now.optString("icon", "100");

                JSONObject out = new JSONObject();
                out.put("temp", temp);
                out.put("desc", desc);
                out.put("icon", mapIcon(iconCode));
                if (!cityHint.isEmpty()) out.put("city", cityHint);

                String json = out.toString();
                Log.d(TAG, "weather = " + json);

                prefs.edit().putString("weather_json", json).apply();

                final String js = "window.onWeatherUpdate && window.onWeatherUpdate(" + json + ")";
                main.post(() -> {
                    if (webView != null) webView.evaluateJavascript(js, null);
                });

            } catch (Exception e) {
                Log.w(TAG, "fetch weather failed", e);
            }
        }).start();
    }

    // ---------------- 工具 ----------------

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "HealthApp/1.0");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "httpGet failed: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 和风天气图标码 → emoji */
    private String mapIcon(String code) {
        int c = 0;
        try { c = Integer.parseInt(code); } catch (Exception ignored) {}
        if (c == 100) return "☀️";
        if (c == 101 || c == 102 || c == 103) return "⛅";
        if (c == 104) return "☁️";
        if (c >= 150 && c <= 153) return "🌙";
        if (c >= 300 && c <= 399) return "🌧️";
        if (c >= 400 && c <= 499) return "❄️";
        if (c >= 500 && c <= 515) return "🌫️";
        return "☀️";
    }
}
