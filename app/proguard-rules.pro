# VAYU ProGuard Rules

# Keep accessibility service
-keep class com.vayu.agent.VayuService { *; }

# Keep models for Gson
-keep class com.vayu.agent.models.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
