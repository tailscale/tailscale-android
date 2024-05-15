# Keep all classes with native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep specific classes from Tink library
-keep class com.google.crypto.tink.** { *; }

# Ignore warnings about missing Error Prone annotations
-dontwarn com.google.errorprone.annotations.**

# Keep Error Prone annotations if referenced
-keep class com.google.errorprone.annotations.** { *; }

# Keep Google HTTP Client classes
-keep class com.google.api.client.http.** { *; }
-dontwarn com.google.api.client.http.**

# Keep Joda-Time classes
-keep class org.joda.time.** { *; }
-dontwarn org.joda.time.**