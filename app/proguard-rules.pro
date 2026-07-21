-keep class com.crunchbarcode.app.data.model.** { *; }
-keepattributes *Annotation*

# Tink / security-crypto
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**

# Health Connect
-keep class androidx.health.connect.client.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
