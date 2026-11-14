# Keep AndroidX WebKit and Kotlin metadata intact when R8 minification runs.
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**
