package com.jk.mediaplayer.ui.video

import com.jk.mediaplayer.ui.common.AppToast

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.jk.mediaplayer.data.PreferencesManager
import com.jk.mediaplayer.data.VideoSortField
import com.jk.mediaplayer.data.VideoThumbnailCache
import com.jk.mediaplayer.model.VideoFolder
import com.jk.mediaplayer.model.VideoItem
import com.jk.mediaplayer.ui.common.NetworkStreamDialog
import com.jk.mediaplayer.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoFoldersScreen(
    folders: List<VideoFolder>,
    onFolderClick: (VideoFolder) -> Unit,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
    isScanning: Boolean = false,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenThemeDialog: () -> Unit = {},
    currentThemeMode: String = "SYSTEM",
    onThemeChange: (String) -> Unit = {},
    onRefreshLibrary: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val isNetworkStreamIconEnabled = remember(PreferencesManager.settingsVersion) {
        prefsManager.isNetworkStreamIconEnabled()
    }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Folders, 1 = All Videos

    var isFolderGrid by remember { mutableStateOf(prefsManager.getVideoViewMode(null)) }
    var folderSortField by remember { mutableStateOf(prefsManager.getVideoSortField(null)) }
    var folderSortAscending by remember { mutableStateOf(prefsManager.getVideoSortAscending(null)) }

    var isVideosGrid by remember { mutableStateOf(prefsManager.getVideoViewMode(null)) }
    var videosSortField by remember { mutableStateOf(prefsManager.getVideoSortField(null)) }
    var videosSortAscending by remember { mutableStateOf(prefsManager.getVideoSortAscending(null)) }

    // Persistent scroll states across tab/view switching and back navigation
    val foldersListState = rememberLazyListState()
    val foldersGridState = rememberLazyGridState()
    val videosListState = rememberLazyListState()
    val videosGridState = rememberLazyGridState()

    var showViewSortDialog by remember { mutableStateOf(false) }
    var showStreamDialog by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Dialog Targets
    var actionVideo by remember { mutableStateOf<VideoItem?>(null) }
    var detailsVideo by remember { mutableStateOf<VideoItem?>(null) }
    var deleteConfirmVideo by remember { mutableStateOf<VideoItem?>(null) }
    val deleteVideoAction = rememberVideoDeleteAction { onRefreshLibrary?.invoke() }
    var blacklistConfirmVideo by remember { mutableStateOf<VideoItem?>(null) }

    var actionFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var detailsFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var deleteConfirmFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var blacklistConfirmFolder by remember { mutableStateOf<VideoFolder?>(null) }

    // Pull to refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val refreshScope = rememberCoroutineScope()

    val allVideos = remember(folders) { folders.flatMap { it.videos } }

    // Intelligent Multi-Token Fuzzy Matching
    fun fuzzyMatches(text: String, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val target = text.lowercase()
        if (target.contains(q)) return true
        val tokens = q.split(" ").filter { it.isNotBlank() }
        return tokens.all { target.contains(it) }
    }

    val filteredFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter { folder ->
            fuzzyMatches(folder.name, searchQuery) ||
                fuzzyMatches(folder.path, searchQuery) ||
                folder.videos.any { fuzzyMatches(it.title, searchQuery) }
        }
    }

    val sortedFolders = remember(filteredFolders, folderSortField, folderSortAscending) {
        when (folderSortField) {
            VideoSortField.NAME -> if (folderSortAscending) filteredFolders.sortedBy { it.name.lowercase() } else filteredFolders.sortedByDescending { it.name.lowercase() }
            VideoSortField.DATE -> if (folderSortAscending) filteredFolders.sortedBy { f -> f.videos.maxOfOrNull { it.dateModified } ?: 0L } else filteredFolders.sortedByDescending { f -> f.videos.maxOfOrNull { it.dateModified } ?: 0L }
            VideoSortField.SIZE -> if (folderSortAscending) filteredFolders.sortedBy { f -> f.videos.sumOf { it.size } } else filteredFolders.sortedByDescending { f -> f.videos.sumOf { it.size } }
        }
    }

    val filteredVideos = remember(allVideos, searchQuery) {
        if (searchQuery.isBlank()) allVideos
        else allVideos.filter {
            fuzzyMatches(it.title, searchQuery) ||
                fuzzyMatches(it.folderName, searchQuery) ||
                fuzzyMatches(it.path, searchQuery)
        }
    }

    val sortedVideos = remember(filteredVideos, videosSortField, videosSortAscending) {
        when (videosSortField) {
            VideoSortField.NAME -> if (videosSortAscending) filteredVideos.sortedBy { it.title.lowercase() } else filteredVideos.sortedByDescending { it.title.lowercase() }
            VideoSortField.DATE -> if (videosSortAscending) filteredVideos.sortedBy { it.dateModified } else filteredVideos.sortedByDescending { it.dateModified }
            VideoSortField.SIZE -> if (videosSortAscending) filteredVideos.sortedBy { it.size } else filteredVideos.sortedByDescending { it.size }
        }
    }

    val watchedVer = PreferencesManager.watchedVersion
    val folderUnplayedCounts = remember(sortedFolders, watchedVer) {
        sortedFolders.associate { folder ->
            folder.path to folder.videos.count { prefsManager.isVideoNew(it.path, it.dateModified) }
        }
    }

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val fullPageGradient = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    Color(0xFF0F153E), // Luminous Royal Indigo glow at header
                    Color(0xFF080C26), // Smooth mid transition
                    Color(0xFF040614), // Deep lower fade
                    Color(0xFF000000)  // AMOLED pure black at bottom
                )
            } else {
                listOf(
                    Color(0xFFECEFFF), // Soft luminous Indigo pastel at header
                    Color(0xFFF2F5FF), // Mid pastel fade
                    Color(0xFFFAFAFC), // Crisp clean white/light at bottom
                    Color(0xFFF4F5F8)
                )
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(fullPageGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Modern Video Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = VideoIndigo,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "JK Videos",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Network Stream
                    if (isNetworkStreamIconEnabled) {
                        IconButton(
                            onClick = { showStreamDialog = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Network Stream",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Search (Opens dedicated full VideoSearchScreen)
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Grid / List View & Sort Dialog
                    IconButton(
                        onClick = { showViewSortDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        val currentGrid = if (selectedTab == 0) isFolderGrid else isVideosGrid
                        Icon(
                            imageVector = if (currentGrid) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = "View and Sort",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Settings Icon (Opens dedicated full VideoSettingsScreen)
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Search input field with clear action
            AnimatedVisibility(visible = isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search videos or folders...", fontSize = 15.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = VideoIndigo) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                        unfocusedContainerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // Tab Pills (Folders | All Videos)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val tabs = listOf("Folders", "All Videos")
                tabs.forEachIndexed { index, tabName ->
                    val isSelected = selectedTab == index
                    val tabBg = if (isSelected) VideoIndigo else (if (isDark) Color(0xFF161824) else Color(0xFFF0F2F8))
                    val tabBorder = if (isSelected) VideoIndigo else (if (isDark) Color(0xFF2C2F42) else Color(0xFFD6DAE8))
                    val tabTextColor = if (isSelected) Color.White else (if (isDark) Color(0xFFA0A6C0) else Color(0xFF4A4E69))
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = tabBg,
                        border = BorderStroke(1.dp, tabBorder),
                        shadowElevation = if (isSelected) 3.dp else 0.dp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { selectedTab = index }
                    ) {
                        Text(
                            text = tabName,
                            color = tabTextColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            val listBottomPadding = 76.dp + navBarBottom

            // Content (pull down to rescan device storage)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (isRefreshing) return@PullToRefreshBox
                    isRefreshing = true
                    refreshScope.launch {
                        val startedAt = System.currentTimeMillis()
                        try {
                            onRefreshLibrary?.invoke()
                        } catch (e: Exception) {
                        }
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (elapsed < 650L) kotlinx.coroutines.delay(650L - elapsed)
                        isRefreshing = false
                    }
                },
                state = pullRefreshState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = isRefreshing,
                        color = VideoIndigo,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                    )
                }
            ) {
                if (selectedTab == 0) {
                    // Folders
                    if (isScanning && folders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = VideoIndigo,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    } else if (filteredFolders.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isDark) Color(0xFF161824) else Color(0xFFECEFF8),
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = VideoIndigo,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isBlank()) "No Videos Found" else "No matching folders found",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (searchQuery.isBlank()) "Video files on your device storage will appear here." else "Try searching with a different folder or file name.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else if (isFolderGrid) {
                        // Grid View
                        LazyVerticalGrid(
                            state = foldersGridState,
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = listBottomPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(sortedFolders, key = { it.path }, contentType = { "folder" }) { folder ->
                                FolderCard(
                                    folder = folder,
                                    unplayedCount = folderUnplayedCounts[folder.path] ?: 0,
                                    onClick = { onFolderClick(folder) },
                                    onMoreClick = { actionFolder = folder }
                                )
                            }
                        }
                    } else {
                        // List View (with unplayed count badge)
                        LazyColumn(
                            state = foldersListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = listBottomPadding)
                        ) {
                            items(sortedFolders, key = { it.path }, contentType = { "folder" }) { folder ->
                                FolderRowItem(
                                    folder = folder,
                                    unplayedCount = folderUnplayedCounts[folder.path] ?: 0,
                                    onClick = { onFolderClick(folder) },
                                    onMoreClick = { actionFolder = folder }
                                )
                            }
                        }
                    }
                } else {
                    // All Videos List
                    if (isScanning && folders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = VideoIndigo,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    } else if (sortedVideos.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isDark) Color(0xFF161824) else Color(0xFFECEFF8),
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = VideoIndigo,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isBlank()) "No Videos Found" else "No matching videos found",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (searchQuery.isBlank()) "Video files on your device storage will appear here." else "Try searching with a different video title.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else if (isVideosGrid) {
                        LazyVerticalGrid(
                            state = videosGridState,
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = listBottomPadding),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(sortedVideos, key = { it.id }, contentType = { "video_grid" }) { video ->
                                VideoGridCard(
                                    video = video,
                                    onClick = { onVideoClick(video, sortedVideos) },
                                    onMoreClick = { actionVideo = video }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = videosListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = listBottomPadding)
                        ) {
                            items(sortedVideos, key = { it.id }, contentType = { "video" }) { video ->
                                VideoRowItem(
                                    video = video,
                                    onClick = { onVideoClick(video, sortedVideos) },
                                    onMoreClick = { actionVideo = video }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Action Button (FAB) at Bottom-Right to Resume/Play
        if (allVideos.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    val prefs = PreferencesManager(context)
                    val lastPath = prefs.getLastPlayedVideoPath()
                    val targetVideo = if (!lastPath.isNullOrEmpty()) {
                        allVideos.firstOrNull { it.path == lastPath } ?: allVideos.first()
                    } else {
                        allVideos.first()
                    }
                    onVideoClick(targetVideo, allVideos)
                },
                containerColor = VideoIndigo,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp, end = 20.dp)
                    .size(68.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Resume Video",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }

    // View & Sort Dialog
    if (showViewSortDialog) {
        VideoViewSortDialog(
            currentIsGrid = if (selectedTab == 0) isFolderGrid else isVideosGrid,
            currentSortField = if (selectedTab == 0) folderSortField else videosSortField,
            currentSortAscending = if (selectedTab == 0) folderSortAscending else videosSortAscending,
            folderName = if (selectedTab == 0) "Folders" else "All Videos",
            onDismiss = { showViewSortDialog = false },
            onApply = { newGrid, newField, newAsc, _ ->
                if (selectedTab == 0) {
                    isFolderGrid = newGrid
                    folderSortField = newField
                    folderSortAscending = newAsc
                    prefsManager.saveVideoViewMode(newGrid, null, false)
                    prefsManager.saveVideoSort(newField, newAsc, null, false)
                } else {
                    isVideosGrid = newGrid
                    videosSortField = newField
                    videosSortAscending = newAsc
                    prefsManager.saveVideoViewMode(newGrid, null, false)
                    prefsManager.saveVideoSort(newField, newAsc, null, false)
                }
            }
        )
    }

    // Network Stream Dialog
    if (showStreamDialog) {
        NetworkStreamDialog(
            isOpen = true,
            accentColor = VideoIndigo,
            isVideoMode = true,
            onDismiss = { showStreamDialog = false },
            onPlay = { url, username, password, isVideo ->
                showStreamDialog = false
                val streamUser = username.ifBlank { null }
                val streamPass = password.ifBlank { null }
                if (!isVideo) {
                    val musicIntent = Intent(context, com.jk.mediaplayer.ui.MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = android.net.Uri.parse(url)
                    }
                    context.startActivity(musicIntent)
                } else {
                    val playIntent = Intent(context, VideoPlayerActivity::class.java).apply {
                        putExtra("EXTRA_VIDEO_URI", url)
                        putExtra("EXTRA_VIDEO_PATH", url)
                        putExtra("EXTRA_VIDEO_TITLE", android.net.Uri.parse(url).lastPathSegment ?: "Network Stream")
                        putExtra("EXTRA_STREAM_USER", streamUser)
                        putExtra("EXTRA_STREAM_PASS", streamPass)
                    }
                    context.startActivity(playIntent)
                }
            }
        )
    }

    // Video Action Options Modal Dialog
    actionVideo?.let { targetVideo ->
        VideoActionDialog(
            video = targetVideo,
            onDismiss = { actionVideo = null },
            onPlayInPip = {
                val prefsManager = PreferencesManager(context)
                prefsManager.markVideoWatched(targetVideo.path)
                VideoPlayerActivity.playlist = allVideos
                val idx = allVideos.indexOfFirst { it.id == targetVideo.id }
                VideoPlayerActivity.currentVideoIndex = if (idx >= 0) idx else 0
                val playIntent = Intent(context, VideoPlayerActivity::class.java).apply {
                    putExtra("EXTRA_VIDEO_URI", targetVideo.uri.toString())
                    putExtra("EXTRA_VIDEO_PATH", targetVideo.path)
                    putExtra("EXTRA_VIDEO_TITLE", targetVideo.title)
                    putExtra("EXTRA_IS_PORTRAIT", targetVideo.isPortrait)
                    putExtra(VideoPlayerActivity.EXTRA_START_IN_PIP, true)
                }
                context.startActivity(playIntent)
                actionVideo = null
            },
            onShare = {
                shareVideo(context, targetVideo)
                actionVideo = null
            },
            onDetails = {
                detailsVideo = targetVideo
                actionVideo = null
            },
            onDelete = {
                deleteConfirmVideo = targetVideo
                actionVideo = null
            },
            onBlacklist = {
                blacklistConfirmVideo = targetVideo
                actionVideo = null
            }
        )
    }

    // Video Blacklist Confirmation Dialog
    blacklistConfirmVideo?.let { targetVideo ->
        com.jk.mediaplayer.ui.common.BlacklistConfirmDialog(
            title = "Blacklist Video?",
            itemName = targetVideo.title,
            accentColor = VideoIndigo,
            onConfirm = {
                val prefs = PreferencesManager(context)
                prefs.addVideoBlacklistedFile(targetVideo.path)
                com.jk.mediaplayer.data.MediaScanner.cachedVideoFolders = null
                onRefreshLibrary?.invoke()
                com.jk.mediaplayer.ui.common.AppToast.show("\"${targetVideo.title}\" added to blacklist")
                blacklistConfirmVideo = null
            },
            onDismiss = { blacklistConfirmVideo = null }
        )
    }

    // Video Info Modal Dialog (IMDb Movie Details + Tech Specs)
    detailsVideo?.let { targetVideo ->
        VideoInfoDialog(
            video = targetVideo,
            onDismiss = { detailsVideo = null }
        )
    }

    // Video Delete Confirmation Dialog
    deleteConfirmVideo?.let { targetVideo ->
        AlertDialog(
            onDismissRequest = { deleteConfirmVideo = null },
            shape = RoundedCornerShape(22.dp),
            containerColor = if (isDark) Color(0xFF14151B) else Color.White,
            titleContentColor = if (isDark) Color.White else Color(0xFF111216),
            textContentColor = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.94f),
            title = { Text("Delete Video?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete \"${targetVideo.title}\"?") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteVideoAction(context, targetVideo)
                        deleteConfirmVideo = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmVideo = null }) {
                    Text("Cancel", color = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280))
                }
            }
        )
    }

    // Folder Action Options Modal Dialog
    actionFolder?.let { targetFolder ->
        FolderActionDialog(
            folder = targetFolder,
            onDismiss = { actionFolder = null },
            onDetails = {
                detailsFolder = targetFolder
                actionFolder = null
            },
            onDelete = {
                deleteConfirmFolder = targetFolder
                actionFolder = null
            },
            onBlacklist = {
                blacklistConfirmFolder = targetFolder
                actionFolder = null
            }
        )
    }

    // Folder Blacklist Confirmation Dialog
    blacklistConfirmFolder?.let { targetFolder ->
        com.jk.mediaplayer.ui.common.BlacklistConfirmDialog(
            title = "Blacklist Folder?",
            itemName = targetFolder.name,
            accentColor = VideoIndigo,
            onConfirm = {
                val prefs = PreferencesManager(context)
                if (targetFolder.path.isNotBlank()) {
                    prefs.addVideoBlacklistedFolder(targetFolder.path)
                }
                targetFolder.videos.forEach { video ->
                    val p = try { java.io.File(video.path).parent ?: "" } catch (_: Exception) { "" }
                    if (p.isNotBlank()) {
                        prefs.addVideoBlacklistedFolder(p)
                    }
                }
                com.jk.mediaplayer.data.MediaScanner.cachedVideoFolders = null
                onRefreshLibrary?.invoke()
                com.jk.mediaplayer.ui.common.AppToast.show("\"${targetFolder.name}\" added to blacklist")
                blacklistConfirmFolder = null
            },
            onDismiss = { blacklistConfirmFolder = null }
        )
    }

    // Folder Details Modal Dialog
    detailsFolder?.let { targetFolder ->
        FolderDetailsDialog(
            folder = targetFolder,
            onDismiss = { detailsFolder = null }
        )
    }

    // Folder Delete Confirmation Dialog (Rounded Rectangle & Theme adaptive)
    deleteConfirmFolder?.let { targetFolder ->
        AlertDialog(
            onDismissRequest = { deleteConfirmFolder = null },
            shape = RoundedCornerShape(22.dp),
            containerColor = if (isDark) Color(0xFF14151B) else Color.White,
            titleContentColor = if (isDark) Color.White else Color(0xFF111216),
            textContentColor = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.94f),
            title = { Text("Delete Folder?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete \"${targetFolder.name}\" and all ${targetFolder.videoCount} videos in it?") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteFolderFiles(context, targetFolder) {
                            onRefreshLibrary?.invoke()
                        }
                        deleteConfirmFolder = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmFolder = null }) {
                    Text("Cancel", color = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280))
                }
            }
        )
    }

    // Video Settings Modal Dialog
    if (showSettingsDialog) {
        VideoSettingsDialog(
            currentThemeMode = currentThemeMode,
            onThemeChange = onThemeChange,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun VideoUnplayedBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val text = if (count > 99) "99+" else "$count"
    val isMulti = count > 9
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFA2D48),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.background),
        shadowElevation = 2.dp,
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .height(20.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = if (isMulti) 5.dp else 2.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 13.sp
            )
        }
    }
}

/**
 * Folder Row (with red count badge & 3-dot menu)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRowItem(
    folder: VideoFolder,
    unplayedCount: Int,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onMoreClick()
                }
            )
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder Icon with Red Unplayed Badge (prominent folder, tight spacing)
        Box(modifier = Modifier.size(58.dp)) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = VideoIndigo,
                modifier = Modifier
                    .size(52.dp)
                    .align(Alignment.CenterStart)
            )

            VideoUnplayedBadge(
                count = unplayedCount,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (folder.videoCount == 1) "1 video" else "${folder.videoCount} videos",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }

        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Folder Card for Grid View (with 3-dot menu & long press)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: VideoFolder,
    unplayedCount: Int,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onMoreClick()
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(66.dp)) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = VideoIndigo,
                        modifier = Modifier
                            .size(56.dp)
                            .align(Alignment.CenterStart)
                    )

                    VideoUnplayedBadge(
                        count = unplayedCount,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }

                IconButton(onClick = onMoreClick, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = folder.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (folder.videoCount == 1) "1 video" else "${folder.videoCount} videos",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Video Row Item (with Caching, Resolution Badge, and 3-dot menu)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRowItem(
    video: VideoItem,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbFile = remember(video.id, video.dateModified) {
        VideoThumbnailCache.getThumbnailFile(context, video)
    }
    val thumbRequest = remember(video.id, video.dateModified) {
        val model: Any = if (thumbFile.exists() && thumbFile.length() > 0) thumbFile else video
        ImageRequest.Builder(context)
            .data(model)
            .size(230, 140)
            .memoryCacheKey("vthumb_v2_${video.id}_${video.dateModified}")
            .diskCacheKey("vthumb_v2_${video.id}_${video.dateModified}")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with Duration & Resume Progress
        Box(
            modifier = Modifier
                .width(110.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = thumbRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Duration badge bottom-right
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                Text(
                    text = video.durationFormatted,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // New Badge top-left (reactive to watched state)
            val context = LocalContext.current
            val prefsManager = remember { PreferencesManager(context) }
            val watchedVer = PreferencesManager.watchedVersion
            val isVideoNew = remember(video.path, watchedVer) {
                prefsManager.isVideoNew(video.path, video.dateModified)
            }
            if (isVideoNew) {
                Surface(
                    shape = RoundedCornerShape(bottomEnd = 4.dp),
                    color = Color(0xFFFA2D48),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = "NEW",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            // Resume progress bar at bottom
            if (video.resumePosition > 0 && video.duration > 0) {
                val progress = (video.resumePosition.toFloat() / video.duration).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = VideoIndigo,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val badge = video.resolutionBadge
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = VideoIndigo.copy(alpha = 0.15f),
                        border = BorderStroke(0.5.dp, VideoIndigo.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = badge,
                            color = VideoIndigo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Text(
                    text = "${video.folderName} • ${video.sizeFormatted}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionDialog(
    folder: VideoFolder,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onBlacklist: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = VideoIndigo.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = VideoIndigo,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${folder.videoCount} videos • ${folder.totalDurationFormatted}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            // Details Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onDetails)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text("Details", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            }

            // Blacklist Folder Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBlacklist)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Block, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text("Blacklist Folder", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            }

            // Delete Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onDelete)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text("Delete Folder", fontSize = 16.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FolderDetailsDialog(
    folder: VideoFolder,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(0.94f)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Folder Details", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("Folder Name", folder.name)
                    DetailRow("Path", folder.path)
                    DetailRow("Total Videos", "${folder.videoCount} videos")
                    DetailRow("Total Duration", folder.totalDurationFormatted)
                    DetailRow("Total Storage Size", folder.totalSizeFormatted)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = VideoIndigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("OK", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VideoSettingsDialog(
    currentThemeMode: String,
    onThemeChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    var volBoost by remember { mutableStateOf(prefs.isVolumeBoostEnabled()) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(0.94f)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Video Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Theme Mode Section
                Text("Theme Mode", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                val themeOptions = listOf(
                    Triple("DARK", "Dark Mode (AMOLED)", Icons.Default.DarkMode),
                    Triple("LIGHT", "White Mode (Light)", Icons.Default.LightMode),
                    Triple("SYSTEM", "System Default", Icons.Default.BrightnessAuto)
                )

                themeOptions.forEach { (mode, label, icon) ->
                    val isSelected = (currentThemeMode == mode)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onThemeChange(mode) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onThemeChange(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = VideoIndigo)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(icon, contentDescription = null, tint = if (isSelected) VideoIndigo else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) VideoIndigo else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // 200% Volume Boost Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("200% Volume Boost", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Enable LibVLC software preamp boost up to 200%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = volBoost,
                        onCheckedChange = {
                            volBoost = it
                            prefs.saveVolumeBoostEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = VideoIndigo,
                            uncheckedThumbColor = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280),
                            uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                        )
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // Engine & App Info
                Column {
                    Text("Media Engine", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("LibVLC Android NDK 3.6.5 (Bit-Perfect)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = VideoIndigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done", color = Color.White)
                }
            }
        }
    }
}

private fun deleteFolderFiles(context: Context, folder: VideoFolder, onDeleted: () -> Unit) {
    try {
        var count = 0
        folder.videos.forEach { video ->
            try {
                File(video.path).delete()
                context.contentResolver.delete(video.uri, null, null)
                count++
            } catch (_: Exception) {}
        }
        AppToast.show("Deleted $count videos from ${folder.name}")
        onDeleted()
    } catch (e: Exception) {
        AppToast.show("Delete failed: ${e.message}")
    }
}
