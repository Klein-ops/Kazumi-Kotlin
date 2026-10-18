# Kazumi ProGuard/R8 规则

# ===== Kotlin 协程 =====
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ===== Gson 反射（网络 JSON 模型）=====
-keep class com.kazumi.port.data.model.** { <fields>; }
-keep class com.kazumi.port.data.repository.** { <fields>; }
-keep class com.kazumi.port.network.** { <fields>; }

# ===== Room（KSP 生成类）=====
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# ===== WebView JS 接口（视频源解析注入）=====
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ===== 视频源插件（反射加载）=====
-keep class com.kazumi.port.service.videosource.** { *; }
-keep class com.kazumi.port.rule.** { *; }

# ===== 序列化 =====
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepattributes SourceFile, LineNumberTable