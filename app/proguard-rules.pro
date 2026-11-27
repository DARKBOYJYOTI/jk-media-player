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
