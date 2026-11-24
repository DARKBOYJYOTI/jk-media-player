package com.jk.mediaplayer.ui.video

import com.jk.mediaplayer.ui.common.AppToast

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.jk.mediaplayer.ui.theme.VideoIndigo
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import java.io.File




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderVideosScreen(
    folder: VideoFolder,
    onBackClick: () -> Unit,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
    onRefreshLibrary: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    var isGridView by remember { mutableStateOf(prefsManager.getVideoViewMode(folder.path)) }
    var sortField by remember { mutableStateOf(prefsManager.getVideoSortField(folder.path)) }
    var sortAscending by remember { mutableStateOf(prefsManager.getVideoSortAscending(folder.path)) }
    var showViewSortDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
        keyboardController?.hide()
    }

    var actionVideo by remember { mutableStateOf<VideoItem?>(null) }
    var detailsVideo by remember { mutableStateOf<VideoItem?>(null) }
    var deleteConfirmVideo by remember { mutableStateOf<VideoItem?>(null) }
    val deleteVideoAction = rememberVideoDeleteAction { onRefreshLibrary() }
    var blacklistConfirmVideo by remember { mutableStateOf<VideoItem?>(null) }



    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val refreshScope = rememberCoroutineScope()

    val displayedVideos = remember(folder.videos, searchQuery, sortField, sortAscending) {
        val filtered = if (searchQuery.isBlank()) folder.videos else {
            val q = searchQuery.trim().lowercase()
            val tokens = q.split(" ").filter { it.isNotBlank() }
            folder.videos.filter { video ->
                val target = video.title.lowercase()
                tokens.all { target.contains(it) }
            }
        }
        when (sortField) {
            VideoSortField.NAME -> if (sortAscending) filtered.sortedBy { it.title.lowercase() } else filtered.sortedByDescending { it.title.lowercase() }
            VideoSortField.DATE -> if (sortAscending) filtered.sortedBy { it.dateModified } else filtered.sortedByDescending { it.dateModified }
            VideoSortField.SIZE -> if (sortAscending) filtered.sortedBy { it.size } else filtered.sortedByDescending { it.size }
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fullPageGradient = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    Color(0xFF0F153E),
                    Color(0xFF080C26),
                    Color(0xFF040614),
                    Color(0xFF000000)
                )
            } else {
                listOf(
                    Color(0xFFECEFFF),
                    Color(0xFFF2F5FF),
                    Color(0xFFFAFAFC),
                    Color(0xFFF4F5F8)
                )
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(fullPageGradient)
    ) {
        // Dynamic Animated Header (Seamlessly morphs into rounded search bar with keyboard auto-open/close)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.95f, animationSpec = tween(220, easing = FastOutSlowInEasing)))
                        .togetherWith(
                            fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                                    scaleOut(targetScale = 0.95f, animationSpec = tween(160, easing = FastOutSlowInEasing))
                        )
                },
                label = "FolderHeaderSearchTransition"
            ) { active ->
                if (active) {
                    // Modern Rounded Search Bar in Header (No oversized placeholder, pure clean single-line cursor)
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.replace("\n", "").replace("\r", "") },
                        leadingIcon = {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                keyboardController?.hide()
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear all",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        maxLines = 1,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                            unfocusedContainerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                } else {
                    // Normal Header Row with 16sp Title & clean 14dp gap before Search icon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 14.dp) // Clean 14dp gap so title does not bump into search icon
                        ) {
                            Text(
                                text = folder.name,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 17.sp, // Slightly smaller for premium balance
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${displayedVideos.size} videos",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Search Icon Button
                        IconButton(
                            onClick = { isSearchActive = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(2.dp))

                        // View and Sort Dialog Button
                        IconButton(
                            onClick = { showViewSortDialog = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = "View and Sort",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // Videos List / Grid with Pull to Refresh
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (isRefreshing) return@PullToRefreshBox
                isRefreshing = true
                refreshScope.launch {
                    val startedAt = System.currentTimeMillis()
                    try {
                        onRefreshLibrary()
                    } catch (e: Exception) {
                        e.printStackTrace()
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
            if (displayedVideos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching videos found" else "No videos in this folder",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp + navBarBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayedVideos, key = { it.id }, contentType = { "video_grid" }) { video ->
                        VideoGridCard(
                            video = video,
                            onClick = { onVideoClick(video, displayedVideos) },
                            onMoreClick = { actionVideo = video }
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp + navBarBottom)
                ) {
                    items(displayedVideos, key = { it.id }, contentType = { "video" }) { video ->
                        VideoListItem(
                            video = video,
                            onClick = { onVideoClick(video, displayedVideos) },
                            onMoreClick = { actionVideo = video }
                        )
                    }
                }
            }
        }
    }

    // Video Action Options Modal Dialog
    actionVideo?.let { targetVideo ->
        VideoActionDialog(
            video = targetVideo,
            onDismiss = { actionVideo = null },
            onPlayInPip = {
                val prefsManager = PreferencesManager(context)
                prefsManager.markVideoWatched(targetVideo.path)
                VideoPlayerActivity.playlist = folder.videos
                val idx = folder.videos.indexOfFirst { it.id == targetVideo.id }
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
                onRefreshLibrary()
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

    // Delete Confirmation Dialog (Rounded Rectangle & Theme adaptive)
    deleteConfirmVideo?.let { targetVideo ->
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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

    // View & Sort Dialog
    if (showViewSortDialog) {
        VideoViewSortDialog(
            currentIsGrid = isGridView,
            currentSortField = sortField,
            currentSortAscending = sortAscending,
            folderName = folder.name,
            onDismiss = { showViewSortDialog = false },
            onApply = { newGrid, newField, newAsc, applyToFolderOnly ->
                isGridView = newGrid
                sortField = newField
                sortAscending = newAsc
                prefsManager.saveVideoViewMode(newGrid, folder.path, applyToFolderOnly)
                prefsManager.saveVideoSort(newField, newAsc, folder.path, applyToFolderOnly)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoListItem(
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
        // Thumbnail with Duration & Resume Progress Bar
        Box(
            modifier = Modifier
                .width(115.dp)
                .height(70.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = thumbRequest,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Duration Badge (Bottom-Right)
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

            // "NEW" Badge (Top-Left, reactive to watched state)
            val prefsManager = remember { PreferencesManager(context) }
            val watchedVer = PreferencesManager.watchedVersion
            val isVideoNew = remember(video.path, watchedVer) {
                prefsManager.isVideoNew(video.path, video.dateModified)
            }
            if (isVideoNew) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFA2D48),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
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

            // Resume Progress Bar
            if (video.resumePosition > 0 && video.duration > 0) {
                val progress = (video.resumePosition.toFloat() / video.duration).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = VideoIndigo,
                    trackColor = Color.Transparent
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title & Metadata
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = MaterialTheme.colorScheme.onBackground,
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
                    text = video.sizeFormatted,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        // 3-Dot More Menu Button
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

@Composable
fun VideoActionDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onPlayInPip: () -> Unit,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onBlacklist: () -> Unit = {}
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
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = video.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${video.sizeFormatted} • ${video.durationFormatted}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                // Play in Pop-up (PiP) Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPlayInPip)
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("Play in Pop-up (PiP)", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                }

                // Share Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onShare)
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("Share", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                }

                // Info Option (IMDb Details & File Specs)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDetails)
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("Info", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                }

                // Blacklist Video Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBlacklist)
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("Blacklist Video", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                }

                // Delete Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDelete)
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("Delete", fontSize = 17.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Text(text = value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

fun shareVideo(context: Context, video: VideoItem) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            putExtra(Intent.EXTRA_SUBJECT, video.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
    } catch (e: Exception) {
        AppToast.show("Cannot share video: ${e.message}")
    }
}

fun deleteVideoFile(context: Context, video: VideoItem, onDeleted: () -> Unit) {
    try {
        var deleted = false
        val file = File(video.path)
        if (file.exists()) {
            deleted = file.delete()
        }
        try {
            val rows = context.contentResolver.delete(video.uri, null, null)
            if (rows > 0) deleted = true
        } catch (_: Exception) {}

        if (deleted) {
            AppToast.show("Video deleted successfully")
            onDeleted()
        } else {
            AppToast.show("Could not delete video file")
        }
    } catch (e: Exception) {
        AppToast.show("Delete failed: ${e.message}")
    }
}

/**
 * Returns a delete action that asks for system consent on scoped storage builds. When the delete
 * is allowed directly it falls back to [deleteVideoFile].
 */
@Composable
fun rememberVideoDeleteAction(onDeleted: () -> Unit): (Context, VideoItem) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppToast.show("Video deleted successfully")
            onDeleted()
        } else {
            AppToast.show("Delete cancelled")
        }
    }
    return remember(launcher) {
        { context, video ->
            val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                try {
                    MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri))
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            if (pending != null) {
                launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } else {
                deleteVideoFile(context, video, onDeleted)
            }
        }
    }
}
