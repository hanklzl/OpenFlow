# OpenFlow ProGuard rules.

# Keep JNI-bound classes
-keep class com.hank.flow.open.asr.WhisperJni { *; }
-keep class com.hank.flow.open.llm.LlamaJni { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
