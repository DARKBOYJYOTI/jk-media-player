package com.jk.mediaplayer.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
    /** Bumped whenever playback state changes, so a stale pause fade cannot touch new media. */
    private var pauseGeneration: Long = 0L
    /** Music crossfades softly; video starts and stops instantly. */
    private val musicFadeInMs: Long = if (isVideoFlow) 0L else 300L
    private val musicFadeOutMs: Long = if (isVideoFlow) 0L else 220L
    private var targetAudioSessionId: Int = 0

    private var bufferingJob: kotlinx.coroutines.Job? = null
    private var playbackWatchdogJob: kotlinx.coroutines.Job? = null
    private var hasEverPlayed: Boolean = false
    private var playbackStartTimeMs: Long = 0L

    private val playerEventListener = MediaPlayer.EventListener { event ->
        // Buffering floods at every percent; log only milestones so the useful events stay readable.
        val noisyBuffering = event.type == MediaPlayer.Event.Buffering &&
            event.buffering > 0f && event.buffering < 100f && event.buffering % 10f != 0f
        if (!noisyBuffering) {
            Log.i(AUDIO_DIAG_TAG, "LibVLC event: type=${event.type}")
        }
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                Log.i(AUDIO_DIAG_TAG, "LibVLC Event Playing")
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
                if (mediaPlayer?.volume != lastRequestedVolume) {
                    // Do not snap the level while the start ramp is still running; a hard jump
                    // from a partly faded level is audible as a dip.
                    val current = mediaPlayer?.volume ?: lastRequestedVolume
                    if (kotlin.math.abs(current - lastRequestedVolume) > 20) {
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
                Log.i(AUDIO_DIAG_TAG, "LibVLC Paused volume=${mediaPlayer?.volume}")
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
                Log.i(AUDIO_DIAG_TAG, "LibVLC Stopped volume=${mediaPlayer?.volume}")
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
                    if (wasFirstFrame) {
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
                // Audio/Subtitle/Video track discovered
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
                Log.i(AUDIO_DIAG_TAG, "LibVLC EndReached hasEverPlayed=$hasEverPlayed currentPos=$currentPos totalLen=$totalLen volume=${mediaPlayer?.volume}")
                playbackWatchdogJob?.cancel()
                playbackWatchdogJob = null
                bufferingJob?.cancel()
                bufferingJob = null
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressUpdates()

                // If EndReached fired without ever playing or 0 duration, LibVLC failed to decode or open the file/stream
                if (!hasEverPlayed || (currentPos <= 1000L && totalLen <= 0L)) {
                    Log.e(AUDIO_DIAG_TAG, "LibVLC EndReached without valid playback -> reporting error")
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
                Log.e(AUDIO_DIAG_TAG, "LibVLC EncounteredError")
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
        val floorPx = (12f * metrics.density).toInt()
        val capPx = (screenShort * 0.12f).toInt()
        val marginPx = (videoHeightPx * 0.08f).toInt().coerceIn(floorPx, capPx)
        return "--sub-margin=$marginPx"
    }

    private fun initLibVLC() {
        targetAudioSessionId = customAudioSessionId ?: if (enableAudioSession) AudioSession.id(context) else 0
        Log.i(AUDIO_DIAG_TAG, "AudioSession id=$targetAudioSessionId (applied as global libVLC option)")
        // Every option in this list must exist in the bundled libvlc build: one unknown option
        // makes libvlc_new() return null and the whole list is thrown away. "sub-autodetect-caching"
        // is not part of VLC 3.6 and used to force the safe fallback on every launch.
        val primaryOptions = arrayListOf(
            "--aout=android_audiotrack", // High-fidelity native Android AudioTrack engine for bit-perfect audio effects
            "--avcodec-hw=any",        // Hardware acceleration with fallback to software
            "--file-caching=400",      // Optimal buffer eliminating choke & initial stall
            "--network-caching=1000",
            "--audio-time-stretch",    // Preserve audio pitch when changing speed
            "--sub-text-scale=100",    // Crisp high-resolution subtitles
            "--subsdec-encoding=UTF-8",// Universal UTF-8 subtitle decoding (prevents boxes/tofu on non-English scripts)
            subtitleMarginOption(),    // Density-aware bottom margin keeps cues clear of the seek bar
            "--sub-autodetect-fuzzy=3" // Automatic subtitle file detection (.srt, .vtt, .sub, .ass)
        )
        // Cached positions are not worth the crash: if the rich list is ever rejected by a future
        // bundled build, retry without the optional media options before giving up on them,
        // because audio-time-stretch and HW decoding must survive.
        val mediaOnlyOptions = arrayOf("--avcodec-hw=any", "--network-caching=1000", "--audio-time-stretch")
        val fallbackOptions = arrayListOf(
            "--aout=android_audiotrack",
            "--file-caching=400",
            "--subsdec-encoding=UTF-8",
            "--sub-margin=50"
        )
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
            Log.e(AUDIO_DIAG_TAG, "LibVLC init failed with every option set")
            reportError("Audio engine could not start on this device.")
            return
        }
        mediaPlayer = MediaPlayer(libVLC).apply {
            volume = lastRequestedVolume
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
            Log.w(AUDIO_DIAG_TAG, "LibVLC rejected optional option set (${options.size} opts): ${e.message}")
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
            Log.e(AUDIO_DIAG_TAG, "attachVideoLayout error: ${e.message}")
        }
    }

    fun detachVideoLayout() {
        try {
            val vout = mediaPlayer?.vlcVout
            if (vout != null && vout.areViewsAttached()) {
                vout.detachViews()
            } else {
                mediaPlayer?.detachViews()
            }
        } catch (e: Throwable) {
            Log.e(AUDIO_DIAG_TAG, "detachVideoLayout error: ${e.message}")
        }
    }

    fun reattachVideoLayout() {
        val layout = attachedLayout ?: return
        val vout = mediaPlayer?.vlcVout
        try {
            if (vout == null || !vout.areViewsAttached()) {
                mediaPlayer?.attachViews(layout, null, true, false)
            }
            mediaPlayer?.setVideoTrackEnabled(true)
            applyResizeMode()
        } catch (e: Throwable) {
            Log.e(AUDIO_DIAG_TAG, "reattachVideoLayout error: ${e.message}")
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
                Log.e(AUDIO_DIAG_TAG, "refreshPausedFrame error: ${e.message}")
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
        cancelVolumeRamp()
        stopProgressUpdates()
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        isSwitchingMedia = true
        hasEnded = false
        hasEverPlayed = false
        hasUserSelectedSubtitle = false
        userSelectedSubtitleTrackId = -1
        playbackStartTimeMs = System.currentTimeMillis()
        _isBuffering.value = true
        _encounteredError.value = null
        lastPlayedPath = path
        lastPlayedUri = uri
        _videoSize.value = Pair(0, 0)
        try {
            val prefs = PreferencesManager(context)
            val mediaKey = path.ifEmpty { uri?.toString() ?: "" }
            val savedModeStr = if (mediaKey.isNotEmpty()) prefs.getVideoResizeMode(mediaKey) ?: prefs.getDefaultResizeMode() else prefs.getDefaultResizeMode()
            currentResizeMode = ResizeMode.fromString(savedModeStr)
        } catch (_: Throwable) {}
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

            try { currentPfd?.close() } catch (_: Exception) {}
            currentPfd = null

            val forcedDemux = detectDemuxer(path, uri)
            val media = when {
                isOnlineStream -> {
                    val streamUri = uri ?: Uri.parse(path)
                    Media(vlc, streamUri)
                }
                path.isNotEmpty() && java.io.File(path).exists() -> {
                    val file = java.io.File(path)
                    if (forcedDemux != null) {
                        Log.i(AUDIO_DIAG_TAG, "Misnamed file detected for path=$path: forcing demux=$forcedDemux")
                        val pfd = try {
                            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        } catch (_: Exception) { null }
                        if (pfd != null) {
                            currentPfd = pfd
                            val m = Media(vlc, pfd.fileDescriptor)
                            m.addOption(":demux=$forcedDemux,any")
                            m
                        } else {
                            val m = Media(vlc, path)
                            m.addOption(":demux=$forcedDemux,any")
                            m
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
                        currentPfd = pfd
                        val m = Media(vlc, pfd.fileDescriptor)
                        if (forcedDemux != null) m.addOption(":demux=$forcedDemux,any")
                        m
                    } else {
                        if (uri.scheme == "content") {
                            reportError("Cannot open video. Storage access denied or file was deleted.")
                            return
                        }
                        val m = Media(vlc, uri)
                        if (forcedDemux != null) m.addOption(":demux=$forcedDemux,any")
                        m
                    }
                }
                else -> {
                    reportError("Cannot access or open media file.")
                    return
                }
            }
            if (forcedDemux != null) {
                media.addOption(":demux=$forcedDemux,any")
            }

            if (resumePositionMs > 1000L) {
                val startSec = resumePositionMs / 1000L
                media.addOption(":start-time=$startSec")
                pendingResumePositionMs = 0L
            } else {
                pendingResumePositionMs = resumePositionMs
            }
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
                val isAudioOnly = path.endsWith(".mp3", true) || path.endsWith(".flac", true) ||
                    path.endsWith(".wav", true) || path.endsWith(".ogg", true) ||
                    path.endsWith(".m4a", true) || path.endsWith(".aac", true) ||
                    path.endsWith(".opus", true) || path.endsWith(".wma", true) ||
                    path.endsWith(".alac", true) || path.endsWith(".dsd", true)
                if (isAudioOnly) {
                    media.addOption(":no-video")
                    // 150ms starved the AudioTrack on larger local files: output started, the
                    // cache emptied and the sound dipped before it recovered. Seek never dipped
                    // because the cache was already warm.
                    media.addOption(":file-caching=400")
                } else {
                    media.addOption(":file-caching=400")
                }
                media.addOption(":network-caching=1000")
            }
            // Subtitles are off unless this viewer already chose a track for this playback.
            if (userSelectedSubtitleTrackId >= 0) {
                media.addOption(":sub-track=$userSelectedSubtitleTrackId")
            } else {
                media.addOption(":sub-track=-1")
            }
            // Multichannel soundtracks (AC-3, E-AC-3, DTS, TrueHD) are decoded in software and
            // must be folded into the stereo track this device actually plays. Some 5.1 mixes
            // drop the centre channel without an explicit stereo target, so dialogue vanishes.
            // HDMI outputs keep their native layout, everything else is downmixed to stereo.
            if (!hasMultichannelOutput()) {
                media.addOption(":audio-channels=2")
            }
            // The audio session id is supplied as a global libVLC option (see initLibVLC):
            // per-media it is ignored by VLC's private audiotrack option, which left our
            // AudioEffects attached to a session with no audio flowing through it.
            val prefs = PreferencesManager(context)
            val hwMode = prefs.getVideoHwDecoder()
            val (hwEnabled, forceHW) = when (hwMode) {
                "FORCE_HW" -> Pair(true, true)
                "SOFTWARE" -> Pair(false, false)
                else -> Pair(true, true)
            }
            // Never force MediaCodec on audio-only files: vendor hardware MP3 decoders drop
            // frames on high-bitrate streams, which sounds like a stop/start in the middle of
            // the song. Software audio decoding is glitch-free and cheap.
            val isLocalAudioFile = !isOnlineStream && listOf(
                ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac", ".opus", ".wma", ".alac", ".dsd"
            ).any { path.endsWith(it, true) }
            media.setHWDecoderEnabled(hwEnabled, forceHW && !isLocalAudioFile)
            val deint = prefs.getVideoDeinterlaceMode()
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
            if (isVideoFlow) {
                // The display picture profile and the 32-bit colour option were stored by the video
                // settings screen but never reached the decoder. Media options are applied per file,
                // so a profile change is picked up by the next video (and never by music playback).
                when (prefs.getVideoEnhancerMode()) {
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
                if (prefs.isVideoHighColorQuality()) {
                    media.addOption(":android-display-chroma=RV32")
                }
            }
            mediaPlayer?.apply {
                setEventListener(null)
                this.media = media
                media.release()
                setEventListener(playerEventListener)
                volume = lastRequestedVolume
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
            e.printStackTrace()
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
        pendingPause = false
        pauseGeneration++
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
        // Music fades in only when a track truly starts. Resuming from a pause must not fade from
        // silence: toggling within a few seconds of the start re-created the AudioTrack mid-stream,
        // which was heard as a click on the speaker.
        val logical = lastRequestedVolume
        val freshStart = !hasEverPlayed && _currentTime.value < 500L
        val fadeIn = if (freshStart) musicFadeInMs else 0L
        try { player.volume = if (fadeIn > 0L) 0 else logical } catch (_: Throwable) {}
        player.play()
        if (attachedLayout != null) {
            applyResizeMode()
        }
        if (fadeIn > 0L) {
            volumeRampJob = progressScope.launch {
                rampVolumeInternal(0, logical, fadeIn)
            }
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying || pendingPause) return
        // A pause in the first moment of playback skips the fade: the AudioTrack is still settling
        // and a second volume ramp makes the output restart audibly.
        val sinceStart = SystemClock.elapsedRealtime() - playbackStartTimeMs
        if (sinceStart < 600L) {
            cancelVolumeRamp()
            pendingPause = false
            pauseGeneration++
            try { player.pause() } catch (_: Throwable) {}
            try { player.volume = lastRequestedVolume } catch (_: Throwable) {}
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
            // A new track started while this fade was running: leave its audio alone.
            if (!pendingPause || generation != pauseGeneration) return@launch
            try { player.pause() } catch (_: Throwable) {}
            // The stream is silent here, so restoring the logical level is inaudible and the
            // next resume starts from the user's value instead of 0. Read it after the fade so a
            // volume change made during the short fade window is not lost.
            try { player.volume = lastRequestedVolume } catch (_: Throwable) {}
            pendingPause = false
            if (attachedLayout != null) {
                applyResizeMode()
            }
        }
    }

    /** Fast temporary level change (audio focus ducking) that keeps the user's logical volume. */
    fun rampVolume(targetVolume: Int, durationMs: Long = 90L) {
        val player = mediaPlayer ?: return
        if (pendingPause) return
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
        val steps = (durationMs / 8L).coerceAtLeast(1L).toInt()
        for (i in 1..steps) {
            val level = from + ((to - from) * i) / steps
            try { mediaPlayer?.volume = level.coerceIn(0, 200) } catch (_: Throwable) {}
            delay(8L)
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
        if (!player.isPlaying || SystemClock.elapsedRealtime() - playbackStartTimeMs < 600L) {
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
            try { player.stop() } catch (_: Throwable) {}
            try { player.volume = lastRequestedVolume } catch (_: Throwable) {}
            pendingPause = false
            _isPlaying.value = false
        }
    }

    fun stop() {
        val player = mediaPlayer ?: return
        // Stopping invalidates any fade that is still in flight.
        pauseGeneration++
        pendingPause = false
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
            e.printStackTrace()
        }
    }

    fun getSpeed(): Float {
        return currentPlaybackSpeed
    }

    fun setVolume(volumeLevel: Int) {
        // LibVLC native volume scale is 0 to 200 (200% native software boost)
        val appliedVolume = volumeLevel.coerceIn(0, 200)
        lastRequestedVolume = appliedVolume
        if (!pendingPause) cancelVolumeRamp()
        try {
            mediaPlayer?.volume = appliedVolume
        } catch (e: Throwable) {
            Log.w(AUDIO_DIAG_TAG, "setVolume error: ${e.message}")
        }
        Log.i(AUDIO_DIAG_TAG, "setLibVlcVolume requested=$volumeLevel applied=$appliedVolume")
    }

    fun getVolume(): Int {
        return mediaPlayer?.volume?.takeIf { it >= 0 } ?: lastRequestedVolume
    }

    companion object {
        private const val AUDIO_DIAG_TAG = "JKAudioDiag"
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
            Log.e(AUDIO_DIAG_TAG, "enforceSubtitleOff error: ${e.message}")
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
            Log.i(AUDIO_DIAG_TAG, "applyResizeMode applied mode=$currentResizeMode")
        } catch (e: Throwable) {
            Log.e(AUDIO_DIAG_TAG, "applyResizeMode error: ${e.message}")
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
