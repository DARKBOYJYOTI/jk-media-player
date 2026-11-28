package com.jk.mediaplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.jk.mediaplayer.data.PreferencesManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * Builds LibVLC's Fontconfig subtitle font cache once per OS build on a background IO thread
 * (using headless dummy video/audio outputs) so initial video playback never stalls and users
 * who select ASS/non-Latin subtitles while caching is still running get non-blocking playback
 * with automatic activation as soon as the cache finishes.
 */
object SubtitleFontCacheManager {
    enum class State { IDLE, CACHING, READY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun ensureReady(context: Context) {
        val appContext = context.applicationContext
        val prefs = PreferencesManager(appContext)
        val currentFp = Build.FINGERPRINT ?: "unknown"
        if (prefs.getSubtitleFontCacheFingerprint() == currentFp) {
            _state.value = State.READY
            return
        }
        synchronized(this) {
            if (started || _state.value == State.READY) return
            started = true
            _state.value = State.CACHING
        }
        scope.launch {
            var warmVlc: LibVLC? = null
            var warmPlayer: MediaPlayer? = null
            val pngFile = File(appContext.cacheDir, "jk_font_warm.png")
            val assFile = File(appContext.cacheDir, "jk_font_warm.ass")
            try {
                // Tiny 16x16 black PNG + minimal ASS cue forces libass -> Fontconfig cache build
                // without touching any display surface or audio hardware.
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try {
                    FileOutputStream(pngFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } finally {
                    bmp.recycle()
                }
                assFile.writeText(
                    """
                    [Script Info]
                    ScriptType: v4.00+
                    PlayResX: 384
                    PlayResY: 288

                    [V4+ Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                    Style: Default,sans-serif,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

                    [Events]
                    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,नमस्ते مرحبا Hello
                    """.trimIndent()
                )

                val options = arrayListOf(
                    "--vout=dummy",
                    "--aout=dummy",
                    "--no-stats",
                    "--no-video-title-show",
                    "--subsdec-encoding=UTF-8"
                )
                warmVlc = LibVLC(appContext, options)
                val done = CompletableDeferred<Unit>()
                warmPlayer = MediaPlayer(warmVlc).apply {
                    setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.EndReached,
                            MediaPlayer.Event.EncounteredError,
                            MediaPlayer.Event.Stopped -> done.complete(Unit)
                        }
                    }
                }
                val media = Media(warmVlc, pngFile.absolutePath).apply {
                    addOption(":image-duration=1")
                    addOption(":sub-file=${assFile.absolutePath}")
                    addOption(":no-audio")
                }
                warmPlayer.media = media
                media.release()
                warmPlayer.play()

                withTimeoutOrNull(25_000L) {
                    done.await()
                }
                prefs.setSubtitleFontCacheFingerprint(currentFp)
            } catch (_: Throwable) {
                // Never block subtitles forever if headless warmup fails on an unusual device.
            } finally {
                try { warmPlayer?.setEventListener(null) } catch (_: Throwable) {}
                try { warmPlayer?.stop() } catch (_: Throwable) {}
                try { warmPlayer?.release() } catch (_: Throwable) {}
                try { warmVlc?.release() } catch (_: Throwable) {}
                try { pngFile.delete() } catch (_: Throwable) {}
                try { assFile.delete() } catch (_: Throwable) {}
                _state.value = State.READY
            }
        }
    }
}
