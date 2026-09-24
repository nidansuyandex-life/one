# 保留所有被 JS 调用的桥接方法（防止 release 混淆后失效）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class com.health.app.AndroidBridge {
    public *;
}

-keepattributes JavascriptInterface