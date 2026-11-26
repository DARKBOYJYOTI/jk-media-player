# Add project specific ProGuard rules here.

# LibVLC (JNI + reflection heavy)
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.util.** { *; }
-dontwarn org.videolan.**

# Keep annotations / signatures used by Compose, Coil and kotlinx.coroutines
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Coil / OkHttp optional providers
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.parcelize.**

# AndroidX MediaSession compat reflection on service names
-keep class com.jk.mediaplayer.service.** { *; }
-keep class com.jk.mediaplayer.player.** { *; }
-keep class com.jk.mediaplayer.model.** { *; }

# Jaudiotagger (suppress unused desktop AWT / ImageIO / Swing APIs and keep tag structures)
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-keep class org.jaudiotagger.** { *; }

# NewPipeExtractor: resolves YouTube Music search and stream URLs for the music app.
# The extractor wires its parsers through generated JSON accessors, so the whole tree is kept.
-keep class org.schabi.newpipe.** { *; }
-keep interface org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**
-dontwarn org.jsoup.**

# Rhino runs the YouTube signature/`n` deciphering script. R8 must not touch it, or streams
# come back throttled or fail outright in the minified release build.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**

# BotGuard poToken: the WebView calls back into these classes through JavaScript interfaces,
# so R8 must keep the annotated members and the RxJava plumbing they use.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.jk.mediaplayer.data.potoken.** { *; }
-keep class io.reactivex.rxjava3.** { *; }
-dontwarn io.reactivex.rxjava3.**
