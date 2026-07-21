-keep class com.crunchbarcode.app.data.model.** { *; }
-keepattributes *Annotation*

# Tink / security-crypto missing errorprone annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
