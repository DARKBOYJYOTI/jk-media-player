package com.jk.mediaplayer.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.jk.mediaplayer.data.PreferencesManager
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LibVlcController(
    private val context: Context,
    private val enableAudioSession: Boolean = true,
    private val customAudioSessionId: Int? = null,
    private val isVideoFlow: Boolean = false
) {

    private var libVLC: LibVLC? = null
    var mediaPlayer: MediaPlayer? = null
        private set
    private var currentPfd: android.os.ParcelFileDescriptor? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTime = MutableStateFlow(0L)
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    private val _totalDuration = MutableStateFlow(0L)
    val totalDuration: StateFlow<Long> = _totalDuration.asStateFlow()

    private val _videoSize = MutableStateFlow<Pair<Int, Int>>(Pair(0, 0))
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    private val _buffering = MutableStateFlow(0f)
    val buffering: StateFlow<Float> = _buffering.asStateFlow()

    private val _isBuffering = MutableStateFlow(true)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _encounteredError = MutableStateFlow<String?>(null)
    val encounteredError: StateFlow<String?> = _encounteredError.asStateFlow()

    var onError: ((String) -> Unit)? = null

    fun clearError() {
        _encounteredError.value = null
    }

    /** True when an HDMI sink is connected that can keep a real 5.1 / 7.1 layout. */
    private fun hasMultichannelOutput(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return false
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                val isHdmi = device.type == android.media.AudioDeviceInfo.TYPE_HDMI ||
                    device.type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        device.type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC)
                isHdmi && device.channelCounts.any { it > 2 }
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun reportError(msg: String) {
        isSwitchingMedia = false
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        bufferingJob?.cancel()
        bufferingJob = null
        _isPlaying.value = false
        _isBuffering.value = false
        stopProgressUpdates()
        _encounteredError.value = msg
        onError?.invoke(msg)
    }

    private var pendingResumePositionMs: Long = 0L
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekStartedAtMs: Long = 0L
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var lastPlayedPath: String = ""
    private var lastPlayedUri: Uri? = null
    var onEndReached: (() -> Unit)? = null
    @Volatile var isSwitchingMedia: Boolean = false
    var hasEnded: Boolean = false
        private set

    val playerState: Int get() = mediaPlayer?.playerState ?: 0

    var hasUserSelectedSubtitle: Boolean = false
    var userSelectedSubtitleTrackId: Int = -1

    init {
        initLibVLC()
    }

    private var lastRequestedVolume: Int = 100
    private var volumeRampJob: Job? = null
    private var pendingPause: Boolean = false
    /** True while paused after a fade-out left the native AudioTrack at volume 0. */
    private var isPausedAtZeroVolume: Boolean = false
    /** Bumped whenever playback state changes, so a stale pause fade cannot touch new media. */
    private var pauseGeneration: Long = 0L
    /** Music fades in and out softly; video starts and stops instantly. */
    private val musicFadeInMs: Long = if (isVideoFlow) 0L else 180L
    private val musicFadeOutMs: Long = if (isVideoFlow) 0L else 180L
    private var targetAudioSessionId: Int = 0

    private var bufferingJob: kotlinx.coroutines.Job? = null
    private var playbackWatchdogJob: kotlinx.coroutines.Job? = null
    private var hasEverPlayed: Boolean = false
    private var playbackStartTimeMs: Long = 0L

    private val playerEventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                isSwitchingMedia = false
                hasEnded = false
                hasEverPlayed = true
                bufferingJob?.cancel()
                bufferingJob = null
                playbackWatchdogJob?.cancel()
                playbackWatchdogJob = null
                _isPlaying.value = true
                _isBuffering.value = false
                _encounteredError.value = null
                val currentVol = mediaPlayer?.volume ?: -1
                val rampRunning = volumeRampJob?.isActive == true
                if (!rampRunning && !isPausedAtZeroVolume && currentVol >= 0 && currentVol != lastRequestedVolume) {
                    // Ignore libVLC's -1 sentinel before the AudioTrack is ready, and do not snap
                    // the level while a fade ramp is running; writing volume on the first PCM
                    // frame causes an audible speaker click.
                    if (kotlin.math.abs(currentVol - lastRequestedVolume) > 20) {
                        mediaPlayer?.volume = lastRequestedVolume
                    }
                }
                startProgressUpdates()
                if (pendingResumePositionMs > 0L) {
                    val target = pendingResumePositionMs
                    pendingResumePositionMs = 0L
                    mediaPlayer?.time = target
                    _currentTime.value = target
                }
                if (attachedLayout != null) {
                    applyResizeMode()
                    // Subtitle/vout juggling is video-only.
                    if (hasUserSelectedSubtitle && userSelectedSubtitleTrackId >= 0) {
                        mediaPlayer?.spuTrack = userSelectedSubtitleTrackId
                    } else {
                        enforceSubtitleOff()
                    }
                    val curW = getVideoWidth()
                    val curH = getVideoHeight()
                    if (curW > 0 && curH > 0) {
                        _videoSize.value = Pair(curW, curH)
                    }
                }
            }
            MediaPlayer.Event.Paused -> {
                bufferingJob?.cancel()
                bufferingJob = null
                playbackWatchdogJob?.cancel()
                playbackWatchdogJob = null
                _isBuffering.value = false
                if (!isSwitchingMedia) {
                    _isPlaying.value = false
                    stopProgressUpdates()
                    mediaPlayer?.time?.takeIf { it >= 0L }?.let { _currentTime.value = it }
                }
            }
            MediaPlayer.Event.Stopped -> {
                bufferingJob?.cancel()
                bufferingJob = null
                playbackWatchdogJob?.cancel()
                playbackWatchdogJob = null
                _isBuffering.value = false
                if (!isSwitchingMedia) {
                    _isPlaying.value = false
                    stopProgressUpdates()
                    mediaPlayer?.time?.takeIf { it >= 0L }?.let { _currentTime.value = it }
                }
            }
            MediaPlayer.Event.TimeChanged -> {
                if (event.timeChanged > 0L) {
                    val wasFirstFrame = !hasEverPlayed
                    hasEverPlayed = true
                    bufferingJob?.cancel()
                    bufferingJob = null
                    playbackWatchdogJob?.cancel()
                    playbackWatchdogJob = null
                    _isBuffering.value = false
                    if (wasFirstFrame && attachedLayout != null) {
                        applyResizeMode()
                    }
                }
            }
            MediaPlayer.Event.Vout -> {
                applyResizeMode()
                if (hasUserSelectedSubtitle && userSelectedSubtitleTrackId >= 0) {
                    mediaPlayer?.spuTrack = userSelectedSubtitleTrackId
                } else {
                    enforceSubtitleOff()
                }
                val curW = getVideoWidth()
                val curH = getVideoHeight()
                if (curW > 0 && curH > 0) {
                    _videoSize.value = Pair(curW, curH)
                }
            }
            MediaPlayer.Event.ESAdded -> {
                // Audio/Subtitle/Video track discovered; resize and subtitle checks are video-only.
                if (attachedLayout != null) {
                    applyResizeMode()
                    if (hasUserSelectedSubtitle && userSelectedSubtitleTrackId >= 0) {
                        mediaPlayer?.spuTrack = userSelectedSubtitleTrackId
                    } else {
                        enforceSubtitleOff()
                    }
                    val curW = getVideoWidth()
                    val curH = getVideoHeight()
                    if (curW > 0 && curH > 0) {
                        _videoSize.value = Pair(curW, curH)
                    }
                }
            }
            MediaPlayer.Event.LengthChanged -> _totalDuration.value = event.lengthChanged
            MediaPlayer.Event.Buffering -> {
                _buffering.value = event.buffering
                if (event.buffering >= 100f) {
                    bufferingJob?.cancel()
                    bufferingJob = null
                    _isBuffering.value = false
                } else if (!_isBuffering.value && bufferingJob == null) {
                    // Debounce buffering dialog by 350ms so local frame seeks (<300ms) don't flicker!
                    bufferingJob = progressScope.launch {
                        delay(350L)
                        if (mediaPlayer?.isPlaying == true || isSwitchingMedia) {
                            _isBuffering.value = true
                        }
                    }
                }
            }
            MediaPlayer.Event.EndReached -> {
                val currentPos = mediaPlayer?.time ?: 0L
                val totalLen = mediaPlayer?.length ?: 0L
                playbackWatchdogJob?.cancel()
                playbackWatchdogJob = null
                bufferingJob?.cancel()
                bufferingJob = null
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressUpdates()

                // If EndReached fired without ever playing or 0 duration, LibVLC failed to decode or open the file/stream
                if (!hasEverPlayed || (currentPos <= 1000L && totalLen <= 0L)) {
                    val err = "Cannot play this video. The codec or container format is unsupported, or the file is corrupted."
                    reportError(err)
                    return@EventListener
                }

                if (isSwitchingMedia) {
                    return@EventListener
                }

                hasEnded = true
                _currentTime.value = 0L
                onEndReached?.invoke()
            }
            MediaPlayer.Event.EncounteredError -> {
                val err = "Cannot play this media. The format or stream may be corrupted or unsupported."
                reportError(err)
            }
        }
    }

    /**
     * Builds the `--sub-margin` option.
     *
     * LibVLC counts the margin in pixels of the video output, which is the rendered video, not the
     * screen. A value derived from the screen therefore drifts: a fixed 72 dp became 18% of the
     * picture height and pushed the cue far up, while 50 raw pixels sat under the seek bar on a
     * 440 dpi panel. The margin is now a small share of the video height: in landscape the video
     * fills the screen height, in portrait it is a 16:9 letterbox, so both orientations land at
     * roughly 8% above the bottom of the picture.
     *
     * The option is global to the libVLC instance, so the value is fixed for the orientation the
     * player was created in. Rotating mid-playback keeps it until the next video opens.
     */
    private fun subtitleMarginOption(): String {
        val metrics = context.resources.displayMetrics
        val isLandscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenShort = minOf(metrics.widthPixels, metrics.heightPixels)
        val videoHeightPx = if (isLandscape) {
            // A normal 16:9 file fills the screen height and is pillarboxed horizontally.
            screenShort
        } else {
            // Portrait playback letterboxes a 16:9 file across the screen width.
            (screenShort * 9f / 16f).toInt()
        }
        val floorPx = (14f * metrics.density).toInt()
        val capPx = (screenShort * 0.16f).toInt()
        // 13% keeps subtitles above the 10% bottom crop line on 20:9 displays in Cropped mode.
        val marginPx = (videoHeightPx * 0.13f).toInt().coerceIn(floorPx, capPx)
        return "--sub-margin=$marginPx"
    }

    private fun initLibVLC() {
        targetAudioSessionId = customAudioSessionId ?: if (enableAudioSession) AudioSession.id(context) else 0
        // Every option in this list must exist in the bundled libvlc build: one unknown option
        // makes libvlc_new() return null and the whole list is thrown away. "sub-autodetect-caching"
        // is not part of VLC 3.6 and used to force the safe fallback on every launch.
        // This libvlc build (3.0.22) has no AAudio module, only audiotrack and opensles.
        // Keep the AudioTrack output explicit so the list is not silently downgraded.
        val audioOutputOption = "--aout=android_audiotrack"
        val stretchOption = if (isVideoFlow) "--audio-time-stretch" else "--no-audio-time-stretch"
        val hwDecodeOption = if (isVideoFlow) "--avcodec-hw=any" else "--avcodec-hw=none"
        // Give local video 600 ms preroll so MediaCodec's first keyframe aligns cleanly with
        // AudioTrack's initial PTS (preventing audio starting before the picture appears).
        val localFileCachingMs = if (isVideoFlow) 600 else 1200
        val primaryOptions = arrayListOf(
            audioOutputOption,
            hwDecodeOption,            // Software audio decoding for music avoids MediaCodec init clicks
            "--file-caching=$localFileCachingMs",
            "--network-caching=1000",
            stretchOption              // Pitch correction only where speed control exists
        )
        if (isVideoFlow) {
            primaryOptions.addAll(
                listOf(
                    "--no-video-title-show",   // Prevents freetype/Fontconfig init on frame 1 when subtitles are off
                    "--sub-text-scale=100",    // Crisp high-resolution subtitles
                    "--subsdec-encoding=UTF-8",// Universal UTF-8 subtitle decoding (prevents boxes/tofu on non-English scripts)
                    subtitleMarginOption(),    // Density-aware bottom margin keeps cues clear of the seek bar
                    "--sub-autodetect-fuzzy=3" // Automatic subtitle file detection (.srt, .vtt, .sub, .ass)
                )
            )
        } else {
            primaryOptions.add("--no-sub-autodetect-file")
        }
        // Cached positions are not worth the crash: if the rich list is ever rejected by a future
        // bundled build, retry without the optional media options before giving up on them.
        val mediaOnlyOptions = arrayOf(
            "--avcodec-hw=any",
            "--avcodec-hw=none",
            "--network-caching=1000",
            "--audio-time-stretch",
            "--no-audio-time-stretch",
            "--no-sub-autodetect-file"
        )
        val fallbackOptions = arrayListOf(
            "--aout=android_audiotrack",
            "--file-caching=$localFileCachingMs"
        ).apply {
            if (isVideoFlow) {
                add("--no-video-title-show")
                add("--subsdec-encoding=UTF-8")
                add("--sub-margin=50")
            }
        }
        // The session id must be a global libVLC option. It is registered with change_private()
        // in VLC, so passing it as a per-media ":audiotrack-session-id=" option is silently
        // ignored and the AudioTrack ends up on a different session than our AudioEffects.
        if (targetAudioSessionId > 0) {
            val sessionOption = "--audiotrack-session-id=$targetAudioSessionId"
            primaryOptions.add(sessionOption)
            fallbackOptions.add(sessionOption)
        }
        libVLC = tryCreateLibVlc(primaryOptions)
            ?: tryCreateLibVlc(primaryOptions.filterNot { it in mediaOnlyOptions }.toMutableList())
            ?: tryCreateLibVlc(fallbackOptions)
            // Last resort: drop the session option too, so audio always starts even if a future
            // build rejects it. Without it only the effects are lost, never playback.
            ?: tryCreateLibVlc(
                fallbackOptions.filterNot { it.startsWith("--audiotrack-session-id") }.toMutableList()
            )
        if (libVLC == null) {
            reportError("Audio engine could not start on this device.")
            return
        }
        mediaPlayer = MediaPlayer(libVLC).apply {
            if (lastRequestedVolume != 100) {
                volume = lastRequestedVolume
            }
            if (currentPlaybackSpeed != 1.0f) {
                rate = currentPlaybackSpeed
            }
            setEventListener(playerEventListener)
        }
    }

    /** Retries libvlc_new with a smaller option list; returns null when the list is rejected. */
    private fun tryCreateLibVlc(options: MutableList<String>): LibVLC? {
        return try {
            LibVLC(context.applicationContext, options)
        } catch (e: Throwable) {
            null
        }
    }

    private var attachedLayout: VLCVideoLayout? = null
    var currentResizeMode: ResizeMode = ResizeMode.ORIGINAL
    var currentDisplayWidth: Int = 0
    var currentDisplayHeight: Int = 0

    fun attachVideoLayout(vlcVideoLayout: VLCVideoLayout) {
        val vout = mediaPlayer?.vlcVout
        if (attachedLayout == vlcVideoLayout && vout != null && vout.areViewsAttached()) {
            mediaPlayer?.setVideoTrackEnabled(true)
            applyResizeMode()
            return
        }
        attachedLayout = vlcVideoLayout
        try {
            if (vout != null && vout.areViewsAttached()) {
                vout.detachViews()
            }
            // useTextureView=false selects the supported LibVLC Android layout: a SurfaceView for
            // the picture and the library's own subtitle SurfaceView next to it. The texture path
            // is served by the OpenGL vout, which owns both planes, so a resize of the picture
            // drags the cue with it and the native zoom/aspect controls are ignored. The surface
            // path keeps the cue on its own window, and stretch/crop/zoom stay picture-only.
            mediaPlayer?.attachViews(vlcVideoLayout, null, true, false)
            mediaPlayer?.setVideoTrackEnabled(true)
            applyResizeMode()
        } catch (e: Throwable) {
        }
    }

    fun detachVideoLayout(onlyIfLayout: VLCVideoLayout? = null) {
        if (onlyIfLayout != null && attachedLayout !== onlyIfLayout) {
            return
        }
        try {
            val vout = mediaPlayer?.vlcVout
            if (vout != null && vout.areViewsAttached()) {
                vout.detachViews()
            } else {
                mediaPlayer?.detachViews()
            }
        } catch (e: Throwable) {
        }
    }

    fun reattachVideoLayout(resyncKeyframe: Boolean = false) {
        val layout = attachedLayout ?: return
        val player = mediaPlayer ?: return
        val vout = player.vlcVout
        try {
            val wasDetached = !vout.areViewsAttached()
            if (wasDetached) {
                player.attachViews(layout, null, true, false)
            }
            val w = layout.width
            val h = layout.height
            if (w > 0 && h > 0) {
                player.vlcVout.setWindowSize(w, h)
            }
            player.setVideoTrackEnabled(true)
            applyResizeMode()
            layout.requestLayout()
            // Re-attaching a surface mid-GOP (e.g. returning from background audio) makes hardware
            // decoders paint gray P/B-frames until the next natural I-frame arrives. A micro-seek
            // to the current timestamp snaps the demuxer to the nearest keyframe immediately.
            if (resyncKeyframe || wasDetached) {
                val curTime = player.time
                if (curTime > 0L) {
                    player.time = curTime
                }
            }
        } catch (e: Throwable) {
        }
    }

    /** Updates LibVLC's output window dimensions whenever the video container resizes (e.g. PiP exit or rotation). */
    fun updateVideoWindowSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        syncVideoSurfaceBounds(explicitWidth = width, explicitHeight = height)
    }

    /**
     * Resets stale PiP pixel LayoutParams locked onto player_surface_frame / surface_video by
     * LibVLC's VideoHelper during PiP exit animations, then re-runs VideoHelper.updateVideoSurfaces()
     * at full screen size without ever tearing down the active MediaCodec surface.
     */
    fun syncVideoSurfaceBounds(explicitWidth: Int = 0, explicitHeight: Int = 0) {
        val layout = attachedLayout ?: return
        val player = mediaPlayer ?: return
        try {
            val vout = player.vlcVout
            val wasDetached = !vout.areViewsAttached()
            if (wasDetached) {
                player.attachViews(layout, null, true, false)
            }
            val surfaceVideo: android.view.View? = layout.findViewById<android.view.View>(org.videolan.R.id.surface_video)
                ?: layout.findViewById(org.videolan.R.id.texture_video)
            val surfaceSubtitles: android.view.View? = layout.findViewById(org.videolan.R.id.surface_subtitles)
            val surfaceFrame: android.view.View? = surfaceVideo?.parent as? android.view.View

            fun resetToMatchParent(view: android.view.View?) {
                val lp = view?.layoutParams ?: return
                var changed = false
                if (lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
                if (lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
                if (lp is android.widget.FrameLayout.LayoutParams && lp.gravity != android.view.Gravity.CENTER) {
                    lp.gravity = android.view.Gravity.CENTER
                    changed = true
                }
                if (changed) {
                    view.layoutParams = lp
                }
            }

            resetToMatchParent(surfaceFrame)
            resetToMatchParent(surfaceVideo)
            resetToMatchParent(surfaceSubtitles)
            (surfaceVideo as? android.view.SurfaceView)?.holder?.setSizeFromLayout()
            (surfaceSubtitles as? android.view.SurfaceView)?.holder?.setSizeFromLayout()

            val w = if (explicitWidth > 0) explicitWidth else layout.width
            val h = if (explicitHeight > 0) explicitHeight else layout.height
            if (w > 0 && h > 0) {
                vout.setWindowSize(w, h)
            }
            player.setVideoTrackEnabled(true)
            player.aspectRatio = null
            player.scale = 0f
            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            layout.requestLayout()
            if (wasDetached) {
                val curTime = player.time
                if (curTime > 0L) {
                    player.time = curTime
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun reattachAfterSurfaceRecreate(vlcVideoLayout: VLCVideoLayout) {
        val player = mediaPlayer ?: return
        try {
            attachedLayout = vlcVideoLayout
            val vout = player.vlcVout
            if (vout.areViewsAttached()) {
                vout.detachViews()
            }
            player.attachViews(vlcVideoLayout, null, true, false)
            val w = vlcVideoLayout.width
            val h = vlcVideoLayout.height
            if (w > 0 && h > 0) {
                player.vlcVout.setWindowSize(w, h)
            }
            player.setVideoTrackEnabled(true)
            applyResizeMode()
            vlcVideoLayout.requestLayout()

            val curTime = player.time.takeIf { it > 0L } ?: _currentTime.value
            val wasPlaying = _isPlaying.value
            if (wasPlaying) {
                if (curTime > 0L) {
                    player.time = curTime
                }
            } else if (player.media != null) {
                // When paused during PiP restore, briefly decode a single keyframe onto the new
                // SurfaceView and immediately re-pause so the user sees a crisp fullscreen frame
                // rather than a black surface.
                val savedVol = player.volume
                player.volume = 0
                player.play()
                if (curTime > 0L) {
                    player.time = curTime
                }
                vlcVideoLayout.postDelayed({
                    try {
                        player.pause()
                        _isPlaying.value = false
                        if (savedVol >= 0) player.volume = savedVol
                        applyResizeMode()
                    } catch (_: Throwable) {}
                }, 140L)
            }
        } catch (_: Throwable) {
        }
    }

    fun refreshPausedFrame() {
        val mp = mediaPlayer ?: return
        if (_isPlaying.value) return
        val layout = attachedLayout ?: return
        layout.post {
            try {
                if (!_isPlaying.value && mp.media != null) {
                    applyResizeMode()
                }
            } catch (e: Throwable) {
            }
        }
    }

    fun playMedia(
        path: String,
        uri: Uri? = null,
        resumePositionMs: Long = 0L,
        username: String? = null,
        password: String? = null,
        httpHeaders: Map<String, String> = emptyMap(),
        audioOnly: Boolean = false
    ) {
        val vlc = libVLC ?: return
        // A pause fade left running by the previous track must never act on the media that is
        // about to start: it would drop the volume to zero and pause the new song, which is heard
        // as "play, duck, play" while the UI still says playing.
        pauseGeneration++
        pendingPause = false
        isPausedAtZeroVolume = false
        cancelVolumeRamp()
        stopProgressUpdates()
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        isSwitchingMedia = true
        hasEnded = false
        hasEverPlayed = false
        hasUserSelectedSubtitle = false
        userSelectedSubtitleTrackId = -1
        // Elapsed-realtime clock: pause() compares this value against SystemClock.elapsedRealtime()
        // to decide whether a fade is safe. Mixing it with the wall clock made the difference a
        // large negative number, so the fade branch never ran.
        playbackStartTimeMs = SystemClock.elapsedRealtime()
        _isBuffering.value = true
        _encounteredError.value = null
        lastPlayedPath = path
        lastPlayedUri = uri
        _videoSize.value = Pair(0, 0)
        if (isVideoFlow) {
            try {
                val prefs = PreferencesManager(context)
                val mediaKey = path.ifEmpty { uri?.toString() ?: "" }
                val savedModeStr = if (mediaKey.isNotEmpty()) prefs.getVideoResizeMode(mediaKey) ?: prefs.getDefaultResizeMode() else prefs.getDefaultResizeMode()
                currentResizeMode = ResizeMode.fromString(savedModeStr)
            } catch (_: Throwable) {}
        }
        val previousPfd = currentPfd
        var newPfd: android.os.ParcelFileDescriptor? = null
        try {
            val isOnlineStream = path.startsWith("http://", true) ||
                path.startsWith("https://", true) ||
                path.startsWith("rtsp://", true) ||
                path.startsWith("rtmp://", true) ||
                path.startsWith("mms://", true) ||
                path.startsWith("ftp://", true) ||
                path.startsWith("udp://", true) ||
                (uri != null && (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "rtsp" || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "udp"))

            // Validate local file integrity upfront
            if (path.isNotEmpty() && !isOnlineStream) {
                val file = java.io.File(path)
                if (!file.exists()) {
                    reportError("Video file not found. It may have been moved, deleted, or unmounted.")
                    return
                }
                if (file.length() == 0L) {
                    reportError("Cannot play video. File is empty (0 bytes) or corrupted.")
                    return
                }
            }

            val forcedDemux = detectDemuxer(path, uri)
            val media = when {
                isOnlineStream -> {
                    val streamUri = uri ?: Uri.parse(path)
                    Media(vlc, streamUri)
                }
                path.isNotEmpty() && java.io.File(path).exists() -> {
                    val file = java.io.File(path)
                    if (forcedDemux != null) {
                        val pfd = try {
                            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        } catch (_: Exception) { null }
                        if (pfd != null) {
                            newPfd = pfd
                            Media(vlc, pfd.fileDescriptor)
                        } else {
                            Media(vlc, path)
                        }
                    } else {
                        Media(vlc, path)
                    }
                }
                uri != null -> {
                    val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                    if (pfd != null) {
                        if (pfd.statSize == 0L) {
                            try { pfd.close() } catch (_: Exception) {}
                            reportError("Cannot play video. File is empty (0 bytes) or corrupted.")
                            return
                        }
                        newPfd = pfd
                        Media(vlc, pfd.fileDescriptor)
                    } else {
                        if (uri.scheme == "content") {
                            reportError("Cannot open video. Storage access denied or file was deleted.")
                            return
                        }
                        Media(vlc, uri)
                    }
                }
                else -> {
                    reportError("Cannot access or open media file.")
                    return
                }
            }
            val isOggOrOpus = !isOnlineStream && (
                forcedDemux == "ogg" ||
                    path.endsWith(".opus", true) ||
                    path.endsWith(".ogg", true) ||
                    path.endsWith(".oga", true)
                )
            if (forcedDemux != null) {
                // VLC's native ogg demuxer only timestamps at page boundaries (1 s on Opus),
                // triggering AudioTrack flushes and underruns after pause/resume. Use FFmpeg's
                // avformat demuxer for Ogg/Opus music so every 20 ms packet gets an exact PTS.
                val effectiveDemux = if (!isVideoFlow && forcedDemux == "ogg") "avformat" else forcedDemux
                media.addOption(":demux=$effectiveDemux,any")
            } else if (!isVideoFlow && isOggOrOpus) {
                media.addOption(":demux=avformat,any")
            }

            if (resumePositionMs > 1000L) {
                val startSec = resumePositionMs / 1000L
                media.addOption(":start-time=$startSec")
                pendingResumePositionMs = 0L
            } else {
                pendingResumePositionMs = resumePositionMs
            }
            val isLocalAudioFile = !isOnlineStream && listOf(
                ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac", ".opus", ".wma", ".alac", ".dsd"
            ).any { path.endsWith(it, true) }
            if (isOnlineStream) {
                // Audio-only playback has no video track to open, so no :no-video option is
                // needed. A short cache plus HTTP reconnect starts the song faster and survives a
                // dropped connection that otherwise looks like an endless buffer.
                media.addOption(":network-caching=${if (audioOnly) 800 else 2000}")
                if (audioOnly) {
                    media.addOption(":http-reconnect")
                }
                httpHeaders["User-Agent"]?.let { media.addOption(":http-user-agent=$it") }
                httpHeaders["Referer"]?.let { media.addOption(":http-referrer=$it") }
                if (!username.isNullOrBlank()) {
                    media.addOption(":http-user=$username")
                    media.addOption(":rtsp-user=$username")
                }
                if (!password.isNullOrBlank()) {
                    media.addOption(":http-pwd=$password")
                    media.addOption(":rtsp-pwd=$password")
                }
            } else {
                if (isLocalAudioFile) {
                    media.addOption(":no-video")
                }
                // Ogg Opus/Vorbis files commonly pack 1,000 ms of audio per Ogg page; a 400 ms
                // cache starves and triggers AudioTrack resync clicks on pause/resume. 1,200 ms
                // accommodates full Ogg pages cleanly across all music formats.
                val localFileCachingMs = if (isVideoFlow) 600 else 1200
                media.addOption(":file-caching=$localFileCachingMs")
            }
            if (isVideoFlow) {
                // Subtitles are off unless this viewer already chose a track for this playback.
                // Passing both :sub-track=-1 and :sub-track-id=-1 prevents libass from opening
                // during initial video start while keeping all tracks selectable on demand.
                if (userSelectedSubtitleTrackId >= 0) {
                    media.addOption(":sub-track=$userSelectedSubtitleTrackId")
                } else {
                    media.addOption(":sub-track=-1")
                    media.addOption(":sub-track-id=-1")
                }
                // Multichannel soundtracks (AC-3, E-AC-3, DTS, TrueHD) are decoded in software and
                // must be folded into the stereo track this device actually plays. Some 5.1 mixes
                // drop the centre channel without an explicit stereo target, so dialogue vanishes.
                // HDMI outputs keep their native layout, everything else is downmixed to stereo.
                if (!hasMultichannelOutput()) {
                    media.addOption(":audio-channels=2")
                }
                val prefs = PreferencesManager(context)
                val hwMode = prefs.getVideoHwDecoder()
                val profileMode = prefs.getVideoEnhancerMode()
                val highColor = prefs.isVideoHighColorQuality()
                val deint = prefs.getVideoDeinterlaceMode()

                // LibVLC's pixel-level filters (adjust picture profile, RV32 32-bit chroma, and
                // yadif/blend deinterlacing) operate on decoded software frames, not opaque
                // MediaCodec HW surfaces. Automatically switch to software rendering whenever any
                // of those filters is active so every option applies natively with zero emulation.
                val needsSoftwarePipeline =
                    profileMode == "VIVID" ||
                    profileMode == "CINEMA" ||
                    highColor ||
                    deint == "YADIF" ||
                    deint == "BLEND"

                val (hwEnabled, forceHW) = when {
                    isLocalAudioFile || hwMode == "SOFTWARE" || needsSoftwarePipeline -> Pair(false, false)
                    hwMode == "FORCE_HW" -> Pair(true, true)
                    else -> Pair(true, false)
                }
                media.setHWDecoderEnabled(hwEnabled, forceHW)

                when (deint) {
                    "YADIF" -> {
                        media.addOption(":deinterlace=1")
                        media.addOption(":deinterlace-mode=yadif")
                    }
                    "BLEND" -> {
                        media.addOption(":deinterlace=1")
                        media.addOption(":deinterlace-mode=blend")
                    }
                    "OFF" -> {
                        media.addOption(":deinterlace=0")
                    }
                }
                when (profileMode) {
                    "VIVID" -> {
                        media.addOption(":video-filter=adjust")
                        media.addOption(":contrast=1.10")
                        media.addOption(":saturation=1.18")
                        media.addOption(":gamma=0.98")
                        media.addOption(":brightness=1.02")
                    }
                    "CINEMA" -> {
                        media.addOption(":video-filter=adjust")
                        media.addOption(":contrast=1.06")
                        media.addOption(":saturation=0.94")
                        media.addOption(":gamma=1.06")
                        media.addOption(":brightness=0.98")
                    }
                }
                if (highColor) {
                    media.addOption(":android-display-chroma=RV32")
                }
                // Reset stale dimensions and probe local video dimensions off the UI thread so
                // Fit / Stretch / Cropped work reliably even on MKVs where currentVideoTrack lags or returns 0.
                _videoSize.value = Pair(0, 0)
                if (!isOnlineStream) {
                    probeLocalVideoSizeAsync(path, uri)
                }
            } else {
                // Keep MediaCodec disabled for music playback: probing vendor hardware audio
                // decoders before falling back to software causes a startup click on local tracks.
                media.setHWDecoderEnabled(false, false)
                if (isOggOrOpus) {
                    // Pair avformat with FFmpeg's software Opus/Vorbis decoder to avoid libopus
                    // PTS drift flushes in libVLC 3.0.x on Android AudioTrack resume.
                    media.addOption(":codec=avcodec,all")
                }
            }
            mediaPlayer?.apply {
                setEventListener(null)
                this.media = media
                media.release()
                currentPfd = newPfd
                if (previousPfd != null && previousPfd !== newPfd) {
                    try { previousPfd.close() } catch (_: Exception) {}
                }
                setEventListener(playerEventListener)
                val currentVol = volume
                if ((currentVol >= 0 && currentVol != lastRequestedVolume) || lastRequestedVolume != 100) {
                    volume = lastRequestedVolume
                }
                if (currentPlaybackSpeed != 1.0f) {
                    rate = currentPlaybackSpeed
                }
                play()
            }

            // Watchdog: a network stream can spend a long time opening and buffering before the
            // first frame, so one fixed deadline is not a failure signal. The check repeats and
            // only reports when the player itself is silent — nothing playing, nothing buffering,
            // no open in progress. An explicit player error is reported at once.
            val watchdogStepMs = if (isOnlineStream) 4000L else 2500L
            playbackWatchdogJob = progressScope.launch {
                var silentChecks = 0
                while (isActive) {
                    delay(watchdogStepMs)
                    if (_isPlaying.value || hasEverPlayed || _encounteredError.value != null) return@launch
                    val player = mediaPlayer ?: return@launch
                    val state = player.playerState
                    if (state == org.videolan.libvlc.interfaces.IMedia.State.Error) {
                        reportError(
                            if (isOnlineStream) {
                                "Cannot connect to video stream. The link may be offline, invalid, or unreachable."
                            } else {
                                "Video playback timed out. The format or codec may be unsupported."
                            }
                        )
                        return@launch
                    }
                    val waiting = isSwitchingMedia ||
                        _isBuffering.value ||
                        _buffering.value > 0f ||
                        player.isPlaying ||
                        state == org.videolan.libvlc.interfaces.IMedia.State.Opening ||
                        state == org.videolan.libvlc.interfaces.IMedia.State.Playing
                    if (waiting) {
                        silentChecks = 0
                        continue
                    }
                    silentChecks++
                    if (silentChecks < if (isOnlineStream) 3 else 2) continue
                    reportError(
                        if (isOnlineStream) {
                            "Cannot connect to video stream. The link may be offline, invalid, or unreachable."
                        } else {
                            "Video playback timed out. The format or codec may be unsupported."
                        }
                    )
                    return@launch
                }
            }
        } catch (e: Exception) {
            if (newPfd != null && newPfd !== currentPfd) {
                try { newPfd.close() } catch (_: Exception) {}
            }
            val err = "Cannot play this media: ${e.localizedMessage ?: "Playback error"}"
            reportError(err)
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        // While the short fade-out before a pause is still running the stream is technically
        // playing, so a fast second tap must resume instead of queueing another pause.
        if (pendingPause) {
            play()
            return
        }
        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val player = mediaPlayer ?: return
        cancelVolumeRamp()
        val wasPendingPause = pendingPause
        val wasZeroPaused = isPausedAtZeroVolume
        pendingPause = false
        isPausedAtZeroVolume = false
        pauseGeneration++
        val logical = lastRequestedVolume
        if (wasPendingPause && player.isPlaying) {
            // Resumed while the pause fade-out was still running: ramp smoothly back up from the
            // current level instead of snapping the volume.
            if (musicFadeInMs > 0L) {
                val start = currentPlayerVolume(0)
                if (start != logical) {
                    volumeRampJob = progressScope.launch {
                        rampVolumeInternal(start, logical, musicFadeInMs)
                    }
                }
            }
            return
        }
        if (player.isPlaying) return
        val state = player.playerState
        val isAtEnd = _totalDuration.value > 0L && _currentTime.value >= _totalDuration.value - 1000L
        // Reload only when the media is genuinely finished. A transient Stopped/NothingSpecial
        // state while opening or seeking used to reload the file from 0:00, which is heard as
        // the song stopping and then playing again.
        if (hasEnded || state == 6 || isAtEnd) {
            hasEnded = false
            if (lastPlayedPath.isNotEmpty() || lastPlayedUri != null) {
                playMedia(lastPlayedPath, lastPlayedUri, 0L)
                return
            }
            player.time = 0L
        }
        if (wasZeroPaused && musicFadeInMs > 0L) {
            // The AudioTrack was paused at volume 0, so resuming starts silently and ramps up
            // after a short settle delay without any gain snap or click.
            player.play()
            if (attachedLayout != null) {
                applyResizeMode()
            }
            volumeRampJob = progressScope.launch {
                delay(25L)
                rampVolumeInternal(0, logical, musicFadeInMs)
            }
            return
        }
        // Plain resume without a prior fade-out: keep the AudioTrack volume untouched.
        player.play()
        if (attachedLayout != null) {
            applyResizeMode()
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying || pendingPause) return
        // A pause in the first moment of playback skips the fade: the AudioTrack is still settling
        // and a volume ramp makes the output restart audibly.
        val sinceStart = SystemClock.elapsedRealtime() - playbackStartTimeMs
        if (sinceStart < 600L || musicFadeOutMs <= 0L) {
            cancelVolumeRamp()
            pendingPause = false
            isPausedAtZeroVolume = false
            pauseGeneration++
            try { player.pause() } catch (_: Throwable) {}
            _isPlaying.value = false
            return
        }
        cancelVolumeRamp()
        pendingPause = true
        pauseGeneration++
        val generation = pauseGeneration
        volumeRampJob = progressScope.launch {
            val start = currentPlayerVolume(lastRequestedVolume)
            rampVolumeInternal(start, 0, musicFadeOutMs.coerceAtLeast(1L))
            // A new track or resume started while this fade was running: leave its audio alone.
            if (!pendingPause || generation != pauseGeneration) return@launch
            // Let the AudioTrack render true silence before pausing asynchronously.
            delay(35L)
            if (!pendingPause || generation != pauseGeneration) return@launch
            // Keep volume at 0 while paused instead of restoring it right after async pause():
            // writing volume back to 100 before the native AudioTrack finishes pausing caused a
            // pop/snap (especially on Ogg Opus's 1 s pages) and left AudioFlinger gain dirty.
            isPausedAtZeroVolume = true
            try { player.pause() } catch (_: Throwable) {}
            pendingPause = false
            _isPlaying.value = false
            if (attachedLayout != null) {
                applyResizeMode()
            }
        }
    }

    /** Fast temporary level change (audio focus ducking) that keeps the user's logical volume. */
    fun rampVolume(targetVolume: Int, durationMs: Long = 90L) {
        val player = mediaPlayer ?: return
        if (pendingPause || isPausedAtZeroVolume) return
        cancelVolumeRamp()
        val target = targetVolume.coerceIn(0, 200)
        val start = currentPlayerVolume(target)
        if (start == target) return
        volumeRampJob = progressScope.launch {
            rampVolumeInternal(start, target, durationMs)
        }
    }

    private fun currentPlayerVolume(fallback: Int): Int {
        val raw = try { mediaPlayer?.volume ?: -1 } catch (_: Throwable) { -1 }
        return if (raw >= 0) raw else fallback
    }

    private suspend fun rampVolumeInternal(from: Int, to: Int, durationMs: Long) {
        val steps = (durationMs / 10L).coerceAtLeast(1L).toInt()
        val stepDelayMs = (durationMs / steps).coerceAtLeast(1L)
        var lastWritten = -1
        for (i in 1..steps) {
            val progress = i.toDouble() / steps.toDouble()
            // Cosine ease-in-out gives a smooth perceptual transition near silence.
            val eased = 0.5 * (1.0 - kotlin.math.cos(kotlin.math.PI * progress))
            val level = (from + (to - from) * eased).toInt().coerceIn(0, 200)
            if (level != lastWritten) {
                try { mediaPlayer?.volume = level } catch (_: Throwable) {}
                lastWritten = level
            }
            delay(stepDelayMs)
        }
        val finalLevel = to.coerceIn(0, 200)
        if (lastWritten != finalLevel) {
            try { mediaPlayer?.volume = finalLevel } catch (_: Throwable) {}
        }
    }

    private fun cancelVolumeRamp() {
        volumeRampJob?.cancel()
        volumeRampJob = null
    }

    /** Fade the current audio out before stopping it, used when a new track takes over. */
    fun stopWithFade() {
        val player = mediaPlayer ?: return
        // Too early to fade: the output is still settling and a ramp would restart it audibly.
        if (!player.isPlaying || musicFadeOutMs <= 0L ||
            SystemClock.elapsedRealtime() - playbackStartTimeMs < 600L) {
            stop()
            return
        }
        pauseGeneration++
        val generation = pauseGeneration
        cancelVolumeRamp()
        pendingPause = true
        volumeRampJob = progressScope.launch {
            rampVolumeInternal(currentPlayerVolume(lastRequestedVolume), 0, musicFadeOutMs.coerceAtLeast(1L))
            if (generation != pauseGeneration) return@launch
            delay(35L)
            if (generation != pauseGeneration) return@launch
            try { player.stop() } catch (_: Throwable) {}
            isPausedAtZeroVolume = true
            pendingPause = false
            _isPlaying.value = false
        }
    }

    fun stop() {
        val player = mediaPlayer ?: return
        // Stopping invalidates any fade that is still in flight.
        pauseGeneration++
        pendingPause = false
        isPausedAtZeroVolume = false
        cancelVolumeRamp()
        if (player.isPlaying) player.stop()
        _isPlaying.value = false
        try { currentPfd?.close() } catch (_: Exception) {}
        currentPfd = null
    }

    fun seekTo(timeMs: Long) {
        val target = timeMs.coerceAtLeast(0L)
        pendingSeekTargetMs = target
        pendingSeekStartedAtMs = SystemClock.elapsedRealtime()
        mediaPlayer?.time = target
        _currentTime.value = target
    }

    /** Clears the cached position/duration when a new track is loaded. */
    fun resetProgress() {
        pendingResumePositionMs = 0L
        pendingSeekTargetMs = null
        _currentTime.value = 0L
        _totalDuration.value = 0L
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = progressScope.launch {
            while (isActive) {
                val player = mediaPlayer ?: break
                if (!player.isPlaying) break
                val position = player.time
                if (position >= 0L) {
                    val seekTarget = pendingSeekTargetMs
                    val seekIsStillPending = seekTarget != null &&
                        SystemClock.elapsedRealtime() - pendingSeekStartedAtMs < 800L &&
                        kotlin.math.abs(position - seekTarget) > 1000L
                    if (seekIsStillPending) {
                        _currentTime.value = seekTarget!!
                    } else {
                        pendingSeekTargetMs = null
                        _currentTime.value = position
                    }
                }
                delay(100L)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    /** Live player position (falls back to the cached tick when the player is not ready). */
    fun currentPositionMs(): Long {
        val live = mediaPlayer?.time ?: -1L
        return if (live > 0L) live else _currentTime.value
    }

    fun seekBy(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val curTime = player.time
        val dur = if (player.length > 0) player.length else _totalDuration.value
        val safeDur = if (dur > 0) dur else Long.MAX_VALUE
        val target = (curTime + deltaMs).coerceIn(0L, safeDur)
        seekTo(target)
    }

    private var currentPlaybackSpeed: Float = 1.0f

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        currentPlaybackSpeed = clamped
        try {
            mediaPlayer?.rate = clamped
        } catch (e: Exception) {
        }
    }

    fun getSpeed(): Float {
        return currentPlaybackSpeed
    }

    fun setVolume(volumeLevel: Int) {
        // LibVLC native volume scale is 0 to 200 (200% native software boost)
        val appliedVolume = volumeLevel.coerceIn(0, 200)
        lastRequestedVolume = appliedVolume
        if (pendingPause || isPausedAtZeroVolume) return
        cancelVolumeRamp()
        try {
            mediaPlayer?.volume = appliedVolume
        } catch (_: Throwable) {
        }
    }

    fun getVolume(): Int {
        return mediaPlayer?.volume?.takeIf { it >= 0 } ?: lastRequestedVolume
    }

    fun setAspectRatio(ratio: String?) {
        mediaPlayer?.aspectRatio = ratio
    }

    // Audio & Subtitle Track Management
    fun getAudioTracks(): List<Pair<Int, String>> {
        val tracks = mediaPlayer?.audioTracks ?: return emptyList()
        return tracks.map { Pair(it.id, it.name ?: "Track ${it.id}") }
    }

    fun getCurrentAudioTrack(): Int {
        return mediaPlayer?.audioTrack ?: -1
    }

    fun setAudioTrack(trackId: Int) {
        mediaPlayer?.audioTrack = trackId
    }

    fun getSubtitleTracks(): List<Pair<Int, String>> {
        val tracks = mediaPlayer?.spuTracks ?: return emptyList()
        return tracks.map { Pair(it.id, it.name ?: "Track ${it.id}") }
    }

    fun getCurrentSubtitleTrack(): Int {
        return mediaPlayer?.spuTrack ?: -1
    }

    /**
     * Subtitles start off and stay off until the viewer picks a track.
     *
     * The per-media option alone is not enough: a sidecar file discovered while opening, or a
     * subtitle stream that arrives late, is selected by the demuxer after that option is applied.
     * Re-asserting the off state whenever playback starts or a track shows up keeps the default
     * clean, and a real choice (including an explicit Off) wins because it sets the user flag.
     */
    private fun enforceSubtitleOff() {
        if (hasUserSelectedSubtitle) return
        try {
            mediaPlayer?.spuTrack = -1
        } catch (e: Throwable) {
        }
    }

    fun setSubtitleTrack(trackId: Int) {
        hasUserSelectedSubtitle = true
        userSelectedSubtitleTrackId = trackId
        val player = mediaPlayer ?: return
        player.spuTrack = trackId
        if (trackId >= 0) {
            val curTime = player.time
            if (curTime > 0L) {
                // Micro-seek to current timestamp flushes demuxer buffer so LibVLC immediately
                // decodes and renders the active subtitle cue for this moment
                player.time = curTime
            }
        }
    }


    private var videoSizeProbeJob: Job? = null

    private fun probeLocalVideoSizeAsync(path: String, uri: Uri?) {
        videoSizeProbeJob?.cancel()
        videoSizeProbeJob = progressScope.launch(Dispatchers.IO) {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                if (path.isNotEmpty() && java.io.File(path).exists()) {
                    mmr.setDataSource(path)
                } else if (uri != null) {
                    mmr.setDataSource(context, uri)
                } else {
                    return@launch
                }
                val rawW = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val rawH = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rawW > 0 && rawH > 0) {
                    val (w, h) = if (rot == 90 || rot == 270) rawH to rawW else rawW to rawH
                    if (_videoSize.value.first <= 0 || _videoSize.value.second <= 0) {
                        _videoSize.value = Pair(w, h)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }
    }

    fun getVideoWidth(): Int {
        return try {
            val track = mediaPlayer?.currentVideoTrack
            if (track != null && track.width > 0) {
                val sarNum = track.sarNum
                val sarDen = track.sarDen
                if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
                    return (track.width * sarNum) / sarDen
                }
                return track.width
            }
            val media = mediaPlayer?.media
            if (media != null) {
                for (i in 0 until media.trackCount) {
                    val t = media.getTrack(i)
                    if (t is org.videolan.libvlc.interfaces.IMedia.VideoTrack && t.width > 0) {
                        val sarNum = t.sarNum
                        val sarDen = t.sarDen
                        if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
                            return (t.width * sarNum) / sarDen
                        }
                        return t.width
                    }
                }
            }
            0
        } catch (_: Throwable) { 0 }
    }

    fun getVideoHeight(): Int {
        return try {
            val track = mediaPlayer?.currentVideoTrack
            if (track != null && track.height > 0) return track.height
            val media = mediaPlayer?.media
            if (media != null) {
                for (i in 0 until media.trackCount) {
                    val t = media.getTrack(i)
                    if (t is org.videolan.libvlc.interfaces.IMedia.VideoTrack && t.height > 0) {
                        return t.height
                    }
                }
            }
            0
        } catch (_: Throwable) { 0 }
    }

    enum class ResizeMode(val label: String) {
        ORIGINAL("Original"),
        STRETCH("Stretch"),
        CROPPED("Cropped");

        companion object {
            fun fromString(value: String?): ResizeMode {
                return when (value?.uppercase()) {
                    "ORIGINAL", "FIT" -> ORIGINAL
                    "STRETCH" -> STRETCH
                    "CROPPED", "ZOOM" -> CROPPED
                    else -> ORIGINAL
                }
            }
        }
    }

    fun setResizeMode(mode: ResizeMode, displayWidth: Int = 0, displayHeight: Int = 0) {
        val sameMode = (currentResizeMode == mode)
        val sameWidth = (displayWidth <= 0 || currentDisplayWidth == displayWidth)
        val sameHeight = (displayHeight <= 0 || currentDisplayHeight == displayHeight)
        if (sameMode && sameWidth && sameHeight) {
            return
        }
        currentResizeMode = mode
        if (displayWidth > 0) currentDisplayWidth = displayWidth
        if (displayHeight > 0) currentDisplayHeight = displayHeight
        applyResizeMode()
    }

    fun applyResizeMode() {
        val player = mediaPlayer ?: return
        try {
            // The surface always renders the video fitted. Stretch, crop and the pinch zoom are
            // applied by the player as a transform of the picture view alone (see the video
            // activity), which is the only way to keep the cue out of it: the Android video output
            // silently ignores runtime zoom/fill changes (display.c returns VLC_SUCCESS for
            // VOUT_DISPLAY_CHANGE_ZOOM without applying anything), so asking LibVLC to crop does
            // nothing, while a texture-path resize would drag the cue along.
            player.aspectRatio = null
            player.scale = 0f
            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            attachedLayout?.post { attachedLayout?.requestLayout() }
        } catch (e: Throwable) {
        }
    }

    private fun detectDemuxer(path: String, uri: Uri?): String? {
        try {
            val header = ByteArray(12)
            var bytesRead = 0
            if (path.isNotEmpty()) {
                val f = java.io.File(path)
                if (f.exists() && f.length() >= 12) {
                    java.io.FileInputStream(f).use { bytesRead = it.read(header) }
                    val ext = f.extension.lowercase()
                    return detectDemuxerFromHeader(header, bytesRead, ext)
                }
            }
            if (uri != null) {
                context.contentResolver.openInputStream(uri)?.use { bytesRead = it.read(header) }
                val ext = path.substringAfterLast('.', "").ifEmpty { uri.lastPathSegment?.substringAfterLast('.', "") ?: "" }.lowercase()
                return detectDemuxerFromHeader(header, bytesRead, ext)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun detectDemuxerFromHeader(header: ByteArray, bytesRead: Int, ext: String): String? {
        if (bytesRead < 4) return null
        val lowerExt = ext.lowercase()

        // EBML (Matroska / WebM) -> e.g. Opus or Vorbis WebM disguised/misnamed as .mp3
        if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) {
            return if (lowerExt != "mkv" && lowerExt != "webm") "mkv" else null
        }
        // Ogg (Ogg Opus / Ogg Vorbis)
        if (header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() && header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()) {
            return if (lowerExt != "ogg" && lowerExt != "opus" && lowerExt != "oga") "ogg" else null
        }
        // MP4 / M4A (ftyp at offset 4)
        if (bytesRead >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) {
            return if (lowerExt != "mp4" && lowerExt != "m4a" && lowerExt != "m4v") "mp4" else null
        }
        // FLAC
        if (header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()) {
            return if (lowerExt != "flac") "flac" else null
        }
        // RIFF (WAV)
        if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) {
            return if (lowerExt != "wav") "wav" else null
        }
        return null
    }

    fun release() {
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        stopProgressUpdates()
        cancelVolumeRamp()
        pendingPause = false
        progressScope.coroutineContext[Job]?.cancelChildren()
        try { currentPfd?.close() } catch (_: Exception) {}
        currentPfd = null
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
        mediaPlayer?.release()
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
    }
}
