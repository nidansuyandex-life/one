# 保留 JS 桥接方法
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.health.app.AndroidBridge {
    public *;
}
-keepattributes JavascriptInterface

# ★ SparkChain SDK 混淆规则
-keep class com.iflytek.sparkchain.** { *; }
-keep class com.iflytek.** { *; }
-dontwarn com.iflytek.**