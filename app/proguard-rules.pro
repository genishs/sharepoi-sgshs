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
