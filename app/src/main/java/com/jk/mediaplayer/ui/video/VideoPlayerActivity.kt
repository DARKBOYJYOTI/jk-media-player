package com.jk.mediaplayer.ui.video

import android.annotation.SuppressLint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.MediaStore
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.bluetooth.BluetoothDevice
import com.jk.mediaplayer.service.VideoBackgroundService
import com.jk.mediaplayer.model.VideoItem
import com.jk.mediaplayer.R
import java.io.File
import com.jk.mediaplayer.ui.common.AppToast
import com.jk.mediaplayer.ui.common.GlobalAppToastHost
import android.util.Rational
import android.view.WindowManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*





import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jk.mediaplayer.data.PreferencesManager
import com.jk.mediaplayer.model.formatDuration
import com.jk.mediaplayer.player.LibVlcController
import com.jk.mediaplayer.ui.theme.JKMediaPlayerTheme
import com.jk.mediaplayer.ui.theme.TABULAR_FIGURES
import com.jk.mediaplayer.ui.theme.VideoIndigo
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

class VideoPlayerActivity : ComponentActivity() {
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    val isPlaying = libVlcController.isPlaying.value
                    if (isPlaying) {
                        libVlcController.pause()
                    } else {
                        requestAudioFocus()
                        libVlcController.play()
                    }
                    updatePipParams()
                }
                ACTION_PIP_PREV -> {
                    playPreviousVideo()
                    updatePipParams()
                }
                ACTION_PIP_NEXT -> {
                    playNextVideo()
                    updatePipParams()
                }
            }
        }
    }

    private fun buildPipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val actions = mutableListOf<RemoteAction>()

        // Previous Action
        val prevIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_PIP_PREV).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_notif_prev),
                "Previous",
                "Previous video",
                prevIntent
            )
        )

        // Play/Pause Action
        val isPlaying = libVlcController.isPlaying.value
        val playPauseIcon = if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, playPauseIcon),
                playPauseTitle,
                playPauseTitle,
                playPauseIntent
            )
        )

        // Next Action
        val nextIntent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(ACTION_PIP_NEXT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_notif_next),
                "Next",
                "Next video",
                nextIntent
            )
        )

        return actions
    }


    companion object {
        const val EXTRA_START_IN_PIP = "com.jk.mediaplayer.extra.START_IN_PIP"
        private const val ACTION_PIP_PLAY_PAUSE = "com.jk.mediaplayer.action.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_PREV = "com.jk.mediaplayer.action.PIP_PREV"
        private const val ACTION_PIP_NEXT = "com.jk.mediaplayer.action.PIP_NEXT"
        var playlist: List<com.jk.mediaplayer.model.VideoItem> = emptyList()
        var currentVideoIndex: Int = 0
        var activeInstance: VideoPlayerActivity? = null
        @SuppressLint("StaticFieldLeak")
        var activeController: LibVlcController? = null
        var isBackgroundAudioRunning: Boolean = false
    }

    private lateinit var libVlcController: LibVlcController
    private lateinit var prefsManager: PreferencesManager
    private lateinit var audioManager: AudioManager
    private var originalSystemVolume: Int = -1
    private var videoUriString by mutableStateOf("")
    private var videoPath by mutableStateOf("")
    private var videoTitle by mutableStateOf("")
    var hasInitiatedPlayback: Boolean = false

    private var activeIndex by mutableIntStateOf(0)
    private var currentTitle by mutableStateOf("")

    var currentAudioRoute: PreferencesManager.AudioOutputRoute = PreferencesManager.AudioOutputRoute.SPEAKER
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var videoSessionId: Int = 0

    private val audioRouteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::libVlcController.isInitialized) return
            when (intent?.action) {
                Intent.ACTION_HEADSET_PLUG,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    handleAudioRouteChange(detectCurrentAudioRoute())
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    libVlcController.mediaPlayer?.pause()
                    handleAudioRouteChange(PreferencesManager.AudioOutputRoute.SPEAKER)
                }
            }
        }
    }

    fun detectCurrentAudioRoute(): PreferencesManager.AudioOutputRoute {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBt = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (hasBt) return PreferencesManager.AudioOutputRoute.BLUETOOTH
        val hasWired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        if (hasWired) return PreferencesManager.AudioOutputRoute.HEADPHONES
        return PreferencesManager.AudioOutputRoute.SPEAKER
    }


    fun getCurrentLogicalVolume(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val stream = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val boost = if (::libVlcController.isInitialized) libVlcController.getVolume() else 100
        val boostAllowed = prefsManager.isVolumeBoostEnabled()
        // Past the top of the device scale the only thing left is the software boost band, so the
        // readout follows the engine there and the stream everywhere else.
        return if (boostAllowed && stream >= max && boost > 100) {
            boost.coerceIn(101, 200)
        } else {
            Math.round(stream * 100f / max)
        }
    }

    fun applyLogicalVolume(
        volume: Int,
        route: PreferencesManager.AudioOutputRoute = currentAudioRoute,
        userInitiated: Boolean = false
    ) {
        val boostAllowed = prefsManager.isVolumeBoostEnabled()
        val effectiveVol = if (boostAllowed) volume.coerceIn(0, 200) else volume.coerceIn(0, 100)
        val maxStream = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        // 0–100% is the device media volume itself, which is the scale the hardware keys move and
        // the only place a real level change happens. Above that the stream sits at its top step
        // and the rest of the band is the software boost.
        val streamTarget = if (effectiveVol >= 100) {
            maxStream
        } else {
            Math.round(effectiveVol / 100f * maxStream).coerceIn(0, maxStream)
        }
        try {
            if (userInitiated && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != streamTarget) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamTarget, 0)
                // The viewer moved the device volume on purpose, so the value kept for the exit
                // snapshot must not put it back.
                originalSystemVolume = -1
            }
        } catch (_: Exception) {
        }
        val engineVol = if (boostAllowed && effectiveVol > 100) effectiveVol else 100
        if (::libVlcController.isInitialized) {
            libVlcController.setVolume(engineVol)
        }
        prefsManager.saveVideoVolumeForRoute(route, effectiveVol)
    }

    fun handleAudioRouteChange(newRoute: PreferencesManager.AudioOutputRoute) {
        if (!::libVlcController.isInitialized) return
        if (newRoute == currentAudioRoute) return
        val oldVol = getCurrentLogicalVolume()
        prefsManager.saveVideoVolumeForRoute(currentAudioRoute, oldVol)
        currentAudioRoute = newRoute
        val savedTarget = prefsManager.getVideoVolumeForRoute(newRoute)
        applyLogicalVolume(savedTarget, newRoute)
    }

    private var foregroundMediaSession: MediaSessionCompat? = null

    private fun initForegroundMediaSession() {
        foregroundMediaSession = MediaSessionCompat(this, "JKVideoForegroundSession").apply {
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    requestAudioFocus()
                    libVlcController.play()
                    updateForegroundPlaybackState(true)
                }

                override fun onPause() {
                    libVlcController.pause()
                    updateForegroundPlaybackState(false)
                }

                override fun onSkipToNext() {
                    playNextVideo()
                }

                override fun onSkipToPrevious() {
                    playPreviousVideo()
                }

                override fun onSeekTo(pos: Long) {
                    libVlcController.seekTo(pos)
                    updateForegroundPlaybackState(libVlcController.isPlaying.value, pos)
                }
            })
        }
    }

    private fun updateForegroundPlaybackState(isPlaying: Boolean, customPos: Long = -1L) {
        val session = foregroundMediaSession ?: return
        if (!session.isActive) return
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pos = if (customPos >= 0L) customPos else libVlcController.currentTime.value
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, pos, if (isPlaying) 1.0f else 0.0f, android.os.SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun updateForegroundMetadata() {
        val session = foregroundMediaSession ?: return
        if (!session.isActive) return
        val duration = libVlcController.totalDuration.value
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "JK Video Player")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Video Playback")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (duration > 0L) duration else -1L)
                .build()
        )
    }

    fun startBackgroundAudioPlayback() {
        isBackgroundAudioRunning = true
        activeInstance = this
        activeController = libVlcController
        foregroundMediaSession?.isActive = false
        libVlcController.detachVideoLayout()
        VideoBackgroundService.start(this, currentTitle, videoPath, videoUriString)
        moveTaskToBack(true)
    }

    private var resumeOnFocusGain = false
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isBackgroundAudioRunning) return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                libVlcController.mediaPlayer?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = libVlcController.isPlaying.value
                resumeOnFocusGain = wasPlaying
                libVlcController.mediaPlayer?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                libVlcController.setVolume(40)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                libVlcController.setVolume(100)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    libVlcController.mediaPlayer?.play()
                }
            }
        }
    }

    var isInPipMode by mutableStateOf(false)
    private var resumeTrigger by mutableIntStateOf(0)

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val track = libVlcController.mediaPlayer?.currentVideoTrack
                val w = track?.width?.takeIf { it > 0 } ?: 16
                val h = track?.height?.takeIf { it > 0 } ?: 9
                val aspect = w.toFloat() / h.toFloat()
                val rational = if (aspect in 0.42f..2.38f) Rational(w, h) else Rational(16, 9)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(true)
                }
                enterPictureInPictureMode(builder.build())
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    enterPictureInPictureMode()
                } catch (_: Exception) {}
            }
        }
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val track = libVlcController.mediaPlayer?.currentVideoTrack
                val w = track?.width?.takeIf { it > 0 } ?: 16
                val h = track?.height?.takeIf { it > 0 } ?: 9
                val aspect = w.toFloat() / h.toFloat()
                val rational = if (aspect in 0.42f..2.38f) Rational(w, h) else Rational(16, 9)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val autoPip = if (::prefsManager.isInitialized) prefsManager.isAutoPipEnabled() else false
                    builder.setAutoEnterEnabled(autoPip)
                }
                setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val autoPip = if (::prefsManager.isInitialized) prefsManager.isAutoPipEnabled() else false
        if (autoPip && libVlcController.isPlaying.value && !isFinishing) {
            enterPipMode()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            if (isFinishing || isDestroyed) {
                libVlcController.pause()
                abandonAudioFocus()
            } else {
                hideSystemUI()
                libVlcController.reattachVideoLayout()
                // Wait for the OEM PiP-to-fullscreen window resize animation to settle, then
                // force-sync LibVLC's SurfaceView bounds so the video never stays stuck at PiP size.
                window.decorView.post {
                    if (!isFinishing && !isDestroyed && !isInPipMode) {
                        hideSystemUI()
                        libVlcController.forceReattachVideoLayout()
                        libVlcController.refreshPausedFrame()
                    }
                }
                window.decorView.postDelayed({
                    if (!isFinishing && !isDestroyed && !isInPipMode) {
                        hideSystemUI()
                        libVlcController.forceReattachVideoLayout()
                        libVlcController.refreshPausedFrame()
                    }
                }, 260L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        val pipFilter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_PREV)
            addAction(ACTION_PIP_NEXT)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            pipReceiver,
            pipFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val amInit = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalSystemVolume = amInit.getStreamVolume(AudioManager.STREAM_MUSIC)
        hideSystemUI()
        initForegroundMediaSession()

        activeIndex = currentVideoIndex
        videoUriString = intent.getStringExtra("EXTRA_VIDEO_URI") ?: ""
        videoPath = intent.getStringExtra("EXTRA_VIDEO_PATH") ?: ""
        videoTitle = intent.getStringExtra("EXTRA_VIDEO_TITLE") ?: "Video Player"

        if (videoUriString.isEmpty() && videoPath.isEmpty()) {
            handleIntentData(intent, isNewIntent = false)
        } else {
            val netCheck = when {
                videoPath.startsWith("http://", true) || videoPath.startsWith("https://", true) -> videoPath
                videoUriString.startsWith("http://", true) || videoUriString.startsWith("https://", true) -> videoUriString
                else -> null
            }
            val fromBrowser = intent.getBooleanExtra("EXTRA_FROM_BROWSER", false)
            if (netCheck != null && !fromBrowser) {
                inspectNetworkUrl(netCheck)
            }
            if (playlist.isEmpty()) {
                val itemUri = if (videoUriString.isNotEmpty()) Uri.parse(videoUriString) else Uri.parse(videoPath)
                playlist = listOf(
                    com.jk.mediaplayer.model.VideoItem(
                        id = kotlin.math.abs((videoPath.ifEmpty { videoUriString }).hashCode().toLong()),
                        title = videoTitle,
                        path = videoPath,
                        uri = itemUri,
                        duration = 0L,
                        size = 0L,
                        dateModified = System.currentTimeMillis(),
                        folderName = "Network Stream"
                    )
                )
                activeIndex = 0
                currentVideoIndex = 0
            }
        }

        currentTitle = videoTitle
        val hasExplicitPortrait = intent.hasExtra("EXTRA_IS_PORTRAIT")
        val asyncUri = if (videoUriString.isNotEmpty()) Uri.parse(videoUriString) else null
        val asyncPath = videoPath

        val isPort = if (hasExplicitPortrait) {
            intent.getBooleanExtra("EXTRA_IS_PORTRAIT", false)
        } else {
            // External intent (Gallery / MiXplorer / File Manager / Downloads): synchronous MediaStore check with fallback
            fastCheckPortrait(asyncPath, asyncUri) ?: detectVideoIsPortrait(asyncPath, asyncUri)
        }
        if (playlist.isNotEmpty() && activeIndex in playlist.indices) {
            val cur = playlist[activeIndex]
            if (cur.isPortrait != isPort) {
                val updatedList = playlist.toMutableList()
                updatedList[activeIndex] = cur.copy(isPortrait = isPort)
                playlist = updatedList
            }
        }
        requestedOrientation = if (isPort) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        prefsManager = PreferencesManager(this)
        val savedB = prefsManager.getVideoBrightness()
        if (savedB >= 0f) {
            val lp = window.attributes
            lp.screenBrightness = savedB
            window.attributes = lp
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        videoSessionId = audioManager.generateAudioSessionId()
        currentAudioRoute = detectCurrentAudioRoute()
        val initialVol = prefsManager.getVideoVolumeForRoute(currentAudioRoute)

        libVlcController = LibVlcController(
            this,
            enableAudioSession = false,
            customAudioSessionId = videoSessionId,
            isVideoFlow = true
        )
        activeInstance = this
        activeController = libVlcController
        lifecycleScope.launch {
            libVlcController.isPlaying.collect { playing ->
                updateForegroundPlaybackState(playing)
            }
        }
        libVlcController.onEndReached = {
            runOnUiThread {
                if (playlist.isNotEmpty() && activeIndex < playlist.size - 1) {
                    playVideoIndex(activeIndex + 1)
                } else {
                    libVlcController.seekTo(0L)
                }
            }
        }
        applyLogicalVolume(initialVol, currentAudioRoute)

        val routeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioRouteReceiver, routeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(audioRouteReceiver, routeFilter)
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                handleAudioRouteChange(detectCurrentAudioRoute())
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                handleAudioRouteChange(detectCurrentAudioRoute())
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        audioDeviceCallback = callback
        if (videoPath.isNotEmpty()) {
            prefsManager.markVideoWatched(videoPath)
            prefsManager.saveLastPlayedVideoPath(videoPath)
        }
        if (videoUriString.isNotEmpty()) {
            prefsManager.markVideoWatched(videoUriString)
        }

        requestAudioFocus()

        val resumePos = prefsManager.getVideoResumePosition(videoPath)

        val streamUser = intent.getStringExtra("EXTRA_STREAM_USER")
        val streamPass = intent.getStringExtra("EXTRA_STREAM_PASS")

        setContent {
            JKMediaPlayerTheme(isDark = true, accentColor = VideoIndigo) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val currentVideoItem = playlist.getOrNull(activeIndex) ?: VideoItem(
                        id = kotlin.math.abs(videoPath.hashCode().toLong()),
                        title = currentTitle,
                        path = videoPath,
                        uri = Uri.parse(videoUriString),
                        duration = 0L,
                        size = if (videoPath.isNotEmpty()) File(videoPath).length() else 0L,
                        dateModified = if (videoPath.isNotEmpty()) File(videoPath).lastModified() else System.currentTimeMillis(),
                        folderName = ""
                    )

                    GestureVideoPlayerScreen(
                        title = currentTitle,
                        videoPath = videoPath,
                        videoUriString = videoUriString,
                        resumePos = resumePos,
                        streamUser = streamUser,
                        streamPass = streamPass,
                        controller = libVlcController,
                        currentVideo = currentVideoItem,
                        hasNext = playlist.isNotEmpty() && activeIndex < playlist.size - 1,
                        hasPrevious = playlist.isNotEmpty() && activeIndex > 0,
                        isInPip = isInPipMode,
                        resumeTrigger = resumeTrigger,
                        onPlayNextVideo = { playNextVideo() },
                        onPlayPreviousVideo = { playPreviousVideo() },
                        onBack = { finish() }
                    )
                    GlobalAppToastHost(bottomPadding = 48.dp)
                }
            }
        }
    }

    fun playNextVideo() {
        if (playlist.isNotEmpty() && activeIndex < playlist.size - 1) {
            playVideoIndex(activeIndex + 1)
        }
    }

    fun playPreviousVideo() {
        if (libVlcController.currentTime.value > 3000L) {
            libVlcController.seekTo(0L)
            return
        }
        if (playlist.isNotEmpty() && activeIndex > 0) {
            playVideoIndex(activeIndex - 1)
        }
    }

    private fun playVideoIndex(index: Int) {
        if (index !in playlist.indices) return
        hasInitiatedPlayback = true
        val prevPath = videoPath
        val current = libVlcController.currentTime.value
        if (current > 0 && prevPath.isNotEmpty()) {
            prefsManager.saveVideoResumePosition(prevPath, current)
        }
        activeIndex = index
        currentVideoIndex = index
        val item = playlist[index]
        videoPath = item.path
        videoUriString = item.uri.toString()
        videoTitle = item.title
        currentTitle = item.title
        val isPortrait = item.isPortrait
        requestedOrientation = if (isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (item.path.isNotEmpty()) {
            prefsManager.markVideoWatched(item.path)
            prefsManager.saveLastPlayedVideoPath(item.path)
        }
        if (!item.isPortrait) {
            autoOrientVideo(item.path, item.uri.toString())
        }
        val resume = prefsManager.getVideoResumePosition(item.path)
        libVlcController.playMedia(item.path, item.uri, resume)
    }

    private fun fastCheckPortrait(path: String, uri: Uri?): Boolean? {
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("rtsp://") || path.startsWith("rtmp://") || path.startsWith("mms://") || path.startsWith("ftp://")) return false
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "rtsp" || uri.scheme == "rtmp")) return false
        
        // 1. Fast ContentResolver query by URI (1ms)
        if (uri != null && uri.scheme == "content") {
            try {
                val proj = arrayOf(
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.ORIENTATION else MediaStore.Video.Media._ID
                )
                contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val w = cursor.getInt(0)
                        val h = cursor.getInt(1)
                        val orientCol = cursor.getColumnIndex("orientation")
                        val orient = if (orientCol >= 0) cursor.getInt(orientCol) else 0
                        val isRot = (orient == 90 || orient == 270)
                        val effW = if (isRot) h else w
                        val effH = if (isRot) w else h
                        if (effW > 0 && effH > 0) return effH > effW
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fast MediaStore query by file path (1ms)
        if (path.isNotEmpty() && java.io.File(path).exists()) {
            try {
                val proj = arrayOf(
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.ORIENTATION else MediaStore.Video.Media._ID
                )
                val sel = "${MediaStore.Video.Media.DATA} = ?"
                contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(path), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val w = cursor.getInt(0)
                        val h = cursor.getInt(1)
                        val orientCol = cursor.getColumnIndex("orientation")
                        val orient = if (orientCol >= 0) cursor.getInt(orientCol) else 0
                        val isRot = (orient == 90 || orient == 270)
                        val effW = if (isRot) h else w
                        val effH = if (isRot) w else h
                        if (effW > 0 && effH > 0) return effH > effW
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun detectVideoIsPortrait(path: String, uri: Uri?): Boolean {
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("rtsp://") || path.startsWith("rtmp://") || path.startsWith("mms://") || path.startsWith("ftp://")) return false
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "rtsp" || uri.scheme == "rtmp")) return false
        if (uri != null && uri.scheme == "content") {
            try {
                val proj = arrayOf(
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.ORIENTATION else MediaStore.Video.Media._ID
                )
                contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val w = cursor.getInt(0)
                        val h = cursor.getInt(1)
                        val orientCol = cursor.getColumnIndex("orientation")
                        val orient = if (orientCol >= 0) cursor.getInt(orientCol) else 0
                        val isRot = (orient == 90 || orient == 270)
                        val effW = if (isRot) h else w
                        val effH = if (isRot) w else h
                        if (effW > 0 && effH > effW) return true
                    }
                }
            } catch (_: Exception) {}
        }
        if (path.isNotEmpty() && java.io.File(path).exists()) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val r = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val isRot = (r == 90 || r == 270)
                val effW = if (isRot) h else w
                val effH = if (isRot) w else h
                if (effW > 0 && effH > effW) return true
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } else if (uri != null) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                var loaded = false
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        loaded = true
                    }
                } catch (_: Exception) {}
                if (!loaded) {
                    retriever.setDataSource(this, uri)
                }
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val r = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val isRot = (r == 90 || r == 270)
                val effW = if (isRot) h else w
                val effH = if (isRot) w else h
                if (effW > 0 && effH > 0) return effH > effW
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun autoOrientVideo(path: String, uriString: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (path.isNotEmpty() && java.io.File(path).exists()) {
                    retriever.setDataSource(path)
                } else if (uriString.isNotEmpty()) {
                    val u = Uri.parse(uriString)
                    var loaded = false
                    try {
                        contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            loaded = true
                        }
                    } catch (_: Exception) {}
                    if (!loaded) {
                        retriever.setDataSource(this@VideoPlayerActivity, u)
                    }
                } else {
                    return@launch
                }
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val r = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val isRotated = (r == 90 || r == 270)
                val effW = if (isRotated) h else w
                val effH = if (isRotated) w else h
                if (effH > effW && effW > 0) {
                    runOnUiThread {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val focusRequest = audioFocusRequest ?: android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    var isControlsShowing: Boolean = true

    @Suppress("DEPRECATION")
    fun updateSystemBars(showStatus: Boolean) {
        isControlsShowing = showStatus
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        if (showStatus) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        if (isControlsShowing) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = lp
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private var wasPlayingBeforePause = true
    private var savedPositionBeforePause: Long = -1L

    override fun onStart() {
        super.onStart()
        libVlcController.reattachVideoLayout()
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        activeController = libVlcController
        hideSystemUI()
        if (isBackgroundAudioRunning) {
            isBackgroundAudioRunning = false
            VideoBackgroundService.stop(this)
        }
        foregroundMediaSession?.isActive = true
        updateForegroundPlaybackState(libVlcController.isPlaying.value)
        updateForegroundMetadata()
        libVlcController.reattachVideoLayout()
        val dm = resources.displayMetrics
        val savedB = prefsManager.getVideoBrightness()
        if (savedB >= 0f) {
            val lp = window.attributes
            lp.screenBrightness = savedB
            window.attributes = lp
        }
        val currentKey = videoPath.ifEmpty { videoUriString }
        val savedModeStr = prefsManager.getVideoResizeMode(currentKey) ?: prefsManager.getDefaultResizeMode()
        val savedMode = LibVlcController.ResizeMode.fromString(savedModeStr)
        libVlcController.setResizeMode(savedMode)
        resumeTrigger++

        if (wasPlayingBeforePause) {
            requestAudioFocus()
            libVlcController.play()
        } else {
            libVlcController.refreshPausedFrame()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing && !isBackgroundAudioRunning) {
            val current = libVlcController.currentTime.value
            val saveKey = videoPath.ifEmpty { videoUriString }
            if (current > 0 && saveKey.isNotEmpty()) {
                prefsManager.saveVideoResumePosition(saveKey, current)
            }
            libVlcController.mediaPlayer?.pause()
            abandonAudioFocus()
            return
        }
        if (isInPictureInPictureMode) {
            return
        }
        val current = libVlcController.currentTime.value
        val saveKey = videoPath.ifEmpty { videoUriString }
        if (current > 0 && saveKey.isNotEmpty()) {
            prefsManager.saveVideoResumePosition(saveKey, current)
        }
        if (current > 0L) {
            savedPositionBeforePause = current
        }
        wasPlayingBeforePause = libVlcController.isPlaying.value
        if (!isBackgroundAudioRunning) {
            foregroundMediaSession?.isActive = false
            libVlcController.mediaPlayer?.pause()
            abandonAudioFocus()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBackgroundAudioRunning) {
            return
        }
        val wasInPip = isInPipMode
        isInPipMode = false
        libVlcController.pause()
        abandonAudioFocus()
        if (wasInPip) {
            finish()
        }
    }

    private fun extractNetworkUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val schemes = listOf("http://", "https://", "rtsp://", "rtmp://", "mms://", "ftp://", "udp://")
        for (scheme in schemes) {
            val idx = trimmed.indexOf(scheme, ignoreCase = true)
            if (idx >= 0) {
                val candidate = trimmed.substring(idx)
                val endIdx = candidate.indexOfAny(charArrayOf(' ', '\n', '\r', '\t'))
                val rawUrl = if (endIdx > 0) candidate.substring(0, endIdx) else candidate
                return rawUrl.trim().trimEnd('.', ',', ';', ')', ']', '}')
            }
        }
        return null
    }

    private fun inspectNetworkUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return
        val pathLower = try { Uri.parse(trimmed).path?.lowercase() ?: "" } catch (_: Exception) { "" }
        val isDirectMediaExt = listOf(
            ".mp4", ".mkv", ".m3u8", ".webm", ".avi", ".mov", ".flv", ".ts", ".wmv", ".m4v", ".3gp", ".mpd"
        ).any { pathLower.endsWith(it) }
        if (isDirectMediaExt) return // Direct media stream; no need to intercept with HTML check

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = java.net.URL(trimmed).openConnection() as? java.net.HttpURLConnection ?: return@launch
                conn.instanceFollowRedirects = true
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                )
                val code = conn.responseCode
                var contentType = conn.contentType ?: ""
                conn.disconnect()

                if (code == 405 || contentType.isBlank()) {
                    val getConn = java.net.URL(trimmed).openConnection() as? java.net.HttpURLConnection ?: return@launch
                    getConn.instanceFollowRedirects = true
                    getConn.setRequestProperty("Range", "bytes=0-1")
                    getConn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    )
                    getConn.connectTimeout = 3000
                    getConn.readTimeout = 3000
                    contentType = getConn.contentType ?: ""
                    getConn.disconnect()
                }

                if (contentType.contains("text/html", ignoreCase = true) || contentType.contains("application/xhtml", ignoreCase = true)) {
                    withContext(Dispatchers.Main) {
                        libVlcController.stop()
                        libVlcController.reportError("This link returned a webpage, not a direct video stream.")
                    }
                }
            } catch (_: Exception) {
                // If inspection network call fails, let LibVLC attempt playback normally
            }
        }
    }

    private fun handleIntentData(intent: Intent, isNewIntent: Boolean = false) {
        val sharedText = if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("android.intent.extra.TEXT")
        } else null

        val incomingUri: Uri? = intent.data ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        val extractedNetUrl = extractNetworkUrl(sharedText)
        if (extractedNetUrl != null) {
            val netUrl = extractedNetUrl
            inspectNetworkUrl(netUrl)
            videoUriString = netUrl
            videoPath = netUrl
            videoTitle = Uri.parse(netUrl).lastPathSegment ?: Uri.parse(netUrl).host ?: "Live Stream"
            currentTitle = videoTitle
            playlist = listOf(
                com.jk.mediaplayer.model.VideoItem(
                    id = System.currentTimeMillis(),
                    title = videoTitle,
                    path = netUrl,
                    uri = Uri.parse(netUrl),
                    duration = 0L,
                    size = 0L,
                    dateModified = System.currentTimeMillis(),
                    folderName = "Network Stream"
                )
            )
            activeIndex = 0
            currentVideoIndex = 0
            if (isNewIntent) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                val streamUser = intent.getStringExtra("EXTRA_STREAM_USER")
                val streamPass = intent.getStringExtra("EXTRA_STREAM_PASS")
                hasInitiatedPlayback = true
                libVlcController.playMedia(netUrl, Uri.parse(netUrl), 0L, streamUser, streamPass)
            }
        } else if (intent.action == Intent.ACTION_SEND && incomingUri == null) {
            videoUriString = ""
            videoPath = ""
            playlist = emptyList()
            if (isNewIntent) {
                libVlcController.reportError("Invalid video link or unsupported shared content.")
            }
        } else if (incomingUri != null) {
            videoUriString = incomingUri.toString()
            val uriScheme = incomingUri.scheme?.lowercase() ?: ""
            if (uriScheme in listOf("http", "https", "rtsp", "rtmp", "mms", "ftp", "udp")) {
                videoPath = incomingUri.toString()
                if (uriScheme == "http" || uriScheme == "https") {
                    inspectNetworkUrl(videoPath)
                }
                videoTitle = incomingUri.lastPathSegment ?: incomingUri.host ?: "Live Stream"
                currentTitle = videoTitle
                playlist = listOf(
                    com.jk.mediaplayer.model.VideoItem(
                        id = System.currentTimeMillis(),
                        title = videoTitle,
                        path = videoPath,
                        uri = incomingUri,
                        duration = 0L,
                        size = 0L,
                        dateModified = System.currentTimeMillis(),
                        folderName = "Network Stream"
                    )
                )
                activeIndex = 0
                currentVideoIndex = 0
                if (isNewIntent) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    val streamUser = intent.getStringExtra("EXTRA_STREAM_USER")
                    val streamPass = intent.getStringExtra("EXTRA_STREAM_PASS")
                    hasInitiatedPlayback = true
                    libVlcController.playMedia(videoPath, incomingUri, 0L, streamUser, streamPass)
                }
            } else {
                // Local Content or File URI from File Manager / Gallery
                videoPath = if (uriScheme == "file") incomingUri.path ?: "" else ""
                var resolvedName: String? = null
                try {
                    if (uriScheme == "content") {
                        val proj = arrayOf(
                            android.provider.OpenableColumns.DISPLAY_NAME,
                            android.provider.MediaStore.Video.Media.DATA
                        )
                        contentResolver.query(incomingUri, proj, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameCol >= 0) resolvedName = cursor.getString(nameCol)
                                val dataCol = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                                if (dataCol >= 0) {
                                    val d = cursor.getString(dataCol)
                                    if (!d.isNullOrEmpty() && java.io.File(d).exists()) {
                                        videoPath = d
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                val resolvedTitle = when {
                    !resolvedName.isNullOrBlank() -> resolvedName
                    videoPath.isNotBlank() && java.io.File(videoPath).exists() -> java.io.File(videoPath).name
                    !incomingUri.lastPathSegment.isNullOrBlank() -> incomingUri.lastPathSegment
                    else -> "Video Player"
                }
                videoTitle = resolvedTitle ?: "Video Player"
                currentTitle = videoTitle

                playlist = listOf(
                    com.jk.mediaplayer.model.VideoItem(
                        id = System.currentTimeMillis(),
                        title = videoTitle,
                        path = videoPath,
                        uri = incomingUri,
                        duration = 0L,
                        size = 0L,
                        dateModified = System.currentTimeMillis(),
                        folderName = "External"
                    )
                )
                activeIndex = 0
                currentVideoIndex = 0

                // Auto-populate folder siblings in background
                if (videoPath.isNotEmpty()) {
                    val f = java.io.File(videoPath)
                    val parent = f.parentFile
                    if (parent != null && parent.exists() && parent.isDirectory) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val videoExts = setOf(
                                "mp4", "mkv", "avi", "mov", "flv", "webm", "wmv", "3gp", "ts", "m4v",
                                "mpeg", "mpg", "vob", "ogv", "m2ts", "mts", "divx", "rmvb", "asf", "3g2", "m2v", "f4v"
                            )
                            val siblings = parent.listFiles { sf ->
                                sf.isFile && videoExts.contains(sf.extension.lowercase())
                            }?.sortedBy { it.name.lowercase() } ?: emptyList()
                            if (siblings.isNotEmpty()) {
                                val siblingItems = siblings.map { sib ->
                                    com.jk.mediaplayer.model.VideoItem(
                                        id = kotlin.math.abs(sib.absolutePath.hashCode().toLong()),
                                        title = sib.nameWithoutExtension,
                                        path = sib.absolutePath,
                                        uri = Uri.fromFile(sib),
                                        duration = 0L,
                                        size = sib.length(),
                                        dateModified = sib.lastModified(),
                                        folderName = parent.name
                                    )
                                }
                                val idx = siblingItems.indexOfFirst { it.path == videoPath }
                                runOnUiThread {
                                    playlist = siblingItems
                                    activeIndex = if (idx >= 0) idx else 0
                                    currentVideoIndex = activeIndex
                                }
                            }
                        }
                    }
                }

                if (isNewIntent) {
                    val isPort = detectVideoIsPortrait(videoPath, incomingUri)
                    requestedOrientation = if (isPort) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    val newKey = videoPath.ifEmpty { incomingUri.toString() }
                    val savedModeStr = prefsManager.getVideoResizeMode(newKey) ?: prefsManager.getDefaultResizeMode()
                    val savedMode = LibVlcController.ResizeMode.fromString(savedModeStr)
                    libVlcController.setResizeMode(savedMode)
                    hasInitiatedPlayback = true
                    libVlcController.playMedia(videoPath, incomingUri, 0L)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_FROM_BACKGROUND_NOTIFICATION", false) || isBackgroundAudioRunning) {
            return
        }
        val extraUri = intent.getStringExtra("EXTRA_VIDEO_URI") ?: ""
        val extraPath = intent.getStringExtra("EXTRA_VIDEO_PATH") ?: ""
        val extraTitle = intent.getStringExtra("EXTRA_VIDEO_TITLE") ?: "Video Player"
        if (extraUri.isNotEmpty() || extraPath.isNotEmpty()) {
            videoUriString = extraUri
            videoPath = extraPath
            videoTitle = extraTitle
            currentTitle = videoTitle
            val itemUri = if (videoUriString.isNotEmpty()) Uri.parse(videoUriString) else Uri.parse(videoPath)
            playlist = listOf(
                com.jk.mediaplayer.model.VideoItem(
                    id = kotlin.math.abs((videoPath.ifEmpty { videoUriString }).hashCode().toLong()),
                    title = videoTitle,
                    path = videoPath,
                    uri = itemUri,
                    duration = 0L,
                    size = 0L,
                    dateModified = System.currentTimeMillis(),
                    folderName = "Network Stream"
                )
            )
            activeIndex = 0
            currentVideoIndex = 0
            val fromBrowser = intent.getBooleanExtra("EXTRA_FROM_BROWSER", false)
            val isNet = videoPath.startsWith("http://", true) || videoPath.startsWith("https://", true)
            if (isNet && !fromBrowser) inspectNetworkUrl(videoPath)
            hasInitiatedPlayback = true
            libVlcController.playMedia(videoPath, itemUri, 0L)
        } else {
            handleIntentData(intent, isNewIntent = true)
        }
    }

    /**
     * Reloads the running media at its current position so per-media decoder/filter options
     * (picture profile, colour depth, deinterlace, HW decoder) take effect without waiting for
     * the next video. The pause state is preserved.
     */
    fun reloadCurrentVideo() {
        if (!hasInitiatedPlayback) return
        if (videoPath.isEmpty() && videoUriString.isEmpty()) return
        val resumeAt = libVlcController.currentTime.value
        val wasPlaying = libVlcController.isPlaying.value
        val uri = if (videoUriString.isNotEmpty()) Uri.parse(videoUriString) else null
        val user = intent.getStringExtra("EXTRA_STREAM_USER")
        val pass = intent.getStringExtra("EXTRA_STREAM_PASS")
        libVlcController.playMedia(videoPath, uri, resumeAt, user, pass)
        if (!wasPlaying) {
            libVlcController.pause()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (libVlcController.isPlaying.value) {
                        libVlcController.pause()
                        updateForegroundPlaybackState(false)
                    } else {
                        requestAudioFocus()
                        libVlcController.play()
                        updateForegroundPlaybackState(true)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    requestAudioFocus()
                    libVlcController.play()
                    updateForegroundPlaybackState(true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    libVlcController.pause()
                    updateForegroundPlaybackState(false)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextVideo()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playPreviousVideo()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            foregroundMediaSession?.isActive = false
            foregroundMediaSession?.release()
            foregroundMediaSession = null
        } catch (_: Exception) {}
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        try {
            unregisterReceiver(audioRouteReceiver)
        } catch (_: Exception) {}
        if (activeInstance == this) activeInstance = null
        if (activeController == libVlcController) activeController = null
        abandonAudioFocus()
        val current = libVlcController.currentTime.value
        val saveKey = videoPath.ifEmpty { videoUriString }
        if (current > 0 && saveKey.isNotEmpty()) {
            prefsManager.saveVideoResumePosition(saveKey, current)
        }
        if (originalSystemVolume >= 0 && !isBackgroundAudioRunning) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalSystemVolume, 0)
            } catch (_: Exception) {}
        }
        if (!isBackgroundAudioRunning) {
            libVlcController.release()
        }
    }
}


@Composable
fun GestureVideoPlayerScreen(
    title: String,
    videoPath: String,
    videoUriString: String,
    resumePos: Long,
    streamUser: String? = null,
    streamPass: String? = null,
    controller: LibVlcController,
    currentVideo: VideoItem? = null,
    hasNext: Boolean = false,
    hasPrevious: Boolean = false,
    isInPip: Boolean = false,
    resumeTrigger: Int = 0,
    onPlayNextVideo: () -> Unit = {},
    onPlayPreviousVideo: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val gestureScope = rememberCoroutineScope()
    val activity = context as? ComponentActivity
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isPlaying by controller.isPlaying.collectAsState()
    val currentTime by controller.currentTime.collectAsState()
    val totalDuration by controller.totalDuration.collectAsState()

    val effectiveVideo = currentVideo ?: remember(videoPath, title) {
        VideoItem(
            id = kotlin.math.abs(videoPath.hashCode().toLong()),
            title = title,
            path = videoPath,
            uri = Uri.parse(videoUriString),
            duration = totalDuration,
            size = if (videoPath.isNotEmpty()) File(videoPath).length() else 0L,
            dateModified = if (videoPath.isNotEmpty()) File(videoPath).lastModified() else 0L,
            folderName = ""
        )
    }
    var showVideoInfoDialog by remember { mutableStateOf(false) }

    var isResumingFromPause by remember { mutableStateOf(false) }
    var isInitialResumeCheck by remember { mutableStateOf(true) }

    LaunchedEffect(isPlaying) {
        if (activity is VideoPlayerActivity) {
            activity.updatePipParams()
        }
    }

    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showUnlockPill by remember { mutableStateOf(false) }

    val prefs = remember(context) { PreferencesManager(context) }

    // Dialog bodies are scrollable; capping their height keeps every modal inside the screen on
    // small or thick-bezel devices instead of running past the display edge.
    val dialogMaxHeightDp = with(androidx.compose.ui.platform.LocalConfiguration.current) {
        (screenHeightDp * 0.86f).dp
    }

    // Resize Mode: Original, Stretch, Cropped (Persistent per video & global default)
    val resizeKey = remember(currentVideo?.path, currentVideo?.uri, videoPath, videoUriString) {
        val p = currentVideo?.path ?: videoPath
        val u = currentVideo?.uri?.toString() ?: videoUriString
        p.ifEmpty { u }
    }
    var currentResizeMode by remember(resizeKey) {
        val saved = prefs.getVideoResizeMode(resizeKey) ?: prefs.getDefaultResizeMode()
        mutableStateOf(LibVlcController.ResizeMode.fromString(saved))
    }
    val resizeIcon = when (currentResizeMode) {
        LibVlcController.ResizeMode.ORIGINAL -> Icons.Default.FitScreen
        LibVlcController.ResizeMode.STRETCH -> Icons.Default.AspectRatio
        LibVlcController.ResizeMode.CROPPED -> Icons.Default.CropFree
    }
    var showResizePill by remember { mutableStateOf(false) }

    // Manual pinch-to-zoom percentage HUD state (MX Player style)
    var manualZoomPercent by remember { mutableIntStateOf(-1) }
    var showZoomPill by remember { mutableStateOf(false) }
    val zoomHandler = remember { Handler(Looper.getMainLooper()) }
    val zoomRunnable = remember { Runnable { showZoomPill = false } }

    val realDimensions = remember(context, isPortrait) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (context as? Activity)?.windowManager?.currentWindowMetrics?.bounds
            if (bounds != null) {
                Pair(bounds.width().toFloat(), bounds.height().toFloat())
            } else {
                val dm = context.resources.displayMetrics
                Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
            }
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (context as? Activity)?.windowManager?.defaultDisplay?.getRealMetrics(dm)
            if (dm.widthPixels > 0) {
                Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
            } else {
                val resDm = context.resources.displayMetrics
                Pair(resDm.widthPixels.toFloat(), resDm.heightPixels.toFloat())
            }
        }
    }
    val screenW = realDimensions.first
    val screenH = realDimensions.second

    // Long press 2X Speed State
    var isFastForwarding by remember { mutableStateOf(false) }

    // 2-Finger Pinch Zoom & Pan
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var isPinchGesturing by remember { mutableStateOf(false) }
    // The video output views. Stretch, crop and pinch transform the picture view only, so the cue
    // keeps its own size and position on the library's separate subtitle surface.
    var videoSurfaceLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var videoContainerSize by remember { mutableStateOf(IntSize.Zero) }
    val animatedZoomScale by animateFloatAsState(
        targetValue = zoomScale,
        animationSpec = if (isPinchGesturing) snap() else tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "animatedZoomScale"
    )
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    val isPlayingState by controller.isPlaying.collectAsState()
    val videoSize by controller.videoSize.collectAsState()

    // Reset manual pinch zoom & pan offsets ONLY when switching display resize modes
    LaunchedEffect(currentResizeMode) {
        zoomScale = 1.0f
        panOffsetX = 0f
        panOffsetY = 0f
    }

    // Keep LibVLC resize mode in sync with layout, screen orientation, and stream track updates (NEVER reset on play/pause)
    LaunchedEffect(currentResizeMode, isPortrait, videoSize) {
        val dw = if (isPortrait) minOf(screenW, screenH).toInt() else maxOf(screenW, screenH).toInt()
        val dh = if (isPortrait) maxOf(screenW, screenH).toInt() else minOf(screenW, screenH).toInt()
        controller.setResizeMode(currentResizeMode, dw, dh)
        controller.applyResizeMode()
    }

    // Subtitle, Audio Track & Playback Speed dialogs
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showAudioTrackSheet by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showVideoOptimizationDialog by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(controller.getSpeed()) }
    var subtitleTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var currentSubtitleTrack by remember { mutableIntStateOf(-1) }
    var audioTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var currentAudioTrack by remember { mutableIntStateOf(-1) }

    // Battery & Charging State (for Low Battery Emergency Warning HUD)
    var batteryPercentage by remember { mutableIntStateOf(-1) }
    var isBatteryCharging by remember { mutableStateOf(false) }
    var lastObservedBatteryLevel by remember { mutableIntStateOf(-1) }

    // Low Battery Animated HUD Box State (1.5s on-screen box)
    var showBatteryWarningPill by remember { mutableStateOf(false) }
    var batteryWarningMessage by remember { mutableStateOf("") }
    var batteryWarningLevel by remember { mutableIntStateOf(0) }
    var batteryWarningJob by remember { mutableStateOf<Job?>(null) }

    // Start directly in PiP if launched via Pop-up Play
    val actIntent = (activity as? Activity)?.intent
    LaunchedEffect(Unit) {
        if (actIntent?.getBooleanExtra(VideoPlayerActivity.EXTRA_START_IN_PIP, false) == true) {
            actIntent.removeExtra(VideoPlayerActivity.EXTRA_START_IN_PIP)
            kotlinx.coroutines.delay(400L)
            (activity as? VideoPlayerActivity)?.enterPipMode()
        }
    }

    // 3-Stage Smart Low Battery Warning (25%, 15%, 5% Critical) with Audio Dimming & Tone Alert
    // Triggers ONLY during active playback when battery transitions downward across a threshold.
    // NEVER triggers on initial video launch/open or when paused.
    LaunchedEffect(batteryPercentage, isBatteryCharging, isPlaying) {
        if (batteryPercentage <= 0 || !prefs.isLowBatteryWarningEnabled()) {
            return@LaunchedEffect
        }

        if (isBatteryCharging || batteryPercentage > 30) {
            lastObservedBatteryLevel = batteryPercentage
            return@LaunchedEffect
        }

        // Initial battery measurement upon entering screen - record and never warn
        if (lastObservedBatteryLevel == -1) {
            lastObservedBatteryLevel = batteryPercentage
            return@LaunchedEffect
        }

        // Only trigger during active playback as requested by user
        if (!isPlaying) {
            lastObservedBatteryLevel = batteryPercentage
            return@LaunchedEffect
        }

        val previous = lastObservedBatteryLevel
        val threshold = when {
            previous > 5 && batteryPercentage <= 5 -> 5
            previous > 15 && batteryPercentage <= 15 -> 15
            previous > 25 && batteryPercentage <= 25 -> 25
            else -> null
        }

        lastObservedBatteryLevel = batteryPercentage

        if (threshold != null) {
            batteryWarningLevel = threshold
            batteryWarningMessage = when (threshold) {
                5 -> "Battery Critical: 5%"
                15 -> "Battery Low: 15%"
                else -> "Battery Low: 25%"
            }
            batteryWarningJob?.cancel()
            batteryWarningJob = gestureScope.launch {
                showBatteryWarningPill = true
                kotlinx.coroutines.delay(1500L) // 1.5 seconds HUD display as requested
                showBatteryWarningPill = false
            }

            val originalVol = controller.getVolume()
            if (originalVol > 0) {
                val dimmedVol = (originalVol * 0.25f).roundToInt().coerceAtLeast(10)
                controller.setVolume(dimmedVol)
            }
            withContext(Dispatchers.IO) {
                try {
                    val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
                    if (threshold == 5) {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                        Thread.sleep(300)
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                        Thread.sleep(300)
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
                        Thread.sleep(400)
                    } else {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                        Thread.sleep(350)
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
                        Thread.sleep(350)
                    }
                    toneGen.release()
                } catch (_: Exception) {}
            }
            if (originalVol > 0) {
                controller.setVolume(originalVol)
            }
        }
    }

    // Gesture HUD States
    var gestureBrightness by remember { mutableFloatStateOf(-1f) }
    var gestureVolume by remember { mutableIntStateOf(-1) }
    var gestureSeekDiff by remember { mutableLongStateOf(0L) }
    var showSeekOverlay by remember { mutableStateOf(false) }

    // Double tap feedback
    var showRewindIndicator by remember { mutableStateOf(false) }
    var showForwardIndicator by remember { mutableStateOf(false) }

    val hideHandler = remember { Handler(Looper.getMainLooper()) }
    val hideControlsRunnable = remember {
        Runnable {
            if (!isLocked && !showSubtitleSheet && !showAudioTrackSheet && !showSpeedDialog && !showVideoInfoDialog && !showVideoOptimizationDialog) {
                showControls = false
            }
        }
    }

    val unlockHandler = remember { Handler(Looper.getMainLooper()) }
    val unlockRunnable = remember {
        Runnable { showUnlockPill = false }
    }

    // Locked screen must never exit by accident: swallow back for three seconds and show the
    // unlock affordance instead.
    BackHandler(enabled = isLocked) {
        showUnlockPill = true
        unlockHandler.removeCallbacks(unlockRunnable)
        unlockHandler.postDelayed(unlockRunnable, 3000)
    }

    val resizeHandler = remember { Handler(Looper.getMainLooper()) }
    val resizeRunnable = remember {
        Runnable { showResizePill = false }
    }

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxSystemVol = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (!showSubtitleSheet && !showAudioTrackSheet && !showSpeedDialog && !showVideoInfoDialog && !showVideoOptimizationDialog) {
            hideHandler.postDelayed(hideControlsRunnable, 4000)
        }
    }

    LaunchedEffect(showControls, showSubtitleSheet, showAudioTrackSheet, showSpeedDialog, showVideoInfoDialog, showVideoOptimizationDialog) {
        if (showControls && !isLocked && !showSubtitleSheet && !showAudioTrackSheet && !showSpeedDialog && !showVideoInfoDialog && !showVideoOptimizationDialog) {
            scheduleHideControls()
        } else {
            hideHandler.removeCallbacks(hideControlsRunnable)
        }
    }

    // Resume from pause: show HUD controls and loading indicator while refreshing frame
    LaunchedEffect(resumeTrigger) {
        if (isInitialResumeCheck) {
            isInitialResumeCheck = false
            return@LaunchedEffect
        }
        if (!controller.isPlaying.value) {
            showControls = true
            scheduleHideControls()
            isResumingFromPause = true
            controller.refreshPausedFrame()
            kotlinx.coroutines.delay(450L)
            isResumingFromPause = false
        }
    }

// Saved brightness is applied directly to window.attributes in onCreate/onResume

    // Show system status bar with pure white icons when controls are visible and unlocked (both Portrait & Landscape)
    LaunchedEffect(showControls, isLocked, isPortrait) {
        val shouldShowStatus = showControls && !isLocked
        (activity as? VideoPlayerActivity)?.updateSystemBars(shouldShowStatus) ?: run {
            val act = activity ?: return@run
            val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
            insetsController.isAppearanceLightStatusBars = false
            if (shouldShowStatus) {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Real-time Battery & Charging Listener (Sticky Intent + Broadcast Receiver)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val plugged: Int = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPercentage = (level * 100 / scale)
                    }
                    isBatteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL ||
                            plugged > 0)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialStatus = context.registerReceiver(receiver, filter)
        initialStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged: Int = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            if (level >= 0 && scale > 0) {
                batteryPercentage = (level * 100 / scale)
            }
            isBatteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged > 0)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }



    // Active processing is exposed through the Sound and Video optimisation icon buttons.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
    ) {
        // LibVLC Native Video Surface
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    fitsSystemWindows = false
                    videoSurfaceLayout = this
                    controller.attachVideoLayout(this)
                    addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val newW = right - left
                        val newH = bottom - top
                        val oldW = oldRight - oldLeft
                        val oldH = oldBottom - oldTop
                        if (newW > 0 && newH > 0 && (newW != oldW || newH != oldH)) {
                            controller.updateVideoWindowSize(newW, newH)
                        }
                    }

                    post {
                        val act = ctx as? VideoPlayerActivity
                        if (act?.hasInitiatedPlayback != true) {
                            act?.hasInitiatedPlayback = true
                            if (videoPath.isNotEmpty() || videoUriString.isNotEmpty()) {
                                val uri = if (videoUriString.isNotEmpty()) Uri.parse(videoUriString) else null
                                controller.playMedia(videoPath, uri, resumePos, streamUser, streamPass)
                            } else {
                                val msg = if ((ctx as? Activity)?.intent?.action == Intent.ACTION_SEND) {
                                    "Invalid media link or unsupported shared content."
                                } else {
                                    "No video file or stream URL provided to play."
                                }
                                controller.reportError(msg)
                            }
                        }
                    }
                }
            },
            onRelease = {
                videoSurfaceLayout = null
                controller.detachVideoLayout()
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { videoContainerSize = it }
        )

        // Stretch, crop and the pinch zoom are a transform of the picture view alone.
        //
        // LibVLC's Android video output ignores runtime zoom and fill changes (its Control() answers
        // VLC_SUCCESS for VOUT_DISPLAY_CHANGE_ZOOM and applies nothing), so a crop cannot be asked
        // of the engine. The picture view is scaled here instead, while the cue lives on the
        // library's own subtitle surface next to it: the frame stretches or crops, the text keeps
        // its size and stays fully on screen, exactly like a dedicated player does it.
        val pictureAspect = if (videoContainerSize.height > 0) {
            videoContainerSize.width.toFloat() / videoContainerSize.height
        } else {
            16f / 9f
        }
        // The size flow can lag or return 0 on some multi-track MKV containers, so fall back to
        // the stream track query, then the fitted surface_video bounds, and finally 16:9 (never
        // pictureAspect, which would make Fit / Stretch / Cropped all collapse to 1:1).
        val sourceWidth = if (videoSize.first > 0) videoSize.first else controller.getVideoWidth()
        val sourceHeight = if (videoSize.second > 0) videoSize.second else controller.getVideoHeight()
        val surfaceViewBounds = videoSurfaceLayout?.let { layout ->
            val v = layout.findViewById<android.view.View>(org.videolan.R.id.surface_video)
                ?: layout.findViewById(org.videolan.R.id.texture_video)
            if (v != null && v.width > 0 && v.height > 0 &&
                (v.width != videoContainerSize.width || v.height != videoContainerSize.height)
            ) {
                v.width to v.height
            } else null
        }
        val videoAspect = when {
            sourceWidth > 0 && sourceHeight > 0 -> sourceWidth.toFloat() / sourceHeight.toFloat()
            surfaceViewBounds != null -> surfaceViewBounds.first.toFloat() / surfaceViewBounds.second.toFloat()
            else -> 16f / 9f
        }
        val baseScale = when (currentResizeMode) {
            LibVlcController.ResizeMode.STRETCH ->
                if (videoAspect > pictureAspect) {
                    1f to (videoAspect / pictureAspect)
                } else {
                    (pictureAspect / videoAspect) to 1f
                }
            LibVlcController.ResizeMode.CROPPED -> {
                val fill = maxOf(pictureAspect / videoAspect, videoAspect / pictureAspect)
                fill to fill
            }
            else -> 1f to 1f
        }
        val pictureScaleX = baseScale.first * animatedZoomScale
        val pictureScaleY = baseScale.second * animatedZoomScale
        val picturePanX = panOffsetX
        val picturePanY = panOffsetY
        SideEffect {
            val layout = videoSurfaceLayout ?: return@SideEffect
            // Only the picture view is touched. Scaling a parent container would carry the cue
            // surface along and crop the text with the frame.
            val picture = layout.findViewById<android.view.View>(org.videolan.R.id.surface_video)
                ?: layout.findViewById(org.videolan.R.id.texture_video)
                ?: return@SideEffect
            picture.scaleX = pictureScaleX
            picture.scaleY = pictureScaleY
            picture.translationX = picturePanX
            picture.translationY = picturePanY
        }

        // Picture profiles (Vivid / Cinema / Natural) are applied natively by LibVLC's
        // pixel-level "adjust" filter (auto-switching to software decoding when active).

        if (!isInPip) {
        // Unified Gesture Touch Overlay
        var lastTapTime by remember { mutableLongStateOf(0L) }
        var lastTapX by remember { mutableFloatStateOf(0f) }
        var lastTapY by remember { mutableFloatStateOf(0f) }
        var singleTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val initialX = down.position.x
                        val initialY = down.position.y
                        val screenWidth = size.width.toFloat()
                        val screenHeight = size.height.toFloat()
                        // Palm rejection: a touch that lands in a corner or on a side edge must never
                        // become the second finger of a pinch while the user is seeking one-handed.
                        val guardPx = (minOf(screenWidth, screenHeight) * 0.15f)
                            .coerceIn(40.dp.toPx(), 96.dp.toPx())
                        val palmPointers = HashSet<androidx.compose.ui.input.pointer.PointerId>()
                        fun rememberPointerStart(change: androidx.compose.ui.input.pointer.PointerInputChange) {
                            if (!change.previousPressed && change.pressed) {
                                val pos = change.position
                                if (pos.x < guardPx || pos.x > screenWidth - guardPx ||
                                    pos.y < guardPx || pos.y > screenHeight - guardPx
                                ) {
                                    palmPointers.add(change.id)
                                }
                            }
                        }
                        rememberPointerStart(down)
                        // Same guard for the 2x long-press: a touch that starts near a corner or edge
                        // is almost always the palm while seeking, never an intentional speed hold.
                        val startedInGuardZone = down.position.x < guardPx ||
                            down.position.x > screenWidth - guardPx ||
                            down.position.y < guardPx ||
                            down.position.y > screenHeight - guardPx
                        var isHorizontal = false
                        var isVertical = false
                        var hasMoved = false
                        var isPinching = false
                        var pinchEngaged = false
                        var is2xActive = false
                        var accumulatedVol = -1f
                        var initialPinchDistance = 0f
                        var initialPinchScale = zoomScale
                        var initialPanX = panOffsetX
                        var initialPanY = panOffsetY
                        var initialCentroidX = 0f
                        var initialCentroidY = 0f

                        val longPressJob = gestureScope.launch {
                            kotlinx.coroutines.delay(400)
                            if (!hasMoved && !isLocked && !isPinching && !startedInGuardZone) {
                                is2xActive = true
                                isFastForwarding = true
                                val speed2x = if (playbackSpeed >= 2.0f) (playbackSpeed * 1.5f).coerceAtMost(4.0f) else 2.0f
                                controller.setSpeed(speed2x)
                            }
                        }

                        do {
                            val event = awaitPointerEvent()
                            if (isLocked) continue

                            event.changes.forEach { rememberPointerStart(it) }
                            val activePointers = event.changes.filter { it.pressed && !palmPointers.contains(it.id) }
                            // A palm landing anywhere cancels a pending speed hold.
                            if (palmPointers.isNotEmpty() && !hasMoved) longPressJob.cancel()
                            if (activePointers.size >= 2) {
                                longPressJob.cancel()
                                if (is2xActive) {
                                    is2xActive = false
                                    isFastForwarding = false
                                    controller.setSpeed(playbackSpeed)
                                }
                                val p0 = activePointers[0].position
                                val p1 = activePointers[1].position
                                val dist = hypot(p0.x - p1.x, p0.y - p1.y)
                                val cX = (p0.x + p1.x) / 2f
                                val cY = (p0.y + p1.y) / 2f

                                if (!isPinching) {
                                    isPinching = true
                                    isPinchGesturing = true
                                    hasMoved = true
                                    isHorizontal = false
                                    isVertical = false
                                    showSeekOverlay = false
                                    gestureBrightness = -1f
                                    gestureVolume = -1
                                    initialPinchDistance = dist
                                    initialPinchScale = zoomScale
                                    initialPanX = panOffsetX
                                    initialPanY = panOffsetY
                                    initialCentroidX = cX
                                    initialCentroidY = cY
                                } else if (initialPinchDistance > 10f) {
                                    val ratio = dist / initialPinchDistance
                                    if (!pinchEngaged) {
                                        // Ignore a second finger that barely moves: an accidental brush
                                        // must not start a zoom. 3% travel engages the pinch.
                                        if (abs(ratio - 1f) < 0.03f) {
                                            activePointers.forEach { it.consume() }
                                            continue
                                        }
                                        pinchEngaged = true
                                    }
                                    val scale = (initialPinchScale * ratio).coerceIn(1.0f, 4.0f)
                                    zoomScale = scale
                                    val pct = (scale * 100f).roundToInt()
                                    manualZoomPercent = pct
                                    showZoomPill = true
                                    zoomHandler.removeCallbacks(zoomRunnable)
                                    zoomHandler.postDelayed(zoomRunnable, 1400)
                                    if (zoomScale <= 1.05f) {
                                        panOffsetX = 0f
                                        panOffsetY = 0f
                                    } else {
                                        val maxPanX = (zoomScale - 1f) * screenWidth * 0.5f
                                        val maxPanY = (zoomScale - 1f) * screenHeight * 0.5f
                                        panOffsetX = (initialPanX + (cX - initialCentroidX)).coerceIn(-maxPanX, maxPanX)
                                        panOffsetY = (initialPanY + (cY - initialCentroidY)).coerceIn(-maxPanY, maxPanY)
                                    }
                                }
                                activePointers.forEach { it.consume() }
                                continue
                            }
                            if (isPinching) {
                                continue
                            }

                            val change = event.changes.firstOrNull() ?: break

                            val deltaX = change.position.x - initialX
                            val deltaY = change.position.y - initialY

                            if (!isHorizontal && !isVertical && !is2xActive) {
                                if (abs(deltaX) > 40 && abs(deltaX) > abs(deltaY)) {
                                    isHorizontal = true
                                    hasMoved = true
                                    longPressJob.cancel()
                                } else if (abs(deltaY) > 40 && abs(deltaY) > abs(deltaX)) {
                                    // Strict 25% Left & 25% Right zones in Landscape (4-split screen with 50% middle safety gap)
                                    val leftBound = if (isPortrait) screenWidth * 0.35f else screenWidth * 0.25f
                                    val rightBound = if (isPortrait) screenWidth * 0.65f else screenWidth * 0.75f
                                    if (initialX <= leftBound || initialX >= rightBound) {
                                        isVertical = true
                                        hasMoved = true
                                        longPressJob.cancel()
                                    }
                                }
                            }

                            if (isVertical && !is2xActive) {
                                val diffY = change.position.y - change.previousPosition.y
                                val percentChange = -diffY / screenHeight
                                val leftBound = if (isPortrait) screenWidth * 0.35f else screenWidth * 0.25f
                                val rightBound = if (isPortrait) screenWidth * 0.65f else screenWidth * 0.75f

                                if (initialX <= leftBound) {
                                    // Left Side (25% in landscape): Brightness
                                    activity?.window?.attributes?.let { lp ->
                                        var currentBrightness = lp.screenBrightness
                                        if (currentBrightness < 0) currentBrightness = 0.5f
                                        val newBrightness = (currentBrightness + percentChange * 5.0f).coerceIn(0.01f, 1.0f)
                                        lp.screenBrightness = newBrightness
                                        activity.window.attributes = lp
                                        gestureBrightness = newBrightness
                                        prefs.saveVideoBrightness(newBrightness)
                                    }
                                } else if (initialX >= rightBound) {
                                    // Right Side: device media volume for 0–100%, software boost above
                                    val boostAllowed = prefs.isVolumeBoostEnabled()
                                    val maxLogicalVol = if (boostAllowed) 200 else 100
                                    if (accumulatedVol < 0f) {
                                        val currentLogicalVol = (activity as? VideoPlayerActivity)
                                            ?.getCurrentLogicalVolume()
                                            ?: controller.getVolume()
                                        accumulatedVol = currentLogicalVol
                                            .coerceIn(0, maxLogicalVol).toFloat()
                                    }
                                    val sensitivity = if (isPortrait) {
                                        if (boostAllowed) 4.5f else 6.5f
                                    } else {
                                        if (boostAllowed) 2.0f else 3.5f
                                    }
                                    val volChange = (-diffY / screenHeight) * 100f * sensitivity
                                    accumulatedVol = (accumulatedVol + volChange).coerceIn(0f, maxLogicalVol.toFloat())
                                    val newLogicalVol = accumulatedVol.roundToInt()

                                    (activity as? VideoPlayerActivity)?.applyLogicalVolume(
                                        newLogicalVol,
                                        userInitiated = true
                                    ) ?: run {
                                        controller.setVolume(newLogicalVol)
                                    }
                                    gestureVolume = newLogicalVol
                                }
                                change.consume()
                            } else if (isHorizontal && !is2xActive) {
                                val diffSeconds = (deltaX / 10).toLong()
                                gestureSeekDiff = diffSeconds * 1000L
                                showSeekOverlay = true
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        longPressJob.cancel()

                        if (gestureBrightness >= 0f) {
                            prefs.saveVideoBrightness(gestureBrightness)
                        }
                        if (gestureVolume >= 0) {
                            (activity as? VideoPlayerActivity)?.applyLogicalVolume(
                                gestureVolume,
                                userInitiated = true
                            )
                        }

                        // Release 2X Speed on touch up
                        if (is2xActive) {
                            controller.setSpeed(playbackSpeed)
                            isFastForwarding = false
                        }

                        val upTime = System.currentTimeMillis()
                        if (!hasMoved && (upTime - downTime) < 400 && !is2xActive) {
                            if (isLocked) {
                                // Locked screen tapped: Show Unlock button for 3 seconds then auto-hide
                                showUnlockPill = true
                                unlockHandler.removeCallbacks(unlockRunnable)
                                unlockHandler.postDelayed(unlockRunnable, 3000)
                            } else {
                                val isDoubleTap = (upTime - lastTapTime) < 320 && kotlin.math.abs(initialX - lastTapX) < 160 && kotlin.math.abs(initialY - lastTapY) < 160
                                if (isDoubleTap) {
                                    lastTapTime = 0L
                                    singleTapJob?.cancel()
                                    singleTapJob = null
                                    if (initialX < screenWidth * 0.5f) {
                                        val target = (controller.currentTime.value - 10000L).coerceAtLeast(0L)
                                        controller.seekTo(target)
                                        showRewindIndicator = true
                                        Handler(Looper.getMainLooper()).postDelayed({ showRewindIndicator = false }, 800)
                                    } else {
                                        val target = (controller.currentTime.value + 10000L).coerceAtMost(controller.totalDuration.value)
                                        controller.seekTo(target)
                                        showForwardIndicator = true
                                        Handler(Looper.getMainLooper()).postDelayed({ showForwardIndicator = false }, 800)
                                    }
                                } else {
                                    lastTapTime = upTime
                                    lastTapX = initialX
                                    lastTapY = initialY
                                    singleTapJob?.cancel()
                                    singleTapJob = gestureScope.launch {
                                        kotlinx.coroutines.delay(300)
                                        showControls = !showControls
                                        if (showControls) scheduleHideControls()
                                    }
                                }
                            }
                        }

                        if (showSeekOverlay && gestureSeekDiff != 0L) {
                            val target = (controller.currentTime.value + gestureSeekDiff).coerceIn(0L, controller.totalDuration.value)
                            controller.seekTo(target)
                        }
                        gestureBrightness = -1f
                        gestureVolume = -1
                        showSeekOverlay = false
                        gestureSeekDiff = 0L
                        isPinchGesturing = false
                    }
                }
        )

        // Gesture HUD: Brightness Indicator (Top sleek horizontal pill)
        if (gestureBrightness >= 0f) {
            TopPillHUD(
                icon = Icons.Default.BrightnessMedium,
                valueText = "${(gestureBrightness * 100).toInt()}%",
                progress = gestureBrightness,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 54.dp)
            )
        }

        // Gesture HUD: Volume Indicator (Top sleek horizontal pill with clean percentage, no "Boost" text)
        if (gestureVolume >= 0) {
            val boostAllowed = prefs.isVolumeBoostEnabled()
            val isBoost = gestureVolume > 100 && boostAllowed
            val maxVol = if (boostAllowed) 200f else 100f
            TopPillHUD(
                icon = if (isBoost) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                valueText = "$gestureVolume%",
                progress = (gestureVolume / maxVol).coerceIn(0f, 1f),
                accentColor = if (isBoost) Color(0xFFFF5252) else VideoIndigo,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 54.dp)
            )
        }

        // Gesture HUD: Scrubbing Time Overlay (Center)
        if (showSeekOverlay) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.Black.copy(alpha = 0.88f),
                border = BorderStroke(1.5.dp, VideoIndigo),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .defaultMinSize(minWidth = 180.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 18.dp)
                ) {
                    val target = (currentTime + gestureSeekDiff).coerceIn(0L, totalDuration)
                    val sign = when {
                        abs(gestureSeekDiff) < 1000L -> ""
                        gestureSeekDiff < 0 -> "-"
                        else -> "+"
                    }
                    Text(
                        text = "$sign${formatDuration(abs(gestureSeekDiff))}",
                        color = VideoIndigo,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatDuration(target)} / ${formatDuration(totalDuration)}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Center HUD Notification for Resize Mode (Original, Stretch, Cropped) - Perfectly centered in Root Box
        AnimatedVisibility(
            visible = showResizePill,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF14151D).copy(alpha = 0.98f),
                border = BorderStroke(2.dp, VideoIndigo),
                shadowElevation = 14.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pillIcon = when (currentResizeMode) {
                        LibVlcController.ResizeMode.ORIGINAL -> Icons.Default.FitScreen
                        LibVlcController.ResizeMode.STRETCH -> Icons.Default.AspectRatio
                        LibVlcController.ResizeMode.CROPPED -> Icons.Default.CropFree
                    }
                    Icon(pillIcon, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = currentResizeMode.label.uppercase(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // Center HUD Notification for Manual Pinch-to-Zoom (MX Player style)
        AnimatedVisibility(
            visible = showZoomPill && manualZoomPercent > 0,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.88f),
                border = BorderStroke(1.5.dp, VideoIndigo),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.widthIn(min = 54.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$manualZoomPercent%",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // 2X Fast Forward Playback HUD (Positioned comfortably at top = 54.dp)
        AnimatedVisibility(
            visible = isFastForwarding,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 54.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.85f),
                border = BorderStroke(1.5.dp, VideoIndigo),
                shadowElevation = 10.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "2X",
                        color = VideoIndigo,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Speed",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Smart Low Battery Warning Animated HUD Box (Positioned at top = 54.dp)
        AnimatedVisibility(
            visible = showBatteryWarningPill,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 54.dp)
        ) {
            val pillColor = if (batteryWarningLevel <= 5) Color(0xFFFF2D55) else Color(0xFFFF9500)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.88f),
                border = BorderStroke(1.5.dp, pillColor),
                shadowElevation = 10.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (batteryWarningLevel <= 5) Icons.Default.BatteryAlert else Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = pillColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = batteryWarningMessage,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Double Tap Seek Feedback (Rewind - Left)
        AnimatedVisibility(
            visible = showRewindIndicator,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color.Black.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "10s",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Double Tap Seek Feedback (Fast Forward - Right)
        AnimatedVisibility(
            visible = showForwardIndicator,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color.Black.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "10s",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Loading / Buffering Box — same shape and border as the gesture seek box so the player
        // keeps one visual language while waiting. Hidden during a scrub, where the seek box owns
        // the centre of the screen.
        val isBuffering by controller.isBuffering.collectAsState()
        val bufferingPercent by controller.buffering.collectAsState()
        val encounteredError by controller.encounteredError.collectAsState()

        if ((isBuffering || isResumingFromPause) && encounteredError == null && !showSeekOverlay) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.Black.copy(alpha = 0.88f),
                border = BorderStroke(1.5.dp, VideoIndigo),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .defaultMinSize(minWidth = 180.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 18.dp)
                ) {
                    val percent = bufferingPercent.roundToInt()
                    if (percent in 1..99) {
                        Text(
                            text = "$percent%",
                            color = VideoIndigo,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            color = VideoIndigo,
                            trackColor = VideoIndigo.copy(alpha = 0.2f),
                            strokeWidth = 3.5.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LoadingDotsLabel(
                        text = if (isPlayingState && currentTime > 1000L) "Buffering" else "Loading"
                    )
                }
            }
        }

        // Unplayable Video Dialog with Browser Intercept
        if (encounteredError != null) {
            val isWebUrl = videoPath.startsWith("http://", ignoreCase = true) ||
                    videoPath.startsWith("https://", ignoreCase = true) ||
                    videoUriString.startsWith("http://", ignoreCase = true) ||
                    videoUriString.startsWith("https://", ignoreCase = true)
            // Only an error that names a webpage means a webpage. A URL without a file extension is
            // a normal direct stream address, so it must not be reported as one: doing that showed
            // the browser dialog over a stream that then started playing.
            val isWebpage = isWebUrl && (
                encounteredError?.contains("webpage", ignoreCase = true) == true ||
                encounteredError?.contains("html", ignoreCase = true) == true
            )
            AlertDialog(
                onDismissRequest = { /* Modal: require user action */ },
                shape = RoundedCornerShape(22.dp),
                containerColor = Color(0xFF161720),
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.fillMaxWidth(if (isPortrait) 0.92f else 0.60f),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isWebUrl) VideoIndigo.copy(alpha = 0.2f) else Color(0xFFFA2D48).copy(alpha = 0.2f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isWebUrl) Icons.Default.Explore else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (isWebUrl) VideoIndigo else Color(0xFFFA2D48),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isWebpage) "Webpage Link Detected" else if (isWebUrl) "Stream Playback Error" else "Playback Error",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                text = {
                    Column {
                        val message = if (isWebpage) {
                            "This link returned a webpage instead of a direct video stream. Would you like to open it in your browser?"
                        } else if (isWebUrl) {
                            "This online stream could not be played directly. Would you like to open it in your browser?"
                        } else {
                            encounteredError ?: "This video cannot be played. The format or codec may be unsupported, or the file is corrupted."
                        }
                        Text(
                            text = message,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 15.sp,
                            lineHeight = 24.sp
                        )
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isWebUrl) {
                            OutlinedButton(
                                onClick = {
                                    controller.clearError()
                                    (context as? ComponentActivity)?.finish()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383C50)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(text = "Cancel", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Button(
                                onClick = {
                                    val targetUrl = when {
                                        videoPath.startsWith("http://", ignoreCase = true) || videoPath.startsWith("https://", ignoreCase = true) -> videoPath
                                        else -> videoUriString
                                    }
                                    controller.clearError()
                                    try {
                                        // Hand the link to the default browser as an external URL;
                                        // no in-app custom tab.
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                            addCategory(Intent.CATEGORY_BROWSABLE)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(browserIntent)
                                    } catch (_: Exception) {}
                                    (context as? ComponentActivity)?.finish()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VideoIndigo,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(text = "Open in Browser", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    controller.clearError()
                                    (context as? ComponentActivity)?.finish()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VideoIndigo,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(text = "OK", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            )
        }

        // Fullscreen HUD Overlay (Header, Battery/Clock, Fit/Stretch/Zoom, Rotation, Subtitles, Audio, Seekbar, Lock)
        AnimatedVisibility(
            visible = (showControls && !isLocked) || (isLocked && showUnlockPill),
            enter = fadeIn(androidx.compose.animation.core.tween(150)),
            exit = fadeOut(androidx.compose.animation.core.tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            ) {
                // 1. Top Action Bar Area (Gradient scrim only at top, center stays bright)
                if (!isLocked || isPortrait) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.78f),
                                        Color.Black.copy(alpha = 0.40f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = if (isPortrait) 16.dp else 40.dp, vertical = 8.dp)
                    ) {
                        // Header Row: Back, Title + Info Button (Only shown when unlocked)
                        if (!isLocked) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = onBack,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                val displayTitle = when {
                                    title.isNotBlank() && title != "Video Player" -> title
                                    currentVideo?.title?.isNotBlank() == true && currentVideo?.title != "Video Player" -> currentVideo?.title ?: "Video"
                                    videoPath.isNotBlank() -> java.io.File(videoPath).name
                                    videoUriString.isNotBlank() -> {
                                        val u = Uri.parse(videoUriString)
                                        u.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Video"
                                    }
                                    else -> "Video"
                                }
                                Text(
                                    text = displayTitle,
                                    color = Color.White,
                                    fontSize = if (isPortrait) 15.sp else 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                IconButton(
                                    onClick = { showVideoInfoDialog = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Landscape: the video optimisation shortcut lives on the side of the
                                // title bar opposite the back arrow, next to the info button.
                                if (!isPortrait) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { showVideoOptimizationDialog = true },
                                        modifier = Modifier.size(36.dp).clip(CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Default.Tune,
                                            contentDescription = "Video Optimization",
                                            tint = if (prefs.getVideoEnhancerMode() != "STANDARD") VideoIndigo else Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                    }
                }

                // 2. Bottom Controls Bar (Gradient scrim only at bottom, center stays bright)
                if (!isLocked || !isPortrait) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Black.copy(alpha = 0.88f)
                                    )
                                )
                            )
                            .then(if (isPortrait) Modifier.navigationBarsPadding() else Modifier)
                            .padding(horizontal = if (isPortrait) 16.dp else 40.dp, vertical = if (isPortrait) 10.dp else 16.dp)
                    ) {
                        // Portrait tool bar sits directly above the play/pause controls so the
                        // thumb can reach it; the two optimisation shortcuts come first so they
                        // are visible without scrolling the row.
                        if (isPortrait && !isLocked) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        isLocked = true
                                        showControls = false
                                        showUnlockPill = false
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Lock Screen",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                // Video optimisation shortcut (same profile as Video Settings)
                                IconButton(
                                    onClick = { showVideoOptimizationDialog = true },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Video Optimization",
                                        tint = if (prefs.getVideoEnhancerMode() != "STANDARD") VideoIndigo else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(1.dp)
                                        .height(20.dp)
                                        .background(Color.White.copy(alpha = 0.22f))
                                )

                                IconButton(
                                    onClick = { (activity as? VideoPlayerActivity)?.enterPipMode() },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { (activity as? VideoPlayerActivity)?.startBackgroundAudioPlayback() },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Headphones,
                                        contentDescription = "Background Audio",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { showSpeedDialog = true },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = "Playback Speed",
                                        tint = if (playbackSpeed != 1.0f) VideoIndigo else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(1.dp)
                                        .height(20.dp)
                                        .background(Color.White.copy(alpha = 0.22f))
                                )

                                IconButton(
                                    onClick = {
                                        subtitleTracks = controller.getSubtitleTracks()
                                        currentSubtitleTrack = controller.getCurrentSubtitleTrack()
                                        showSubtitleSheet = true
                                    },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White, modifier = Modifier.size(22.dp))
                                }

                                IconButton(
                                    onClick = {
                                        audioTracks = controller.getAudioTracks()
                                        currentAudioTrack = controller.getCurrentAudioTrack()
                                        showAudioTrackSheet = true
                                    },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(Icons.Default.Audiotrack, contentDescription = "Audio Track", tint = Color.White, modifier = Modifier.size(22.dp))
                                }

                                IconButton(
                                    onClick = {
                                        val nextMode = when (currentResizeMode) {
                                            LibVlcController.ResizeMode.ORIGINAL -> LibVlcController.ResizeMode.STRETCH
                                            LibVlcController.ResizeMode.STRETCH -> LibVlcController.ResizeMode.CROPPED
                                            LibVlcController.ResizeMode.CROPPED -> LibVlcController.ResizeMode.ORIGINAL
                                        }
                                        currentResizeMode = nextMode
                                        zoomScale = 1.0f
                                        panOffsetX = 0f
                                        panOffsetY = 0f
                                        prefs.saveVideoResizeMode(resizeKey, nextMode.name)
                                        prefs.saveDefaultResizeMode(nextMode.name)
                                        val dw = if (isPortrait) minOf(screenW, screenH).toInt() else maxOf(screenW, screenH).toInt()
                                        val dh = if (isPortrait) maxOf(screenW, screenH).toInt() else minOf(screenW, screenH).toInt()
                                        controller.setResizeMode(nextMode, dw, dh)
                                        controller.applyResizeMode()
                                        showResizePill = true
                                        resizeHandler.removeCallbacks(resizeRunnable)
                                        resizeHandler.postDelayed(resizeRunnable, 1200)
                                    },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(resizeIcon, contentDescription = "Resize Mode", tint = Color.White, modifier = Modifier.size(22.dp))
                                }

                                IconButton(
                                    onClick = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE },
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                ) {
                                    Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate Screen", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // Timeline & Progress Bar: Only shown when unlocked
                        if (!isLocked) {
                            SleekVideoProgressBar(
                                currentTime = currentTime,
                                totalDuration = totalDuration,
                                onSeek = { controller.seekTo(it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // Controls Row below Seeker: Strictly centered Prev/Play/Next
                        if (isPortrait) {
                            if (!isLocked) {
                                // Portrait Mode: Clean strictly centered Prev, Play/Pause, Next (Enlarged!)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                                    ) {
                                        IconButton(
                                            onClick = onPlayPreviousVideo,
                                            enabled = hasPrevious,
                                            modifier = Modifier.size(52.dp).clip(CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SkipPrevious,
                                                contentDescription = "Previous Video",
                                                tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.35f),
                                                modifier = Modifier.size(38.dp)
                                            )
                                        }

                                        Surface(
                                            shape = CircleShape,
                                            color = VideoIndigo,
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(CircleShape)
                                                .clickable { controller.togglePlayPause() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                    contentDescription = "Play/Pause",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(42.dp)
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = onPlayNextVideo,
                                            enabled = hasNext,
                                            modifier = Modifier.size(52.dp).clip(CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SkipNext,
                                                contentDescription = "Next Video",
                                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f),
                                                modifier = Modifier.size(38.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                        // Landscape Mode: Left Tools (scrollable, weight=1), Center Controls (static, centered), Right Tools (scrollable, weight=1)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // LEFT: Lock Screen, PiP, Audio, Speed (Horizontally scrollable, weight=1f)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (isLocked) {
                                                isLocked = false
                                                showUnlockPill = false
                                                showControls = true
                                                scheduleHideControls()
                                            } else {
                                                isLocked = true
                                                showControls = false
                                                showUnlockPill = false
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                            contentDescription = if (isLocked) "Unlock Screen" else "Lock Screen",
                                            tint = if (isLocked) VideoIndigo else Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    if (!isLocked) {
                                        IconButton(
                                            onClick = {
                                                (activity as? VideoPlayerActivity)?.enterPipMode()
                                            },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Default.PictureInPictureAlt,
                                                contentDescription = "Picture in Picture",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        // Background Audio Button right after PiP
                                        IconButton(
                                            onClick = {
                                                (activity as? VideoPlayerActivity)?.startBackgroundAudioPlayback()
                                            },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Default.Headphones,
                                                contentDescription = "Background Audio",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        // Playback Speed Button right next to Headphone
                                        IconButton(
                                            onClick = { showSpeedDialog = true },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Speed,
                                                contentDescription = "Playback Speed",
                                                tint = if (playbackSpeed != 1.0f) VideoIndigo else Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (!isLocked) {
                                // CENTER: Prev, Play/Pause, Next (Guaranteed 100% geometric center, static!)
                                Row(
                                    modifier = Modifier
                                        .wrapContentSize()
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                                ) {
                                    IconButton(
                                        onClick = onPlayPreviousVideo,
                                        enabled = hasPrevious,
                                        modifier = Modifier.size(52.dp).clip(CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipPrevious,
                                            contentDescription = "Previous Video",
                                            tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.35f),
                                            modifier = Modifier.size(38.dp)
                                        )
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = VideoIndigo,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(CircleShape)
                                            .clickable { controller.togglePlayPause() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play/Pause",
                                                tint = Color.White,
                                                modifier = Modifier.size(42.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = onPlayNextVideo,
                                        enabled = hasNext,
                                        modifier = Modifier.size(52.dp).clip(CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipNext,
                                            contentDescription = "Next Video",
                                            tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f),
                                            modifier = Modifier.size(38.dp)
                                        )
                                    }
                                }
                            }

                            // RIGHT: Subtitles, Audio, Resize Mode, Rotation (Horizontally scrollable, weight=1f)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                if (!isLocked) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                subtitleTracks = controller.getSubtitleTracks()
                                                currentSubtitleTrack = controller.getCurrentSubtitleTrack()
                                                showSubtitleSheet = true
                                            },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White, modifier = Modifier.size(24.dp))
                                        }

                                        IconButton(
                                            onClick = {
                                                audioTracks = controller.getAudioTracks()
                                                currentAudioTrack = controller.getCurrentAudioTrack()
                                                showAudioTrackSheet = true
                                            },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(Icons.Default.Audiotrack, contentDescription = "Audio Track", tint = Color.White, modifier = Modifier.size(24.dp))
                                        }

                                        IconButton(
                                            onClick = {
                                                val nextMode = when (currentResizeMode) {
                                                    LibVlcController.ResizeMode.ORIGINAL -> LibVlcController.ResizeMode.STRETCH
                                                    LibVlcController.ResizeMode.STRETCH -> LibVlcController.ResizeMode.CROPPED
                                                    LibVlcController.ResizeMode.CROPPED -> LibVlcController.ResizeMode.ORIGINAL
                                                }
                                                currentResizeMode = nextMode
                                                zoomScale = 1.0f
                                                panOffsetX = 0f
                                                panOffsetY = 0f
                                                prefs.saveVideoResizeMode(resizeKey, nextMode.name)
                                                prefs.saveDefaultResizeMode(nextMode.name)
                                                val dw = if (isPortrait) minOf(screenW, screenH).toInt() else maxOf(screenW, screenH).toInt()
                                                val dh = if (isPortrait) maxOf(screenW, screenH).toInt() else minOf(screenW, screenH).toInt()
                                                controller.setResizeMode(nextMode, dw, dh)
                                                controller.applyResizeMode()
                                                showResizePill = true
                                                resizeHandler.removeCallbacks(resizeRunnable)
                                                resizeHandler.postDelayed(resizeRunnable, 1200)
                                            },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(resizeIcon, contentDescription = "Resize Mode", tint = Color.White, modifier = Modifier.size(24.dp))
                                        }

                                        IconButton(
                                            onClick = {
                                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            },
                                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                        ) {
                                            Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate Screen", tint = Color.White, modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // Subtitles Centered Modal Dialog
        if (showSubtitleSheet) {
            Dialog(
                onDismissRequest = { showSubtitleSheet = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF16171E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth(if (isPortrait) 0.94f else 0.62f)
                        .heightIn(max = dialogMaxHeightDp)
                        .padding(horizontal = 16.dp, vertical = if (isPortrait) 20.dp else 10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = if (isPortrait) 20.dp else 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Subtitles,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Subtitles",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = { showSubtitleSheet = false },
                                modifier = Modifier.size(if (isPortrait) 32.dp else 28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(if (isPortrait) 14.dp else 8.dp))

                        val validSubtitleTracks = remember(subtitleTracks) {
                            subtitleTracks.filter { it.first != -1 && !it.second.trim().equals("disable", ignoreCase = true) }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (isPortrait) 260.dp else 150.dp)
                        ) {
                            if (validSubtitleTracks.isEmpty()) {
                                item {
                                    Text(
                                        text = "No subtitle tracks available",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 15.sp,
                                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                                    )
                                }
                            } else {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                controller.setSubtitleTrack(-1)
                                                currentSubtitleTrack = -1
                                                showSubtitleSheet = false
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (currentSubtitleTrack == -1),
                                            onClick = {
                                                controller.setSubtitleTrack(-1)
                                                currentSubtitleTrack = -1
                                                showSubtitleSheet = false
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = VideoIndigo, unselectedColor = Color.White.copy(alpha = 0.5f))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Disable Subtitles",
                                            color = if (currentSubtitleTrack == -1) VideoIndigo else Color.White,
                                            fontSize = 17.sp,
                                            fontWeight = if (currentSubtitleTrack == -1) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }

                                items(validSubtitleTracks) { track ->
                                    val isSelected = currentSubtitleTrack == track.first
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                controller.setSubtitleTrack(track.first)
                                                currentSubtitleTrack = track.first
                                                showSubtitleSheet = false
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                controller.setSubtitleTrack(track.first)
                                                currentSubtitleTrack = track.first
                                                showSubtitleSheet = false
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = VideoIndigo, unselectedColor = Color.White.copy(alpha = 0.5f))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = track.second,
                                            color = if (isSelected) VideoIndigo else Color.White,
                                            fontSize = 17.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Audio Track / Language Centered Modal Dialog
        if (showAudioTrackSheet) {
            Dialog(
                onDismissRequest = { showAudioTrackSheet = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF16171E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth(if (isPortrait) 0.94f else 0.62f)
                        .heightIn(max = dialogMaxHeightDp)
                        .padding(horizontal = 16.dp, vertical = if (isPortrait) 20.dp else 10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = if (isPortrait) 20.dp else 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Audio Track / Language",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = { showAudioTrackSheet = false },
                                modifier = Modifier.size(if (isPortrait) 32.dp else 28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(if (isPortrait) 14.dp else 8.dp))

                        if (audioTracks.isEmpty()) {
                            Text(
                                text = "No additional audio tracks found.",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = if (isPortrait) 260.dp else 150.dp)
                            ) {
                                items(audioTracks) { track ->
                                    val isSelected = currentAudioTrack == track.first
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                controller.setAudioTrack(track.first)
                                                currentAudioTrack = track.first
                                                showAudioTrackSheet = false
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                controller.setAudioTrack(track.first)
                                                currentAudioTrack = track.first
                                                showAudioTrackSheet = false
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = VideoIndigo, unselectedColor = Color.White.copy(alpha = 0.5f))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = track.second,
                                            color = if (isSelected) VideoIndigo else Color.White,
                                            fontSize = 17.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Video Optimization Centered Modal Dialog — mirrors the Video Settings logic
        if (showVideoOptimizationDialog) {
            val qualityVersion = com.jk.mediaplayer.data.PreferencesManager.videoQualityVersion
            val profileMode = remember(qualityVersion) { prefs.getVideoEnhancerMode() }
            val hwMode = remember(qualityVersion) { prefs.getVideoHwDecoder() }
            val highColor = remember(qualityVersion) { prefs.isVideoHighColorQuality() }
            val deinterlace = remember(qualityVersion) { prefs.getVideoDeinterlaceMode() }

            Dialog(
                onDismissRequest = { showVideoOptimizationDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF16171E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth(if (isPortrait) 0.94f else 0.62f)
                        .heightIn(max = dialogMaxHeightDp)
                        .padding(horizontal = 16.dp, vertical = if (isPortrait) 20.dp else 10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = if (isPortrait) 20.dp else 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Video Optimization",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Picture profile, decoder, colour & deinterlace",
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showVideoOptimizationDialog = false },
                                modifier = Modifier.size(if (isPortrait) 32.dp else 28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "DISPLAY PICTURE PROFILE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.45f),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Pair("VIVID", "Vivid"),
                                Pair("CINEMA", "Cinema"),
                                Pair("STANDARD", "Natural")
                            ).forEach { (mode, label) ->
                                val selected = profileMode == mode
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) VideoIndigo else Color(0xFF222432),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (profileMode != mode) {
                                                val msg = prefs.applyVideoProfileChange(mode, label)
                                                (context as? VideoPlayerActivity)?.reloadCurrentVideo()
                                                AppToast.show(msg)
                                            }
                                        }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = Color.White,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 9.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "HARDWARE DECODING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.45f),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Pair("AUTO", "Auto"),
                                Pair("FORCE_HW", "Force HW"),
                                Pair("SOFTWARE", "Software")
                            ).forEach { (mode, label) ->
                                val selected = hwMode == mode
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) VideoIndigo else Color(0xFF222432),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (hwMode != mode) {
                                                val msg = prefs.applyVideoHwDecoderChange(mode, label)
                                                (context as? VideoPlayerActivity)?.reloadCurrentVideo()
                                                AppToast.show(msg)
                                            }
                                        }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = Color.White,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 9.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "32-Bit True Color (RV32)",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Removes gradient banding in dark scenes",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.55f)
                                )
                            }
                            Switch(
                                checked = highColor,
                                onCheckedChange = { checked ->
                                    val msg = prefs.applyVideoHighColorChange(checked)
                                    (context as? VideoPlayerActivity)?.reloadCurrentVideo()
                                    AppToast.show(msg)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = VideoIndigo,
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                    uncheckedTrackColor = Color(0xFF2A2D3A)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "DEINTERLACING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.45f),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Pair("AUTO", "Auto"),
                                Pair("YADIF", "Yadif"),
                                Pair("BLEND", "Blend"),
                                Pair("OFF", "Off")
                            ).forEach { (mode, label) ->
                                val selected = deinterlace == mode
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) VideoIndigo else Color(0xFF222432),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (deinterlace != mode) {
                                                val msg = prefs.applyVideoDeinterlaceChange(mode, label)
                                                (context as? VideoPlayerActivity)?.reloadCurrentVideo()
                                                AppToast.show(msg)
                                            }
                                        }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = Color.White,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 9.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (prefs.resetVideoQualityToDefaults()) {
                                        (context as? VideoPlayerActivity)?.reloadCurrentVideo()
                                        AppToast.show("Video display settings reset to default")
                                    } else {
                                        AppToast.show("Already using default video settings")
                                    }
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Reset Video to Default",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        // Playback Speed Centered Modal Dialog
        if (showSpeedDialog) {
            Dialog(
                onDismissRequest = { showSpeedDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF16171E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth(if (isPortrait) 0.94f else 0.62f)
                        .heightIn(max = dialogMaxHeightDp)
                        .padding(horizontal = 16.dp, vertical = if (isPortrait) 20.dp else 8.dp)
                ) {
                    val speedScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(speedScrollState)
                            .padding(horizontal = 20.dp, vertical = if (isPortrait) 20.dp else 12.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Playback Speed",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = { showSpeedDialog = false },
                                modifier = Modifier.size(if (isPortrait) 32.dp else 28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(if (isPortrait) 16.dp else 8.dp))

                        // Presets Title
                        Text(
                            text = "PRESETS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(if (isPortrait) 8.dp else 4.dp))

                        // Presets: 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2x (8 items in 2 rows of 4)
                        val speedPresets = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                        val chunked = speedPresets.chunked(4)
                        Column(verticalArrangement = Arrangement.spacedBy(if (isPortrait) 8.dp else 6.dp)) {
                            chunked.forEach { rowPresets ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(if (isPortrait) 8.dp else 6.dp)
                                ) {
                                    rowPresets.forEach { preset ->
                                        val isSelected = kotlin.math.abs(playbackSpeed - preset) < 0.02f
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) VideoIndigo else Color(0xFF222432),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    playbackSpeed = preset
                                                    controller.setSpeed(preset)
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = if (isPortrait) 10.dp else 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (preset == 1.0f) "1.0x" else "${preset}x",
                                                    fontSize = if (isPortrait) 13.sp else 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(if (isPortrait) 20.dp else 10.dp))

                        // Custom Speed Slider (up to 4.0x)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CUSTOM SPEED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.2fx", playbackSpeed),
                                fontSize = if (isPortrait) 17.sp else 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = VideoIndigo
                            )
                        }

                        Spacer(modifier = Modifier.height(if (isPortrait) 6.dp else 2.dp))

                        Slider(
                            value = playbackSpeed,
                            onValueChange = { newSpeed ->
                                val rounded = (kotlin.math.round(newSpeed * 20f) / 20f).coerceIn(0.25f, 4.0f)
                                playbackSpeed = rounded
                                controller.setSpeed(rounded)
                            },
                            valueRange = 0.25f..4.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = VideoIndigo,
                                activeTrackColor = VideoIndigo,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("0.25x", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                            Text("1.0x", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                            Text("2.0x", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                            Text("3.0x", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                            Text("4.0x", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                        }

                        Spacer(modifier = Modifier.height(if (isPortrait) 14.dp else 8.dp))

                        // Action: Reset (1.0x)
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(
                                onClick = {
                                    playbackSpeed = 1.0f
                                    controller.setSpeed(1.0f)
                                }
                            ) {
                                Text(
                                    text = "Reset (1.0x)",
                                    color = VideoIndigo,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Video Info Modal Dialog (IMDb Details + Technical Specifications)
        if (showVideoInfoDialog) {
            VideoInfoDialog(
                video = effectiveVideo,
                onDismiss = { showVideoInfoDialog = false }
            )
        }


    }
}

}
@Composable
private fun SleekVideoProgressBar(
    currentTime: Long,
    totalDuration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentFraction = if (totalDuration > 0) (currentTime.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f
    val displayFraction = if (isDragging) dragProgress else currentFraction

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val showTime = if (isDragging) (dragProgress * totalDuration).toLong() else currentTime
            Text(
                text = formatDuration(showTime),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES)
            )
            Text(
                text = formatDuration(totalDuration),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(totalDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        if (w > 0) {
                            isDragging = true
                            dragProgress = (down.position.x / w).coerceIn(0f, 1f)
                            down.consume()
                        }
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                dragProgress = (change.position.x / w).coerceIn(0f, 1f)
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        if (isDragging) {
                            val targetMs = (dragProgress * totalDuration).toLong()
                            onSeek(targetMs)
                            isDragging = false
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Inactive slim line (3.5dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )

            // Active slim line (3.5dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = displayFraction)
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(VideoIndigo)
            )

            // Small sleek thumb (11dp)
            Box(
                modifier = Modifier.fillMaxWidth(fraction = displayFraction)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = 5.5.dp)
                        .size(if (isDragging) 14.dp else 11.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun TopPillHUD(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueText: String,
    progress: Float,
    accentColor: Color = VideoIndigo,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.88f),
        border = BorderStroke(1.5.dp, accentColor),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                modifier = Modifier
                    .width(72.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color.White.copy(alpha = 0.25f)
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accentColor)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier.widthIn(min = 46.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = valueText,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}


@Composable
private fun LoadingDotsLabel(text: String) {
    val transition = rememberInfiniteTransition(label = "loadingDots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$text ",
            color = VideoIndigo,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        repeat(3) { index ->
            val waveOffset by transition.animateFloat(
                initialValue = 0f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160)
                ),
                label = "loadingDot$index"
            )
            Text(
                text = ".",
                color = VideoIndigo,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = waveOffset.dp)
            )
        }
    }
}

