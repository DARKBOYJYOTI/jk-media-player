package com.jk.mediaplayer.player

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts the LibVLC controller to the [AudioEngine] surface.
 *
 * Written as plain forwarding so the controller keeps its own defaults and its video behaviour is
 * untouched while the service can treat both engines the same way.
 */
class LibVlcAudioEngine(
    private val controller: LibVlcController
) : AudioEngine {

    override val isPlaying: StateFlow<Boolean> get() = controller.isPlaying
    override val isBuffering: StateFlow<Boolean> get() = controller.isBuffering
    override val currentTime: StateFlow<Long> get() = controller.currentTime
    override val totalDuration: StateFlow<Long> get() = controller.totalDuration
    override val encounteredError: StateFlow<String?> get() = controller.encounteredError

    override val isFinished: Boolean
        get() = controller.hasEnded || controller.playerState == 6

    override fun hasMedia(): Boolean = controller.mediaPlayer?.media != null

    override fun playMedia(
        path: String,
        uri: Uri?,
        resumePositionMs: Long,
        username: String?,
        password: String?,
        httpHeaders: Map<String, String>,
        audioOnly: Boolean
    ) {
        controller.playMedia(path, uri, resumePositionMs, username, password, httpHeaders, audioOnly)
    }

    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun togglePlayPause() = controller.togglePlayPause()
    override fun stop() = controller.stop()
    override fun seekTo(timeMs: Long) = controller.seekTo(timeMs)
    override fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)
    override fun setVolume(volumeLevel: Int) = controller.setVolume(volumeLevel)
    override fun getVolume(): Int = controller.getVolume()
    override fun rampVolume(targetVolume: Int, durationMs: Long) =
        controller.rampVolume(targetVolume, durationMs)

    override fun clearError() = controller.clearError()
    override fun currentPositionMs(): Long = controller.currentPositionMs()
    override fun resetProgress() = controller.resetProgress()
    override fun release() = controller.release()
}
