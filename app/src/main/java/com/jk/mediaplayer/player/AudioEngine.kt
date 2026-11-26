package com.jk.mediaplayer.player

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * The playback surface the service needs, implemented by the LibVLC engine for local media and by
 * ExoPlayer for YouTube Music streams.
 */
interface AudioEngine {
    val isPlaying: StateFlow<Boolean>
    val isBuffering: StateFlow<Boolean>
    val currentTime: StateFlow<Long>
    val totalDuration: StateFlow<Long>
    val encounteredError: StateFlow<String?>

    /** True when the media finished on its own. */
    val isFinished: Boolean

    /** True when some media is loaded, paused or not. */
    fun hasMedia(): Boolean

    fun playMedia(
        path: String,
        uri: Uri? = null,
        resumePositionMs: Long = 0L,
        username: String? = null,
        password: String? = null,
        httpHeaders: Map<String, String> = emptyMap(),
        audioOnly: Boolean = false
    )

    fun play()
    fun pause()
    fun togglePlayPause()
    fun stop()
    fun seekTo(timeMs: Long)
    fun seekBy(deltaMs: Long)
    fun setVolume(volumeLevel: Int)
    fun getVolume(): Int
    fun rampVolume(targetVolume: Int, durationMs: Long = 90L)
    fun clearError()
    /** Live playback position, used when a completed track has to be restarted. */
    fun currentPositionMs(): Long
    fun resetProgress()
    fun release()
}
