# OpenFlow ProGuard rules.

# Keep JNI-bound classes
-keep class com.hank.flow.open.asr.WhisperJni { *; }
-keep class com.hank.flow.open.llm.LlamaJni { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# === JNI: 保留所有 native 方法的声明类，防 R8 改类名导致 JNI_OnLoad 找不到符号 ===
-keepclasseswithmembers class * {
    native <methods>;
}
-keepclassmembers class com.hank.flow.open.** {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# === Manifest 引用的组件 ===
-keep class com.hank.flow.open.MainActivity { *; }
-keep class com.hank.flow.open.OpenFlowApp { *; }
-keep class com.hank.flow.open.service.FlowAccessibilityService { *; }
-keep class com.hank.flow.open.service.RecordingForegroundService { *; }

# === DataStore Preferences ===
-keep class androidx.datastore.*.** { *; }
-dontwarn androidx.datastore.**

# === Kotlin 元数据（DataStore / Flow 类型信息依赖） ===
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class kotlin.Metadata { *; }

# === Logan（上游未提供 consumer-rules，自己加） ===
-keep class com.dianping.logan.** { *; }
-dontwarn com.dianping.logan.**

# === OkHttp 默认规则的扩展 ===
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# === 协程 ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepclassmembers class kotlinx.coroutines.flow.** { *; }

# === enum 反射（防御） ===
-keepclassmembers enum * { *; }

# === 崩溃栈反混淆所需 ===
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
