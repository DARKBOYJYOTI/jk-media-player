package com.jk.mediaplayer.player

/**
 * Adapts the LibVLC controller to the [AudioEngine] surface.
 *
 * Keeping the adapter outside the controller means the controller's own API, defaults and video
 * behaviour stay untouched while the service can treat both engines the same way.
 */
class LibVlcAudioEngine(
    private val controller: LibVlcController
) : AudioEngine by controller {

    override val isFinished: Boolean
        get() = controller.hasEnded || controller.playerState == 6

    override fun hasMedia(): Boolean = controller.mediaPlayer?.media != null

    override fun clearError() = controller.clearError()

    override fun currentPositionMs(): Long = controller.currentPositionMs()
}
