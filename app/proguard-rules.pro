# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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

# Keep MempoolApi interface and its methods
-keep interface com.example.mempal.api.MempoolApi { *; }
-keepclassmembers interface com.example.mempal.api.MempoolApi { *; }

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

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
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

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.** { *; }

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

# Keep Retrofit service methods
-keepclassmembernames,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep generic signatures and annotations
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

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