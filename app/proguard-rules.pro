# R8 rules for sharepoi

# Keep line numbers for readable Play Console crash stack traces (deobfuscate with mapping.txt)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kakao Maps SDK v2 (official guidance: keep SDK classes; it uses JNI/reflection internally)
-keep class com.kakao.vectormap.** { *; }
-keep interface com.kakao.vectormap.** { *; }
-keep class com.kakao.sdk.** { *; }
-dontwarn com.kakao.vectormap.**

# Kakao Local REST responses are parsed with org.json (Android framework, not obfuscated);
# no Gson/Moshi/reflection-based model classes exist in this app, so no model keep rules are needed.

# Keep native method names (SDK JNI)
-keepclasseswithmembernames class * { native <methods>; }

# Release builds: drop android.util.Log v/d/i/w calls from every class, including the Kakao Maps SDK
# (#35). The SDK logs the app key header, signing key hash, API URLs and map coordinates under tag
# "K3fAApi" even in release. Needs R8 optimization (proguard-android-optimize.txt) and is a no-op
# for native (JNI) logging. Error-level (Log.e) is kept for real crash diagnostics.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
