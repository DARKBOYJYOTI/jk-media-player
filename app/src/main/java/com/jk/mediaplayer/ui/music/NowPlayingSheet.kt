package com.jk.mediaplayer.ui.music

import com.jk.mediaplayer.ui.common.AppToast
import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import com.jk.mediaplayer.data.PreferencesManager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import com.jk.mediaplayer.ui.theme.TABULAR_FIGURES
import com.jk.mediaplayer.ui.theme.albumSurfaceTint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.jk.mediaplayer.R
import com.jk.mediaplayer.ui.theme.MusicRed
import com.jk.mediaplayer.data.OnlineMetadataFetcher
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.model.LyricsLine
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


enum class AudioOutputType {
    SPEAKER,
    HEADPHONES,
    BLUETOOTH
}

data class QueueItemModel(
    val originalIndex: Int,
    val track: AudioItem,
    val isCurrent: Boolean
)

private data class QueueTrackEntry(
    val entryId: String,
    val track: AudioItem
)

/**
 * Stable queue keys: the track id plus its occurrence index. Reordering the queue keeps the same
 * keys, so rows are moved instead of recreated and the scroll position survives a move.
 */
private fun buildQueueEntries(list: List<AudioItem>): List<QueueTrackEntry> {
    val seen = HashMap<Long, Int>()
    return list.map { track ->
        val occurrence = seen.getOrDefault(track.id, 0)
        seen[track.id] = occurrence + 1
        QueueTrackEntry("${track.id}#$occurrence", track)
    }
}

fun detectAudioOutputType(audioManager: AudioManager?): AudioOutputType {
    if (audioManager == null) return AudioOutputType.SPEAKER
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val hasBluetooth = devices.any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
    if (hasBluetooth) return AudioOutputType.BLUETOOTH

    val hasHeadphones = devices.any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_HEADSET) ||
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
    }
    if (hasHeadphones) return AudioOutputType.HEADPHONES

    return AudioOutputType.SPEAKER
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingSheet(
    track: AudioItem,
    isPlaying: Boolean,
    playbackTime: () -> Long,
    totalDurationMs: Long,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    playlist: List<AudioItem> = emptyList(),
    playbackSource: String = "All Songs",
    repeatMode: Int = 0,
    isShuffle: Boolean = false,
    canGoPrevious: Boolean = true,
    canGoNext: Boolean = true,
    onTrackSelect: (Int, AudioItem) -> Unit = { _, _ -> },
    onSeekBy: ((Long) -> Unit)? = null,
    onToggleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onRemoveFromQueue: (Long) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onDeleteTrack: (AudioItem) -> Unit = {},
    onSetVolumeBoost: (Int) -> Unit = {},
    currentVolumeBoost: Int = 100,
    onPlayNext: (AudioItem) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onGoToAlbum: (String) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleLike: () -> Unit = {},
    expansionProgress: () -> Float = { 1f },
    // Surface colour resolved by the host (the same value the mini card wears), so the
    // background never changes hue while the player grows.
    topTint: Color? = null,
    volumeBarVisibility: () -> Float = { 1f },
    volumeBarInteractionEnabled: () -> Boolean = { true },
    collapseControlAlpha: () -> Float = { 1f },
    onArtTargetChange: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onArtModelChange: (AudioItem) -> Unit = {},
    onDragBlockedChange: (Boolean) -> Unit = {},
    onVolumeBarBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
    onVolumeBarLocalBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit = {}
) {
    val context = LocalContext.current
    val prefsManager = remember { com.jk.mediaplayer.data.PreferencesManager(context) }
    var showLyrics by remember { mutableStateOf(false) }
    val isLiked = isFavorite
    var isDisliked by remember { mutableStateOf(false) }
    var isQueueExpanded by remember { mutableStateOf(false) }
    // 0 = sheet, 1 = queue. Driven by an Animatable so the queue slides with the same feel as the
    // mini/full player morph instead of a generic fade.
    val queueProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val queueDrag = remember { androidx.compose.animation.core.Animatable(0f) }
    val queueScope = rememberCoroutineScope()
    // Hoisted so both the drag handle and the long-press menu can scroll the queue after a move.
    val queueListState = rememberLazyListState()
    LaunchedEffect(isQueueExpanded) {
        if (isQueueExpanded) {
            queueDrag.snapTo(0f)
            queueProgress.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else {
            queueProgress.animateTo(0f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            queueDrag.snapTo(0f)
        }
    }
    var menuTargetTrack by remember { mutableStateOf<Pair<Int, AudioItem>?>(null) }
    var detailsTrack by remember { mutableStateOf<AudioItem?>(null) }
    var tagEditorTrack by remember { mutableStateOf<AudioItem?>(null) }
    var ringtoneTrack by remember { mutableStateOf<AudioItem?>(null) }
    var blacklistConfirmTrack by remember { mutableStateOf<AudioItem?>(null) }

    val effectivePlaylist = remember(playlist, track.id) {
        if (playlist.isNotEmpty() && playlist.any { it.id == track.id }) playlist
        else if (playlist.isNotEmpty()) playlist + track
        else listOf(track)
    }

    // Fallback slide direction. The real direction is derived from the pager below, so a swipe
    // and a skip button always animate the title/artist the same way.
    var transitionDirection by remember { mutableIntStateOf(1) }

    // Intercept back button to collapse queue view first
    BackHandler(enabled = isQueueExpanded) {
        isQueueExpanded = false
    }

    // The queue and the lyrics views own vertical drags of their own, so the player
    // expand/collapse drag is paused while either of them is on screen.
    LaunchedEffect(isQueueExpanded, showLyrics) {
        onDragBlockedChange(isQueueExpanded || showLyrics)
    }

    // Online Metadata (Synchronized Lyrics and HD Art) - with smart caching
    var onlineArtUrl by remember(track.id) { mutableStateOf<String?>(track.onlineArtUrl) }
    var fetchedLyrics by remember(track.id) { mutableStateOf<List<LyricsLine>>(emptyList()) }
    var isLoadingLyrics by remember(track.id) { mutableStateOf(false) }
    var lyricsLoaded by remember(track.id) { mutableStateOf(false) }

    LaunchedEffect(track.id) {
        // A track that already carries artwork keeps it. YouTube Music results arrive with a
        // real thumbnail, and an iTunes lookup keyed on the channel name used to overwrite it
        // with null, which is why the big player showed the default cover.
        if (onlineArtUrl.isNullOrEmpty() && !AlbumArtColorExtractor.hasValidLocalAlbumArt(context, track)) {
            val cached = OnlineMetadataFetcher.getCachedAlbumArtUrl(context, track.artist, track.album, track.title)
            if (cached != null) {
                onlineArtUrl = cached
            } else if (!OnlineMetadataFetcher.hasAlbumArtChecked(context, track.artist, track.album, track.title)) {
                onlineArtUrl = OnlineMetadataFetcher.fetchAlbumArtUrl(context, track.artist, track.album, track.title)
            }
        }
    }

    // Lyrics are fetched only when the user opens the lyrics view. Local files and YouTube Music
    // tracks use the same path, and a resolved result (even an empty one) is never asked for twice.
    LaunchedEffect(track.id, showLyrics) {
        if (!showLyrics || lyricsLoaded) return@LaunchedEffect
        val cached = OnlineMetadataFetcher.getCachedLyrics(context, track.artist, track.title)
        if (cached != null) {
            fetchedLyrics = cached
            lyricsLoaded = true
            return@LaunchedEffect
        }
        isLoadingLyrics = true
        fetchedLyrics = OnlineMetadataFetcher.fetchLyrics(
            context,
            track.artist,
            track.title,
            track.duration
        )
        isLoadingLyrics = false
        lyricsLoaded = true
    }

    // Dynamic ambient background gradient extracted directly from the actual album art (100% OPAQUE)
    var dominantColor by remember(track.id) {
        mutableStateOf(AlbumArtColorExtractor.getInitialDominantColor(context, track, onlineArtUrl))
    }
    LaunchedEffect(track.id, onlineArtUrl) {
        val extracted = AlbumArtColorExtractor.extractDominantColor(context, track, onlineArtUrl)
        if (extracted != dominantColor) {
            dominantColor = extracted
        }
    }
    // Kept as a State (not delegated) so the animated tone can be read in the draw phase only.
    val animatedDominantColorState = animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "dominantColor"
    )

    var showRingtoneCutter by remember { mutableStateOf(false) }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val playerTextColorPrimary = if (isDark) Color.White else Color(0xFF141416)
    val playerTextColorSecondary = if (isDark) Color(0xFFAAAAAA) else Color(0xFF6B6B76)
    val playerIconTint = if (isDark) Color.White else Color(0xFF141416)

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(isDark) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    window.isStatusBarContrastEnforced = false
                    @Suppress("DEPRECATION")
                    window.isNavigationBarContrastEnforced = false
                }
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                MiuiDarkModeHelper.apply(window, isDark)
            }
            onDispose {}
        }
    }

    // System Media Volume Control & Output Detection
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }
    var currentVolume by remember {
        mutableIntStateOf(audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxVolume / 2))
    }
    var audioOutputType by remember { mutableStateOf(AudioOutputType.SPEAKER) }
    LaunchedEffect(Unit) {
        val detected = detectAudioOutputType(audioManager)
        if (detected != audioOutputType) {
            audioOutputType = detected
        }
    }
    val boostVersion = PreferencesManager.musicVolumeBoostVersion
    val isBoostEnabled = remember(boostVersion) { prefsManager.isMusicVolumeBoostEnabled() }

    val initialPercent = remember(isBoostEnabled) {
        if (isBoostEnabled && currentVolumeBoost > 100) {
            currentVolumeBoost
        } else {
            if (maxVolume > 0) ((currentVolume.toFloat() / maxVolume.toFloat()) * 100).roundToInt().coerceIn(0, 100) else 0
        }
    }
    var localVolumePercent by remember { mutableIntStateOf(initialPercent) }
    var shouldAnimateVolumeChange by remember { mutableStateOf(false) }
    var lastUserVolumeInteractionTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(shouldAnimateVolumeChange) {
        if (shouldAnimateVolumeChange) {
            kotlinx.coroutines.delay(350L)
            shouldAnimateVolumeChange = false
        }
    }

    LaunchedEffect(currentVolumeBoost, isBoostEnabled) {
        if (isBoostEnabled && currentVolumeBoost > 100) {
            localVolumePercent = currentVolumeBoost
        } else if (!isBoostEnabled && localVolumePercent > 100) {
            localVolumePercent = 100
            onSetVolumeBoost(100)
        }
    }

    DisposableEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val updateVolumeAndDevice = { isRouteChange: Boolean ->
            val newVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: currentVolume
            val newType = detectAudioOutputType(audioManager)
            val typeChanged = newType != audioOutputType
            currentVolume = newVol
            audioOutputType = newType
            if (isRouteChange || typeChanged) {
                shouldAnimateVolumeChange = true
            }
            val isUserInteracting = System.currentTimeMillis() - lastUserVolumeInteractionTime < 800L
            if (!isUserInteracting) {
                if (newVol < maxVolume && currentVolumeBoost > 100) {
                    onSetVolumeBoost(100)
                    localVolumePercent = if (maxVolume > 0) ((newVol.toFloat() / maxVolume.toFloat()) * 100).roundToInt() else 0
                } else if (currentVolumeBoost <= 100) {
                    localVolumePercent = if (maxVolume > 0) ((newVol.toFloat() / maxVolume.toFloat()) * 100).roundToInt() else 0
                }
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action
                val isRoute = action != "android.media.VOLUME_CHANGED_ACTION"
                mainHandler.postDelayed({ updateVolumeAndDevice(isRoute) }, 100)
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (_: Exception) {}

        var deviceCallback: AudioDeviceCallback? = null
        if (audioManager != null) {
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    mainHandler.postDelayed({ updateVolumeAndDevice(true) }, 150)
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    mainHandler.postDelayed({ updateVolumeAndDevice(true) }, 150)
                }
            }
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
            if (deviceCallback != null) {
                try {
                    audioManager?.unregisterAudioDeviceCallback(deviceCallback)
                } catch (_: Exception) {}
            }
        }
    }

    // Interactive Real-Time Gesture Physics for Sheet Dragging (100% Solid Opaque Surface)
    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    fun dismissSmoothly() {
        onDismiss()
    }

    var sheetRootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var artSlotCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lastReportedArtRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    LaunchedEffect(track.id, onlineArtUrl) {
        onArtModelChange(track.copy(onlineArtUrl = onlineArtUrl))
    }
    // The host draws the morphing hero, so the sheet reports the real cover square in
    // sheet-root coordinates. The window translation cancels out, so the rect stays stable
    // while the sheet slides.
    fun reportArtRect() {
        val root = sheetRootCoords ?: return
        val slot = artSlotCoords ?: return
        if (!root.isAttached || !slot.isAttached) return
        val twelve = with(density) { 12.dp.toPx() }
        val eight = with(density) { 8.dp.toPx() }
        val minArt = with(density) { 48.dp.toPx() }
        val topLeft = root.localPositionOf(
            slot,
            androidx.compose.ui.geometry.Offset.Zero
        )
        val artPx = minOf(
            slot.size.width.toFloat() - twelve,
            slot.size.height.toFloat() - eight
        ).coerceAtLeast(minArt)
        val left = topLeft.x + (slot.size.width - artPx) / 2f
        val top = topLeft.y + (slot.size.height - artPx) / 2f
        val rect = androidx.compose.ui.geometry.Rect(left, top, left + artPx, top + artPx)
        if (lastReportedArtRect != rect) {
            lastReportedArtRect = rect
            onArtTargetChange(rect)
        }
    }
    val ambientStops = remember(animatedDominantColorState.value, isDark, topTint) {
        ambientGradientColorStops(
            animatedDominantColorState.value,
            isDark,
            topColor = topTint ?: albumSurfaceTint(
                animatedDominantColorState.value,
                isDark
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .onGloballyPositioned {
                sheetRootCoords = it
                reportArtRect()
            }
            .drawBehind {
                // One opaque gradient fills the unified window; its top tint matches the mini
                // content and window background exactly.
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = ambientStops
                    )
                )
            }
    ) {
        // NORMAL FULLSCREEN PLAYER VIEW
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 12.dp)
        ) {
            var showPlayerDropdown by remember { mutableStateOf(false) }
            val settingsVersion = PreferencesManager.settingsVersion
            val showPlayerVolumeBar = remember(settingsVersion) { prefsManager.isShowPlayerVolumeBar() }

            // Top Navigation Bar (Down Chevron, Slim Volume Slider + Info, 3-Dot Menu)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .graphicsLayer {
                        val sharedControlsAlpha = if (showPlayerVolumeBar) {
                            volumeBarVisibility()
                        } else {
                            collapseControlAlpha()
                        }
                        alpha = ((expansionProgress() - 0.28f) / 0.22f)
                            .coerceIn(0f, 1f) * sharedControlsAlpha
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { dismissSmoothly() },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = playerIconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }

                if (showPlayerVolumeBar) {
                    val maxPercent = if (isBoostEnabled) 200 else 100
                    val isBoostActive = isBoostEnabled && localVolumePercent > 100

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 6.dp)
                            .onGloballyPositioned { coordinates ->
                                // Reported in window coordinates so the player drag handler can skip
                                // touches that start on the slider.
                                onVolumeBarBoundsChange(coordinates.boundsInWindow())
                            }
                    ) {
                        CustomSlimVolumeBar(
                            currentPercent = localVolumePercent,
                            maxPercent = maxPercent,
                            onPercentChange = { newPercent ->
                                lastUserVolumeInteractionTime = System.currentTimeMillis()
                                localVolumePercent = newPercent
                                if (isBoostEnabled) {
                                    if (newPercent <= 100) {
                                        val newSysVol = ((newPercent / 100f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                        if (newSysVol != currentVolume) {
                                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newSysVol, 0)
                                            currentVolume = newSysVol
                                        }
                                        if (currentVolumeBoost != 100) {
                                            onSetVolumeBoost(100)
                                        }
                                    } else {
                                        if (currentVolume != maxVolume) {
                                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                                            currentVolume = maxVolume
                                        }
                                        onSetVolumeBoost(newPercent)
                                    }
                                } else {
                                    val newSysVol = ((newPercent / 100f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                    if (newSysVol != currentVolume) {
                                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newSysVol, 0)
                                        currentVolume = newSysVol
                                    }
                                    if (currentVolumeBoost != 100) {
                                        onSetVolumeBoost(100)
                                    }
                                }
                            },
                            isDark = isDark,
                            isBoostActive = isBoostActive,
                            animateChanges = shouldAnimateVolumeChange,
                            isInteractionEnabled = { volumeBarInteractionEnabled() },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(20.dp)
                                .onGloballyPositioned { coordinates ->
                                    val root = sheetRootCoords
                                    if (root != null && root.isAttached && coordinates.isAttached) {
                                        val origin = root.localPositionOf(
                                            coordinates,
                                            androidx.compose.ui.geometry.Offset.Zero
                                        )
                                        onVolumeBarLocalBoundsChange(
                                            androidx.compose.ui.geometry.Rect(
                                                origin.x,
                                                origin.y,
                                                origin.x + coordinates.size.width,
                                                origin.y + coordinates.size.height
                                            )
                                        )
                                    }
                                }
                        )

                        val volIcon = when {
                            isBoostActive -> Icons.Default.Bolt
                            audioOutputType == AudioOutputType.BLUETOOTH -> Icons.Default.BluetoothAudio
                            audioOutputType == AudioOutputType.HEADPHONES -> Icons.Default.Headphones
                            localVolumePercent == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                            localVolumePercent < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        }
                        val volTint = if (isBoostActive) Color(0xFFFA2D48) else (if (isDark) Color.White else Color(0xFF141416))
                        val volLabelPrefix = when {
                            isBoostActive -> "⚡ Boost •"
                            audioOutputType == AudioOutputType.BLUETOOTH -> "Bluetooth •"
                            audioOutputType == AudioOutputType.HEADPHONES -> "Headphones •"
                            else -> "Volume •"
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = volIcon,
                                contentDescription = "Volume",
                                tint = volTint,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            // The label and the number are separate runs. The number keeps tabular
                            // figures, so every digit is the same width and the centred row no longer
                            // slides sideways while the user drags the bar. The weight stays the same
                            // in boost mode too: the red tint already marks the boost, and switching
                            // to Bold would swap the static font file and jump the glyph widths.
                            Text(
                                text = volLabelPrefix,
                                color = volTint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "$localVolumePercent%",
                                color = volTint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES),
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Box {
                    IconButton(
                        onClick = { showPlayerDropdown = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = playerIconTint.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showPlayerDropdown,
                        onDismissRequest = { showPlayerDropdown = false },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Set as Ringtone", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                showRingtoneCutter = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Equalizer", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                (context as? android.app.Activity)?.let { act ->
                                    com.jk.mediaplayer.player.AudioSession.openSystemEqualizer(act) {
                                        AppToast.show("No system equalizer app found. Install Wavelet or Poweramp Equalizer to apply DSP audio effects.", 3000L)
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                shareTrack(context, track)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Go to album", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.Album, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                onGoToAlbum(track.album)
                                dismissSmoothly()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Go to artist", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                onGoToArtist(track.artist)
                                dismissSmoothly()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Tags", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                tagEditorTrack = track
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Details", color = playerTextColorPrimary) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = playerTextColorPrimary) },
                            onClick = {
                                showPlayerDropdown = false
                                detailsTrack = track
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Blacklist song", color = Color(0xFFFF5252)) },
                            leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = Color(0xFFFF5252)) },
                            onClick = {
                                showPlayerDropdown = false
                                blacklistConfirmTrack = track
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete track", color = Color(0xFFFF5252)) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFFF5252)) },
                            onClick = {
                                showPlayerDropdown = false
                                onDeleteTrack(track)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Body: Full-Bleed Artwork with Horizontal Pager Carousel or Lyrics
            if (showLyrics) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp)
                ) {
                    KaraokeLyricsView(
                        lyrics = fetchedLyrics,
                        isLoading = isLoadingLyrics,
                        playbackTime = playbackTime,
                        onSeekTo = onSeekTo,
                        isDark = isDark
                    )
                }
            } else {
                var seekFeedbackText by remember { mutableStateOf<String?>(null) }
                var seekFeedbackIsLeft by remember { mutableStateOf(true) }

                LaunchedEffect(seekFeedbackText) {
                    if (seekFeedbackText != null) {
                        kotlinx.coroutines.delay(650)
                        seekFeedbackText = null
                    }
                }

                val currentTrackIndex = remember(effectivePlaylist, track.id) {
                    val idx = effectivePlaylist.indexOfFirst { it.id == track.id }
                    if (idx != -1) idx else 0
                }
                val wrapsQueue = effectivePlaylist.size > 1
                val pageOffset = if (wrapsQueue) 1 else 0
                val pagerPageCount = effectivePlaylist.size + if (wrapsQueue) 2 else 0
                fun trackIndexForPage(page: Int): Int = when {
                    !wrapsQueue -> page
                    page == 0 -> effectivePlaylist.lastIndex
                    page == pagerPageCount - 1 -> 0
                    else -> page - 1
                }

                val pagerState = rememberPagerState(
                    initialPage = (currentTrackIndex + pageOffset).coerceIn(0, pagerPageCount - 1),
                    pageCount = { pagerPageCount }
                )
                val currentTrackId by rememberUpdatedState(track.id)
                var previousWrapsQueue by remember { mutableStateOf(wrapsQueue) }
                var isFirstPagerComposition by remember { mutableStateOf(true) }

                // Keep the artwork pager aligned with the actually playing track
                // (next / previous, notification, auto-advance, queue edits).
                // Programmatic pager moves (queue tap, skip buttons, notification) must never be
                // mistaken for a user swipe, otherwise one long jump replays every page it passes.
                var programmaticPagerMove by remember { mutableStateOf(false) }

                LaunchedEffect(track.id, effectivePlaylist, wrapsQueue) {
                    val targetIdx = effectivePlaylist.indexOfFirst { it.id == track.id }
                    if (targetIdx < 0) return@LaunchedEffect
                    val targetPage = targetIdx + pageOffset
                    val wrapModeChanged = previousWrapsQueue != wrapsQueue
                    previousWrapsQueue = wrapsQueue

                    if (isFirstPagerComposition) {
                        isFirstPagerComposition = false
                        if (pagerState.currentPage != targetPage) {
                            pagerState.scrollToPage(targetPage)
                        }
                        return@LaunchedEffect
                    }

                    when {
                        // Playlist shrank or repeat mode changed the pager's sentinel pages.
                        pagerState.currentPage >= pagerPageCount || wrapModeChanged -> {
                            programmaticPagerMove = true
                            try {
                                pagerState.scrollToPage(targetPage)
                            } finally {
                                programmaticPagerMove = false
                            }
                        }
                        targetPage != pagerState.currentPage && !pagerState.isScrollInProgress -> {
                            transitionDirection = if (targetPage > pagerState.currentPage) 1 else -1
                            programmaticPagerMove = true
                            try {
                                pagerState.scrollToPage(targetPage)
                            } finally {
                                programmaticPagerMove = false
                            }
                        }
                    }
                }

                // Simple model: the pager page the user lands on IS the song. No settle wait and no
                // debounce, so the artwork, the title and the audio all move in the same frame.
                LaunchedEffect(pagerState, effectivePlaylist, wrapsQueue) {
                    var previousPage = pagerState.currentPage
                    snapshotFlow { pagerState.settledPage to programmaticPagerMove }
                        .distinctUntilChanged()
                        .collect { (page, isProgrammaticMove) ->
                            // Programmatic jumps (next/prev, queue tap, notification) already
                            // selected their track; never replay the pages they pass over.
                            if (isProgrammaticMove) return@collect
                            val targetIndex = trackIndexForPage(page)
                            val target = effectivePlaylist.getOrNull(targetIndex) ?: return@collect
                            val forward = page >= previousPage
                            previousPage = page
                            if (target.id == currentTrackId) return@collect
                            transitionDirection = if (forward) 1 else -1
                            val normalizedPage = targetIndex + pageOffset
                            if (normalizedPage != page) {
                                programmaticPagerMove = true
                                try {
                                    pagerState.scrollToPage(normalizedPage)
                                    previousPage = normalizedPage
                                    onTrackSelect(targetIndex, target)
                                } finally {
                                    programmaticPagerMove = false
                                }
                            } else {
                                onTrackSelect(targetIndex, target)
                            }
                        }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .onGloballyPositioned { slot ->
                            artSlotCoords = slot
                            reportArtRect()
                        }
                        .graphicsLayer {
                            alpha = if (expansionProgress() >= 0.995f) 1f else 0f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val safeTotal = if (totalDurationMs > 0) totalDurationMs else track.duration
                    val artSize = minOf(maxWidth - 12.dp, maxHeight - 8.dp).coerceAtLeast(48.dp)
                    val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }

                    // Detect overscroll swipes past the first/last track when repeat is off and
                    // surface the same queue-boundary toast without triggering a 3s restart.
                    val edgeSwipeConnection = remember(pagerState, pagerPageCount, wrapsQueue, swipeThresholdPx) {
                        object : NestedScrollConnection {
                            private var accumulatedX = 0f
                            private var toasted = false

                            override fun onPostScroll(
                                consumed: androidx.compose.ui.geometry.Offset,
                                available: androidx.compose.ui.geometry.Offset,
                                source: NestedScrollSource
                            ): androidx.compose.ui.geometry.Offset {
                                if (source == NestedScrollSource.UserInput && !wrapsQueue && available.x != 0f) {
                                    accumulatedX += available.x
                                    if (!toasted) {
                                        if (pagerState.currentPage == 0 && accumulatedX > swipeThresholdPx) {
                                            toasted = true
                                            AppToast.show("No previous song in queue")
                                        } else if (pagerState.currentPage == pagerPageCount - 1 && accumulatedX < -swipeThresholdPx) {
                                            toasted = true
                                            AppToast.show("No next song in queue")
                                        }
                                    }
                                }
                                return androidx.compose.ui.geometry.Offset.Zero
                            }

                            override suspend fun onPreFling(
                                available: androidx.compose.ui.unit.Velocity
                            ): androidx.compose.ui.unit.Velocity {
                                accumulatedX = 0f
                                toasted = false
                                return androidx.compose.ui.unit.Velocity.Zero
                            }
                        }
                    }

                    @Suppress("DEPRECATION")
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(0.dp),
                        pageSpacing = 16.dp,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = effectivePlaylist.size > 1,
                        key = { page ->
                            val index = trackIndexForPage(page)
                            val sentinel = wrapsQueue && (page == 0 || page == pagerPageCount - 1)
                            "${effectivePlaylist.getOrNull(index)?.id ?: "page"}_$index${if (sentinel) "_wrap$page" else ""}"
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(edgeSwipeConnection)
                            .then(
                                if (effectivePlaylist.size <= 1) {
                                    Modifier.pointerInput(effectivePlaylist.size, repeatMode, swipeThresholdPx) {
                                        var totalDragX = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { totalDragX = 0f },
                                            onDragCancel = { totalDragX = 0f },
                                            onHorizontalDrag = { _, dragAmount ->
                                                totalDragX += dragAmount
                                            },
                                            onDragEnd = {
                                                // Album art swipe is an explicit track-change gesture,
                                                // so it bypasses the 3s restart rule on single-song queues.
                                                if (totalDragX < -swipeThresholdPx) {
                                                    if (repeatMode != 0) onSeekTo(0L)
                                                    else AppToast.show("No next song in queue")
                                                } else if (totalDragX > swipeThresholdPx) {
                                                    if (repeatMode != 0) onSeekTo(0L)
                                                    else AppToast.show("No previous song in queue")
                                                }
                                                totalDragX = 0f
                                            }
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) { pageIndex ->
                        val pageTrack = effectivePlaylist.getOrNull(trackIndexForPage(pageIndex)) ?: track
                        val isCurrentPage = pageIndex == pagerState.currentPage
                        // Every page resolves its own artwork. Before this, neighbour pages used
                        // the currently playing track's online art, so a swipe could show the
                        // wrong cover for a moment.
                        var pageOnlineArt by remember(pageTrack.id, pageTrack.albumArtUri, pageTrack.onlineArtUrl) {
                            mutableStateOf(pageTrack.onlineArtUrl)
                        }
                        LaunchedEffect(pageTrack.id, pageTrack.albumArtUri, pageTrack.onlineArtUrl) {
                            pageOnlineArt = when {
                                !pageTrack.onlineArtUrl.isNullOrBlank() -> pageTrack.onlineArtUrl
                                pageTrack.id == track.id && !onlineArtUrl.isNullOrBlank() -> onlineArtUrl
                                AlbumArtColorExtractor.hasValidLocalAlbumArt(context, pageTrack) -> null
                                else -> {
                                    val cached = OnlineMetadataFetcher.getCachedAlbumArtUrl(context, pageTrack.artist, pageTrack.album, pageTrack.title)
                                    cached ?: OnlineMetadataFetcher.fetchAlbumArtUrl(context, pageTrack.artist, pageTrack.album, pageTrack.title)
                                }
                            }
                        }
                        val pageArtRequest = remember(pageTrack.id, pageTrack.albumArtUri, pageOnlineArt) {
                            ImageRequest.Builder(context)
                                .data(if (pageOnlineArt != null) pageTrack.copy(onlineArtUrl = pageOnlineArt) else pageTrack)
                                .crossfade(false)
                                .error(R.drawable.ic_default_album_art)
                                .fallback(R.drawable.ic_default_album_art)
                                .build()
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(artSize)
                                    .shadow(
                                        elevation = 10.dp,
                                        shape = RoundedCornerShape(22.dp),
                                        ambientColor = Color.Transparent,
                                        spotColor = Color.Black.copy(alpha = 0.40f)
                                    )
                                    .clip(RoundedCornerShape(22.dp))
                                    .pointerInput(pageTrack.id) {
                                        detectTapGestures(
                                            onDoubleTap = { tapOffset ->
                                                // Read the position only when the gesture happens, so pager
                                                // pages are not recomposed on every playback tick.
                                                val position = playbackTime()
                                                val isLeft = tapOffset.x < size.width / 2f
                                                if (isLeft) {
                                                    if (onSeekBy != null) {
                                                        onSeekBy(-10000L)
                                                    } else {
                                                        onSeekTo((position - 10000L).coerceAtLeast(0L))
                                                    }
                                                    seekFeedbackIsLeft = true
                                                    seekFeedbackText = "-10s"
                                                } else {
                                                    if (onSeekBy != null) {
                                                        onSeekBy(10000L)
                                                    } else {
                                                        onSeekTo((position + 10000L).coerceAtMost(safeTotal))
                                                    }
                                                    seekFeedbackIsLeft = false
                                                    seekFeedbackText = "+10s"
                                                }
                                            }
                                        )
                                    }
                            ) {
                                SubcomposeAsyncImage(
                                    model = pageArtRequest,
                                    contentDescription = pageTrack.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    loading = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(if (isDark) Color(0xFF1E1E24) else Color(0xFFE8EAF0)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = if (isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF1A1C1E).copy(alpha = 0.7f),
                                                strokeWidth = 2.5.dp,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    },
                                    error = {
                                        Image(
                                            painter = painterResource(R.drawable.ic_default_album_art),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                )

                                // Visual Seek Badge Feedback Overlay
                                if (isCurrentPage && seekFeedbackText != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = if (seekFeedbackIsLeft) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = Color.Black.copy(alpha = 0.70f),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = seekFeedbackText ?: "",
                                                    color = Color.White,
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
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

            Spacer(modifier = Modifier.height(14.dp))

            // Title & Artist Info with Animated Slide/Fade on song change (Direction Aware!)
            AnimatedContent(
                targetState = track,
                contentKey = { it.id },
                modifier = Modifier.graphicsLayer {
                    // Details fade in only after the artwork has started growing.
                    alpha = ((expansionProgress() - 0.3f) / 0.45f).coerceIn(0f, 1f)
                },
                transitionSpec = {
                    if (initialState.id == targetState.id) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // Prefer the queue order: it is correct on the very first frame, even for a
                        // pager swipe (the state-based hint updates a frame later).
                        val fromIdx = playlist.indexOfFirst { it.id == initialState.id }
                        val toIdx = playlist.indexOfFirst { it.id == targetState.id }
                        val forward = if (fromIdx >= 0 && toIdx >= 0) toIdx >= fromIdx
                        else transitionDirection >= 0
                        if (forward) {
                            (slideInHorizontally(animationSpec = tween(240)) { width -> width } + fadeIn(tween(240)))
                                .togetherWith(slideOutHorizontally(animationSpec = tween(220)) { width -> -width } + fadeOut(tween(220)))
                        } else {
                            (slideInHorizontally(animationSpec = tween(240)) { width -> -width } + fadeIn(tween(240)))
                                .togetherWith(slideOutHorizontally(animationSpec = tween(220)) { width -> width } + fadeOut(tween(220)))
                        }
                    }
                },
                label = "trackTitleAnimation"
            ) { targetTrack ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        // Swallow taps on the empty title space so nothing underneath can
                        // interpret them as a track change or a menu request. Artist links stay
                        // tappable because child handlers run first.
                        .pointerInput(targetTrack.id) { detectTapGestures { } },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val cleanTitle = targetTrack.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a")
                    Text(
                        text = cleanTitle,
                        color = playerTextColorPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val isTargetArtistUnknown = targetTrack.artist == "<unknown>" || targetTrack.artist.isBlank() || targetTrack.artist.equals("Unknown Artist", ignoreCase = true)
                    val cachedArtist = if (isTargetArtistUnknown) OnlineMetadataFetcher.getCachedArtistName(context, targetTrack.title) else null
                    val cleanArtist = if (isTargetArtistUnknown) (cachedArtist ?: "Unknown Artist") else targetTrack.artist
                    // Render a single artist as one clean marquee line; split into tappable
                    // credits only when multiple collaborators exist.
                    val artistCredits = remember(cleanArtist) {
                        splitArtistNames(cleanArtist).ifEmpty { listOf(cleanArtist) }
                    }
                    if (artistCredits.size <= 1) {
                        val singleArtist = artistCredits.firstOrNull() ?: cleanArtist
                        Text(
                            text = singleArtist,
                            color = playerTextColorSecondary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !isTargetArtistUnknown) {
                                    onGoToArtist(singleArtist)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .wrapContentWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            artistCredits.forEachIndexed { creditIndex, creditName ->
                                if (creditIndex > 0) {
                                    Text(
                                        text = "•",
                                        color = playerTextColorSecondary,
                                        fontSize = 15.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                }
                                Text(
                                    text = creditName,
                                    color = playerTextColorSecondary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !isTargetArtistUnknown) {
                                            onGoToArtist(creditName)
                                        }
                                        .padding(horizontal = 2.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Pills - CENTERED & HORIZONTALLY SCROLLABLE FOR EXTENSIONS!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .graphicsLayer {
                        alpha = ((expansionProgress() - 0.35f) / 0.45f).coerceIn(0f, 1f)
                    }
                    .padding(vertical = 2.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pillBg = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0x10000000)
                val pillBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0x18000000)

                // Standalone Like Pill Button (Dislike & Separator completely removed)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isLiked) MusicRed.copy(alpha = 0.22f) else pillBg,
                    border = BorderStroke(1.dp, if (isLiked) MusicRed.copy(alpha = 0.45f) else pillBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            onToggleLike()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
                            contentDescription = "Like",
                            tint = if (isLiked) MusicRed else playerTextColorPrimary.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLiked) "Liked" else "Like",
                            color = if (isLiked) MusicRed else playerTextColorPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Lyrics Pill Toggle
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (showLyrics) (if (isDark) Color.White else Color(0xFF141416)) else pillBg,
                    border = BorderStroke(1.dp, pillBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showLyrics = !showLyrics }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) (if (isDark) Color.Black else Color.White) else playerTextColorPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Lyrics",
                            color = if (showLyrics) (if (isDark) Color.Black else Color.White) else playerTextColorPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Up Next Queue Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = pillBg,
                    border = BorderStroke(1.dp, pillBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { isQueueExpanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            tint = playerTextColorPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Queue",
                            color = playerTextColorPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Modern Sleek Progress Bar
            val safeTotal = if (totalDurationMs > 0) totalDurationMs else track.duration
            LeanProgressBar(
                playbackTime = playbackTime,
                totalDurationMs = safeTotal,
                onSeekTo = onSeekTo,
                isDark = isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Main Playback Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconSubtleTint = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffle) MusicRed else iconSubtleTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                SeekHoldButton(
                    onClick = {
                        transitionDirection = -1
                        onPrevious()
                    },
                    onSeekStep = {
                        if (onSeekBy != null) onSeekBy(-5000L) else onSeekTo((playbackTime() - 5000L).coerceAtLeast(0L))
                    },
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    iconSize = 36.dp,
                    enabled = true,
                    tint = playerIconTint
                )

                // Mathematically 100% Anti-Aliased Circular Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .drawBehind {
                            drawCircle(
                                color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                                radius = this.size.minDimension / 2f + 3.dp.toPx()
                            )
                            drawCircle(
                                color = if (isDark) Color.White else Color(0xFF141416),
                                radius = this.size.minDimension / 2f
                            )
                        }
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = if (isDark) Color.Black else Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                SeekHoldButton(
                    onClick = {
                        transitionDirection = 1
                        onNext()
                    },
                    onSeekStep = {
                        val maxTotal = if (totalDurationMs > 0) totalDurationMs else track.duration
                        if (onSeekBy != null) onSeekBy(5000L) else onSeekTo((playbackTime() + 5000L).coerceAtMost(maxTotal))
                    },
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    iconSize = 36.dp,
                    enabled = true,
                    tint = playerIconTint
                )

                IconButton(
                    onClick = onToggleRepeat,
                    modifier = Modifier.clip(CircleShape)
                ) {
                    Icon(
                        imageVector = when (repeatMode) {
                            2 -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (repeatMode > 0) MusicRed else iconSubtleTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom source indicator (Queue opens via [Queue] button above)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val currentIdx = effectivePlaylist.indexOfFirst { it.id == track.id }
                val queuePosition = if (currentIdx >= 0) "(${currentIdx + 1}/${effectivePlaylist.size})" else ""
                val resolvedSource = if (playbackSource.isNotBlank()) playbackSource else {
                    if (track.folderName.isNotBlank() && !track.folderName.equals("Music", ignoreCase = true) && !track.folderName.equals("Internal Storage", ignoreCase = true)) {
                        track.folderName
                    } else if (track.album.isNotBlank() && track.album != "Unknown Album") {
                        track.album
                    } else {
                        "All Songs"
                    }
                }
                val displayText = if (queuePosition.isNotEmpty()) "$resolvedSource • $queuePosition" else resolvedSource

                Text(
                    text = displayText,
                    color = if (isDark) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
            }
        }

        // UP NEXT QUEUE SLIDING OVERLAY
        // Always composed but parked offscreen while closed: the first frame of the open
        // animation then only translates a ready layout, instead of composing the whole list.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val queueFullHeightPx = constraints.maxHeight.toFloat()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = queueProgress.value.coerceIn(0f, 1f)
                    }
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            0,
                            ((1f - queueProgress.value) * queueFullHeightPx + queueDrag.value).toInt()
                        )
                    }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            queueScope.launch { queueDrag.snapTo(queueDrag.value + delta) }
                        },
                        onDragStopped = { velocity ->
                            val shouldClose = queueDrag.value > 140f || velocity > 650f
                            if (shouldClose) {
                                isQueueExpanded = false
                            } else {
                                queueScope.launch { queueDrag.animateTo(0f, spring()) }
                            }
                        }
                    )
                    .drawBehind {
                        drawRect(Brush.verticalGradient(queueGradientStops(animatedDominantColorState.value, isDark)))
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                // Queue Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { isQueueExpanded = false }) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse Queue",
                            tint = playerIconTint,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = {
                        val targetIndex = playlist.indexOfFirst { it.id == track.id }
                        menuTargetTrack = Pair(if (targetIndex >= 0) targetIndex else 0, track)
                    }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = playerIconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Header Track Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isDark) Color(0xFF262626) else Color(0xFFE5E7EB))
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(if (onlineArtUrl != null) track.copy(onlineArtUrl = onlineArtUrl) else track)
                                .crossfade(true)
                                .error(R.drawable.ic_default_album_art)
                                .placeholder(R.drawable.ic_default_album_art)
                                .fallback(R.drawable.ic_default_album_art)
                                .build(),
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = playerTextColorPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val isTrackArtistUnknown = track.artist == "<unknown>" || track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true)
                        val cachedTrackArtist = if (isTrackArtistUnknown) OnlineMetadataFetcher.getCachedArtistName(context, track.title) else null
                        val displayTrackArtist = if (isTrackArtistUnknown) (cachedTrackArtist ?: "Unknown Artist") else track.artist
                        Text(
                            text = displayTrackArtist,
                            color = playerTextColorSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Progress Bar
                val safeTotal = if (totalDurationMs > 0) totalDurationMs else track.duration
                LeanProgressBar(
                    playbackTime = playbackTime,
                    totalDurationMs = safeTotal,
                    onSeekTo = onSeekTo,
                    isDark = isDark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )

                // Compact Controls Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffle) MusicRed else playerIconTint.copy(alpha = 0.45f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = playerIconTint,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (isDark) Color.White else Color(0xFF141416),
                        modifier = Modifier
                            .size(52.dp)
                            .clickable(onClick = onTogglePlayPause)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = if (isDark) Color.Black else Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = playerIconTint,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onToggleRepeat, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = when (repeatMode) {
                                2 -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (repeatMode > 0) MusicRed else playerIconTint.copy(alpha = 0.45f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Collapse handle pill
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 6.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.25f))
                        .clickable { isQueueExpanded = false }
                )

                // "Playing from" Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Playing from",
                            color = playerTextColorSecondary,
                            fontSize = 13.sp
                        )
                        val currentIdx = effectivePlaylist.indexOfFirst { it.id == track.id }
                        val queuePosition = if (currentIdx >= 0) "(${currentIdx + 1}/${effectivePlaylist.size})" else ""
                        val resolvedSource = if (playbackSource.isNotBlank()) playbackSource else {
                            if (track.folderName.isNotBlank() && !track.folderName.equals("Music", ignoreCase = true) && !track.folderName.equals("Internal Storage", ignoreCase = true)) {
                                track.folderName
                            } else if (track.album.isNotBlank() && track.album != "Unknown Album") {
                                track.album
                            } else {
                                "All Songs"
                            }
                        }
                        val displayText = if (queuePosition.isNotEmpty()) "$resolvedSource • $queuePosition" else resolvedSource
                        Text(
                            text = displayText,
                            color = playerTextColorPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val currentTrackIndex = remember(playlist, track.id) {
                    val idx = playlist.indexOfFirst { it.id == track.id }
                    if (idx >= 0) idx else 0
                }

                // Initialized with currentTrackIndex so it is already positioned in the background
                // without any visible jumping animation when opening the queue.
                LaunchedEffect(track.id) {
                    val idx = playlist.indexOfFirst { it.id == track.id }
                    if (idx >= 0) {
                        queueListState.scrollToItem(idx)
                    }
                }

                // Queue songs list with continuous live drag-reordering on the 3-line handle.
                // Keys are derived from the track id plus its occurrence, so a move never recreates
                // every row (which used to reset the scroll position when moving the last item).
                var localQueue by remember { mutableStateOf(buildQueueEntries(playlist)) }
                var draggingEntryId by remember { mutableStateOf<String?>(null) }
                var dragStartIndex by remember { mutableStateOf<Int?>(null) }
                var currentDragIndex by remember { mutableStateOf<Int?>(null) }
                var dragOffsetY by remember { mutableFloatStateOf(0f) }
                val density = LocalDensity.current
                val itemHeightPx = with(density) { 60.dp.toPx() }
                val haptics = LocalHapticFeedback.current

                LaunchedEffect(playlist) {
                    if (draggingEntryId == null) {
                        localQueue = buildQueueEntries(playlist)
                    }
                }

                LazyColumn(
                    state = queueListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = localQueue,
                        key = { _, entry -> entry.entryId }
                    ) { index, entry ->
                        val queueTrack = entry.track
                        val isCurrent = queueTrack.id == track.id
                        val isBeingDragged = draggingEntryId == entry.entryId

                        val imageModel = remember(queueTrack.id, queueTrack.onlineArtUrl) {
                            ImageRequest.Builder(context)
                                .data(queueTrack)
                                .crossfade(false)
                                .error(R.drawable.ic_default_album_art)
                                .placeholder(R.drawable.ic_default_album_art)
                                .fallback(R.drawable.ic_default_album_art)
                                .build()
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = if (isBeingDragged) null else tween(220, easing = FastOutSlowInEasing)
                                )
                                .zIndex(if (isBeingDragged) 10f else 1f)
                                .graphicsLayer {
                                    if (isBeingDragged) {
                                        translationY = dragOffsetY
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                        shadowElevation = 10.dp.toPx()
                                    }
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isBeingDragged) {
                                        if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
                                    } else if (isCurrent) {
                                        if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (isCurrent) {
                                            isQueueExpanded = false
                                        } else {
                                            val currentIdx = localQueue.indexOfFirst { it.entryId == entry.entryId }.takeIf { it != -1 } ?: index
                                            onTrackSelect(currentIdx, queueTrack)
                                        }
                                    },
                                    onLongClick = {
                                        val activeIdx = localQueue.indexOfFirst { it.entryId == entry.entryId }.takeIf { it != -1 } ?: index
                                        menuTargetTrack = Pair(activeIdx, queueTrack)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDark) Color(0xFF262626) else Color(0xFFE5E7EB))
                            ) {
                                AsyncImage(
                                    model = imageModel,
                                    contentDescription = queueTrack.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                if (isCurrent) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AnimatedEqualizerIndicator(isPlaying = isPlaying, barColor = MusicRed)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = queueTrack.title,
                                    color = if (isCurrent) MusicRed else playerTextColorPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${queueTrack.artist} • ${formatDurationLean(queueTrack.duration)}",
                                    color = if (isCurrent) MusicRed.copy(alpha = 0.8f) else playerTextColorSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 3-line reorder drag handle & options menu
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val activeIdx = localQueue.indexOfFirst { it.entryId == entry.entryId }.takeIf { it != -1 } ?: index
                                        menuTargetTrack = Pair(activeIdx, queueTrack)
                                    }
                                    .pointerInput(entry.entryId) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggingEntryId = entry.entryId
                                                val sIdx = localQueue.indexOfFirst { it.entryId == entry.entryId }
                                                dragStartIndex = if (sIdx != -1) sIdx else index
                                                currentDragIndex = dragStartIndex
                                                dragOffsetY = 0f
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                val threshold = itemHeightPx * 0.5f
                                                var curIdx = currentDragIndex ?: dragStartIndex ?: index
                                                while (dragOffsetY > threshold && curIdx < localQueue.lastIndex) {
                                                    val updated = localQueue.toMutableList()
                                                    val item = updated.removeAt(curIdx)
                                                    updated.add(curIdx + 1, item)
                                                    localQueue = updated
                                                    curIdx += 1
                                                    currentDragIndex = curIdx
                                                    dragOffsetY -= itemHeightPx
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                while (dragOffsetY < -threshold && curIdx > 0) {
                                                    val updated = localQueue.toMutableList()
                                                    val item = updated.removeAt(curIdx)
                                                    updated.add(curIdx - 1, item)
                                                    localQueue = updated
                                                    curIdx -= 1
                                                    currentDragIndex = curIdx
                                                    dragOffsetY += itemHeightPx
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                val from = dragStartIndex
                                                val to = currentDragIndex
                                                if (from != null && to != null && from != to) {
                                                    onMoveQueueItem(from, to)
                                                    // Keep the moved row on screen; without this the list
                                                    // felt stuck when dragging the last queue item.
                                                    queueScope.launch {
                                                        queueListState.animateScrollToItem(to)
                                                    }
                                                }
                                                draggingEntryId = null
                                                dragStartIndex = null
                                                currentDragIndex = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                // The optimistic local order was never persisted, so
                                                // restore it from the real playlist before the next move.
                                                localQueue = buildQueueEntries(playlist)
                                                draggingEntryId = null
                                                dragStartIndex = null
                                                currentDragIndex = null
                                                dragOffsetY = 0f
                                            }
                                        )
                                    },
                                    contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Reorder track",
                                    tint = if (isBeingDragged) MusicRed else playerIconTint.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Queue Item Options Modal Bottom Sheet
        if (menuTargetTrack != null) {
            val (targetIndex, targetTrack) = menuTargetTrack!!
            ModalBottomSheet(
                onDismissRequest = { menuTargetTrack = null },
                containerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                contentColor = playerTextColorPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isDark) Color(0xFF2E2E2E) else Color(0xFFE5E7EB))
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(targetTrack)
                                    .crossfade(true)
                                    .error(R.drawable.ic_default_album_art)
                                    .placeholder(R.drawable.ic_default_album_art)
                                    .fallback(R.drawable.ic_default_album_art)
                                    .build(),
                                contentDescription = targetTrack.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = targetTrack.title,
                                color = playerTextColorPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = targetTrack.artist,
                                color = playerTextColorSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(6.dp))

                    // 1. Play next (the track that is already playing has nothing to queue after itself)
                    if (targetTrack.id != track.id) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPlayNext(targetTrack)
                                    menuTargetTrack = null
                                    AppToast.show("Playing next: '${targetTrack.title}'")
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Play next",
                                tint = playerIconTint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Play next", color = playerTextColorPrimary, fontSize = 17.sp)
                        }
                    }

                    // 2. Move Up in Queue
                    if (targetIndex > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMoveQueueItem(targetIndex, targetIndex - 1)
                                    menuTargetTrack = null
                                    queueScope.launch {
                                        queueListState.animateScrollToItem((targetIndex - 1).coerceAtLeast(0))
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Move Up",
                                tint = playerIconTint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Move Up in Queue", color = playerTextColorPrimary, fontSize = 17.sp)
                        }
                    }

                    // 3. Move Down in Queue
                    if (targetIndex < playlist.size - 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMoveQueueItem(targetIndex, targetIndex + 1)
                                    menuTargetTrack = null
                                    queueScope.launch {
                                        queueListState.animateScrollToItem(targetIndex + 1)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Move Down",
                                tint = playerIconTint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Move Down in Queue", color = playerTextColorPrimary, fontSize = 17.sp)
                        }
                    }

                    // 4. Go to artist
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                menuTargetTrack = null
                                onGoToArtist(targetTrack.artist)
                                dismissSmoothly()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Go to artist",
                            tint = playerIconTint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Go to artist", color = playerTextColorPrimary, fontSize = 17.sp)
                    }

                    // 5. Go to album
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                menuTargetTrack = null
                                onGoToAlbum(targetTrack.album)
                                dismissSmoothly()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = "Go to album",
                            tint = playerIconTint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Go to album", color = playerTextColorPrimary, fontSize = 17.sp)
                    }

                    // 6. Set as Ringtone
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ringtoneTrack = targetTrack
                                menuTargetTrack = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = "Set as Ringtone",
                            tint = playerIconTint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Set as Ringtone", color = playerTextColorPrimary, fontSize = 17.sp)
                    }

                    // 7. Share
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                menuTargetTrack = null
                                shareTrack(context, targetTrack)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = playerIconTint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Share", color = playerTextColorPrimary, fontSize = 17.sp)
                    }

                    // 8. Details
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                detailsTrack = targetTrack
                                menuTargetTrack = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Details",
                            tint = playerIconTint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Details", color = playerTextColorPrimary, fontSize = 17.sp)
                    }

                    // 9. Remove from Queue (the playing entry stays in its own queue)
                    if (targetTrack.id != track.id) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onRemoveFromQueue(targetTrack.id)
                                    menuTargetTrack = null
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove from Queue",
                                tint = playerIconTint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Remove from Queue", color = playerTextColorPrimary, fontSize = 17.sp)
                        }
                    }

                    // 10. Blacklist Song
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val target = targetTrack
                                menuTargetTrack = null
                                blacklistConfirmTrack = target
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = "Blacklist Song",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Blacklist Song", color = Color(0xFFFF5252), fontSize = 17.sp)
                    }

                    // 11. Delete from device
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                menuTargetTrack = null
                                onDeleteTrack(targetTrack)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete from device",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Delete from device", color = Color(0xFFFF5252), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // Details Dialog
        if (detailsTrack != null) {
            MusicTrackDetailsDialog(
                track = detailsTrack!!,
                folders = emptyList(),
                onDismiss = { detailsTrack = null },
                onGoToArtist = { art ->
                    detailsTrack = null
                    onGoToArtist(art)
                    dismissSmoothly()
                },
                onGoToAlbum = { alb ->
                    detailsTrack = null
                    onGoToAlbum(alb)
                    dismissSmoothly()
                },
                onGoToFolder = {},
                onOpenRingtoneCutter = { trk ->
                    detailsTrack = null
                    ringtoneTrack = trk
                },
                onDeleteTrack = { trk ->
                    detailsTrack = null
                    onDeleteTrack(trk)
                }
            )
        }

        // Professional Audio ID3 Tag Editor
        if (tagEditorTrack != null) {
            MusicTagEditorDialog(
                track = tagEditorTrack!!,
                onDismiss = { tagEditorTrack = null },
                onTagsSaved = { updatedTrack ->
                    AppToast.show("Tags updated successfully")
                }
            )
        }

        // Ringtone Cutter for selected track
        if (ringtoneTrack != null) {
            RingtoneCutterDialog(
                track = ringtoneTrack!!,
                onDismiss = { ringtoneTrack = null }
            )
        }

        // Blacklist Song Confirmation Dialog
        blacklistConfirmTrack?.let { trackToBlacklist ->
            com.jk.mediaplayer.ui.common.BlacklistConfirmDialog(
                title = "Blacklist Song?",
                itemName = trackToBlacklist.title,
                accentColor = MusicRed,
                onConfirm = {
                    prefsManager.addMusicBlacklistedFile(trackToBlacklist.path)
                    onBlacklistTrack(trackToBlacklist)
                    onRemoveFromQueue(trackToBlacklist.id)
                    if (trackToBlacklist.id == track.id) {
                        if (canGoNext) {
                            onNext()
                        } else {
                            dismissSmoothly()
                        }
                    }
                    AppToast.show("\"${trackToBlacklist.title}\" blacklisted and hidden from library")
                    blacklistConfirmTrack = null
                },
                onDismiss = { blacklistConfirmTrack = null }
            )
        }

    }

    if (showRingtoneCutter) {
        RingtoneCutterDialog(
            track = track,
            onDismiss = { showRingtoneCutter = false }
        )
    }
}

/**
 * Synchronized Karaoke-style Lyrics View
 */
@Composable
private fun KaraokeLyricsView(
    lyrics: List<LyricsLine>,
    isLoading: Boolean,
    playbackTime: () -> Long,
    onSeekTo: (Long) -> Unit,
    isDark: Boolean = true
) {
    val activeColor = if (isDark) Color.White else Color(0xFF111216)
    val inactiveColor = if (isDark) Color.White.copy(alpha = 0.38f) else Color(0xFF111216).copy(alpha = 0.38f)
    val secondaryText = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666677)

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MusicRed, modifier = Modifier.size(36.dp))
        }
        return
    }

    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No synchronized lyrics found.",
                color = secondaryText,
                fontSize = 17.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val positionProvider = rememberUpdatedState(playbackTime)
    val activeIndex by remember(lyrics) {
        derivedStateOf {
            val t = positionProvider.value()
            lyrics.indexOfLast { it.timeMs <= t }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex in lyrics.indices && !listState.isScrollInProgress) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 40.dp)
    ) {
        itemsIndexed(lyrics) { index, line ->
            val isActive = index == activeIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSeekTo(line.timeMs) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = line.text,
                    color = if (isActive) activeColor else inactiveColor,
                    fontSize = if (isActive) 22.sp else 17.sp,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Modern Smooth Progress Bar:
 * - 3.5dp sleek white / translucent track
 * - 11dp circular thumb
 * - Time format: 0:02 / 5:14 (single digit minute)
 */
@Composable
private fun LeanProgressBar(
    playbackTime: () -> Long,
    totalDurationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = true
) {
    val safeTotal = if (totalDurationMs > 0) totalDurationMs else 1L
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current

    // Read the position here (leaf scope) so only the progress bar redraws on player ticks.
    val currentTimeMs = playbackTime()
    val rawFraction = (currentTimeMs.toFloat() / safeTotal).coerceIn(0f, 1f)
    // No interpolation: the player already emits a position every 100 ms, which is
    // finer than the eye can resolve on a bar this wide.
    val displayFraction = if (isDragging) dragFraction else rawFraction

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                // Bigger invisible touch target (Material minimum is 48dp) while the bar stays slim
                .height(36.dp)
                .pointerInput(safeTotal) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragEnd = {
                            isDragging = false
                            onSeekTo((dragFraction * safeTotal).toLong())
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    )
                }
                .pointerInput(safeTotal) {
                    detectTapGestures { offset ->
                        val tapped = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeekTo((tapped * safeTotal).toLong())
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val thumbXPx = widthPx * displayFraction
            val inactiveColor = if (isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.12f)
            val activeColor = if (isDark) Color.White else Color(0xFF141416)

            // Fast Draw-Phase Rendering: draws track, active progress and thumb in a single Canvas draw pass,
            // bypassing Compose measure and layout passes on every 100ms playback tick!
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            ) {
                val barHeight = 3.5.dp.toPx()
                val centerY = size.height / 2f
                val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f, barHeight / 2f)

                // 1. Inactive background track
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(0f, centerY - barHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, barHeight),
                    cornerRadius = cornerRadius
                )

                // 2. Active played track
                if (thumbXPx > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, centerY - barHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(thumbXPx.coerceAtMost(size.width), barHeight),
                        cornerRadius = cornerRadius
                    )
                }

                // 3. Thumb circle
                drawCircle(
                    color = activeColor,
                    radius = 6.5.dp.toPx(),
                    center = Offset(thumbXPx.coerceIn(6.5.dp.toPx(), size.width - 6.5.dp.toPx()), centerY)
                )
            }
        }

        // Time labels: Left "0:02", Right "5:14" (single digit minute)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val elapsed = if (isDragging) (dragFraction * safeTotal).toLong() else currentTimeMs
            val timeColor = if (isDark) Color.White.copy(alpha = 0.65f) else Color(0xFF6B6B76)
            Text(
                text = formatDurationLean(elapsed),
                color = timeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES)
            )
            Text(
                text = formatDurationLean(safeTotal),
                color = timeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES)
            )
        }
    }
}

fun formatDurationLean(durationMs: Long): String {
    if (durationMs <= 0) return "0:00"
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Custom Slim Volume Bar:
 * - Slim 4.5dp height (slightly thicker than the 3.5dp seekbar)
 * - Pure white active track with subtle 7dp thumb
 * - Interactive horizontal drag and tap gestures
 */
@Composable
fun CustomSlimVolumeBar(
    currentPercent: Int,
    maxPercent: Int,
    onPercentChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    isBoostActive: Boolean = false,
    animateChanges: Boolean = false,
    isInteractionEnabled: () -> Boolean = { true }
) {
    val safeMax = if (maxPercent > 0) maxPercent else 100
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val rawTargetFraction = (currentPercent.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)

    val animatedFraction by animateFloatAsState(
        targetValue = rawTargetFraction,
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "volumeFraction"
    )

    val displayFraction = when {
        isDragging -> dragFraction
        animateChanges -> animatedFraction
        else -> rawTargetFraction
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(safeMax) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (!isInteractionEnabled()) return@detectHorizontalDragGestures
                        isDragging = true
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        dragFraction = frac
                        val newPct = (frac * safeMax).roundToInt().coerceIn(0, safeMax)
                        onPercentChange(newPct)
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        if (!isInteractionEnabled()) return@detectHorizontalDragGestures
                        change.consume()
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragFraction = frac
                        val newPct = (frac * safeMax).roundToInt().coerceIn(0, safeMax)
                        onPercentChange(newPct)
                    }
                )
            }
            .pointerInput(safeMax) {
                detectTapGestures { offset ->
                    if (!isInteractionEnabled()) return@detectTapGestures
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    val newPct = (frac * safeMax).roundToInt().coerceIn(0, safeMax)
                    onPercentChange(newPct)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val fillWidthPx = widthPx * displayFraction
        val inactiveColor = if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.12f)
        val barColor = if (isBoostActive) Color(0xFFFA2D48) else (if (isDark) Color.White else Color(0xFF141416))

        // Fast Draw-Phase Rendering for volume bar: zero layout recalculations
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val barHeight = 5.5.dp.toPx()
            val centerY = size.height / 2f
            val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f, barHeight / 2f)

            // 1. Inactive background track
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - barHeight / 2f),
                size = androidx.compose.ui.geometry.Size(size.width, barHeight),
                cornerRadius = cornerRadius
            )

            // 2. Active volume bar
            if (fillWidthPx > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(0f, centerY - barHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(fillWidthPx.coerceAtMost(size.width), barHeight),
                    cornerRadius = cornerRadius
                )
            }

            // 3. Thumb
            drawCircle(
                color = barColor,
                radius = 5.dp.toPx(),
                center = Offset(fillWidthPx.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), centerY)
            )
        }
    }
}

/**
 * SeekHoldButton:
 * - Single click: normal skip track
 * - Long press / Hold: continuous fast-forward or rewind every 160ms
 */
@Composable
fun SeekHoldButton(
    onClick: () -> Unit,
    onSeekStep: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    iconSize: Dp = 36.dp,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnSeekStep by rememberUpdatedState(onSeekStep)
    val haptics = LocalHapticFeedback.current
    // Android's own long-press timeout (~500ms). 260ms made slow taps seek instead of skipping.
    val holdTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
    var isPressed by remember { mutableStateOf(false) }
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (!isHolding) return@LaunchedEffect
        delay(holdTimeoutMs)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        while (isHolding) {
            // The controller clamps the target, so continuous holding simply stops at 0:00 / end
            currentOnSeekStep()
            delay(140)
        }
    }

    val resolvedTint = if (tint != Color.Unspecified) tint else LocalContentColor.current

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isPressed) resolvedTint.copy(alpha = 0.14f) else Color.Transparent)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isPressed = true
                    isHolding = true
                    val startTime = System.currentTimeMillis()
                    val upOrCancel = waitForUpOrCancellation()
                    upOrCancel?.consume()
                    isHolding = false
                    isPressed = false
                    val elapsed = System.currentTimeMillis() - startTime
                    if (upOrCancel != null && elapsed < holdTimeoutMs) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = resolvedTint.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Animated Equalizer Indicator:
 * 3 dynamic jumping vertical bars overlaid on playing song thumbnail
 */
@Composable
fun AnimatedEqualizerIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MusicRed
) {
    if (isPlaying) {
        AnimatedBars(modifier, barColor)
    } else {
        StaticBars(modifier, barColor)
    }
}

@Composable
private fun AnimatedBars(modifier: Modifier = Modifier, barColor: Color = MusicRed) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_bars")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(510, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq3"
    )

    // STATIC fixed-size container: NEVER fluctuates, shrinks, or grows! Zero recompositions (Draw phase only)
    Box(
        modifier = modifier
            .size(width = 22.dp, height = 22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 12.dp, height = 13.dp)) {
            val barW = 2.5.dp.toPx()
            val cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
            val maxH = size.height
            val spacing = 2.dp.toPx()

            // Bar 1
            val h1Px = maxH * h1
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(0f, maxH - h1Px),
                size = androidx.compose.ui.geometry.Size(barW, h1Px),
                cornerRadius = cornerRadius
            )
            // Bar 2
            val h2Px = maxH * h2
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(barW + spacing, maxH - h2Px),
                size = androidx.compose.ui.geometry.Size(barW, h2Px),
                cornerRadius = cornerRadius
            )
            // Bar 3
            val h3Px = maxH * h3
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset((barW + spacing) * 2, maxH - h3Px),
                size = androidx.compose.ui.geometry.Size(barW, h3Px),
                cornerRadius = cornerRadius
            )
        }
    }
}

@Composable
private fun StaticBars(modifier: Modifier = Modifier, barColor: Color = MusicRed) {
    Box(
        modifier = modifier
            .size(width = 22.dp, height = 22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(14.dp)
                .wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((13 * 0.45f).dp)
                    .background(barColor, RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((13 * 0.75f).dp)
                    .background(barColor, RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((13 * 0.35f).dp)
                    .background(barColor, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * Ambient player background gradient stops. Kept as a plain function so it can be evaluated
 * inside a draw block instead of during composition (which caused a recomposition per
 * animation frame while the artwork colour was animating).
 */
private fun ambientGradientColorStops(
    color: Color,
    isDark: Boolean,
    topColor: Color = albumSurfaceTint(color, isDark)
): Array<Pair<Float, Color>> {
    return if (isDark) {
        val lum = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
        val topFactor = if (lum > 0.35f) (0.35f / lum) else 1.0f
        val r = (color.red * topFactor).coerceIn(0f, 1f)
        val g = (color.green * topFactor).coerceIn(0f, 1f)
        val b = (color.blue * topFactor).coerceIn(0f, 1f)
        arrayOf(
            0.00f to topColor,
            0.20f to Color(
                red = (r * 0.48f + 0.015f).coerceIn(0f, 1f),
                green = (g * 0.48f + 0.015f).coerceIn(0f, 1f),
                blue = (b * 0.48f + 0.015f).coerceIn(0f, 1f)
            ),
            0.40f to Color(
                red = (r * 0.30f + 0.01f).coerceIn(0f, 1f),
                green = (g * 0.30f + 0.01f).coerceIn(0f, 1f),
                blue = (b * 0.30f + 0.01f).coerceIn(0f, 1f)
            ),
            0.60f to Color(
                red = (r * 0.16f + 0.006f).coerceIn(0f, 1f),
                green = (g * 0.16f + 0.006f).coerceIn(0f, 1f),
                blue = (b * 0.16f + 0.006f).coerceIn(0f, 1f)
            ),
            0.80f to Color(
                red = (r * 0.06f + 0.002f).coerceIn(0f, 1f),
                green = (g * 0.06f + 0.002f).coerceIn(0f, 1f),
                blue = (b * 0.06f + 0.002f).coerceIn(0f, 1f)
            ),
            1.00f to Color(0xFF000000)
        )
    } else {
        arrayOf(
            0.0f to topColor,
            0.35f to Color(
                red = (color.red * 0.16f + 0.84f).coerceIn(0f, 1f),
                green = (color.green * 0.16f + 0.84f).coerceIn(0f, 1f),
                blue = (color.blue * 0.16f + 0.84f).coerceIn(0f, 1f)
            ),
            0.65f to Color(
                red = (color.red * 0.06f + 0.94f).coerceIn(0f, 1f),
                green = (color.green * 0.06f + 0.94f).coerceIn(0f, 1f),
                blue = (color.blue * 0.06f + 0.94f).coerceIn(0f, 1f)
            ),
            1.0f to Color(0xFFFFFFFF)
        )
    }
}

/** Up Next queue overlay gradient stops (darker variant of [ambientGradientColorStops]). */
private fun queueGradientStops(color: Color, isDark: Boolean): List<Color> {
    return if (isDark) {
        val lum = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
        val topFactor = if (lum > 0.30f) (0.30f / lum) else 1.0f
        val r = (color.red * topFactor).coerceIn(0f, 1f)
        val g = (color.green * topFactor).coerceIn(0f, 1f)
        val b = (color.blue * topFactor).coerceIn(0f, 1f)
        listOf(
            Color(
                red = (r * 0.45f + 0.015f).coerceIn(0f, 1f),
                green = (g * 0.45f + 0.015f).coerceIn(0f, 1f),
                blue = (b * 0.45f + 0.015f).coerceIn(0f, 1f)
            ),
            Color(
                red = (r * 0.28f + 0.01f).coerceIn(0f, 1f),
                green = (g * 0.28f + 0.01f).coerceIn(0f, 1f),
                blue = (b * 0.28f + 0.01f).coerceIn(0f, 1f)
            ),
            Color(
                red = (r * 0.14f + 0.005f).coerceIn(0f, 1f),
                green = (g * 0.14f + 0.005f).coerceIn(0f, 1f),
                blue = (b * 0.14f + 0.005f).coerceIn(0f, 1f)
            ),
            Color(
                red = (r * 0.04f + 0.002f).coerceIn(0f, 1f),
                green = (g * 0.04f + 0.002f).coerceIn(0f, 1f),
                blue = (b * 0.04f + 0.002f).coerceIn(0f, 1f)
            ),
            Color(0xFF000000)
        )
    } else {
        listOf(
            Color(
                red = (color.red * 0.18f + 0.82f).coerceIn(0f, 1f),
                green = (color.green * 0.18f + 0.82f).coerceIn(0f, 1f),
                blue = (color.blue * 0.18f + 0.82f).coerceIn(0f, 1f)
            ),
            Color(
                red = (color.red * 0.06f + 0.94f).coerceIn(0f, 1f),
                green = (color.green * 0.06f + 0.94f).coerceIn(0f, 1f),
                blue = (color.blue * 0.06f + 0.94f).coerceIn(0f, 1f)
            ),
            Color(0xFFFFFFFF)
        )
    }
}

@SuppressLint("PrivateApi")
private object MiuiDarkModeHelper {
    private var isChecked = false
    private var darkModeFlag: Int? = null
    private var setExtraFlagsMethod: java.lang.reflect.Method? = null

    fun apply(window: android.view.Window, isDark: Boolean) {
        try {
            if (!isChecked) {
                isChecked = true
                val layoutParams = Class.forName("android.view.MiuiWindowManager\$LayoutParams")
                val field = layoutParams.getField("EXTRA_FLAG_STATUS_BAR_DARK_MODE")
                darkModeFlag = field.getInt(layoutParams)
                setExtraFlagsMethod = window.javaClass.getMethod("setExtraFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }
            val flag = darkModeFlag
            val method = setExtraFlagsMethod
            if (flag != null && method != null) {
                method.invoke(window, if (!isDark) flag else 0, flag)
            }
        } catch (_: Throwable) {}
    }
}



/** Shares the local audio file via the system share sheet. */
private fun shareTrack(
    context: android.content.Context,
    track: AudioItem
) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, track.uri)
            putExtra(Intent.EXTRA_TEXT, "${track.title} - ${track.artist}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
    } catch (e: Exception) {
        AppToast.show("Cannot share track: ${e.message}")
    }
}
