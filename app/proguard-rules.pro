# Keep AndroidX WebKit, Kotlin metadata, and JNI bridges intact when R8 minification runs.
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**
-keep class com.nromanovich.stealthsnake.NativePinKdf { *; }
-keepclassmembers class com.nromanovich.stealthsnake.NativePinKdf {
    <methods>;
}
