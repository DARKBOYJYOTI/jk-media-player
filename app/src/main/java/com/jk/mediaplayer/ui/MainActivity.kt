package com.jk.mediaplayer.ui

import com.jk.mediaplayer.ui.common.GlobalAppToastHost
import com.jk.mediaplayer.ui.common.AppToast
import com.jk.mediaplayer.ui.video.VideoPlayerActivity

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.animation.core.AnimationVector1D
import kotlinx.coroutines.CoroutineScope
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.DeleteOutline
import com.jk.mediaplayer.util.TrackDeleter
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt
import com.jk.mediaplayer.ui.music.MiniPlayerContent
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jk.mediaplayer.data.MediaScanner
import com.jk.mediaplayer.data.PreferencesManager
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.service.AudioPlayerService
import com.jk.mediaplayer.ui.music.MusicScreen
import com.jk.mediaplayer.ui.music.NowPlayingSheet
import com.jk.mediaplayer.ui.music.AlbumArtColorExtractor
import com.jk.mediaplayer.ui.music.SettingsScreen
import com.jk.mediaplayer.ui.theme.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class PlayerWindowCardShape(
    private val insetPx: Float,
    private val radiusPx: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val inset = insetPx.coerceIn(0f, size.width / 2f)
        val radius = radiusPx.coerceIn(0f, minOf(size.width, size.height) / 2f)
        return Outline.Rounded(
            RoundRect(
                left = inset,
                top = 0f,
                right = size.width - inset,
                bottom = size.height,
                cornerRadius = CornerRadius(radius)
            )
        )
    }
}

class MainActivity : ComponentActivity() {

    private var audioService by mutableStateOf<AudioPlayerService?>(null)
    private var isServiceBound = false
    private lateinit var prefsManager: PreferencesManager

    // Queue for instant playback if user taps before service binds
    private var pendingPlayTrack: Triple<List<AudioItem>, Int, String?>? = null
    private var pendingStreamUrl: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlayerService.LocalBinder
            audioService = binder.getService()
            isServiceBound = true

            // Immediately execute queued playback if any
            pendingPlayTrack?.let { (list, index, source) ->
                audioService?.playTrackList(list, index, source)
                pendingPlayTrack = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isServiceBound = false
        }
    }

    private var hasStoragePermission by mutableStateOf(false)
    private var shouldExpandNowPlaying by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStoragePermission = checkStoragePermission()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = checkStoragePermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (forwardToVideoPlayerIfBackgroundRunning(intent)) return
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        prefsManager = PreferencesManager(this)

        requestNotificationPermissionIfNeeded()

        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
        }

        // Eagerly start and bind background audio service for instant playback
        val serviceIntent = Intent(this, AudioPlayerService::class.java)
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        hasStoragePermission = checkStoragePermission()
        if (!hasStoragePermission) {
            requestStoragePermissions()
        }

        handleIncomingMediaIntent(intent)

        setContent {
            val systemDark = isSystemInDarkTheme()
            var themeMode by remember { mutableStateOf(prefsManager.getMusicThemeMode()) }
            val isDark = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemDark
            }

            JKMediaPlayerTheme(isDark = isDark, accentColor = MusicRed) {
                MusicAppRoot(
                    hasPermission = hasStoragePermission,
                    onRequestPermission = { requestStoragePermissions() },
                    audioService = audioService,
                    currentThemeMode = themeMode,
                    shouldExpandNowPlaying = shouldExpandNowPlaying,
                    onResetExpandNowPlaying = { shouldExpandNowPlaying = false },
                    onThemeChange = { newMode ->
                        themeMode = newMode
                        prefsManager.saveMusicThemeMode(newMode)
                    },
                    onPlayTrackRequest = { trackList, index, source ->
                        if (audioService != null) {
                            audioService?.playTrackList(trackList, index, source)
                        } else {
                            pendingPlayTrack = Triple(trackList, index, source)
                        }
                    }
                )
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when {
                Environment.isExternalStorageManager() -> true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    // Granular media permission is enough for the MediaStore based library.
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
                        PackageManager.PERMISSION_GRANTED
                else ->
                    // Android 11 and 12 still accept the legacy read permission for MediaStore.
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
            }
        } else {
            val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        } else {
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestPermissionLauncher.launch(permissions)
        }
    }

    override fun onResume() {
        super.onResume()
        val perm = checkStoragePermission()
        if (perm != hasStoragePermission) {
            hasStoragePermission = perm
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (forwardToVideoPlayerIfBackgroundRunning(intent)) return
        setIntent(intent)
        handleIncomingMediaIntent(intent)
    }

    private fun forwardToVideoPlayerIfBackgroundRunning(incomingIntent: Intent?): Boolean {
        val hasExplicitAudioIntent = incomingIntent?.data != null ||
            incomingIntent?.getBooleanExtra(AudioPlayerService.EXTRA_OPEN_NOW_PLAYING, false) == true ||
            incomingIntent?.action == Intent.ACTION_VIEW ||
            incomingIntent?.action == Intent.ACTION_SEND
        if (VideoPlayerActivity.isBackgroundAudioRunning && !hasExplicitAudioIntent) {
            val forwardIntent = Intent(this, VideoPlayerActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_DEFAULT)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_FROM_BACKGROUND_NOTIFICATION", true)
            }
            startActivity(forwardIntent)
            finish()
            return true
        }
        return false
    }

    private fun handleIncomingMediaIntent(intent: Intent) {
        // Launched from the system media controls: show the full player once the service has a
        // current track, instead of leaving the user on the home screen.
        if (intent.getBooleanExtra(AudioPlayerService.EXTRA_OPEN_NOW_PLAYING, false)) {
            intent.removeExtra(AudioPlayerService.EXTRA_OPEN_NOW_PLAYING)
            shouldExpandNowPlaying = true
        }
        val sharedText = if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("android.intent.extra.TEXT")
        } else null

        val isNetwork = { s: String ->
            val lower = s.trim().lowercase()
            lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtsp://") || lower.startsWith("rtmp://") || lower.startsWith("mms://") || lower.startsWith("ftp://") || lower.startsWith("udp://")
        }

        val networkUrl = if (!sharedText.isNullOrBlank() && isNetwork(sharedText)) {
            sharedText.trim()
        } else if (intent.data?.scheme in listOf("http", "https", "rtsp", "rtmp", "mms", "ftp", "udp")) {
            intent.data.toString()
        } else null

        if (networkUrl != null) {
            val srv = audioService
            if (srv != null) {
                srv.playStream(networkUrl)
                shouldExpandNowPlaying = true
            } else {
                pendingStreamUrl = networkUrl
            }
            return
        }

        val uri: Uri = (intent.data ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }) ?: return

        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) { resolveExternalAudio(uri) }

            val currentItem = com.jk.mediaplayer.model.AudioItem(
                id = System.currentTimeMillis(),
                title = resolved.title,
                artist = resolved.artist,
                album = resolved.album,
                duration = resolved.duration,
                path = resolved.path,
                uri = uri,
                albumArtUri = resolved.albumArtUri
            )

            var playlist = listOf(currentItem)
            var startIndex = 0
            val parentName = if (resolved.path.isNotEmpty()) java.io.File(resolved.path).parentFile?.name else null

            if (resolved.siblingPaths.isNotEmpty()) {
                playlist = resolved.siblingPaths.map { siblingPath ->
                    if (siblingPath == resolved.path) {
                        currentItem
                    } else {
                        com.jk.mediaplayer.model.AudioItem(
                            id = kotlin.math.abs(siblingPath.hashCode().toLong()),
                            title = java.io.File(siblingPath).nameWithoutExtension,
                            artist = resolved.artist,
                            album = parentName ?: "External Audio",
                            duration = 0L,
                            path = siblingPath,
                            uri = Uri.fromFile(java.io.File(siblingPath)),
                            albumArtUri = null
                        )
                    }
                }
                val foundIdx = playlist.indexOfFirst { it.path == resolved.path }
                if (foundIdx >= 0) startIndex = foundIdx
            }

            shouldExpandNowPlaying = true
            if (isServiceBound && audioService != null) {
                audioService?.playTrackList(playlist, startIndex, "Direct Open")
            } else {
                pendingPlayTrack = Triple(playlist, startIndex, "Direct Open")
            }

            val openedPath = resolved.path
            val openedPlaylist = playlist
            if (openedPath.isNotEmpty() && openedPlaylist.size > 1) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val mmrBg = android.media.MediaMetadataRetriever()
                    var hasUpdates = false
                    val updatedList = openedPlaylist.map { item ->
                        if (item.path == openedPath) {
                            item
                        } else {
                            try {
                                mmrBg.setDataSource(item.path)
                                val t = mmrBg.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                                val a = mmrBg.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                val alb = mmrBg.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                val dur = mmrBg.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                if (!t.isNullOrBlank() || !a.isNullOrBlank()) {
                                    hasUpdates = true
                                    item.copy(
                                        title = if (!t.isNullOrBlank()) t else item.title,
                                        artist = if (!a.isNullOrBlank()) a else item.artist,
                                        album = if (!alb.isNullOrBlank()) alb else item.album,
                                        duration = dur
                                    )
                                } else {
                                    item
                                }
                            } catch (_: Exception) {
                                item
                            }
                        }
                    }
                    try { mmrBg.release() } catch (_: Exception) {}
                    if (hasUpdates) {
                        withContext(Dispatchers.Main) {
                            audioService?.updatePlaylistSilently(updatedList)
                        }
                    }
                }
            }
        }
    }

    private data class ResolvedExternalAudio(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val path: String,
        val albumArtUri: Uri?,
        val siblingPaths: List<String>
    )

    private fun resolveExternalAudio(uri: Uri): ResolvedExternalAudio {
        var resolvedName: String? = null
        var resolvedPath: String = if (uri.scheme == "file") uri.path ?: "" else ""

        try {
            if (uri.scheme == "content") {
                val proj = arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.MediaStore.Audio.Media.DATA
                )
                contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameCol >= 0) resolvedName = cursor.getString(nameCol)
                        val dataCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                        if (dataCol >= 0) {
                            val dataPath = cursor.getString(dataCol)
                            if (!dataPath.isNullOrEmpty() && java.io.File(dataPath).exists()) {
                                resolvedPath = dataPath
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        var metaTitle: String? = null
        var metaArtist: String? = null
        var metaAlbum: String? = null
        var metaDuration: Long = 0L
        var albumArtUri: Uri? = null

        val mmr = android.media.MediaMetadataRetriever()
        try {
            if (resolvedPath.isNotEmpty() && java.io.File(resolvedPath).exists()) {
                mmr.setDataSource(resolvedPath)
            } else {
                mmr.setDataSource(this, uri)
            }
            metaTitle = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            metaArtist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            metaAlbum = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            metaDuration = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val rawArt = mmr.embeddedPicture
            if (rawArt != null) {
                val hash = kotlin.math.abs(uri.toString().hashCode().toLong())
                val cacheFile = java.io.File(cacheDir, "ext_art_$hash.jpg")
                if (!cacheFile.exists()) {
                    cacheFile.writeBytes(rawArt)
                }
                albumArtUri = Uri.fromFile(cacheFile)
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        val siblingPaths: List<String> = if (resolvedPath.isNotEmpty()) {
            val parentDir = java.io.File(resolvedPath).parentFile
            if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                val audioExts = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
                parentDir.listFiles { f -> f.isFile && audioExts.contains(f.extension.lowercase()) }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { it.absolutePath }
                    ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        return ResolvedExternalAudio(
            title = metaTitle?.takeIf { it.isNotBlank() } ?: resolvedName ?: uri.lastPathSegment ?: "External Audio",
            artist = metaArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            album = metaAlbum?.takeIf { it.isNotBlank() } ?: "External Audio",
            duration = metaDuration,
            path = resolvedPath,
            albumArtUri = albumArtUri,
            siblingPaths = siblingPaths
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MusicAppRoot(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    audioService: AudioPlayerService?,
    currentThemeMode: String,
    shouldExpandNowPlaying: Boolean = false,
    onResetExpandNowPlaying: () -> Unit = {},
    onThemeChange: (String) -> Unit,
    onPlayTrackRequest: (List<AudioItem>, Int, String?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playerExpansionProgress = remember { Animatable(0f) }
    var isPlayerCollapsing by remember { mutableStateOf(false) }
    // One settle animation for taps, back presses and flicks, so opening and closing the
    // player share the same spring feel.
    fun settlePlayer(expand: Boolean, initialVelocity: Float = 0f) {
        isPlayerCollapsing = !expand
        coroutineScope.launch {
            playerExpansionProgress.animateTo(
                targetValue = if (expand) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                initialVelocity = initialVelocity
            )
        }
    }
    // Composed once the mini player is touched so the first drag frame never pays for the sheet.
    var isPlayerPrewarmed by remember { mutableStateOf(false) }
    var artTargetRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var heroArtModel by remember { mutableStateOf<AudioItem?>(null) }
    val isPlayerExpanded by remember {
        derivedStateOf { playerExpansionProgress.value > 0.05f }
    }

    var showSettingsScreen by remember { mutableStateOf(false) }
    var isSearchOpen by remember { mutableStateOf(false) }
    var isSheetDragBlocked by remember { mutableStateOf(false) }
    val volumeBarBoundsState = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val volumeBarLocalBoundsState = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val restoredLibrary = remember {
        MediaScanner.cachedMusic ?: MediaScanner.loadPersistedMusic(context)
    }
    var tracks by remember { mutableStateOf<List<AudioItem>>(restoredLibrary ?: emptyList()) }
    var isScanning by remember { mutableStateOf(restoredLibrary == null) }

    // Intercept back button when Settings screen is open
    BackHandler(enabled = showSettingsScreen) {
        showSettingsScreen = false
    }

    // Intercept back button when Now Playing sheet is expanded
    BackHandler(enabled = isPlayerExpanded && !showSettingsScreen) {
        settlePlayer(expand = false)
    }

    var currentTrack by remember { mutableStateOf<AudioItem?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(0L) }
    val currentTimeState = remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    var pendingDeleteTrack by remember { mutableStateOf<AudioItem?>(null) }
    var trackWaitingForPermission by remember { mutableStateOf<AudioItem?>(null) }

    val deleteIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            trackWaitingForPermission?.let { deletedTrack ->
                val wasPlayingThis = currentTrack?.id == deletedTrack.id
                tracks = tracks.filterNot { it.id == deletedTrack.id }
                audioService?.removeFromPlaylist(deletedTrack.id)
                if (wasPlayingThis && audioService?.playlist?.value?.isEmpty() == true) {
                    coroutineScope.launch { playerExpansionProgress.snapTo(0f) }
                }
                AppToast.show("'${deletedTrack.title}' deleted successfully")
            }
        }
        trackWaitingForPermission = null
    }

    val requestDeleteTrack: (AudioItem) -> Unit = { track ->
        pendingDeleteTrack = track
    }

    LaunchedEffect(shouldExpandNowPlaying, currentTrack) {
        if (shouldExpandNowPlaying && currentTrack != null) {
            isPlayerPrewarmed = true
            settlePlayer(expand = true)
            onResetExpandNowPlaying()
        }
    }

    LaunchedEffect(currentTrack?.id) {
        val trk = currentTrack ?: return@LaunchedEffect
        AlbumArtColorExtractor.extractDominantColor(context, trk)
    }

    LaunchedEffect(audioService) {
        val service = audioService ?: return@LaunchedEffect
        launch { service.currentTrack.collect { currentTrack = it } }
        launch { service.playerController?.isPlaying?.collect { isPlaying = it } }
        launch { service.playerController?.currentTime?.collect { currentTime = it; currentTimeState.longValue = it } }
        launch { service.playerController?.totalDuration?.collect { totalDuration = it } }
        launch { service.playbackError.collect { playbackError = it } }
    }

    // Stable lambdas: players read the live position only inside their own leaf composables,
    // so the 4 Hz player tick no longer recomposes the entire music screen / player sheet.
    val playbackTimeProvider = remember { { currentTimeState.longValue } }

    // Queue state shared by the mini player and the full player sheet.
    val currentPlaylist by audioService?.playlist?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val activePlaylist = if (currentPlaylist.isNotEmpty()) currentPlaylist else tracks
    val repeatMode by audioService?.repeatMode?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val isShuffle by audioService?.isShuffle?.collectAsState() ?: remember { mutableStateOf(false) }
    val favoriteTrackIds by audioService?.favoriteTrackIds?.collectAsState() ?: remember { mutableStateOf(emptySet<String>()) }
    val playbackSource by audioService?.playbackSource?.collectAsState() ?: remember { mutableStateOf("All Songs") }
    var targetArtistToOpen by remember { mutableStateOf<String?>(null) }
    var targetAlbumToOpen by remember { mutableStateOf<String?>(null) }
    // Repeat all / shuffle keep both skips usable. With repeat off the first item has no previous
    // and the last item has no next, so those buttons grey out when reaching bounds.
    val currentQueueIndex = remember(activePlaylist, currentTrack?.id) {
        activePlaylist.indexOfFirst { it.id == currentTrack?.id }
    }
    val canSkipPrevious = isShuffle || repeatMode != 0 || currentQueueIndex > 0
    val canSkipNext = isShuffle || repeatMode != 0 ||
        (currentQueueIndex >= 0 && currentQueueIndex < activePlaylist.lastIndex)

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            if (MediaScanner.cachedMusic == null && tracks.isEmpty()) {
                isScanning = true
            }
            tracks = MediaScanner.scanMusic(context)
            isScanning = false
        } else {
            isScanning = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // The music surface paints its own opaque background, so an opaque Scaffold colour would
        // be a second full-screen fill behind it every frame.
        containerColor = if (hasPermission) Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            val density = LocalDensity.current
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val activeTrack = currentTrack
            val totalHeightPx = constraints.maxHeight.toFloat()
            val navBarsBottomInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
            val navBarContentHeightPx = with(density) { 58.dp.toPx() }
            val miniPlayerHeightDp = 66.dp
            val miniPlayerHeightPx = with(density) { miniPlayerHeightDp.toPx() }
            val marginAboveNavBarPx = 0f
            val collapsedTopOffsetPx = (totalHeightPx - navBarContentHeightPx - navBarsBottomInsetPx - miniPlayerHeightPx - marginAboveNavBarPx).coerceAtLeast(0f)

            // One gesture owner for the whole screen. As the ancestor of both the library and
            // the player it joins their hit path instead of covering them, so it never steals a
            // tap from the screen below; it only claims vertical drags on the mini card or the
            // artwork area.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerDragHandle(
                        coroutineScope = coroutineScope,
                        expansion = playerExpansionProgress,
                        travelPx = collapsedTopOffsetPx,
                        collapsedTopPx = collapsedTopOffsetPx,
                        miniHeightPx = miniPlayerHeightPx,
                        expandedGrabStartPx = 0f,
                        volumeBarBoundsState = volumeBarBoundsState,
                        enabled = activeTrack != null && !showSettingsScreen && !isSheetDragBlocked,
                        onGrabStart = { isPlayerPrewarmed = true },
                        onDragDirectionChange = { isPlayerCollapsing = it }
                    )
            ) {
            if (!hasPermission) {
                // Storage Permission Banner
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MusicRed,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Storage Access Required",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To find and play your high-resolution music files without restrictions, please grant all-files storage access.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = MusicRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Grant Permission", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Dedicated Music Screen with Bottom Navigation
                MusicScreen(
                    tracks = tracks,
                    isScanning = isScanning,
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    onTogglePlayPause = { audioService?.togglePlayPause() },
                    onOpenPlayer = {
                        isPlayerPrewarmed = true
                        settlePlayer(expand = true)
                    },
                    onTrackClick = { playlist, index, source ->
                        onPlayTrackRequest(playlist, index, source)
                        isPlayerPrewarmed = true
                        settlePlayer(expand = true)
                    },
                    onPlayNext = { track ->
                        audioService?.playNextInQueue(track)
                    },
                    onAddToQueue = { track ->
                        audioService?.addToQueue(track)
                    },
                    onOpenSettings = { showSettingsScreen = true },
                    onSearchVisibilityChange = { isSearchOpen = it },
                    isPlayerSheetExpanded = isPlayerExpanded,
                    playerExpansionProgress = { playerExpansionProgress.value },
                    onRescanLibrary = {
                        tracks = MediaScanner.scanMusic(context)
                    },
                    onDeleteTrack = requestDeleteTrack,
                    targetArtistToOpen = targetArtistToOpen,
                    targetAlbumToOpen = targetAlbumToOpen,
                    onResetTargetArtistAlbum = {
                        targetArtistToOpen = null
                        targetAlbumToOpen = null
                    },
                    onPlayStream = { url, username, password, isAudioMode ->
                        if (isAudioMode) {
                            audioService?.playStream(url, username, password)
                            isPlayerPrewarmed = true
                            settlePlayer(expand = true)
                        } else {
                            val playIntent = Intent(context, com.jk.mediaplayer.ui.video.VideoPlayerActivity::class.java).apply {
                                putExtra("EXTRA_VIDEO_URI", url)
                                putExtra("EXTRA_VIDEO_PATH", url)
                                putExtra("EXTRA_VIDEO_TITLE", Uri.parse(url).lastPathSegment ?: "Network Stream")
                                putExtra("EXTRA_STREAM_USER", username)
                                putExtra("EXTRA_STREAM_PASS", password)
                            }
                            context.startActivity(playIntent)
                        }
                    }
                )

                // Unified Expanding Bottom Sheet (single container)
                if (activeTrack != null) {
                    // One album colour source for the mini card and the player gradient, so the
                    // background never changes hue while the player grows.
                    val surfaceTrack = heroArtModel ?: activeTrack
                    var playerDominant by remember(surfaceTrack.id, surfaceTrack.onlineArtUrl) {
                        mutableStateOf(
                            AlbumArtColorExtractor.getInitialDominantColor(
                                context,
                                surfaceTrack,
                                surfaceTrack.onlineArtUrl
                            )
                        )
                    }
                    LaunchedEffect(surfaceTrack.id, surfaceTrack.onlineArtUrl) {
                        val extracted = AlbumArtColorExtractor.extractDominantColor(
                            context,
                            surfaceTrack,
                            surfaceTrack.onlineArtUrl
                        )
                        if (extracted != playerDominant) playerDominant = extracted
                    }
                    val playerSurfaceTint by animateColorAsState(
                        targetValue = albumSurfaceTint(playerDominant, isDark),
                        animationSpec = tween(320),
                        label = "playerSurfaceTint"
                    )
                    fun volumeBarAlpha(progress: Float): Float {
                        if (progress >= 0.95f) return 1f
                        val bar = volumeBarLocalBoundsState.value
                        val target = artTargetRect
                        if (bar == null || target == null) {
                            // Single continuous ramp: no direction-dependent branch, so a drag that
                            // starts downwards cannot snap the bar to a new alpha in one frame.
                            return ((progress - 0.6f) / 0.25f).coerceIn(0f, 1f)
                        }
                        val startY = with(density) { 8.dp.toPx() }
                        val heroTop = startY + (target.top - startY) * progress
                        val gap = with(density) { 8.dp.toPx() }
                        // Constant fade window: switching this value on isPlayerCollapsing made the
                        // alpha jump ~0.8 in one frame the moment a collapse drag began.
                        val fadeStart = with(density) { 16.dp.toPx() }
                        val fadeRange = with(density) { 52.dp.toPx() }
                        val clearance = heroTop - bar.bottom - gap
                        return ((clearance - fadeStart) / fadeRange).coerceIn(0f, 1f)
                    }
                    val isHiddenBySearch by remember {
                        derivedStateOf { isSearchOpen && playerExpansionProgress.value < 0.05f }
                    }
                    val showMiniPlayer by remember {
                        derivedStateOf { playerExpansionProgress.value < 0.995f }
                    }
                    val mountPlayerSheet by remember(isPlayerPrewarmed) {
                        derivedStateOf { playerExpansionProgress.value > 0.01f || isPlayerPrewarmed }
                    }

                    if (!isHiddenBySearch) {
                        Box(modifier = Modifier.fillMaxSize()) {

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .layout { measurable, constraints ->
                                            val p = playerExpansionProgress.value.coerceIn(0f, 1f)
                                            val h = (miniPlayerHeightPx +
                                                (totalHeightPx - miniPlayerHeightPx) * p).roundToInt()
                                            val placeable = measurable.measure(
                                                constraints.copy(minHeight = h, maxHeight = h)
                                            )
                                            layout(placeable.width, h) { placeable.place(0, 0) }
                                        }
                                        .graphicsLayer {
                                            val p = playerExpansionProgress.value.coerceIn(0f, 1f)
                                            translationY = collapsedTopOffsetPx * (1f - p)
                                            val radiusPx = 12.dp.toPx() * (1f - (p * 3f).coerceIn(0f, 1f))
                                            clip = true
                                            shape = PlayerWindowCardShape(
                                                insetPx = 8.dp.toPx() * (1f - (p / 0.2f).coerceIn(0f, 1f)),
                                                radiusPx = radiusPx
                                            )
                                        }
                                        .drawBehind { drawRect(playerSurfaceTint) }
                                ) {
                                    // 1. Full Now Playing Sheet (rendered underneath, activeTrack kept alive in memory, crossfades in smoothly)
                                    @Suppress("DEPRECATION")
                                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                                        // Fully collapsed: the full-size sheet is not composed at all.
                                        // Composing it behind a 68dp window let its header bleed around
                                        // the mini card and cost a full-screen draw every frame.
                                        if (mountPlayerSheet) {
                                        NowPlayingHost(
                                            audioService = audioService,
                                            track = activeTrack,
                                            isPlaying = isPlaying,
                                            playbackTime = playbackTimeProvider,
                                            totalDurationMs = totalDuration,
                                            playlist = activePlaylist,
                                            playbackSource = playbackSource,
                                            repeatMode = repeatMode,
                                            isShuffle = isShuffle,
                                            canGoPrevious = canSkipPrevious,
                                            canGoNext = canSkipNext,
                                            onTrackSelect = { index, clicked -> audioService?.playTrackAt(index, clicked) },
                                            onTogglePlayPause = { audioService?.togglePlayPause() },
                                            onNext = { audioService?.playNext() },
                                            onPrevious = { audioService?.playPrevious() },
                                            onSeekTo = { pos -> audioService?.seekTo(pos) },
                                            onSeekBy = { delta -> audioService?.seekBy(delta) },
                                            onToggleRepeat = { audioService?.toggleRepeatMode() },
                                            onToggleShuffle = { audioService?.toggleShuffle() },
                                            onRemoveFromQueue = { trackId -> audioService?.removeFromPlaylist(trackId) },
                                            onMoveQueueItem = { from, to -> audioService?.movePlaylistItem(from, to) },
                                            onDeleteTrack = requestDeleteTrack,
                                            onDismiss = {
                                                settlePlayer(expand = false)
                                            },
                                            onSetVolumeBoost = { vol -> audioService?.setVolume(vol) },
                                            isFavorite = favoriteTrackIds.contains(activeTrack.id.toString()),
                                            onToggleLike = { audioService?.toggleLike() },
                                            onPlayNext = { trk -> audioService?.playNextInQueue(trk) },
                                            onGoToArtist = { art ->
                                                targetArtistToOpen = art
                                                settlePlayer(expand = false)
                                            },
                                            onGoToAlbum = { alb ->
                                                targetAlbumToOpen = alb
                                                settlePlayer(expand = false)
                                            },
                                            onBlacklistTrack = { blacklistedTrack ->
                                                tracks = tracks.filterNot { it.id == blacklistedTrack.id }
                                                MediaScanner.cachedMusic = tracks
                                            },
                                            onDragBlockedChange = { isSheetDragBlocked = it },
                                            onArtTargetChange = { artTargetRect = it },
                                            onArtModelChange = { heroArtModel = it },
                                            onVolumeBarBoundsChange = { rect ->
                                            // Only trust measurements from the fully expanded player: while
                                            // dragging, the sheet moves and would constantly report new
                                            // bounds.
                                            if (volumeBarBoundsState.value == null || playerExpansionProgress.value > 0.99f) {
                                                volumeBarBoundsState.value = rect
                                            }
                                        },
                                            onVolumeBarLocalBoundsChange = {
                                                volumeBarLocalBoundsState.value = it
                                            },
                                            volumeBarVisibility = {
                                                val p = playerExpansionProgress.value.coerceIn(0f, 1f)
                                                volumeBarAlpha(p)
                                            },
                                            volumeBarInteractionEnabled = {
                                                val p = playerExpansionProgress.value.coerceIn(0f, 1f)
                                                p >= 0.95f || (!isPlayerCollapsing && volumeBarAlpha(p) > 0.4f)
                                            },
                                            collapseControlAlpha = {
                                                if (isPlayerCollapsing) {
                                                    playerExpansionProgress.value.coerceIn(0f, 1f)
                                                } else {
                                                    1f
                                                }
                                            },
                                            expansionProgress = { playerExpansionProgress.value },
                                            topTint = playerSurfaceTint,
                                            modifier = Modifier
                                                .layout { measurable, constraints ->
                                                    val fullHeight = totalHeightPx.roundToInt()
                                                    val placeable = measurable.measure(
                                                        androidx.compose.ui.unit.Constraints.fixed(
                                                            constraints.maxWidth,
                                                            fullHeight
                                                        )
                                                    )
                                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                                        placeable.place(0, 0)
                                                    }
                                                }
                                        )
                                        }
                                    }

                                    // Mini content sits at window top and fades while the window itself
                                    // transforms from a card into the full player.
                                    if (showMiniPlayer) {
                                        MiniPlayerContent(
                                            track = surfaceTrack,
                                            backgroundColor = playerSurfaceTint,
                                            isPlaying = isPlaying,
                                            playbackTime = playbackTimeProvider,
                                            totalDurationMs = totalDuration,
                                            onTogglePlayPause = { audioService?.togglePlayPause() },
                                            onNext = { audioService?.playNext() },
                                            onPrevious = { audioService?.playPrevious() },
                                            canGoNext = canSkipNext,
                                            canGoPrevious = canSkipPrevious,
                                            onClick = {
                                                isPlayerPrewarmed = true
                                                settlePlayer(expand = true)
                                            },
                                            contentAlpha = {
                                                (1f - playerExpansionProgress.value / 0.25f).coerceIn(0f, 1f)
                                            },
                                            miniArtworkAlpha = {
                                                if (playerExpansionProgress.value > 0.001f) 0f else 1f
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
                                                .height(miniPlayerHeightDp)
                                        )
                                    }

                                }

                            // Morphing hero artwork: sibling of the sheet window so it is never
                            // clipped, drawn above it and with no pointer input.
                            val heroEnd = artTargetRect
                            val heroModel = heroArtModel
                            if (heroEnd != null && heroModel != null) {
                                val startSizePx = with(density) { 50.dp.toPx() }
                                val startX = with(density) { 18.dp.toPx() }
                                val startY = collapsedTopOffsetPx + (miniPlayerHeightPx - startSizePx) / 2f
                                AsyncImage(
                                    model = coil.request.ImageRequest.Builder(context)
                                        .data(heroModel)
                                        .size(heroEnd.width.roundToInt())
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .requiredSize(with(density) { heroEnd.width.toDp() })
                                        .graphicsLayer {
                                            val p = playerExpansionProgress.value.coerceIn(0f, 1f)
                                            // Straight rect interpolation: x, y and size all move with the
                                            // finger, so the cover never lags or overshoots the mini slot.
                                            val fromScale = startSizePx / heroEnd.width
                                            val scale = fromScale + (1f - fromScale) * p
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            translationX = startX + (heroEnd.left - startX) * p
                                            translationY = startY + (heroEnd.top - startY) * p
                                            scaleX = scale
                                            scaleY = scale
                                            clip = true
                                            shape = RoundedCornerShape((8.dp.toPx() + 14.dp.toPx() * p) / scale)
                                            alpha = if (p > 0.001f && p < 0.995f) 1f else 0f
                                        }
                                )
                            }
                            }
                        }
                    }
                }
            }

            // Dedicated Settings Screen (Smooth Material 3 Scale + Vertical Slide Transition)
            AnimatedVisibility(
                visible = showSettingsScreen,
                enter = fadeIn(androidx.compose.animation.core.tween(260)) +
                        androidx.compose.animation.scaleIn(initialScale = 0.92f, animationSpec = androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                        slideInVertically(initialOffsetY = { it / 7 }, animationSpec = androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
                exit = fadeOut(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.scaleOut(targetScale = 0.92f, animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                        slideOutVertically(targetOffsetY = { it / 7 }, animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                SettingsScreen(
                    currentThemeMode = currentThemeMode,
                    onThemeChange = onThemeChange,
                    tracks = tracks,
                    audioService = audioService,
                    onRescanLibrary = {
                        coroutineScope.launch {
                            tracks = MediaScanner.scanMusic(context)
                        }
                    },
                    onBack = { showSettingsScreen = false }
                )
            }

            // Can't Play Music Alert Dialog with OK button (Rounded Rectangle & Theme adaptive)
            if (playbackError != null) {
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val dialogBg = if (isDark) Color(0xFF14151B) else Color.White
                val dialogTextPrimary = if (isDark) Color.White else Color(0xFF111216)
                val dialogTextSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)
                AlertDialog(
                    onDismissRequest = {
                        audioService?.clearPlaybackError()
                        playbackError = null
                    },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = dialogBg,
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                    modifier = Modifier.fillMaxWidth(0.94f),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MusicRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Can't Play Music",
                                color = dialogTextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    text = {
                        Text(
                            text = "This audio track or stream cannot be played. The format or link may be unsupported, corrupted, or unreachable.",
                            color = dialogTextSecondary,
                            fontSize = 15.sp,
                            lineHeight = 24.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                audioService?.clearPlaybackError()
                                playbackError = null
                                coroutineScope.launch { playerExpansionProgress.snapTo(0f) }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MusicRed,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "OK", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                )
            }

            // Delete Track Confirmation Dialog
            pendingDeleteTrack?.let { track ->
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val dialogBg = if (isDark) Color(0xFF1E1E24) else Color.White
                val textColorPrimary = if (isDark) Color.White else Color(0xFF111216)
                val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else Color(0xFF6B7280)
                AlertDialog(
                    onDismissRequest = { pendingDeleteTrack = null },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = dialogBg,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Delete Track?",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = textColorPrimary
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to permanently delete '${track.title}' from your device?",
                            fontSize = 15.sp,
                            color = textColorSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val toDelete = track
                                pendingDeleteTrack = null
                                TrackDeleter.deleteTrack(
                                    context = context,
                                    track = toDelete,
                                    launcher = deleteIntentSenderLauncher,
                                    onNeedsPermission = { trackWaitingForPermission = toDelete },
                                    onDeleted = {
                                        val wasPlayingThis = currentTrack?.id == toDelete.id
                                        tracks = tracks.filterNot { it.id == toDelete.id }
                                        audioService?.removeFromPlaylist(toDelete.id)
                                        if (wasPlayingThis && audioService?.playlist?.value?.isEmpty() == true) {
                                            coroutineScope.launch { playerExpansionProgress.snapTo(0f) }
                                        }
                                    }
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                        ) {
                            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { pendingDeleteTrack = null },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel", color = textColorPrimary)
                        }
                    }
                )
            }

            GlobalAppToastHost()
        }
    }
}

@Composable
private fun NowPlayingHost(
    audioService: AudioPlayerService?,
    track: AudioItem,
    isPlaying: Boolean,
    playbackTime: () -> Long,
    totalDurationMs: Long,
    playlist: List<AudioItem>,
    playbackSource: String,
    repeatMode: Int,
    isShuffle: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onTrackSelect: (Int, AudioItem) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: ((Long) -> Unit)?,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRemoveFromQueue: (Long) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onDeleteTrack: (AudioItem) -> Unit,
    onDismiss: () -> Unit,
    onSetVolumeBoost: (Int) -> Unit,
    isFavorite: Boolean,
    onToggleLike: () -> Unit,
    onPlayNext: (AudioItem) -> Unit,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (String) -> Unit,
    onBlacklistTrack: (AudioItem) -> Unit,
    expansionProgress: () -> Float = { 1f },
    topTint: Color? = null,
    volumeBarVisibility: () -> Float = { 1f },
    volumeBarInteractionEnabled: () -> Boolean = { true },
    collapseControlAlpha: () -> Float = { 1f },
    onArtTargetChange: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onArtModelChange: (AudioItem) -> Unit = {},
    onDragBlockedChange: (Boolean) -> Unit = {},
    onVolumeBarBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
    onVolumeBarLocalBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val volume by audioService?.vlcVolume?.collectAsState() ?: remember { mutableIntStateOf(100) }
    NowPlayingSheet(
        track = track,
        isPlaying = isPlaying,
        playbackTime = playbackTime,
        totalDurationMs = totalDurationMs,
        playlist = playlist,
        playbackSource = playbackSource,
        repeatMode = repeatMode,
        isShuffle = isShuffle,
        canGoPrevious = canGoPrevious,
        canGoNext = canGoNext,
        onTrackSelect = onTrackSelect,
        onTogglePlayPause = onTogglePlayPause,
        onNext = onNext,
        onPrevious = onPrevious,
        onSeekTo = onSeekTo,
        onSeekBy = onSeekBy,
        onToggleRepeat = onToggleRepeat,
        onToggleShuffle = onToggleShuffle,
        onRemoveFromQueue = onRemoveFromQueue,
        onMoveQueueItem = onMoveQueueItem,
        onDeleteTrack = onDeleteTrack,
        onDismiss = onDismiss,
        onSetVolumeBoost = onSetVolumeBoost,
        currentVolumeBoost = volume,
        isFavorite = isFavorite,
        onToggleLike = onToggleLike,
        onPlayNext = onPlayNext,
        onGoToArtist = onGoToArtist,
        onGoToAlbum = onGoToAlbum,
        onBlacklistTrack = onBlacklistTrack,
        expansionProgress = expansionProgress,
        topTint = topTint,
        volumeBarVisibility = volumeBarVisibility,
        volumeBarInteractionEnabled = volumeBarInteractionEnabled,
        collapseControlAlpha = collapseControlAlpha,
        onArtTargetChange = onArtTargetChange,
        onArtModelChange = onArtModelChange,
        onDragBlockedChange = onDragBlockedChange,
        onVolumeBarBoundsChange = onVolumeBarBoundsChange,
        onVolumeBarLocalBoundsChange = onVolumeBarLocalBoundsChange,
        modifier = modifier
    )
}

/**
 * Vertical drag that expands / collapses the now playing player.
 *
 * Hosted on a static ancestor of the sheet. A node that moves while it is dragged cancels out
 * its own local pointer deltas, and a surface placed underneath the sheet never receives the
 * gesture at all. Running on the initial pass lets this handler claim a vertical drag before the
 * sheet's own paging, scrolling and sliders see it, while plain taps stay untouched.
 *
 * Only drags that start on the mini card (collapsed) or in the artwork area (expanded) are
 * claimed, so the lists and controls below keep their native gestures.
 */
private fun Modifier.playerDragHandle(
    coroutineScope: CoroutineScope,
    expansion: Animatable<Float, AnimationVector1D>,
    travelPx: Float,
    collapsedTopPx: Float,
    miniHeightPx: Float,
    expandedGrabStartPx: Float = 0f,
    volumeBarBoundsState: androidx.compose.runtime.State<androidx.compose.ui.geometry.Rect?>? = null,
    enabled: Boolean = true,
    expandedGrabFraction: Float = 0.66f,
    onGrabStart: () -> Unit = {},
    onDragDirectionChange: (Boolean) -> Unit = {}
): Modifier {
    if (travelPx <= 0f) return this
    return pointerInput(travelPx, collapsedTopPx, miniHeightPx, expandedGrabStartPx, enabled, expandedGrabFraction) {
        val nodeHeight = size.height.toFloat()
        var targetProgress = expansion.value
        var snapJob: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabled) return@awaitEachGesture
            val startProgress = expansion.value
            targetProgress = startProgress
            val startY = down.position.y
            val startX = down.position.x
            val onMiniCard = startY >= collapsedTopPx && startY <= collapsedTopPx + miniHeightPx
            if (onMiniCard) onGrabStart()
            val sliderTouchSlopPx = with(this) { 12.dp.toPx() }
            val startedOnVolumeBar = volumeBarBoundsState?.value?.let { rect ->
                down.position.x >= rect.left - sliderTouchSlopPx &&
                    down.position.x <= rect.right + sliderTouchSlopPx &&
                    down.position.y >= rect.top - sliderTouchSlopPx &&
                    down.position.y <= rect.bottom + sliderTouchSlopPx
            } == true
            val canGrab = if (startProgress <= 0.02f) {
                onMiniCard
            } else {
                startY >= expandedGrabStartPx && startY <= nodeHeight * expandedGrabFraction
            }
            if (!canGrab) return@awaitEachGesture

            val touchSlop = viewConfiguration.touchSlop
            var claimed = false
            var sliderHoldBlocked = false
            var horizontalLocked = false
            var lastY = startY
            var startTime = down.uptimeMillis
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (!claimed) {
                    val dy = change.position.y - startY
                    val dx = change.position.x - startX
                    // A press that is held on the volume bar is a slider gesture, not a collapse.
                    // A quick swipe from the same spot still collapses the player.
                    if (startedOnVolumeBar && !sliderHoldBlocked &&
                        change.uptimeMillis - startTime > 250L
                    ) {
                        sliderHoldBlocked = true
                    }
                    if (sliderHoldBlocked) {
                        if (!change.pressed) break
                        continue
                    }
                    // Axis lock: whichever axis crosses the slop first owns the gesture until the
                    // finger lifts. A horizontal artwork swipe can never turn into a collapse, and
                    // a collapse drag never nudges the pager sideways.
                    if (!horizontalLocked &&
                        abs(dx) > touchSlop * 1.2f && abs(dx) > abs(dy) * 1.2f
                    ) {
                        horizontalLocked = true
                    }
                    if (horizontalLocked) {
                        if (!change.pressed) break
                        continue
                    }
                    if (abs(dy) > (if (startProgress <= 0.02f) touchSlop * 1.6f else touchSlop * 1.2f) &&
                        abs(dy) > abs(dx) * 1.4f
                    ) {
                        claimed = true
                        // Start the drag from zero so the accumulated slop never dumps into the
                        // first frame and the sheet does not jump.
                        lastY = change.position.y
                    } else if (!change.pressed) {
                        break
                    } else {
                        continue
                    }
                }
                // Apply the movement before checking the release: the final move of a fast
                // flick usually arrives together with the up event.
                val dy = change.position.y - lastY
                lastY = change.position.y
                if (dy != 0f) onDragDirectionChange(dy > 0f)
                change.consume()
                if (dy != 0f) {
                    targetProgress = (targetProgress - dy / travelPx).coerceIn(0f, 1f)
                    // Track the frame snap so the settle animation below can never be cancelled
                    // by a snap that was still queued when the finger lifted.
                    snapJob?.cancel()
                    snapJob = coroutineScope.launch { expansion.snapTo(targetProgress) }
                }
                if (!change.pressed) break
            }
            if (!claimed) return@awaitEachGesture
            // Release velocity from the last movement samples only, so a slow start followed by
            // a flick still reads as a flick.
            val velocityY = velocityTracker.calculateVelocity().y
            val expand = when {
                velocityY < -450f -> true
                velocityY > 450f -> false
                else -> targetProgress > 0.5f
            }
            onDragDirectionChange(!expand)
            // Drop any queued frame snap, then hand over to the settle animation. The release
            // velocity is converted to progress units so the spring carries the flick.
            snapJob?.cancel()
            snapJob = null
            coroutineScope.launch {
                expansion.animateTo(
                    targetValue = if (expand) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    initialVelocity = -velocityY / travelPx
                )
            }
        }
    }
}
