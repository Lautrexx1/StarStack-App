# Add project specific ProGuard rules here.
# By default, the ProGuard rules file is configured to include the rules from
# the Android Gradle plugin's default ProGuard rules.

# Keep our native JNI methods intact
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the StackingEngine JNI interface and progress callbacks
-keep class com.starstack.app.processing.StackingEngine { *; }
-keep class com.starstack.app.processing.StackingProgressCallback { *; }
-keep class com.starstack.app.data.model.Session { *; }
