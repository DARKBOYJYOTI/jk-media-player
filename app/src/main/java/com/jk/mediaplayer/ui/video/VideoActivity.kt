package com.jk.mediaplayer.ui.video

import com.jk.mediaplayer.ui.common.GlobalAppToastHost
import com.jk.mediaplayer.ui.common.AppToast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jk.mediaplayer.data.MediaScanner
import com.jk.mediaplayer.data.PreferencesManager
import com.jk.mediaplayer.model.VideoFolder
import com.jk.mediaplayer.model.VideoItem
import com.jk.mediaplayer.ui.theme.JKMediaPlayerTheme
import com.jk.mediaplayer.ui.theme.VideoIndigo
import kotlinx.coroutines.launch

class VideoActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager
    private var hasStoragePermission by mutableStateOf(false)

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

        hasStoragePermission = checkStoragePermission()
        if (!hasStoragePermission) {
            requestStoragePermissions()
        }

        setContent {
            val systemDark = isSystemInDarkTheme()
            var themeMode by remember { mutableStateOf(prefsManager.getVideoThemeMode()) }
            val isDark = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemDark
            }

            JKMediaPlayerTheme(isDark = isDark, accentColor = VideoIndigo) {
                VideoAppRoot(
                    hasPermission = hasStoragePermission,
                    onRequestPermission = { requestStoragePermissions() },
                    currentThemeMode = themeMode,
                    onThemeChange = { newMode ->
                        themeMode = newMode
                        prefsManager.saveVideoThemeMode(newMode)
                    },
                    onPlayVideo = { video, playlist ->
                        prefsManager.markVideoWatched(video.path)
                        VideoPlayerActivity.playlist = playlist
                        val idx = playlist.indexOfFirst { it.id == video.id }
                        VideoPlayerActivity.currentVideoIndex = if (idx >= 0) idx else 0
                        val playIntent = Intent(this@VideoActivity, VideoPlayerActivity::class.java).apply {
                            putExtra("EXTRA_VIDEO_URI", video.uri.toString())
                            putExtra("EXTRA_VIDEO_PATH", video.path)
                            putExtra("EXTRA_VIDEO_TITLE", video.title)
                            putExtra("EXTRA_IS_PORTRAIT", video.isPortrait)
                        }
                        startActivity(playIntent)
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
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
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
}

@Composable
fun VideoAppRoot(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    currentThemeMode: String,
    onThemeChange: (String) -> Unit,
    onPlayVideo: (VideoItem, List<VideoItem>) -> Unit
) {
    var selectedVideoFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var showSearchPage by remember { mutableStateOf(false) }
    var showSettingsPage by remember { mutableStateOf(false) }
    var videoFolders by remember { mutableStateOf<List<VideoFolder>>(MediaScanner.cachedVideoFolders ?: emptyList()) }
    var isScanning by remember { mutableStateOf(MediaScanner.cachedVideoFolders == null) }
    val refreshScope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    // Intercept back button for Search page
    BackHandler(enabled = showSearchPage) {
        showSearchPage = false
    }

    // Intercept back button for Settings page
    BackHandler(enabled = !showSearchPage && showSettingsPage) {
        showSettingsPage = false
    }

    // Intercept back button when viewing a folder's videos to return to folder explorer
    BackHandler(enabled = !showSearchPage && !showSettingsPage && selectedVideoFolder != null) {
        selectedVideoFolder = null
    }

    // Double back to exit when at root video folder explorer to prevent accidental exit
    BackHandler(enabled = !showSearchPage && !showSettingsPage && selectedVideoFolder == null) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackPressTime = currentTime
            AppToast.show("Press back again to exit")
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasPermission) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && hasPermission) {
                refreshScope.launch {
                    val newFolders = MediaScanner.scanVideos(context)
                    if (newFolders != videoFolders) {
                        videoFolders = newFolders
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            if (MediaScanner.cachedVideoFolders == null) {
                isScanning = true
            }
            videoFolders = MediaScanner.scanVideos(context)
            isScanning = false
        } else {
            isScanning = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = VideoIndigo,
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
                        text = "To find and play your video files without restrictions, please grant all-files storage access.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = VideoIndigo),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Grant Permission", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    VideoFoldersScreen(
                        folders = videoFolders,
                        isScanning = isScanning,
                        onFolderClick = { folder -> selectedVideoFolder = folder },
                        onVideoClick = { video, list -> onPlayVideo(video, list) },
                        onOpenSearch = { showSearchPage = true },
                        onOpenSettings = { showSettingsPage = true },
                        currentThemeMode = currentThemeMode,
                        onThemeChange = onThemeChange,
                        onRefreshLibrary = {
                            refreshScope.launch {
                                videoFolders = MediaScanner.scanVideos(context)
                            }
                        }
                    )

                    if (selectedVideoFolder != null) {
                        FolderVideosScreen(
                            folder = selectedVideoFolder!!,
                            onBackClick = { selectedVideoFolder = null },
                            onVideoClick = { video, list -> onPlayVideo(video, list) },
                            onRefreshLibrary = {
                                refreshScope.launch {
                                    val fresh = MediaScanner.scanVideos(context)
                                    videoFolders = fresh
                                    selectedVideoFolder = fresh.firstOrNull { it.path == selectedVideoFolder?.path }
                                }
                            }
                        )
                    }

                    // Dedicated Search Screen with Material 3 Scale + Vertical Slide Animation (matching Music player)
                    AnimatedVisibility(
                        visible = showSearchPage,
                        enter = fadeIn(tween(260)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                slideInVertically(initialOffsetY = { it / 7 }, animationSpec = tween(260, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(180)) +
                                scaleOut(targetScale = 0.92f, animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                slideOutVertically(targetOffsetY = { it / 7 }, animationSpec = tween(180, easing = FastOutSlowInEasing))
                    ) {
                        VideoSearchScreen(
                            folders = videoFolders,
                            onVideoClick = onPlayVideo,
                            onFolderClick = { folder ->
                                showSearchPage = false
                                selectedVideoFolder = folder
                            },
                            onBack = { showSearchPage = false }
                        )
                    }

                    // Dedicated Video Settings Screen with Material 3 Scale + Vertical Slide Animation (matching Music player)
                    AnimatedVisibility(
                        visible = showSettingsPage,
                        enter = fadeIn(tween(260)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                slideInVertically(initialOffsetY = { it / 7 }, animationSpec = tween(260, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(180)) +
                                scaleOut(targetScale = 0.92f, animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                slideOutVertically(targetOffsetY = { it / 7 }, animationSpec = tween(180, easing = FastOutSlowInEasing))
                    ) {
                        VideoSettingsScreen(
                            currentThemeMode = currentThemeMode,
                            onThemeChange = onThemeChange,
                            videoFolders = videoFolders,
                            onRescanLibrary = {
                                refreshScope.launch {
                                    videoFolders = MediaScanner.scanVideos(context)
                                }
                            },
                            onBack = { showSettingsPage = false }
                        )
                    }
                }
            }

            GlobalAppToastHost()
        }
    }
}
