# Keep all classes with native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Tailcale classes for debuggability, but especially
# keep the classes with syspolicy MDM keys, some of which
# get used only by the Go backend. (The second rule is redundant,
# but explicit.)
-keep class com.tailscale.ipn.** { *; }
-keep class com.tailscale.ipn.mdm.** { *; }

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
