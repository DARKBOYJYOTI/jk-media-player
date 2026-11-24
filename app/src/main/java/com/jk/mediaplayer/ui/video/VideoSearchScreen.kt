package com.jk.mediaplayer.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.jk.mediaplayer.model.VideoFolder
import com.jk.mediaplayer.model.VideoItem
import com.jk.mediaplayer.ui.theme.LightSurface
import com.jk.mediaplayer.ui.theme.LightTextPrimary
import com.jk.mediaplayer.ui.theme.LightTextSecondary
import com.jk.mediaplayer.ui.theme.VideoIndigo
import java.io.File

@Composable
fun VideoSearchScreen(
    folders: List<VideoFolder>,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    onFolderClick: (VideoFolder) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    BackHandler {
        onBack()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val allVideos = remember(folders) { folders.flatMap { it.videos } }

    fun fuzzyMatches(text: String, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val target = text.lowercase()
        if (target.contains(q)) return true
        val tokens = q.split(" ").filter { it.isNotBlank() }
        return tokens.all { target.contains(it) }
    }

    val matchedFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else folders.filter { folder ->
            fuzzyMatches(folder.name, searchQuery) || fuzzyMatches(folder.path, searchQuery)
        }
    }

    val matchedVideos = remember(allVideos, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else allVideos.filter { video ->
            fuzzyMatches(video.title, searchQuery) ||
                fuzzyMatches(video.folderName, searchQuery) ||
                fuzzyMatches(video.path, searchQuery)
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val topBarBg = if (isDark) Color(0xFF0F0F14) else LightSurface
    val textPrimary = if (isDark) Color.White else LightTextPrimary
    val textSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                color = topBarBg,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Search videos, folders...",
                                color = textSecondary,
                                fontSize = 17.sp
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = textSecondary
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                            unfocusedContainerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .padding(end = 8.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (searchQuery.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Search all your local videos",
                        color = textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${allVideos.size} videos available across ${folders.size} folders",
                        color = textSecondary,
                        fontSize = 13.sp
                    )
                }
            } else if (matchedVideos.isEmpty() && matchedFolders.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No matching videos or folders found",
                        color = textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Try searching with a different title or keyword",
                        color = textSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp + navBarBottom, top = 8.dp)
                ) {
                    if (matchedFolders.isNotEmpty()) {
                        item {
                            Text(
                                text = "FOLDERS (${matchedFolders.size})",
                                color = VideoIndigo,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        items(matchedFolders, key = { it.path }) { folder ->
                            SearchFolderRow(
                                folder = folder,
                                onClick = { onFolderClick(folder) },
                                textPrimary = textPrimary,
                                textSecondary = textSecondary
                            )
                        }
                    }

                    if (matchedVideos.isNotEmpty()) {
                        item {
                            Text(
                                text = "VIDEOS (${matchedVideos.size})",
                                color = VideoIndigo,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }
                        items(matchedVideos, key = { it.id }) { video ->
                            SearchVideoRow(
                                video = video,
                                onClick = { onVideoClick(video, matchedVideos) },
                                textPrimary = textPrimary,
                                textSecondary = textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFolderRow(
    folder: VideoFolder,
    onClick: () -> Unit,
    textPrimary: Color,
    textSecondary: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = VideoIndigo.copy(alpha = 0.15f),
            modifier = Modifier.size(50.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = VideoIndigo,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                color = textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${folder.videos.size} videos • ${folder.path}",
                color = textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchVideoRow(
    video: VideoItem,
    onClick: () -> Unit,
    textPrimary: Color,
    textSecondary: Color
) {
    val context = LocalContext.current
    val imageRequest = remember(video.id, video.dateModified) {
        val model: Any = if (video.path.isNotEmpty() && File(video.path).exists()) File(video.path) else video.uri
        ImageRequest.Builder(context)
            .data(model)
            .videoFrameMillis(2000)
            .size(160, 90)
            .memoryCacheKey("vthumb_${video.id}_${video.dateModified}")
            .diskCacheKey("vthumb_${video.id}_${video.dateModified}")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with duration badge
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E24))
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                Text(
                    text = video.durationFormatted,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${video.folderName} • ${video.resolutionBadge}",
                color = textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

