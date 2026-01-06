# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# CRITICAL: Preserve generic signatures FIRST - Required for Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep your model classes and their fields
-keep class com.example.mempal.api.** { *; }
-keep class com.example.mempal.models.** { *; }
-keep class com.example.mempal.model.** { *; }
-keep class com.example.mempal.repository.** { *; }
-keepclassmembers class com.example.mempal.api.** { *; }
-keepclassmembers class com.example.mempal.models.** { *; }
-keepclassmembers class com.example.mempal.model.** { *; }
-keepclassmembers class com.example.mempal.repository.** { *; }
# Preserve names and signatures for all API classes (critical for Retrofit)
-keepnames class com.example.mempal.api.** { *; }
-keepnames interface com.example.mempal.api.** { *; }

# Keep specific model classes
-keep class com.example.mempal.api.FeeRates { *; }
-keep class com.example.mempal.api.MempoolInfo { *; }
-keep class com.example.mempal.api.BlockInfo { *; }
-keep class com.example.mempal.api.TransactionResponse { *; }
-keep class com.example.mempal.api.TransactionStatus { *; }
-keep class com.example.mempal.model.NotificationSettings { *; }
-keep class com.example.mempal.model.FeeRateType { *; }

# Keep NetworkClient and its dependencies
-keep class com.example.mempal.api.NetworkClient { *; }
-keep class com.example.mempal.api.WidgetNetworkClient { *; }
-keepclassmembers class com.example.mempal.api.NetworkClient { *; }
-keepclassmembers class com.example.mempal.api.WidgetNetworkClient { *; }

# Keep MempoolApi interface and its methods - CRITICAL for Retrofit
# Must NOT obfuscate - Retrofit needs exact method signatures with generics
-keepnames interface com.example.mempal.api.MempoolApi
-keep interface com.example.mempal.api.MempoolApi { *; }
-keepclassmembers interface com.example.mempal.api.MempoolApi {
    <methods>;
}

# Keep Android system classes
-keep class android.net.ConnectivityManager { *; }
-keep class android.net.Network { *; }
-keep class android.net.NetworkCapabilities { *; }
-keep class android.net.NetworkRequest { *; }
-keep class android.net.NetworkRequest$Builder { *; }
-keep class android.content.SharedPreferences { *; }
-keep class android.content.SharedPreferences$Editor { *; }

# Keep WeakReference and other essential Java classes
-keep class java.lang.ref.WeakReference { *; }
-keep class java.net.InetSocketAddress { *; }
-keep class java.net.Proxy { *; }
-keep class java.net.Proxy$Type { *; }

# Retrofit - Keep everything to prevent ClassCastException
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keep class retrofit2.Response { *; }
-keep class retrofit2.Response$* { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
# Keep Retrofit service interface methods with their generic signatures
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
# Keep all Retrofit service interfaces
-keep,allowobfuscation interface * extends retrofit2.Call
-keep,allowobfuscation interface * extends retrofit2.CallAdapter$Factory
-keep,allowobfuscation interface * extends retrofit2.Converter$Factory
# Keep suspend function continuations (generic types preserved via Signature attribute)
-keepclassmembers interface * {
    kotlin.coroutines.Continuation *(...);
}

# OkHttp - Keep only necessary classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.Response { *; }
-keep class okhttp3.ResponseBody { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.RequestBody { *; }
-keep class okhttp3.Call { *; }
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.HttpUrl { *; }
-keep class okhttp3.MediaType { *; }
-keep interface okhttp3.Call { *; }
-keepclassmembers class okhttp3.** {
    <init>(...);
}
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin Coroutines - Keep only necessary classes
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.flow.** { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class java.util.concurrent.CancellationException { *; }
-keepclassmembers class kotlinx.coroutines.** {
    <init>(...);
}
# Keep Continuation interface with generic signatures for suspend functions
-keep interface kotlin.coroutines.Continuation { *; }
-keepclassmembers interface kotlin.coroutines.Continuation {
    <methods>;
}
# Remove overly broad kotlin.** rule - Kotlin standard library doesn't need to be kept

# WorkManager
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# Tor
-keep class org.torproject.** { *; }
-keep class net.freehaven.tor.control.** { *; }

# Keep source file names and line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions,InnerClasses

# Keep Retrofit service methods with generic signatures
-keepclassmembernames,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep BuildConfig (auto-generated by Android build system)
-keep class com.example.mempal.BuildConfig { *; }

# Keep Enum members
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep StateFlow and related classes
-keep class kotlinx.coroutines.flow.StateFlow { *; }
-keep class kotlinx.coroutines.flow.MutableStateFlow { *; }
-keep class kotlinx.coroutines.flow.SharedFlow { *; }
-keep class kotlinx.coroutines.flow.Flow { *; }

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Additional Custom Optimization Settings
-allowaccessmodification
-mergeinterfacesaggressively
-optimizationpasses 5
-overloadaggressively