# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# LibVLC — native JNI code calls back into these Java classes by name; R8 must not rename them
-keep class org.videolan.libvlc.** { *; }

# Glide OkHttp integration — R8 strips the no-arg constructor otherwise
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.integration.okhttp3.OkHttpGlideModule { <init>(); }

# Glide generated API
-keep public class * extends com.bumptech.glide.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}