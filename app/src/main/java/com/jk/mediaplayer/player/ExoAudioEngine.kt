package com.jk.mediaplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ExoPlayer engine for YouTube Music streams.
 *
 * LibVLC opens a progressive HTTPS URL slowly and can stall before the first frame; ExoPlayer
 * treats the same URL as a plain progressive source, retries the connection and starts within a
 * few hundred milliseconds. Local files keep using the LibVLC engine, so nothing about local
 * playback changes.
 */
class ExoAudioEngine(context: Context) : AudioEngine {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentTime = MutableStateFlow(0L)
    override val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    private val _totalDuration = MutableStateFlow(0L)
    override val totalDuration: StateFlow<Long> = _totalDuration.asStateFlow()

    private val _encounteredError = MutableStateFlow<String?>(null)
    override val encounteredError: StateFlow<String?> = _encounteredError.asStateFlow()

    private var mediaLoaded = false
    private var finished = false
    private var logicalVolume = 100
    private var progressJob: Job? = null
    private var rampJob: Job? = null

    override val isFinished: Boolean
        get() = finished || player.playbackState == Player.STATE_ENDED

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    val duration = player.duration
                    if (duration > 0L) _totalDuration.value = duration
                }
                Player.STATE_ENDED -> {
                    _isBuffering.value = false
                    _isPlaying.value = false
                    finished = true
                }
                else -> _isBuffering.value = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _isBuffering.value = false
            _isPlaying.value = false
            _encounteredError.value = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Cannot reach YouTube Music. Check the connection."

                else -> "Cannot play this stream."
            }
        }
    }

    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        addListener(listener)
    }

    override fun hasMedia(): Boolean = mediaLoaded

    override fun playMedia(
        path: String,
        uri: Uri?,
        resumePositionMs: Long,
        username: String?,
        password: String?,
        httpHeaders: Map<String, String>,
        audioOnly: Boolean
    ) {
        finished = false
        mediaLoaded = true
        _encounteredError.value = null
        _isBuffering.value = true
        _currentTime.value = 0L
        _totalDuration.value = 0L

        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(httpHeaders["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(
                httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            )
        val source = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(uri ?: Uri.parse(path)))

        player.setMediaSource(source)
        if (resumePositionMs > 1000L) player.seekTo(resumePositionMs)
        player.prepare()
        player.play()
        startProgressUpdates()
    }

    override fun play() {
        if (!mediaLoaded) return
        if (finished) {
            finished = false
            player.seekTo(0L)
        }
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    override fun stop() {
        player.stop()
        _isPlaying.value = false
        _isBuffering.value = false
        _currentTime.value = 0L
        stopProgressUpdates()
    }

    override fun seekTo(timeMs: Long) {
        player.seekTo(timeMs.coerceAtLeast(0L))
        _currentTime.value = timeMs.coerceAtLeast(0L)
    }

    override fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(target)
        _currentTime.value = target
    }

    override fun setVolume(volumeLevel: Int) {
        logicalVolume = volumeLevel.coerceIn(0, 200)
        applyVolume(logicalVolume)
    }

    override fun getVolume(): Int = logicalVolume

    override fun rampVolume(targetVolume: Int, durationMs: Long) {
        rampJob?.cancel()
        val start = (player.volume * 100f).toInt().coerceIn(0, 100)
        val target = targetVolume.coerceIn(0, 100)
        rampJob = scope.launch {
            val steps = (durationMs / 10L).coerceAtLeast(1L).toInt()
            for (i in 1..steps) {
                val level = start + ((target - start) * i) / steps
                player.volume = (level / 100f).coerceIn(0f, 1f)
                delay(10L)
            }
        }
    }

    override fun resetProgress() {
        _currentTime.value = 0L
        _totalDuration.value = 0L
    }

    override fun release() {
        stopProgressUpdates()
        rampJob?.cancel()
        player.release()
        scope.cancel()
    }

    private fun applyVolume(volumeLevel: Int) {
        // ExoPlayer volume is 0..1; the extra preamp range is a LibVLC feature.
        player.volume = (volumeLevel.coerceIn(0, 100) / 100f)
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (mediaLoaded) {
                    val position = player.currentPosition
                    if (position >= 0L) _currentTime.value = position
                    val duration = player.duration
                    if (duration > 0L && duration != _totalDuration.value) {
                        _totalDuration.value = duration
                    }
                }
                delay(200L)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
