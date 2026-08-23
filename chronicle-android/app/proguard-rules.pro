# Chronicle release R8 / ProGuard keep rules

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp / org.json
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ML Kit GenAI
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

# Health Connect
-keep class androidx.health.connect.** { *; }
-dontwarn androidx.health.connect.**

# Chronicle models used via reflection / JSON
-keep class com.chronicle.app.** { *; }
-keepclassmembers class com.chronicle.app.** { *; }
