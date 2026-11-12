# Mempal R8 rules
#
# Retrofit and OkHttp ship their own consumer rules (META-INF/proguard/...).
# This file declares only the reflection surface specific to this app, plus the
# two R8 full-mode exceptions required by Retrofit's suspend functions.

# ---------------------------------------------------------------------------
# Attributes
# ---------------------------------------------------------------------------
# Retrofit reflects on generic type signatures and on method/parameter
# annotations. Gson also needs Signature for parameterized field types.
-keepattributes Signature
-keepattributes *Annotation*

# Keep line numbers so release stack traces stay useful.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# Retrofit
# ---------------------------------------------------------------------------
# Retrofit's service interface uses suspend functions. R8 full mode otherwise
# drops the generic signatures that carry Response<T>, producing:
#   "Class cannot be cast to ParameterizedType"
#   "Response must include generic type (e.g., Response<String>)"
# These keeps are intentionally narrow.
-keep interface com.example.mempal.api.MempoolApi { *; }
-keepclassmembers interface * {
    kotlin.coroutines.Continuation *(...);
}
-keep class retrofit2.Response { *; }
-keep class retrofit2.Response$* { *; }
-keep interface kotlin.coroutines.Continuation { *; }
-keepclassmembers interface kotlin.coroutines.Continuation {
    <methods>;
}

# ---------------------------------------------------------------------------
# Gson (2.8.x has no bundled consumer rules)
# ---------------------------------------------------------------------------
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# API response models populated by Gson. Fields must keep their names because
# most of these classes do not use @SerializedName; constructors are needed by
# Gson's reflection/Unsafe allocation path. Keeping the class names also keeps
# the Response<T> signatures stable for Retrofit.
-keep class com.example.mempal.api.FeeRates { <fields>; <init>(...); }
-keep class com.example.mempal.api.MempoolBlock { <fields>; <init>(...); }
-keep class com.example.mempal.api.MempoolInfo { <fields>; <init>(...); }
-keep class com.example.mempal.api.HashrateInfo { <fields>; <init>(...); }
-keep class com.example.mempal.api.BlockInfo { <fields>; <init>(...); }
-keep class com.example.mempal.api.TransactionResponse { <fields>; <init>(...); }
-keep class com.example.mempal.api.TransactionStatus { <fields>; <init>(...); }
-keep class com.example.mempal.api.DifficultyAdjustment { <fields>; <init>(...); }

# ---------------------------------------------------------------------------
# Tor
# ---------------------------------------------------------------------------
# tor-android does not ship consumer rules and uses JNI internally. Keep its
# small Java surface. jtorctl is a transitive dependency and is not referenced
# by this app, so R8 may shrink it.
-keep class org.torproject.** { *; }

# ---------------------------------------------------------------------------
# Optional platform dependencies referenced defensively by OkHttp/Retrofit
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
