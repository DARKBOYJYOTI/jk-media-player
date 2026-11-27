package com.jk.mediaplayer.ui.music

import com.jk.mediaplayer.ui.common.AppToast

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.jk.mediaplayer.R
import com.jk.mediaplayer.data.MediaScanner
import com.jk.mediaplayer.data.OnlineMetadataFetcher
import com.jk.mediaplayer.data.PreferencesManager
import com.jk.mediaplayer.model.AlbumItem
import com.jk.mediaplayer.model.ArtistItem
import com.jk.mediaplayer.model.AudioFolder
import com.jk.mediaplayer.model.CustomPlaylist
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.ui.common.NetworkStreamDialog
import com.jk.mediaplayer.model.formatDuration
import com.jk.mediaplayer.ui.theme.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

fun splitArtistNames(raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.equals("<unknown>", ignoreCase = true) || trimmed.equals("Unknown Artist", ignoreCase = true)) {
        return emptyList()
    }
    // Delimiters:
    // - Commas: ASCII (,), fullwidth (，), ideographic (、), small (﹐)
    // - Semicolons: ASCII (;), fullwidth (；)
    // - Slashes & backslashes: (/, ／, \, ＼)
    // - Pipes: (|, ｜)
    // - Ampersands: (&, ＆)
    // - Bullets / dots: (•, ·, ‧)
    // - Collaboration keywords: feat., ft., featuring, with, vs., x (surrounded by word boundaries)
    val regex = Regex("""[\s\u00A0\u3000]*(?:[,，、﹐;/／\\＼|｜&＆•·‧]|(?i:\b(?:feat\.?|ft\.?|featuring|with|vs\.?|x)\b))[\s\u00A0\u3000]*""")
    return trimmed.split(regex)
        .map { part ->
            var cleaned = part.trim { ch ->
                ch.isWhitespace() || ch == '\u00A0' || ch == '\u3000' || ch in "\"',;，、﹐|/"
            }
            // Strip only unmatched outer brackets so names like "Artist (Alias)" remain intact.
            if (cleaned.startsWith("(") && cleaned.endsWith(")") && cleaned.count { it == '(' } == 1 && cleaned.count { it == ')' } == 1) {
                cleaned = cleaned.substring(1, cleaned.length - 1).trim()
            } else if (cleaned.startsWith("(") && !cleaned.contains(")")) {
                cleaned = cleaned.removePrefix("(").trim()
            } else if (cleaned.endsWith(")") && !cleaned.contains("(")) {
                cleaned = cleaned.removeSuffix(")").trim()
            }
            cleaned
        }
        .filter { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) && !it.equals("Unknown Artist", ignoreCase = true) }
        .distinctBy { it.lowercase() }
}

fun resolveArtist(name: String, artists: List<ArtistItem>, tracks: List<AudioItem>): ArtistItem {
    // 1. Direct match with artist name
    val direct = artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (direct != null) return direct

    // 2. Check each split name component
    val split = splitArtistNames(name)
    for (part in split) {
        val match = artists.firstOrNull { it.name.equals(part, ignoreCase = true) }
        if (match != null) return match
    }

    // 3. Fallback: match if any known artist's name is contained inside 'name'
    val contained = artists.firstOrNull { it.name.length >= 3 && name.contains(it.name, ignoreCase = true) }
    if (contained != null) return contained

    // 4. Safe fallback: never use full composite string if it was split
    val fallbackName = split.firstOrNull()?.ifBlank { null } ?: name.trim()
    val matchingTracks = tracks.filter { it.artist.contains(fallbackName, ignoreCase = true) }
    return ArtistItem(
        name = fallbackName,
        trackCount = if (matchingTracks.isNotEmpty()) matchingTracks.size else 1,
        tracks = matchingTracks
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    tracks: List<AudioItem>,
    currentTrack: AudioItem?,
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isScanning: Boolean = false,
    isPlaying: Boolean = false,
    onTogglePlayPause: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onSearchVisibilityChange: (Boolean) -> Unit = {},
    isPlayerSheetExpanded: Boolean = false,
    onRescanLibrary: (suspend () -> Unit)? = null,
    onPlayStream: ((String, String?, String?, Boolean) -> Unit)? = null,
    onDeleteTrack: (AudioItem) -> Unit = {},
    targetArtistToOpen: String? = null,
    targetAlbumToOpen: String? = null,
    onResetTargetArtistAlbum: () -> Unit = {},
    playerExpansionProgress: () -> Float = { 0f }
) {
    val smartTrackClick: (List<AudioItem>, Int, String?) -> Unit = { list, index, source ->
        val clickedTrack = list.getOrNull(index)
        if (clickedTrack != null && clickedTrack.id == currentTrack?.id) {
            if (!isPlaying) {
                onTogglePlayPause()
            }
            onOpenPlayer()
        } else {
            onTrackClick(list, index, source)
        }
    }
    // Play all and Shuffle start the tapped list from the top: they rebuild the queue even when
    // the entry at the top of the list is the song that is already playing, so the button never
    // looks dead. Single row taps keep the smart behaviour above, where tapping the playing row
    // only opens the player.
    val startListFromTop: (List<AudioItem>, String, Boolean) -> Unit = { list, source, shuffle ->
        if (list.isNotEmpty()) {
            onTrackClick(if (shuffle) list.shuffled() else list, 0, source)
        }
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val surfaceColor = if (isDark) Color(0xFF141416) else LightSurface
    val surfaceVariantColor = if (isDark) Color(0xFF1C1C1C) else LightSurfaceVariant
    val borderColor = if (isDark) Color(0xFF262626) else LightBorder
    val bottomBarColor = if (isDark) Color(0xFF000000) else LightSurface
    val bottomBarBorder = if (isDark) Color(0xFF141414) else LightBorder

    // Bottom Navigation Tab (0 = Home, 1 = Songs, 2 = Albums, 3 = Artists, 4 = Playlists)
    var currentNavTab by remember { mutableIntStateOf(0) }

    var showSearchScreen by remember { mutableStateOf(false) }
    var showStreamDialog by remember { mutableStateOf(false) }
    LaunchedEffect(showSearchScreen) {
        onSearchVisibilityChange(showSearchScreen)
    }
    var showMenu by remember { mutableStateOf(false) }

    // Selected drilldown states
    var selectedFolder by remember { mutableStateOf<AudioFolder?>(null) }
    var selectedArtist by remember { mutableStateOf<ArtistItem?>(null) }
    var selectedAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var selectedPlaylist by remember { mutableStateOf<Pair<String, List<AudioItem>>?>(null) }
    var detailsTrack by remember { mutableStateOf<AudioItem?>(null) }
    var tagEditorTrack by remember { mutableStateOf<AudioItem?>(null) }
    var ringtoneCutterTrack by remember { mutableStateOf<AudioItem?>(null) }
    var blacklistConfirmTrack by remember { mutableStateOf<AudioItem?>(null) }
    var blacklistConfirmFolder by remember { mutableStateOf<AudioFolder?>(null) }

    // Intercept back button when song details modal or tag editor is active
    BackHandler(enabled = detailsTrack != null || tagEditorTrack != null) {
        if (tagEditorTrack != null) tagEditorTrack = null
        else detailsTrack = null
    }

    // Intercept back button when viewing a specific folder, artist, album, or playlist
    BackHandler(enabled = !isPlayerSheetExpanded && detailsTrack == null && (selectedFolder != null || selectedArtist != null || selectedAlbum != null || selectedPlaylist != null)) {
        selectedFolder = null
        selectedArtist = null
        selectedAlbum = null
        selectedPlaylist = null
    }

    // Intercept back button when search screen is active
    BackHandler(enabled = !isPlayerSheetExpanded && showSearchScreen) {
        showSearchScreen = false
    }

    // Intercept back button when on a sub-tab (Songs, Albums, Artists, Playlists) to return cleanly to Home
    BackHandler(enabled = !isPlayerSheetExpanded && !showSearchScreen && selectedFolder == null && selectedArtist == null && selectedAlbum == null && selectedPlaylist == null && currentNavTab != 0) {
        currentNavTab = 0
    }

    // Single back on root Home minimizes the app to the background instead of finishing it
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler(enabled = !isPlayerSheetExpanded && !showSearchScreen && selectedFolder == null && selectedArtist == null && selectedAlbum == null && selectedPlaylist == null && currentNavTab == 0) {
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }

    // Persistent scroll states to preserve exact user scroll position across back navigation
    val homeListState = rememberLazyListState()
    val songsListState = rememberLazyListState()
    val albumsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val artistsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val foldersListState = rememberLazyListState()

    val prefsManager = remember { com.jk.mediaplayer.data.PreferencesManager(context) }
    val isNetworkStreamIconEnabled = remember(com.jk.mediaplayer.data.PreferencesManager.settingsVersion) {
        prefsManager.isNetworkStreamIconEnabled()
    }
    val coroutineScope = rememberCoroutineScope()
    val playlistVersion = com.jk.mediaplayer.data.PreferencesManager.playlistVersion
    val favoriteIds = remember(tracks, playlistVersion) { prefsManager.getFavorites() }
    val likedTracks = remember(tracks, favoriteIds) {
        tracks.filter { favoriteIds.contains(it.id.toString()) }
    }

    // Artists sorted alphabetically, with smart multi-artist deduplication & grouping!
    val artists = remember(tracks) {
        val artistCaseMap = mutableMapOf<String, String>()
        val artistTracksMap = mutableMapOf<String, MutableList<AudioItem>>()

        tracks.forEach { track ->
            val splitList = splitArtistNames(track.artist)
            if (splitList.isEmpty()) {
                val key = "unknown artist"
                artistCaseMap.putIfAbsent(key, "Unknown Artist")
                artistTracksMap.getOrPut(key) { mutableListOf() }.add(track)
            } else {
                splitList.forEach { singleArtist ->
                    val lower = singleArtist.lowercase()
                    artistCaseMap.putIfAbsent(lower, singleArtist)
                    val list = artistTracksMap.getOrPut(lower) { mutableListOf() }
                    if (list.none { it.id == track.id }) {
                        list.add(track)
                    }
                }
            }
        }

        artistTracksMap.map { (lowerKey, trackList) ->
            val displayName = artistCaseMap[lowerKey] ?: lowerKey.replaceFirstChar { it.uppercase() }
            ArtistItem(
                name = displayName,
                trackCount = trackList.size,
                tracks = trackList,
                artistImageUrl = null
            )
        }.sortedWith(
            compareBy(
                { it.name.equals("Unknown Artist", ignoreCase = true) || it.name.isBlank() },
                { it.name.lowercase() }
            )
        )
    }

    // Albums sorted alphabetically, with Unknown Album strictly at the very end!
    val albums = remember(tracks) {
        tracks.groupBy { it.album.ifBlank { "Unknown Album" } }
            .map { (album, list) ->
                AlbumItem(
                    name = album,
                    artist = list.firstOrNull()?.artist ?: "Unknown Artist",
                    albumArtUri = list.firstOrNull()?.albumArtUri,
                    tracks = list
                )
            }.sortedWith(compareBy({ it.name.equals("Unknown Album", ignoreCase = true) || it.name.isBlank() }, { it.name }))
    }

    val folders = remember(tracks) {
        tracks.groupBy { it.folderPath.ifBlank { try { java.io.File(it.path).parent ?: it.folderName } catch (_: Exception) { it.folderName } } }
            .map { (fPath, list) ->
                val folderName = list.firstOrNull()?.folderName?.ifBlank { try { java.io.File(fPath).name } catch (_: Exception) { "Music" } } ?: "Music"
                AudioFolder(folderName, fPath, list)
            }.sortedBy { it.name.lowercase() }
    }

    // Number of user playlists, shown next to the section name in the header
    val customPlaylistCount = remember(playlistVersion) {
        prefsManager.getCustomPlaylists().size
    }

    LaunchedEffect(targetArtistToOpen, targetAlbumToOpen) {
        if (targetArtistToOpen != null) {
            val art = resolveArtist(targetArtistToOpen, artists, tracks)
            selectedArtist = art
            selectedAlbum = null
            selectedFolder = null
            selectedPlaylist = null
            onResetTargetArtistAlbum()
        } else if (targetAlbumToOpen != null) {
            val alb = albums.firstOrNull { it.name.equals(targetAlbumToOpen, ignoreCase = true) }
                ?: AlbumItem(targetAlbumToOpen, "", null, tracks.filter { it.album.equals(targetAlbumToOpen, ignoreCase = true) })
            selectedAlbum = alb
            selectedArtist = null
            selectedFolder = null
            selectedPlaylist = null
            onResetTargetArtistAlbum()
        }
    }

    // All tracks sorted by dateAdded descending (most recently added first)
    val recentlyAddedTracks = remember(tracks) {
        tracks.sortedByDescending { it.dateAdded }
    }

    // Artwork is loaded on demand: cards request it when they are composed (Coil interceptor) and
    // the visible artist cards fetch their photo in a LaunchedEffect. Eagerly walking the whole
    // library here fired hundreds of network calls and disk writes while the home list scrolled.

    // Pull-to-refresh: shared across every tab and drill-down view
    // A fresh random seed on every launch means the shelves reshuffle each time the app opens;
    // pull-to-refresh bumps it again.
    var refreshSeed by remember { mutableIntStateOf(kotlin.random.Random.nextInt(1, 1_000_000)) }
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val pullRefreshScope = rememberCoroutineScope()

    // The footer nav bar is already removed from the content area by the Scaffold insets, so the
    // lists only need room for the floating mini player. Reserving ~120dp unconditionally left a
    // visible empty band under the last row whenever no mini player was showing.
    val listBottomPadding = if (currentTrack != null) 96.dp else 16.dp

    // Dynamic Theme Color: changes on Artist or Album pages, defaults to MusicRed
    var dynamicAccentColor by remember { mutableStateOf(MusicRed) }
    LaunchedEffect(selectedAlbum, selectedArtist) {
        val targetTrack = selectedAlbum?.tracks?.firstOrNull() ?: selectedArtist?.tracks?.firstOrNull()
        if (targetTrack != null) {
            val extracted = AlbumArtColorExtractor.extractDominantColor(context, targetTrack)
            dynamicAccentColor = extracted
        } else {
            dynamicAccentColor = MusicRed
        }
    }

    // One opaque pass for the page background: the accent gradient is pre-blended over the solid
    // base colour, so no pixel needs alpha blending and the list is never covered twice.
    val pageSolidColor = if (isDark) Color(0xFF000000) else Color(0xFFF8F9FA)
    val gradientStops = remember(isDark, dynamicAccentColor) {
        val accent = dynamicAccentColor
        if (isDark) {
            listOf(
                accent.copy(alpha = 0.32f).compositeOver(Color.Black),
                accent.copy(alpha = 0.12f).compositeOver(Color.Black),
                Color(0xFF090A0F),
                Color(0xFF000000)
            )
        } else {
            listOf(
                accent.copy(alpha = 0.08f).compositeOver(pageSolidColor),
                accent.copy(alpha = 0.02f).compositeOver(pageSolidColor),
                Color(0xFFFFFFFF),
                pageSolidColor
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val gradientHeight = size.height * 0.55f
                val brush = Brush.verticalGradient(gradientStops, startY = 0f, endY = gradientHeight)
                onDrawBehind {
                    drawRect(
                        color = pageSolidColor,
                        topLeft = Offset(0f, gradientHeight),
                        size = Size(size.width, size.height - gradientHeight)
                    )
                    drawRect(brush = brush, size = Size(size.width, gradientHeight))
                }
            }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
        bottomBar = {
            // Footer Navigation Bar (Smoothly slides downwards & hides as player expands)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val progress = playerExpansionProgress().coerceIn(0f, 1f)
                        translationY = size.height * progress
                        alpha = (1f - progress * 1.5f).coerceIn(0f, 1f)
                    },
                color = bottomBarColor,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = if (isDark) Color(0xFF1A1A20) else Color(0xFFE5E7EB)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    val navItems = listOf(
                        Triple(0, "Home", Icons.Default.Home),
                        Triple(1, "Songs", Icons.Default.MusicNote),
                        Triple(2, "Albums", Icons.Default.Album),
                        Triple(3, "Artists", Icons.Default.Person),
                        Triple(4, "Playlists", Icons.AutoMirrored.Filled.QueueMusic)
                    )

                    navItems.forEach { (index, title, icon) ->
                        val isSelected = currentNavTab == index && selectedFolder == null && selectedArtist == null && selectedAlbum == null && selectedPlaylist == null
                        val tint = if (isSelected) (if (isDark) Color.White else MusicRed) else textColorSecondary

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    currentNavTab = index
                                    selectedFolder = null
                                    selectedArtist = null
                                    selectedAlbum = null
                                    selectedPlaylist = null
                                    // The nav bar stays visible under the search screen, so a tab
                                    // tap must also leave the search view.
                                    showSearchScreen = false
                                }
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = tint,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = title,
                                color = tint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            // Top App Bar: Emblem & JK Music on left, Search & Settings on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (selectedFolder != null || selectedArtist != null || selectedAlbum != null || selectedPlaylist != null) {
                    val detailTitle = selectedPlaylist?.first ?: selectedFolder?.name ?: selectedArtist?.name ?: selectedAlbum?.name ?: "Music"
                    val detailCount = if (selectedPlaylist?.first.equals("Liked Music", ignoreCase = true)) {
                        likedTracks.size
                    } else {
                        selectedPlaylist?.second?.size ?: selectedFolder?.tracks?.size ?: selectedArtist?.tracks?.size ?: selectedAlbum?.tracks?.size ?: 0
                    }
                    IconButton(
                        onClick = {
                            selectedFolder = null
                            selectedArtist = null
                            selectedAlbum = null
                            selectedPlaylist = null
                        },
                        modifier = Modifier.clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textColorPrimary
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = detailTitle,
                            color = textColorPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$detailCount tracks",
                            color = textColorSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    // Each tab names itself, so the header says where the user is. Home keeps the
                    // app emblem and brand; every other tab shows the section name and its size.
                    val tabTitle = when (currentNavTab) {
                        1 -> "All Songs"
                        2 -> "Albums"
                        3 -> "Artists"
                        4 -> "Playlists"
                        else -> "JK Music"
                    }
                    val tabSubtitle = when (currentNavTab) {
                        1 -> "${tracks.size} songs"
                        2 -> "${albums.size} albums"
                        3 -> "${artists.size} artists"
                        4 -> "$customPlaylistCount playlists"
                        else -> null
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        if (currentNavTab == 0) {
                            Surface(
                                shape = CircleShape,
                                color = MusicRed,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = "JK Music Emblem",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column {
                            Text(
                                text = tabTitle,
                                color = textColorPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                            if (tabSubtitle != null) {
                                Text(
                                    text = tabSubtitle,
                                    color = textColorSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Header Right: Stream + Search + Settings
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isNetworkStreamIconEnabled) {
                            IconButton(
                                onClick = { showStreamDialog = true },
                                modifier = Modifier.clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = "Network Stream",
                                    tint = textColorPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { showSearchScreen = true },
                            modifier = Modifier.clip(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = textColorPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.clip(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = textColorPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Pull to refresh (smooth indicator pinned under the header, works on all tabs)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (isRefreshing) return@PullToRefreshBox
                    isRefreshing = true
                    pullRefreshScope.launch {
                        val startedAt = System.currentTimeMillis()
                        try {
                            onRescanLibrary?.invoke()
                        } catch (e: Exception) {
                        }
                        // Reset active tab scroll position and shuffle the Home recommendations
                        try {
                            when (currentNavTab) {
                                0 -> homeListState.scrollToItem(0)
                                1 -> songsListState.scrollToItem(0)
                                2 -> albumsGridState.scrollToItem(0)
                                3 -> artistsGridState.scrollToItem(0)
                                4 -> foldersListState.scrollToItem(0)
                            }
                        } catch (_: Exception) {}
                        refreshSeed++
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
                        color = MusicRed,
                        containerColor = if (isDark) Color(0xFF222228) else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                    )
                }
            ) {
                // Keep last non-null detail targets so exit animations stay populated without flashing.
                var lastPlaylist by remember { mutableStateOf<Pair<String, List<AudioItem>>?>(null) }
                if (selectedPlaylist != null) lastPlaylist = selectedPlaylist
                var lastFolder by remember { mutableStateOf<AudioFolder?>(null) }
                if (selectedFolder != null) lastFolder = selectedFolder
                var lastArtist by remember { mutableStateOf<ArtistItem?>(null) }
                if (selectedArtist != null) lastArtist = selectedArtist
                var lastAlbum by remember { mutableStateOf<AlbumItem?>(null) }
                if (selectedAlbum != null) lastAlbum = selectedAlbum

                // Route key encodes whether we are on a root tab (Int) or a detail page (String).
                val pageRoute: Any = when {
                    selectedPlaylist != null -> "playlist:${selectedPlaylist!!.first}"
                    selectedFolder != null -> "folder:${selectedFolder!!.path}"
                    selectedArtist != null -> "artist:${selectedArtist!!.name}"
                    selectedAlbum != null -> "album:${selectedAlbum!!.name}"
                    else -> currentNavTab
                }

                // Main View Content with GPU-composited Material 3 shared-axis transitions
                androidx.compose.animation.AnimatedContent(
                    targetState = pageRoute,
                    transitionSpec = {
                        val from = initialState
                        val to = targetState
                        when {
                            from is Int && to is Int ->
                                com.jk.mediaplayer.ui.common.PageTransitions.tabSwitchTransform(forward = to > from)
                            from is Int && to is String ->
                                com.jk.mediaplayer.ui.common.PageTransitions.drillForwardTransform()
                            from is String && to is Int ->
                                com.jk.mediaplayer.ui.common.PageTransitions.drillBackwardTransform()
                            else ->
                                com.jk.mediaplayer.ui.common.PageTransitions.drillForwardTransform()
                        }
                    },
                    label = "musicPageTransition"
                ) { route ->
                when {
                    route is String && route.startsWith("playlist:") -> {
                        val activePl = selectedPlaylist ?: lastPlaylist
                        if (activePl != null) {
                        val currentSelectedName = activePl.first
                        val currentSelectedTracks = remember(activePl, tracks, favoriteIds) {
                            if (currentSelectedName.equals("Ringtones", ignoreCase = true)) {
                                val customPl = prefsManager.getCustomPlaylists().firstOrNull { it.name.equals("Ringtones", ignoreCase = true) }
                                val ids: List<Long> = customPl?.trackIds ?: emptyList()
                                tracks.filter {
                                    ids.contains(it.id) ||
                                    it.path.contains("jkmediaplayer", ignoreCase = true) ||
                                    it.title.contains("(ringtone)", ignoreCase = true)
                                }
                            } else if (currentSelectedName.equals("Liked Music", ignoreCase = true)) {
                                tracks.filter { favoriteIds.contains(it.id.toString()) }
                            } else {
                                activePl.second
                            }
                        }
                        PlaylistTracksView(
                            contentBottomPadding = listBottomPadding,
                            title = currentSelectedName,
                            tracks = currentSelectedTracks,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = smartTrackClick,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onPlayAllOrShuffle = startListFromTop,
                            onGoToAlbum = { alb ->
                                selectedAlbum = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) } ?: AlbumItem(alb, "", null, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                            },
                            onGoToArtist = { art ->
                                selectedArtist = resolveArtist(art, artists, tracks)
                            },
                            onShowDetails = { detailsTrack = it },
                            onOpenTagEditor = { tagEditorTrack = it },
                            onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                            onDeleteTrack = onDeleteTrack,
                            onBlacklistTrack = { blacklistConfirmTrack = it }
                        )
                        }
                    }
                    route is String && route.startsWith("folder:") -> {
                        val activeFolder = selectedFolder ?: lastFolder
                        if (activeFolder != null) {
                        FolderTracksView(
                            contentBottomPadding = listBottomPadding,
                            folder = activeFolder,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = smartTrackClick,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onPlayAllOrShuffle = startListFromTop,
                            onGoToAlbum = { alb ->
                                selectedAlbum = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) } ?: AlbumItem(alb, "", null, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                            },
                            onGoToArtist = { art ->
                                selectedArtist = resolveArtist(art, artists, tracks)
                            },
                            onShowDetails = { detailsTrack = it },
                            onOpenTagEditor = { tagEditorTrack = it },
                            onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                            onDeleteTrack = onDeleteTrack,
                            onBlacklistTrack = { blacklistConfirmTrack = it }
                        )
                        }
                    }
                    route is String && route.startsWith("artist:") -> {
                        val activeArtist = selectedArtist ?: lastArtist
                        if (activeArtist != null) {
                        ArtistTracksView(
                            contentBottomPadding = listBottomPadding,
                            artist = activeArtist,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = smartTrackClick,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onPlayAllOrShuffle = startListFromTop,
                            onGoToAlbum = { alb ->
                                selectedAlbum = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) } ?: AlbumItem(alb, "", null, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                            },
                            onGoToArtist = { art ->
                                selectedArtist = resolveArtist(art, artists, tracks)
                            },
                            onShowDetails = { detailsTrack = it },
                            onOpenTagEditor = { tagEditorTrack = it },
                            onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                            onDeleteTrack = onDeleteTrack,
                            onBlacklistTrack = { blacklistConfirmTrack = it }
                        )
                        }
                    }
                    route is String && route.startsWith("album:") -> {
                        val activeAlbum = selectedAlbum ?: lastAlbum
                        if (activeAlbum != null) {
                        AlbumTracksView(
                            contentBottomPadding = listBottomPadding,
                            album = activeAlbum,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = smartTrackClick,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onPlayAllOrShuffle = startListFromTop,
                            onGoToAlbum = { alb ->
                                selectedAlbum = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) } ?: AlbumItem(alb, "", null, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                            },
                            onGoToArtist = { art ->
                                selectedArtist = resolveArtist(art, artists, tracks)
                            },
                            onShowDetails = { detailsTrack = it },
                            onOpenTagEditor = { tagEditorTrack = it },
                            onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                            onDeleteTrack = onDeleteTrack,
                            onBlacklistTrack = { blacklistConfirmTrack = it }
                        )
                        }
                    }
                    else -> {
                        when (route as? Int ?: currentNavTab) {
                            0 -> {
                                // TAB 0: HOME LIBRARY (Swipeable sections, liked songs prioritized)
                                HomeSectionView(
                                    contentBottomPadding = listBottomPadding,
                                    tracks = tracks,
                                    recentlyAdded = recentlyAddedTracks,
                                    albums = albums,
                                    artists = artists,
                                    folders = folders,
                                    currentTrack = currentTrack,
                                    isPlaying = isPlaying,
                                    listState = homeListState,
                                    onTrackClick = smartTrackClick,
                                    onPlayNext = onPlayNext,
                                    onAddToQueue = onAddToQueue,
                                    onPlayAllOrShuffle = startListFromTop,
                                    onAlbumClick = { selectedAlbum = it },
                                    onArtistClick = { selectedArtist = it },
                                    onFolderClick = { selectedFolder = it },
                                    onOpenPlaylist = { name, list ->
                                        selectedPlaylist = Pair(name, list)
                                    },
                                    refreshSeed = refreshSeed,
                                    onShowDetails = { detailsTrack = it },
                                    onOpenTagEditor = { tagEditorTrack = it },
                                    onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                                    onDeleteTrack = onDeleteTrack,
                                    onBlacklistTrack = { blacklistConfirmTrack = it },
                                    isScanning = isScanning
                                )
                            }
                            1 -> {
                                // TAB 1: ALL SONGS
                                SongsListView(
                                    contentBottomPadding = listBottomPadding,
                                    tracks = tracks,
                                    currentTrack = currentTrack,
                                    isPlaying = isPlaying,
                                    listState = songsListState,
                                    onTrackClick = smartTrackClick,
                                    onPlayNext = onPlayNext,
                                    onAddToQueue = onAddToQueue,
                                    onPlayAllOrShuffle = startListFromTop,
                                    onGoToAlbum = { alb ->
                                        selectedAlbum = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) } ?: AlbumItem(alb, "", null, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                                    },
                                    onGoToArtist = { art ->
                                        selectedArtist = resolveArtist(art, artists, tracks)
                                    },
                                    onShowDetails = { detailsTrack = it },
                                    onOpenTagEditor = { tagEditorTrack = it },
                                    onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                                    onDeleteTrack = onDeleteTrack,
                                    onBlacklistTrack = { blacklistConfirmTrack = it },
                                    isScanning = isScanning
                                )
                            }
                            2 -> {
                                // TAB 2: ALBUMS GRID
                                AlbumsGridView(
                                    contentBottomPadding = listBottomPadding,
                                    albums = albums,
                                    gridState = albumsGridState,
                                    onAlbumClick = { selectedAlbum = it },
                                    isScanning = isScanning
                                )
                            }
                            3 -> {
                                // TAB 3: ARTISTS GRID
                                ArtistsGridView(
                                    contentBottomPadding = listBottomPadding,
                                    artists = artists,
                                    gridState = artistsGridState,
                                    onArtistClick = { selectedArtist = it },
                                    isScanning = isScanning
                                )
                            }
                            4 -> {
                                // TAB 4: PLAYLISTS & FOLDERS
                                PlaylistsSectionView(
                                    contentBottomPadding = listBottomPadding,
                                    tracks = tracks,
                                    folders = folders,
                                    recentlyAdded = recentlyAddedTracks,
                                    listState = foldersListState,
                                    onOpenPlaylist = { name, list ->
                                        selectedPlaylist = Pair(name, list)
                                    },
                                    onOpenFolder = { folder ->
                                        selectedFolder = folder
                                    },
                                    onBlacklistFolder = { blacklistConfirmFolder = it },
                                    isScanning = isScanning
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    // Dedicated Fullscreen Fuzzy Search Screen (GPU-composited subtle upward glide)
        AnimatedVisibility(
            visible = showSearchScreen,
            enter = com.jk.mediaplayer.ui.common.PageTransitions.searchOverlayEnter(),
            exit = com.jk.mediaplayer.ui.common.PageTransitions.searchOverlayExit()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Reserve the bottom navigation, so Home / Songs / Albums stay tappable
                    // under the search results, with the mini player floating above them.
                    .padding(
                        bottom = 58.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
            ) {
            SearchScreen(
                tracks = tracks,
                albums = albums,
                artists = artists,
                folders = folders,
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                onTrackClick = { list, idx ->
                    // The player opens above the search screen; the search view stays alive and
                    // returns when the sheet collapses.
                    onTrackClick(list, idx, "Search")
                },
                onAlbumClick = { album ->
                    selectedAlbum = album
                    showSearchScreen = false
                },
                onArtistClick = { artist ->
                    selectedArtist = artist
                    showSearchScreen = false
                },
                onFolderClick = { folder ->
                    selectedFolder = folder
                    showSearchScreen = false
                },
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onShowDetails = { detailsTrack = it },
                onOpenTagEditor = { tagEditorTrack = it },
                onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                onDeleteTrack = onDeleteTrack,
                onBlacklistTrack = { blacklistConfirmTrack = it },
                // Leave room for the floating mini player so the last row stays reachable.
                bottomContentPadding = if (currentTrack != null) 96.dp else 24.dp,
                onDismiss = { showSearchScreen = false }
            )
            }
        }

        // Online Network Audio Stream Dialog
        if (showStreamDialog) {
            NetworkStreamDialog(
                isOpen = true,
                accentColor = MusicRed,
                isVideoMode = false,
                onDismiss = { showStreamDialog = false },
                onPlay = { url, username, password, _ ->
                    showStreamDialog = false
                    val streamUser = username.ifBlank { null }
                    val streamPass = password.ifBlank { null }
                    onPlayStream?.invoke(url, streamUser, streamPass, true)
                }
            )
        }

        // Ringtone Cutter & Editor Dialog
        ringtoneCutterTrack?.let { track ->
            RingtoneCutterDialog(
                track = track,
                onRescanLibrary = {
                    coroutineScope.launch {
                        try {
                            onRescanLibrary?.invoke()
                        } catch (e: Exception) {
                        }
                    }
                },
                onDismiss = { ringtoneCutterTrack = null }
            )
        }

        // Song Details Interactive Modal Dialog
        detailsTrack?.let { track ->
            MusicTrackDetailsDialog(
                track = track,
                folders = folders,
                onDismiss = { detailsTrack = null },
                onOpenRingtoneCutter = { ringtoneCutterTrack = it },
                onDeleteTrack = onDeleteTrack,
                onGoToArtist = { art ->
                    selectedArtist = resolveArtist(art, artists, tracks)
                },
                onGoToAlbum = { alb ->
                    selectedAlbum = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) }
                        ?: AlbumItem(alb, track.artist, track.albumArtUri, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                },
                onGoToFolder = { folder ->
                    selectedFolder = folder
                }
            )
        }

        // Professional Audio ID3 Tag Editor Modal Dialog
        tagEditorTrack?.let { track ->
            MusicTagEditorDialog(
                track = track,
                onDismiss = { tagEditorTrack = null },
                onTagsSaved = { updatedTrack ->
                    coroutineScope.launch {
                        try {
                            onRescanLibrary?.invoke()
                        } catch (_: Exception) {}
                    }
                }
            )
        }

        // Blacklist Song Confirmation Dialog
        blacklistConfirmTrack?.let { track ->
            com.jk.mediaplayer.ui.common.BlacklistConfirmDialog(
                title = "Blacklist Song?",
                itemName = track.title,
                accentColor = MusicRed,
                onConfirm = {
                    val prefs = PreferencesManager(context)
                    prefs.addMusicBlacklistedFile(track.path)
                    com.jk.mediaplayer.data.MediaScanner.cachedMusic = null
                    coroutineScope.launch {
                        try {
                            onRescanLibrary?.invoke()
                        } catch (e: Exception) {
                        }
                    }
                    com.jk.mediaplayer.ui.common.AppToast.show("\"${track.title}\" added to blacklist")
                    blacklistConfirmTrack = null
                },
                onDismiss = { blacklistConfirmTrack = null }
            )
        }

        // Blacklist Folder Confirmation Dialog
        blacklistConfirmFolder?.let { folder ->
            com.jk.mediaplayer.ui.common.BlacklistConfirmDialog(
                title = "Blacklist Folder?",
                itemName = folder.name,
                accentColor = MusicRed,
                onConfirm = {
                    val prefs = PreferencesManager(context)
                    if (folder.path.isNotBlank()) {
                        prefs.addMusicBlacklistedFolder(folder.path)
                    }
                    folder.tracks.forEach { track ->
                        val p = track.folderPath.ifBlank { try { java.io.File(track.path).parent ?: "" } catch (_: Exception) { "" } }
                        if (p.isNotBlank()) {
                            prefs.addMusicBlacklistedFolder(p)
                        }
                    }
                    com.jk.mediaplayer.data.MediaScanner.cachedMusic = null
                    if (selectedFolder?.path == folder.path) {
                        selectedFolder = null
                    }
                    coroutineScope.launch {
                        try {
                            onRescanLibrary?.invoke()
                        } catch (e: Exception) {
                        }
                    }
                    com.jk.mediaplayer.ui.common.AppToast.show("\"${folder.name}\" added to blacklist")
                    blacklistConfirmFolder = null
                },
                onDismiss = { blacklistConfirmFolder = null }
            )
        }
    }
}

/**
 * Uniform, professional section header for Home shelves (Quick Picks, Quick Playlists, Albums, Artists, Folders).
 * Enforces identical typography, padding, color hierarchy, and action alignment.
 */
@Composable
private fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColor = if (isDark) Color.White else LightTextPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (action != null) {
            Spacer(modifier = Modifier.weight(1f))
            action()
        }
    }
}

/**
 * Home Screen (Pure AMOLED Black, Quick Picks, Speed Dial, Artists, Folders)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionView(
    contentBottomPadding: Dp = 12.dp,
    tracks: List<AudioItem>,
    recentlyAdded: List<AudioItem>,
    albums: List<AlbumItem>,
    artists: List<ArtistItem>,
    folders: List<AudioFolder>,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onPlayAllOrShuffle: (List<AudioItem>, String, Boolean) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onFolderClick: (AudioFolder) -> Unit,
    onOpenPlaylist: (String, List<AudioItem>) -> Unit = { _, _ -> },
    /** Incremented by the shared pull-to-refresh in [MusicScreen] to reshuffle the shelves. */
    refreshSeed: Int = 0,
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {},
    isScanning: Boolean = false
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val prefsManager = remember { com.jk.mediaplayer.data.PreferencesManager(context) }
    val isNetworkStreamIconEnabled = remember(com.jk.mediaplayer.data.PreferencesManager.settingsVersion) {
        prefsManager.isNetworkStreamIconEnabled()
    }
    val playlistVersion = com.jk.mediaplayer.data.PreferencesManager.playlistVersion

    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val albumGridHeight = remember(configuration.screenWidthDp, density) {
        val cardWidth = ((configuration.screenWidthDp.dp - 52.dp) / 2).coerceAtLeast(0.dp)
        // Match SpeedDialAlbumCard's exact caption metrics: 8 dp gap under artwork,
        // 22 sp title lineHeight, 3 dp gap, and 20 sp artist lineHeight, plus a small
        // buffer for custom system fonts and rounding so the second row never clips.
        val captionHeight = with(density) { (22 + 20).sp.toDp() } + 8.dp + 3.dp
        (cardWidth + captionHeight) * 2 + 14.dp + 4.dp
    }

    var likedTracks by remember { mutableStateOf<List<AudioItem>>(emptyList()) }
    var recentlyPlayedTracks by remember { mutableStateOf<List<AudioItem>>(emptyList()) }
    var mostPlayedTracks by remember { mutableStateOf<List<AudioItem>>(emptyList()) }
    var recentlyAddedAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var recentlyAddedArtists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var quickPickTracks by remember { mutableStateOf<List<AudioItem>>(emptyList()) }

    // Compute all ranked and sorted Home shelves off the UI thread so first launch and
    // background library scans never stall 120 Hz vertical scrolling.
    LaunchedEffect(tracks, albums, artists, playlistVersion, refreshSeed) {
        withContext(Dispatchers.Default) {
            val favoriteIds = prefsManager.getFavorites()
            val computedLiked = tracks.filter { favoriteIds.contains(it.id.toString()) }
            val trackMap = tracks.associateBy { it.id }
            val allTrackIds = tracks.map { it.id }
            val recentlyPlayedIds = prefsManager.getRecentlyPlayedTrackIds()
            val computedRecentlyPlayed = recentlyPlayedIds.mapNotNull { trackMap[it] }
            val mostPlayedIds = prefsManager.getMostPlayedTrackIds(allTrackIds)
            val computedMostPlayed = mostPlayedIds.mapNotNull { trackMap[it] }

            val computedRecentAlbums = albums.filter { it.tracks.isNotEmpty() }
                .map { album -> album to (album.tracks.maxOfOrNull { it.dateAdded } ?: 0L) }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(12)

            val computedRecentArtists = artists.filter { it.tracks.isNotEmpty() }
                .map { artist -> artist to (artist.tracks.maxOfOrNull { it.dateAdded } ?: 0L) }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(12)

            val computedTopAlbums = albums.filter { it.tracks.isNotEmpty() }
                .sortedByDescending { it.tracks.size }
                .take(12)

            val computedTopArtists = artists.filter { it.tracks.isNotEmpty() }
                .sortedByDescending { it.tracks.size }
                .take(12)

            // Quick picks ranking balancing familiarity and discovery without letting one artist dominate.
            val computedQuickPicks = if (tracks.isEmpty()) {
                emptyList()
            } else {
                val allPlayCounts = prefsManager.getAllTrackPlayCounts()
                val playByTrack = tracks.associate { it.id to (allPlayCounts[it.id] ?: 0) }
                val maxPlay = playByTrack.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                val artistPlays = HashMap<String, Int>()
                tracks.forEach { track ->
                    artistPlays[track.artist] = (artistPlays[track.artist] ?: 0) + (playByTrack[track.id] ?: 0)
                }
                val maxArtistPlay = artistPlays.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                val newest = tracks.maxOf { it.dateAdded }
                val oldest = tracks.minOf { it.dateAdded }
                val dateSpan = (newest - oldest).coerceAtLeast(1L)
                val recentIds = recentlyPlayedIds.take(8).toSet()

                fun scoreOf(track: AudioItem): Float {
                    val plays = playByTrack[track.id] ?: 0
                    val favourite = favoriteIds.contains(track.id.toString())
                    val familiarity = (if (favourite) 0.6f else 0f) + 0.4f * (plays.toFloat() / maxPlay)
                    val recency = ((track.dateAdded - oldest).toFloat() / dateSpan).coerceIn(0f, 1f)
                    val affinity = (artistPlays[track.artist] ?: 0).toFloat() / maxArtistPlay
                    val discovery = if (plays == 0) 1f else 0f
                    return 0.45f * familiarity + 0.25f * recency + 0.20f * affinity + 0.10f * discovery
                }

                val scored = tracks.associateWith { scoreOf(it) }
                val rnd = java.util.Random(refreshSeed.toLong())
                val pool = tracks.filterNot { it.id in recentIds }.ifEmpty { tracks }
                val ranked = pool.sortedByDescending { scored[it] ?: 0f }
                val hook = ranked.firstOrNull()
                val familiarPool = ranked.drop(1).take(16).shuffled(rnd)
                val discoveryPool = ranked.drop(1)
                    .filter { (playByTrack[it.id] ?: 0) == 0 }
                    .shuffled(rnd)

                val result = ArrayList<AudioItem>(20)
                fun push(track: AudioItem, perArtistLimit: Int) {
                    if (result.size >= 20) return
                    if (result.any { it.id == track.id }) return
                    if (result.count { it.artist == track.artist } >= perArtistLimit) return
                    result.add(track)
                }
                hook?.let { push(it, 2) }
                familiarPool.forEach { if (result.size < 8) push(it, 2) }
                discoveryPool.forEach { if (result.size < 15) push(it, 1) }
                familiarPool.forEach { if (result.size < 20) push(it, 2) }
                ranked.forEach { if (result.size < 20) push(it, 3) }
                tracks.filterNot { it.id in recentIds }.forEach { if (result.size < 20) push(it, 3) }
                result.take(20)
            }

            withContext(Dispatchers.Main) {
                likedTracks = computedLiked
                recentlyPlayedTracks = computedRecentlyPlayed
                mostPlayedTracks = computedMostPlayed
                recentlyAddedAlbums = computedRecentAlbums
                recentlyAddedArtists = computedRecentArtists
                topAlbums = computedTopAlbums
                topArtists = computedTopArtists
                quickPickTracks = computedQuickPicks
            }
        }
    }

    val recentlyAddedAlbumsChunks = remember(recentlyAddedAlbums) { recentlyAddedAlbums.chunked(4) }
    val recentlyAddedAlbumsPagerState = rememberPagerState(pageCount = { recentlyAddedAlbumsChunks.size })
    val topAlbumsChunks = remember(topAlbums) { topAlbums.chunked(4) }
    val topAlbumsPagerState = rememberPagerState(pageCount = { topAlbumsChunks.size })

    val quickPickChunks = remember(quickPickTracks) { quickPickTracks.chunked(4) }
    val quickPickPagerState = rememberPagerState(pageCount = { quickPickChunks.size })

    // Pre-warm the first visible page of every Home shelf (Quick Picks, Albums, Artists) with
    // exact memory cache keys and RGB_565 so scrolling down hits RAM immediately.
    val prewarmLoader = context.imageLoader
    LaunchedEffect(quickPickTracks, recentlyAddedAlbums, topAlbums, recentlyAddedArtists, topArtists, refreshSeed) {
        if (quickPickTracks.isEmpty() && recentlyAddedAlbums.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val requests = ArrayList<ImageRequest>(24)
            quickPickTracks.take(4).forEach { track ->
                requests += ImageRequest.Builder(context)
                    .data(track)
                    .memoryCacheKey("track_${track.id}")
                    .size(160)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .crossfade(false)
                    .build()
            }
            recentlyAddedAlbums.take(4).forEach { album ->
                requests += ImageRequest.Builder(context)
                    .data(album)
                    .memoryCacheKey("album_${album.name}_${album.artist}")
                    .size(240)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .crossfade(false)
                    .build()
            }
            topAlbums.take(4).forEach { album ->
                requests += ImageRequest.Builder(context)
                    .data(album)
                    .memoryCacheKey("album_${album.name}_${album.artist}")
                    .size(240)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .crossfade(false)
                    .build()
            }
            (recentlyAddedArtists.take(6) + topArtists.take(6))
                .distinctBy { it.name }
                .forEach { artist ->
                    val cachedUrl = artist.artistImageUrl
                        ?: com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedArtistImageUrl(context, artist.name)
                    val fallbackUri = artist.tracks.firstOrNull { it.albumArtUri != null }?.albumArtUri
                    val source = cachedUrl ?: fallbackUri
                    if (source != null) {
                        requests += ImageRequest.Builder(context)
                            .data(source)
                            .memoryCacheKey("artist_${artist.name}_${if (cachedUrl != null) "photo" else "art"}")
                            .size(160, 160)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build()
                    }
                }
            requests.forEach { prewarmLoader.enqueue(it) }
        }
    }

    // Reset all horizontal pagers and list scroll to position 0 ONLY when pull-to-refresh is explicitly triggered!
    var lastHandledSeed by rememberSaveable { mutableIntStateOf(refreshSeed) }
    LaunchedEffect(refreshSeed) {
        if (refreshSeed > 0 && refreshSeed != lastHandledSeed) {
            lastHandledSeed = refreshSeed
            try {
                listState.scrollToItem(0)
            } catch (_: Exception) {}
            try {
                if (quickPickPagerState.pageCount > 0) quickPickPagerState.scrollToPage(0)
            } catch (_: Exception) {}
            try {
                if (recentlyAddedAlbumsPagerState.pageCount > 0) recentlyAddedAlbumsPagerState.scrollToPage(0)
            } catch (_: Exception) {}
            try {
                if (topAlbumsPagerState.pageCount > 0) topAlbumsPagerState.scrollToPage(0)
            } catch (_: Exception) {}
        }
    }

    val isHomeScrolling by remember(listState) {
        derivedStateOf { listState.isScrollInProgress }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentBottomPadding)
    ) {
            if (isScanning && tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MusicRed,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            } else if (tracks.isEmpty()) {
                item {
                    MusicEmptyState(
                        title = "No Music Found",
                        subtitle = "Audio tracks stored on your device storage will appear here.",
                        icon = Icons.Default.MusicNote,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    )
                }
            } else {
                // 1. "Quick Picks" Clean Section Header
                item(key = "home_quick_picks_header", contentType = "home_section_header") {
                    HomeSectionHeader(
                        title = "Quick Picks",
                        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
                        action = {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MusicRed.copy(alpha = if (isDark) 0.2f else 0.12f),
                                border = BorderStroke(1.dp, MusicRed.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = quickPickTracks.isNotEmpty()) {
                                        onPlayAllOrShuffle(quickPickTracks, "Quick Picks", false)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play all Quick Picks",
                                        tint = MusicRed,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Play all",
                                        color = if (isDark) Color.White else MusicRed,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    )
                }

            // Quick picks: one mix per swipe (snapping pager), each mix plays only its own songs
            item(key = "home_quick_picks_pager", contentType = "home_quick_picks_pager") {
                HorizontalPager(
                    state = quickPickPagerState,
                    beyondViewportPageCount = 0,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    pageSpacing = 16.dp,
                    key = { pageIndex -> "quickpick_$pageIndex" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(258.dp)
                ) { pageIndex ->
                    val chunk = quickPickChunks.getOrNull(pageIndex).orEmpty()
                    // Lightweight page instead of a bordered card: fewer layers to compose and
                    // draw per frame, which is what made Quick picks scroll jittery.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        // The 4 songs inside this page
                        chunk.forEach { track ->
                            QuickPickTrackRow(
                                track = track,
                                isPlayingThis = track.id == currentTrack?.id,
                                isPlaying = isPlaying,
                                onClick = { onTrackClick(quickPickTracks, quickPickTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), "Quick picks") },
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onGoToAlbum = { alb ->
                                    val target = albums.firstOrNull { it.name.equals(alb, ignoreCase = true) } ?: AlbumItem(alb, track.artist, null, tracks.filter { it.album.equals(alb, ignoreCase = true) })
                                    onAlbumClick(target)
                                },
                                onGoToArtist = { art ->
                                    val target = resolveArtist(art, artists, tracks)
                                    onArtistClick(target)
                                },
                                onShowDetails = onShowDetails,
                                onOpenTagEditor = onOpenTagEditor,
                                onOpenRingtoneCutter = onOpenRingtoneCutter,
                                onDeleteTrack = onDeleteTrack,
                                onBlacklistTrack = onBlacklistTrack
                            )
                        }
                    }
                }
            }

            // 2. Quick Playlists shortcuts: icon + label, no card surface so they cost one text
            // and one icon draw each.
            item(key = "home_quick_playlists", contentType = "home_quick_playlists") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    HomeSectionHeader(title = "Quick Playlists")

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            QuickPlaylistShortcut(
                                title = "Liked",
                                icon = Icons.Default.Favorite,
                                accent = MusicRed,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenPlaylist("Liked Music", likedTracks) }
                            )
                            QuickPlaylistShortcut(
                                title = "Last Added",
                                icon = Icons.Default.Schedule,
                                accent = Color(0xFF00B0FF),
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenPlaylist("Last Added", recentlyAdded) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            QuickPlaylistShortcut(
                                title = "Recent",
                                icon = Icons.Default.History,
                                accent = Color(0xFFFF9800),
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenPlaylist("Recently Played", recentlyPlayedTracks) }
                            )
                            QuickPlaylistShortcut(
                                title = "Most Played",
                                icon = Icons.Default.Whatshot,
                                accent = Color(0xFF8E24AA),
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenPlaylist("Most Played", mostPlayedTracks) }
                            )
                        }
                    }
                }
            }

            // 5. "Recently Added Albums" Section (2x2 Grid per page with swipe dots)
            if (recentlyAddedAlbums.isNotEmpty()) {
                item(key = "home_recently_added_albums", contentType = "home_albums_grid_pager") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        HomeSectionHeader(title = "Recently Added Albums")

                        Spacer(modifier = Modifier.height(10.dp))

                        HorizontalPager(
                            state = recentlyAddedAlbumsPagerState,
                            beyondViewportPageCount = 0,
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(albumGridHeight),
                            key = { "recent_album_page_$it" }
                        ) { pageIdx ->
                            AlbumGridPage(
                                albums = recentlyAddedAlbumsChunks.getOrNull(pageIdx).orEmpty(),
                                onAlbumClick = onAlbumClick
                            )
                        }

                        PageIndicatorDots(
                            pageCount = recentlyAddedAlbumsChunks.size,
                            currentPage = recentlyAddedAlbumsPagerState.currentPage
                        )
                    }
                }
            }

            // 6. "Recently Added Artists" Section
            if (recentlyAddedArtists.isNotEmpty()) {
                item(key = "home_recently_added_artists", contentType = "home_recent_artists_row") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        HomeSectionHeader(title = "Recently Added Artists")

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(recentlyAddedArtists, key = { "recent_artist_${it.name}" }) { artist ->
                                ArtistCircleCard(
                                    artist = artist,
                                    deferNetwork = isHomeScrolling,
                                    onClick = { onArtistClick(artist) }
                                )
                            }
                        }
                    }
                }
            }

            // 7. "Top Artists" Section (3x3 Grid per page with swipe dots)
            if (topArtists.isNotEmpty()) {
                item(key = "home_top_artists", contentType = "home_top_artists_carousel") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        HomeSectionHeader(title = "Top Artists")

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(topArtists, key = { it.name }) { artist ->
                                ArtistCircleCard(
                                    artist = artist,
                                    deferNetwork = isHomeScrolling,
                                    onClick = { onArtistClick(artist) }
                                )
                            }
                        }
                    }
                }
            }

            // 8. "Top Albums" Section
            if (topAlbums.isNotEmpty()) {
                item(key = "home_top_albums", contentType = "home_top_albums_pager") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        HomeSectionHeader(title = "Top Albums")

                        Spacer(modifier = Modifier.height(10.dp))

                        HorizontalPager(
                            state = topAlbumsPagerState,
                            beyondViewportPageCount = 0,
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(albumGridHeight),
                            key = { "top_album_page_$it" }
                        ) { pageIdx ->
                            AlbumGridPage(
                                albums = topAlbumsChunks.getOrNull(pageIdx).orEmpty(),
                                onAlbumClick = onAlbumClick
                            )
                        }

                        PageIndicatorDots(
                            pageCount = topAlbumsChunks.size,
                            currentPage = topAlbumsPagerState.currentPage
                        )
                    }
                }
            }

            // 9. "Music Folders" Section (2x2 Grid per page with swipe dots, strictly at the end)
            if (folders.isNotEmpty()) {
                item(key = "home_music_folders", contentType = "home_folders_carousel") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        HomeSectionHeader(title = "Music Folders")

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(folders, key = { it.path }) { folder ->
                                FolderGridCard(
                                    folder = folder,
                                    modifier = Modifier.width(150.dp),
                                    onClick = { onFolderClick(folder) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Speed Dial Card for individual tracks when albums are few
 */
/**
 * Modern Rounded 3-Dot Options Dropdown Menu for Songs
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SongOptionsMenu(
    track: AudioItem,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isCurrentTrack: Boolean = false,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onGoToAlbum: () -> Unit = {},
    onGoToArtist: () -> Unit = {},
    onShowDetails: () -> Unit = {},
    onOpenTagEditor: () -> Unit = {},
    onOpenRingtoneCutter: () -> Unit = {},
    onDelete: () -> Unit = {},
    onBlacklist: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColor = if (isDark) Color.White else LightTextPrimary

    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7)
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        // The entry that is already playing has nothing to queue after itself.
        if (!isCurrentTrack) {
            DropdownMenuItem(
                text = { Text("Play next", color = textColor, fontSize = 17.sp) },
                leadingIcon = { Icon(Icons.Default.SkipNext, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
                onClick = {
                    onDismissRequest()
                    onPlayNext()
                    AppToast.show("Added '${track.title}' to play next")
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Add to playing queue", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onAddToQueue()
                AppToast.show("Added '${track.title}' to queue")
            }
        )
        DropdownMenuItem(
            text = { Text("Add to playlist", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                AppToast.show("Saved to Playlist")
            }
        )
        DropdownMenuItem(
            text = { Text("Go to album", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.Album, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onGoToAlbum()
            }
        )
        DropdownMenuItem(
            text = { Text("Go to artist", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onGoToArtist()
            }
        )
        DropdownMenuItem(
            text = { Text("Share", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                try {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, track.uri)
                        putExtra(android.content.Intent.EXTRA_TEXT, "${track.title} - ${track.artist}")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Track"))
                } catch (e: Exception) {
                    AppToast.show("Cannot share track: ${e.message}")
                }
            }
        )
        DropdownMenuItem(
            text = { Text("Set as Ringtone", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onOpenRingtoneCutter()
            }
        )
        DropdownMenuItem(
            text = { Text("Details", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onShowDetails()
            }
        )
        DropdownMenuItem(
            text = { Text("Edit Tags", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onOpenTagEditor()
            }
        )

        DropdownMenuItem(
            text = { Text("Blacklist Song", color = textColor, fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onBlacklist()
            }
        )
        DropdownMenuItem(
            text = { Text("Delete from device", color = Color(0xFFFF5252), fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp)) },
            onClick = {
                onDismissRequest()
                onDelete()
            }
        )
            }
        }
    }
}

/**
 * Modern Interactive Song Details Modal Dialog
 */
@Composable
fun MusicTrackDetailsDialog(
    track: AudioItem,
    folders: List<AudioFolder>,
    onDismiss: () -> Unit,
    onGoToArtist: (String) -> Unit = {},
    onGoToAlbum: (String) -> Unit = {},
    onGoToFolder: (AudioFolder) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val dialogBg = if (isDark) Color(0xFF13151E) else Color(0xFFFFFFFF)
    val textPrimary = if (isDark) Color.White else LightTextPrimary
    val textSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    val cleanTitle = remember(track.title) {
        track.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a").removeSuffix(".opus").replace('_', ' ')
    }
    val cleanArtist = remember(track.artist, track.title) {
        if (track.artist == "<unknown>" || track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true)) {
            OnlineMetadataFetcher.getCachedArtistName(context, track.title) ?: "Unknown Artist"
        } else track.artist
    }
    val cleanAlbum = remember(track.album) {
        if (track.album == "<unknown>" || track.album.isBlank()) "Unknown Album" else track.album
    }
    val durStr = remember(track.duration) { formatDuration(track.duration) }
    val file = remember(track.path) { java.io.File(track.path) }
    val fileSizeStr = remember(track.path) {
        if (file.exists()) {
            val bytes = file.length()
            String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        } else ""
    }
    val formatExt = remember(track.path) {
        file.extension.uppercase().ifEmpty { "AUDIO" }
    }
    val parentFolder = remember(track.path, folders) {
        folders.firstOrNull { it.tracks.any { t -> t.id == track.id } }
            ?: folders.firstOrNull { it.path.equals(file.parent, ignoreCase = true) }
            ?: AudioFolder(file.parentFile?.name ?: "Music", file.parent ?: "", listOf(track))
    }

    var bitrateStr by remember(track.path) { mutableStateOf<String?>(null) }
    var sampleRateStr by remember(track.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.path) {
        withContext(Dispatchers.IO) {
            if (track.path.isNotEmpty() && file.exists()) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(track.path)
                    val rawBitrate = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                    if (rawBitrate != null && rawBitrate > 0) {
                        bitrateStr = "${rawBitrate / 1000} kbps"
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val rawSampleRate = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                        if (rawSampleRate != null && rawSampleRate > 0) {
                            sampleRateStr = "${rawSampleRate / 1000f} kHz"
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
        }
    }

    val dateAddedFormatted = remember(track.dateAdded) {
        if (track.dateAdded > 0) {
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(track.dateAdded * 1000L))
        } else {
            val lastMod = if (file.exists()) file.lastModified() else 0L
            if (lastMod > 0) {
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                sdf.format(java.util.Date(lastMod))
            } else ""
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 24.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = dialogBg,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header: Artwork + Title & Duration/Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0xFF1F2230) else Color(0xFFF0F2F6),
                        border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(track)
                                .crossfade(100)
                                .error(R.drawable.ic_default_album_art)
                                .placeholder(R.drawable.ic_default_album_art)
                                .fallback(R.drawable.ic_default_album_art)
                                .build(),
                            contentDescription = cleanTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cleanTitle,
                            color = textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "$durStr  •  ${fileSizeStr.ifEmpty { formatExt }}",
                            color = textSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                    thickness = 1.dp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable Specs
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                ) {
                    AudioTechSpecRow("Format / Container", formatExt)
                    AudioTechSpecDivider(isDark)

                    AudioTechSpecRow("Title", cleanTitle)
                    AudioTechSpecDivider(isDark)

                    // Clickable Artist Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onGoToArtist(track.artist)
                            }
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Artist",
                            color = textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = cleanArtist,
                                color = textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = textSecondary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                    AudioTechSpecDivider(isDark)

                    // Clickable Album Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onGoToAlbum(track.album)
                            }
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Album",
                            color = textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = cleanAlbum,
                                color = textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = textSecondary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                    AudioTechSpecDivider(isDark)

                    // Clickable Folder Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onGoToFolder(parentFolder)
                            }
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Folder",
                            color = textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = parentFolder.name,
                                color = textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = textSecondary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                    AudioTechSpecDivider(isDark)

                    AudioTechSpecRow("Duration", durStr)
                    AudioTechSpecDivider(isDark)

                    if (fileSizeStr.isNotBlank()) {
                        AudioTechSpecRow("File Size", fileSizeStr)
                        AudioTechSpecDivider(isDark)
                    }

                    if (!bitrateStr.isNullOrBlank()) {
                        AudioTechSpecRow("Bitrate", bitrateStr!!)
                        AudioTechSpecDivider(isDark)
                    }

                    if (!sampleRateStr.isNullOrBlank()) {
                        AudioTechSpecRow("Sample Rate", sampleRateStr!!)
                        AudioTechSpecDivider(isDark)
                    }

                    if (dateAddedFormatted.isNotBlank()) {
                        AudioTechSpecRow("Date Added", dateAddedFormatted)
                        AudioTechSpecDivider(isDark)
                    }

                    // Location with 1-Tap Copy
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "Location",
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = track.path.ifEmpty { track.uri.toString() },
                                color = textPrimary.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("File Path", track.path)
                                    clipboard?.setPrimaryClip(clip)
                                    com.jk.mediaplayer.ui.common.AppToast.show("File path copied to clipboard")
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Path",
                                    tint = MusicRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action: Close Button
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MusicRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "Close",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTechSpecRow(label: String, value: String) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textPrimary = if (isDark) Color.White else LightTextPrimary
    val textSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AudioTechSpecDivider(isDark: Boolean) {
    HorizontalDivider(
        color = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f),
        thickness = 1.dp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/**
 * Quick Pick Song Row (Anti-aliased, rounded ripple, crimson playing indicator)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickPickTrackRow(
    track: AudioItem,
    isPlayingThis: Boolean,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    var showMenu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val cleanTitle = remember(track.title) {
        track.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a").removeSuffix(".opus").replace('_', ' ')
    }
    val isArtistUnknown = remember(track.artist) {
        track.artist == "<unknown>" || track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true)
    }
    val displayArtist = remember(track.artist, isArtistUnknown) {
        if (isArtistUnknown) "Unknown Artist" else track.artist
    }

    val imageRequest = remember(track.id, track.onlineArtUrl) {
        ImageRequest.Builder(context)
            .data(track)
            .memoryCacheKey("track_${track.id}")
            .size(160)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .crossfade(false)
            .error(R.drawable.ic_default_album_art)
            .placeholder(R.drawable.ic_default_album_art)
            .fallback(R.drawable.ic_default_album_art)
            .build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                }
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rounded Square Album Art with Equalizer on Active Track
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            modifier = Modifier.size(52.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isPlayingThis) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedEqualizerIndicator(isPlaying = isPlaying, barColor = MusicRed)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanTitle,
                color = if (isPlayingThis) MusicRed else textColorPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = displayArtist,
                color = if (isPlayingThis) MusicRed.copy(alpha = 0.8f) else textColorSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = textColorSecondary.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            if (showMenu) {
                SongOptionsMenu(
                    track = track,
                    expanded = true,
                    onDismissRequest = { showMenu = false },
                    isCurrentTrack = isPlayingThis,
                    onPlayNext = { onPlayNext(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onGoToAlbum = { onGoToAlbum(track.album) },
                    onGoToArtist = { onGoToArtist(track.artist) },
                    onShowDetails = { onShowDetails(track) },
                    onOpenTagEditor = { onOpenTagEditor(track) },
                    onOpenRingtoneCutter = { onOpenRingtoneCutter(track) },
                    onDelete = { onDeleteTrack(track) },
                    onBlacklist = { onBlacklistTrack(track) }
                )
            }
        }
    }
}

/**
 * Compact smart playlist button: no artwork and no track count, just the label that opens the
 * playlist page.
 */
@Composable
fun SmartPlaylistGradientCard(
    title: String,
    tracks: List<AudioItem>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else Color(0xFF1A1C1E)
    val accent = when (title) {
        "Liked Music" -> MusicRed
        "Last Added" -> Color(0xFF00B0FF)
        "Recently Played" -> Color(0xFFFF9800)
        else -> Color(0xFF8E24AA)
    }
    val icon = when (title) {
        "Liked Music" -> Icons.Default.Favorite
        "Last Added" -> Icons.Default.Schedule
        "Recently Played" -> Icons.Default.History
        else -> Icons.Default.Whatshot
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = if (isDark) 0.16f else 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColorPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 2x2 album grid used by the home pagers. Only the visible page is composed because the pager
 * runs with beyondViewportPageCount = 0.
 */
@Composable
fun AlbumGridPage(
    albums: List<AlbumItem>,
    onAlbumClick: (AlbumItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val first = albums.getOrNull(0)
            if (first != null) {
                SpeedDialAlbumCard(
                    album = first,
                    onClick = { onAlbumClick(first) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            val second = albums.getOrNull(1)
            if (second != null) {
                SpeedDialAlbumCard(
                    album = second,
                    onClick = { onAlbumClick(second) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val third = albums.getOrNull(2)
            if (third != null) {
                SpeedDialAlbumCard(
                    album = third,
                    onClick = { onAlbumClick(third) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            val fourth = albums.getOrNull(3)
            if (fourth != null) {
                SpeedDialAlbumCard(
                    album = fourth,
                    onClick = { onAlbumClick(fourth) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Compact Quick Playlist shortcut: icon plus label, no surface or border to draw.
 */
@Composable
private fun QuickPlaylistShortcut(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = if (isDark) 0.16f else 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = title,
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Album Card with artwork and title/artist below (matching Albums tab style)
 */
@Composable
fun SpeedDialAlbumCard(
    album: AlbumItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else Color(0xFF1A1C1E)
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else Color(0xFF757575)
    val imageRequest = remember(album.name, album.albumArtUri) {
        ImageRequest.Builder(context)
            .data(album)
            .memoryCacheKey("album_${album.name}_${album.artist}")
            .size(240)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .crossfade(false)
            .error(R.drawable.ic_default_album_art)
            .placeholder(R.drawable.ic_default_album_art)
            .fallback(R.drawable.ic_default_album_art)
            .build()
    }

    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (isDark) Color(0xFF262838) else Color(0xFFE2E4EC),
                    RoundedCornerShape(12.dp)
                )
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = album.name,
            color = textColorPrimary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
            )
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = if (album.artist.isNotBlank()) album.artist else "Unknown Artist",
            color = textColorSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

/**
 * Circular Artist Avatar Card
 */
/**
 * Artist photo resolver: disk/memory cache first, then a single background lookup whose result is
 * cached on disk. Only the cards that are actually composed call it, so opening the Artists tab
 * fetches just what the user can see instead of the whole library.
 */
@Composable
fun rememberArtistImage(name: String, deferNetwork: Boolean = false): String? {
    val context = LocalContext.current
    // Initialize synchronously from the in-memory LRU cache so pre-warmed artists render on frame 1.
    var imageUrl by remember(name) {
        mutableStateOf(OnlineMetadataFetcher.getCachedArtistImageUrl(null, name))
    }
    LaunchedEffect(name, deferNetwork) {
        if (!imageUrl.isNullOrBlank()) return@LaunchedEffect
        // Disk and preference reads must never block list composition.
        val cached = withContext(Dispatchers.IO) {
            OnlineMetadataFetcher.getCachedArtistImageUrl(context, name)
        }
        if (!cached.isNullOrBlank()) {
            imageUrl = cached
        } else if (!deferNetwork && !name.equals("Unknown Artist", ignoreCase = true)) {
            imageUrl = OnlineMetadataFetcher.fetchArtistImageUrl(context, name)
        }
    }
    return imageUrl
}

@Composable
fun ArtistCircleCard(
    artist: ArtistItem,
    modifier: Modifier = Modifier.width(88.dp),
    avatarSize: Dp = 80.dp,
    deferNetwork: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val artistImageUrl = artist.artistImageUrl ?: rememberArtistImage(artist.name, deferNetwork)
    val fallbackLocalUri = remember(artist.name) {
        artist.tracks.firstOrNull { it.albumArtUri != null }?.albumArtUri
    }

    val imageSource = artistImageUrl ?: fallbackLocalUri
    val imageRequest = remember(imageSource) {
        if (imageSource != null) {
            ImageRequest.Builder(context)
                .data(imageSource)
                .memoryCacheKey(
                    "artist_${artist.name}_${if (artistImageUrl != null) "photo" else "art"}"
                )
                .size(160, 160)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                .crossfade(false)
                .build()
        } else null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(if (isDark) Color(0xFF222222) else Color(0xFFECEEF2))
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f),
                    CircleShape
                )
        ) {
            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            remember(isDark) {
                                Brush.linearGradient(
                                    if (isDark) listOf(Color(0xFF2E1C22), Color(0xFF190E12))
                                    else listOf(Color(0xFFFFEEF0), Color(0xFFFFDDE2))
                                )
                            }
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MusicRed,
                        modifier = Modifier.size((avatarSize.value * 0.45f).dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            color = textColorPrimary,
            fontSize = if (avatarSize <= 70.dp) 11.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
    }
}


/**
 * Horizontal Swipe Indicator Dots (...)
 */
@Composable
fun PageIndicatorDots(
    pageCount: Int,
    currentPage: Int,
    activeColor: Color = MusicRed,
    inactiveColor: Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF333333) else Color(0xFFD0D0D0),
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until pageCount) {
            val isSelected = i == currentPage
            val dotSize = if (isSelected) 6.dp else 5.dp
            val color = if (isSelected) activeColor else inactiveColor
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * Compact Folder Card
 */
/**
 * 2x2 Folder Grid Card for Home Screen
 */
@Composable
fun FolderGridCard(
    folder: AudioFolder,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isDark) Color(0xFF191A22) else Color.White,
        border = BorderStroke(1.dp, if (isDark) Color(0xFF2B2C3A) else Color(0xFFE5E7EB)),
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MusicRed,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = folder.name,
                    color = textColorPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${folder.tracks.size} songs",
                    color = textColorSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Modern Floating Play All & Shuffle All Capsule (Bottom-Right, AZ Scanner Scan button style)
 */
@Composable
fun FloatingPlayShuffleButton(
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MusicRed,
        shadowElevation = 8.dp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPlayAll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play all",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Play all",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color.White.copy(alpha = 0.4f))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onShuffleAll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle all",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Shuffle",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun MusicEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.MusicNote
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = if (isDark) Color(0xFF221517) else Color(0xFFFFECEF),
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MusicRed,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * All Songs Tab List (Preserves scroll position & plays context playlist)
 */
@Composable
fun SongsListView(
    contentBottomPadding: Dp = 12.dp,
    tracks: List<AudioItem>,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onPlayAllOrShuffle: (List<AudioItem>, String, Boolean) -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {},
    isScanning: Boolean = false
) {
    if (isScanning && tracks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MusicRed,
                modifier = Modifier.size(36.dp)
            )
        }
        return
    }
    if (tracks.isEmpty()) {
        MusicEmptyState(
            title = "No Songs Found",
            subtitle = "Audio tracks stored on your device storage will appear here.",
            icon = Icons.Default.MusicNote
        )
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding, start = 12.dp, end = 12.dp)
        ) {
            itemsIndexed(tracks, key = { _, item -> item.id }, contentType = { _, _ -> "track" }) { index, track ->
                QuickPickTrackRow(
                    track = track,
                    isPlayingThis = track.id == currentTrack?.id,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(tracks, index, "All Songs") },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onShowDetails = onShowDetails,
                    onOpenTagEditor = onOpenTagEditor,
                    onOpenRingtoneCutter = onOpenRingtoneCutter,
                    onDeleteTrack = onDeleteTrack,
                    onBlacklistTrack = onBlacklistTrack
                )
            }
        }

        val fabBottomPadding by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (currentTrack != null) 80.dp else 16.dp,
            label = "fabBottom"
        )

        // Floating Action Button at Bottom Right (like AZ Scanner Scan button)
        FloatingPlayShuffleButton(
            onPlayAll = { onPlayAllOrShuffle(tracks, "All Songs", false) },
            onShuffleAll = { onPlayAllOrShuffle(tracks, "All Songs", true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
        )
    }
}

/**
 * Albums Grid View (Preserves scroll position)
 */
@Composable
fun AlbumsGridView(
    contentBottomPadding: Dp = 12.dp,
    albums: List<AlbumItem>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    onAlbumClick: (AlbumItem) -> Unit,
    isScanning: Boolean = false
) {
    if (isScanning && albums.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MusicRed,
                modifier = Modifier.size(36.dp)
            )
        }
        return
    }
    if (albums.isEmpty()) {
        MusicEmptyState(
            title = "No Albums Found",
            subtitle = "Albums identified from your audio files will appear here.",
            icon = Icons.Default.Album
        )
        return
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentBottomPadding, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            albums,
            key = { "${it.name}|${it.artist}|${it.albumArtUri}|${it.tracks.firstOrNull()?.id ?: -1}" },
            contentType = { "album" }
        ) { album ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onAlbumClick(album) }
            ) {
                val context = LocalContext.current
                val request = remember(album.name, album.albumArtUri) {
                    ImageRequest.Builder(context)
                        .data(album)
                        .crossfade(false)
                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                        .size(300)
                        .error(R.drawable.ic_default_album_art)
                        .placeholder(R.drawable.ic_default_album_art)
                        .fallback(R.drawable.ic_default_album_art)
                        .build()
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E1E),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = album.name,
                    color = textColorPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${album.artist} • ${album.tracks.size} songs",
                    color = textColorSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Artists Grid View (Preserves scroll position)
 */
@Composable
fun ArtistsGridView(
    contentBottomPadding: Dp = 12.dp,
    artists: List<ArtistItem>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    onArtistClick: (ArtistItem) -> Unit,
    isScanning: Boolean = false
) {
    if (isScanning && artists.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MusicRed,
                modifier = Modifier.size(36.dp)
            )
        }
        return
    }
    if (artists.isEmpty()) {
        MusicEmptyState(
            title = "No Artists Found",
            subtitle = "Artists identified from your audio files will appear here.",
            icon = Icons.Default.Person
        )
        return
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentBottomPadding, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(artists, key = { it.name }, contentType = { "artist" }) { artist ->
            ArtistCircleCard(
                artist = artist,
                onClick = { onArtistClick(artist) }
            )
        }
    }
}

/**
 * Folders List View (Preserves scroll position)
 */
@Composable
fun FolderRow(
    folder: AudioFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBlacklistFolder: (AudioFolder) -> Unit = {}
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MusicRed,
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                color = textColorPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${folder.tracks.size} tracks",
                color = textColorSecondary,
                fontSize = 13.sp
            )
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = textColorSecondary.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFF2F4F7)
            ) {
                DropdownMenuItem(
                    text = { Text("Blacklist Folder", color = textColorPrimary, fontSize = 15.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = textColorPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        showMenu = false
                        onBlacklistFolder(folder)
                    }
                )
            }
        }
    }
}

/**
 * Drilldown Views for Folder, Artist, and Album (Plays from context list with Floating Play/Shuffle Button)
 */
@Composable
fun FolderTracksView(
    contentBottomPadding: Dp = 12.dp,
    folder: AudioFolder,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onPlayAllOrShuffle: (List<AudioItem>, String, Boolean) -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding, start = 12.dp, end = 12.dp)
        ) {
            itemsIndexed(folder.tracks, key = { _, item -> item.id }, contentType = { _, _ -> "track" }) { index, track ->
                QuickPickTrackRow(
                    track = track,
                    isPlayingThis = track.id == currentTrack?.id,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(folder.tracks, index, folder.name) },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onShowDetails = onShowDetails,
                    onOpenTagEditor = onOpenTagEditor,
                    onOpenRingtoneCutter = onOpenRingtoneCutter,
                    onDeleteTrack = onDeleteTrack,
                    onBlacklistTrack = onBlacklistTrack
                )
            }
        }

        val fabBottomPadding by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (currentTrack != null) 80.dp else 16.dp,
            label = "fabBottom"
        )

        FloatingPlayShuffleButton(
            onPlayAll = { onPlayAllOrShuffle(folder.tracks, folder.name, false) },
            onShuffleAll = { onPlayAllOrShuffle(folder.tracks, folder.name, true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
        )
    }
}

@Composable
fun ArtistTracksView(
    contentBottomPadding: Dp = 12.dp,
    artist: ArtistItem,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onPlayAllOrShuffle: (List<AudioItem>, String, Boolean) -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {}
) {
    val artistPhoto = rememberArtistImage(artist.name)
    val headerFadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 260.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Soft artist backdrop. The top of the bitmap is masked with a DstIn gradient, so whatever
        // page colour sits behind shows through and the photo emerges gradually below the header
        // instead of starting on a hard line.
        if (!artistPhoto.isNullOrBlank()) {
            AsyncImage(
                model = artistPhoto,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.24f
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.55f to Color.Black,
                                    1.0f to Color.Black
                                ),
                                startY = 0f,
                                endY = headerFadePx
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                        )
                    }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding, start = 12.dp, end = 12.dp)
        ) {
            itemsIndexed(artist.tracks, key = { _, item -> item.id }, contentType = { _, _ -> "track" }) { index, track ->
                QuickPickTrackRow(
                    track = track,
                    isPlayingThis = track.id == currentTrack?.id,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(artist.tracks, index, artist.name) },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onShowDetails = onShowDetails,
                    onOpenTagEditor = onOpenTagEditor,
                    onOpenRingtoneCutter = onOpenRingtoneCutter,
                    onDeleteTrack = onDeleteTrack,
                    onBlacklistTrack = onBlacklistTrack
                )
            }
        }

        val fabBottomPadding by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (currentTrack != null) 80.dp else 16.dp,
            label = "fabBottom"
        )

        FloatingPlayShuffleButton(
            onPlayAll = { onPlayAllOrShuffle(artist.tracks, artist.name, false) },
            onShuffleAll = { onPlayAllOrShuffle(artist.tracks, artist.name, true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
        )
    }
}

@Composable
fun AlbumTracksView(
    contentBottomPadding: Dp = 12.dp,
    album: AlbumItem,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onPlayAllOrShuffle: (List<AudioItem>, String, Boolean) -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {}
) {
    val headerFadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 260.dp.toPx() }
    Box(modifier = Modifier.fillMaxSize()) {
        // Same masked treatment as the artist page: the top of the artwork fades into the page
        // colour, so the header never ends on a hard edge.
        AsyncImage(
            model = remember(album.name, album.albumArtUri) { album },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.24f
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.55f to Color.Black,
                                1.0f to Color.Black
                            ),
                            startY = 0f,
                            endY = headerFadePx
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                    )
                }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding, start = 12.dp, end = 12.dp)
        ) {
            itemsIndexed(album.tracks, key = { _, item -> item.id }, contentType = { _, _ -> "track" }) { index, track ->
                QuickPickTrackRow(
                    track = track,
                    isPlayingThis = track.id == currentTrack?.id,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(album.tracks, index, album.name) },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onShowDetails = onShowDetails,
                    onOpenTagEditor = onOpenTagEditor,
                    onOpenRingtoneCutter = onOpenRingtoneCutter,
                    onDeleteTrack = onDeleteTrack,
                    onBlacklistTrack = onBlacklistTrack
                )
            }
        }

        val fabBottomPadding by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (currentTrack != null) 80.dp else 16.dp,
            label = "fabBottom"
        )

        FloatingPlayShuffleButton(
            onPlayAll = { onPlayAllOrShuffle(album.tracks, album.name, false) },
            onShuffleAll = { onPlayAllOrShuffle(album.tracks, album.name, true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
        )
    }
}

/**
 * Playlist Tracks Drilldown View
 */
@Composable
fun PlaylistTracksView(
    contentBottomPadding: Dp = 12.dp,
    title: String,
    tracks: List<AudioItem>,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    onTrackClick: (List<AudioItem>, Int, String?) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onPlayAllOrShuffle: (List<AudioItem>, String, Boolean) -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (tracks.isEmpty()) {
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val textColorPrimary = if (isDark) Color.White else LightTextPrimary
            val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MusicRed.copy(alpha = 0.5f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when {
                            title.equals("Ringtones", ignoreCase = true) -> "No ringtones yet"
                            title.equals("Liked Music", ignoreCase = true) -> "No liked songs yet"
                            title.equals("Recently Played", ignoreCase = true) -> "No recently played songs"
                            title.equals("Most Played", ignoreCase = true) -> "No played songs yet"
                            else -> "No songs in this playlist"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColorPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            title.equals("Ringtones", ignoreCase = true) ->
                                "Tap the 3-dot menu on any song and select 'Set as Ringtone / Cutter' to create custom ringtones."
                            title.equals("Liked Music", ignoreCase = true) ->
                                "Tap the heart icon on any song or in player to add it to your Liked Music."
                            title.equals("Recently Played", ignoreCase = true) || title.equals("Most Played", ignoreCase = true) ->
                                "Play songs to see them appear here."
                            else -> "Add songs using the 3-dot menu on any song."
                        },
                        fontSize = 13.sp,
                        color = textColorSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding, start = 12.dp, end = 12.dp)
        ) {
            itemsIndexed(tracks, key = { _, item -> item.id }, contentType = { _, _ -> "track" }) { index, track ->
                QuickPickTrackRow(
                    track = track,
                    isPlayingThis = track.id == currentTrack?.id,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(tracks, index, title) },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onShowDetails = onShowDetails,
                    onOpenTagEditor = onOpenTagEditor,
                    onOpenRingtoneCutter = onOpenRingtoneCutter,
                    onDeleteTrack = onDeleteTrack,
                    onBlacklistTrack = onBlacklistTrack
                )
            }
        }

        val fabBottomPadding by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (currentTrack != null) 80.dp else 16.dp,
            label = "fabBottom"
        )

        FloatingPlayShuffleButton(
            onPlayAll = { onPlayAllOrShuffle(tracks, title, false) },
            onShuffleAll = { onPlayAllOrShuffle(tracks, title, true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
        )
    }
}

/**
 * Playlists Section View
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlaylistsSectionView(
    contentBottomPadding: Dp = 12.dp,
    tracks: List<AudioItem>,
    folders: List<AudioFolder>,
    recentlyAdded: List<AudioItem>,
    listState: LazyListState = rememberLazyListState(),
    onOpenPlaylist: (String, List<AudioItem>) -> Unit,
    onOpenFolder: (AudioFolder) -> Unit,
    onBlacklistFolder: (AudioFolder) -> Unit = {},
    isScanning: Boolean = false
) {
    val context = LocalContext.current
    val prefsManager = remember { com.jk.mediaplayer.data.PreferencesManager(context) }
    val isNetworkStreamIconEnabled = remember(com.jk.mediaplayer.data.PreferencesManager.settingsVersion) {
        prefsManager.isNetworkStreamIconEnabled()
    }
    val playlistVersion = com.jk.mediaplayer.data.PreferencesManager.playlistVersion
    val favoriteIds = remember(tracks, playlistVersion) { prefsManager.getFavorites() }
    val likedTracks = remember(tracks, favoriteIds) {
        tracks.filter { favoriteIds.contains(it.id.toString()) }
    }
    val trackMap = remember(tracks) { tracks.associateBy { it.id } }
    val recentlyPlayedIds = remember(playlistVersion) { prefsManager.getRecentlyPlayedTrackIds() }
    val recentlyPlayedTracks = remember(trackMap, recentlyPlayedIds) {
        recentlyPlayedIds.mapNotNull { trackMap[it] }
    }
    val mostPlayedIds = remember(tracks, playlistVersion) { prefsManager.getMostPlayedTrackIds(tracks.map { it.id }) }
    val mostPlayedTracks = remember(trackMap, mostPlayedIds) {
        mostPlayedIds.mapNotNull { trackMap[it] }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF232532) else Color(0xFFE5E7EB)
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)

    val customPlaylists = remember(playlistVersion) { prefsManager.getCustomPlaylists() }
    val folderPages = remember(folders) { folders.chunked(4) }
    val playlistFoldersPagerState = rememberPagerState(pageCount = { folderPages.size })

    // Dialog & Picker States
    var showCreateNameDialog by remember { mutableStateOf(false) }
    var createNameText by remember { mutableStateOf("") }
    var pickerPlaylistTarget by remember { mutableStateOf<CustomPlaylist?>(null) }
    var pickerForNewName by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CustomPlaylist?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CustomPlaylist?>(null) }
    var menuTarget by remember { mutableStateOf<CustomPlaylist?>(null) }

    if (isScanning && tracks.isEmpty() && folders.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MusicRed,
                modifier = Modifier.size(36.dp)
            )
        }
        return
    }
    if (tracks.isEmpty() && folders.isEmpty() && customPlaylists.isEmpty()) {
        MusicEmptyState(
            title = "No Folders Found",
            subtitle = "Folders containing audio files will appear here.",
            icon = Icons.Default.Folder
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentBottomPadding, start = 20.dp, end = 20.dp, top = 8.dp)
    ) {
        item {
            Text(
                text = "Auto Playlists",
                color = textColorPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // 2x2 Grid of Smart Playlists (Album Tab style: Clean artwork with text below)
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmartPlaylistGradientCard(
                        title = "Liked Music",
                        tracks = likedTracks,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenPlaylist("Liked Music", likedTracks)
                        }
                    )

                    SmartPlaylistGradientCard(
                        title = "Last Added",
                        tracks = recentlyAdded,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenPlaylist("Last Added", recentlyAdded)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmartPlaylistGradientCard(
                        title = "Recently Played",
                        tracks = recentlyPlayedTracks,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenPlaylist("Recently Played", recentlyPlayedTracks)
                        }
                    )

                    SmartPlaylistGradientCard(
                        title = "Most Played",
                        tracks = mostPlayedTracks,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenPlaylist("Most Played", mostPlayedTracks)
                        }
                    )
                }
            }
        }

        // Custom Playlists Section Header + "+ New Playlist" Button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playlists (${customPlaylists.size})",
                    color = textColorPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.clickable {
                        createNameText = ""
                        showCreateNameDialog = true
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Playlist",
                            tint = textColorPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "New Playlist",
                            color = textColorPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (customPlaylists.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            createNameText = ""
                            showCreateNameDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MusicRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    tint = MusicRed,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Create Your First Playlist",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColorPrimary
                            )
                            Text(
                                text = "Select songs and organize your personal collection",
                                fontSize = 13.sp,
                                color = textColorSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = textColorSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else {
            items(customPlaylists, key = { it.id }) { pl ->
                val plTracks = remember(pl.trackIds, tracks) {
                    if (pl.name.equals("Ringtones", ignoreCase = true)) {
                        tracks.filter {
                            pl.trackIds.contains(it.id) ||
                            it.path.contains("jkmediaplayer", ignoreCase = true) ||
                            it.title.contains("(ringtone)", ignoreCase = true)
                        }
                    } else {
                        tracks.filter { pl.trackIds.contains(it.id) }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .combinedClickable(
                            onClick = { onOpenPlaylist(pl.name, plTracks) },
                            onLongClick = { menuTarget = pl }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MusicRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = null,
                                    tint = MusicRed,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColorPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${plTracks.size} tracks",
                                fontSize = 13.sp,
                                color = textColorSecondary
                            )
                        }
                        IconButton(onClick = { menuTarget = pl }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = textColorSecondary
                            )
                        }
                    }
                }
            }
        }

        // Folders Section Header
        item {
            Text(
                text = "Music Folders (${folders.size})",
                color = textColorPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp, bottom = 12.dp)
            )
        }

        // 4x1 Folders with horizontal swipe
        if (folders.isNotEmpty()) {
            item {
                HorizontalPager(
                    state = playlistFoldersPagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(316.dp),
                    key = { "playlist_folders_page_$it" }
                ) { pageIndex ->
                    val pageFolders = folderPages.getOrNull(pageIndex).orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(316.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        pageFolders.forEach { folder ->
                            FolderRow(
                                folder = folder,
                                onClick = { onOpenFolder(folder) },
                                onBlacklistFolder = onBlacklistFolder
                            )
                        }
                        val missingCount = 4 - pageFolders.size
                        if (missingCount > 0) {
                            repeat(missingCount) {
                                Spacer(modifier = Modifier.height(76.dp))
                            }
                        }
                    }
                }

                PageIndicatorDots(
                    pageCount = folderPages.size,
                    currentPage = playlistFoldersPagerState.currentPage
                )
            }
        }
    }

    // 1. Enter Playlist Name Dialog
    if (showCreateNameDialog) {
        Dialog(
            onDismissRequest = { showCreateNameDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = "New Playlist",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorPrimary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = createNameText,
                        onValueChange = { createNameText = it },
                        placeholder = { Text("Playlist name (e.g. My Favorites)", color = textColorSecondary) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MusicRed,
                            unfocusedBorderColor = cardBorder,
                            focusedTextColor = textColorPrimary,
                            unfocusedTextColor = textColorPrimary,
                            focusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6),
                            unfocusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCreateNameDialog = false }) {
                            Text("Cancel", color = textColorSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (createNameText.isNotBlank()) {
                                    val name = createNameText.trim()
                                    showCreateNameDialog = false
                                    pickerForNewName = name
                                }
                            },
                            enabled = createNameText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MusicRed)
                        ) {
                            Text("Select Songs", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 2. Playlist 3-Dot / Long-Press Action Modal
    menuTarget?.let { pl ->
        Dialog(
            onDismissRequest = { menuTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = pl.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pl.trackIds.size} songs",
                        fontSize = 13.sp,
                        color = textColorSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Edit Songs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                menuTarget = null
                                pickerPlaylistTarget = pl
                            }
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null, tint = MusicRed, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Text("Add / Remove Songs", fontSize = 17.sp, color = textColorPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    // Rename
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                renameText = pl.name
                                renameTarget = pl
                                menuTarget = null
                            }
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = textColorSecondary, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Text("Rename Playlist", fontSize = 17.sp, color = textColorPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    // Delete
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                deleteTarget = pl
                                menuTarget = null
                            }
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Text("Delete Playlist", fontSize = 17.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // 3. Rename Playlist Dialog
    renameTarget?.let { pl ->
        Dialog(
            onDismissRequest = { renameTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = "Rename Playlist",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorPrimary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MusicRed,
                            unfocusedBorderColor = cardBorder,
                            focusedTextColor = textColorPrimary,
                            unfocusedTextColor = textColorPrimary,
                            focusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6),
                            unfocusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { renameTarget = null }) {
                            Text("Cancel", color = textColorSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (renameText.isNotBlank()) {
                                    prefsManager.renameCustomPlaylist(pl.id, renameText)
                                    renameTarget = null
                                }
                            },
                            enabled = renameText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MusicRed)
                        ) {
                            Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 4. Delete Playlist Confirmation Dialog
    deleteTarget?.let { pl ->
        Dialog(
            onDismissRequest = { deleteTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = "Delete Playlist?",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Are you sure you want to delete playlist \"${pl.name}\"? Song files will not be deleted from storage.",
                        fontSize = 13.sp,
                        color = textColorSecondary
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { deleteTarget = null }) {
                            Text("Cancel", color = textColorSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                prefsManager.deleteCustomPlaylist(pl.id)
                                deleteTarget = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 5. Song Picker Dialog (Used for both New Playlist and Editing Songs)
    val pickerName = pickerForNewName ?: pickerPlaylistTarget?.name
    if (pickerName != null) {
        val initialSelected = remember(pickerForNewName, pickerPlaylistTarget) {
            pickerPlaylistTarget?.trackIds?.toSet() ?: emptySet()
        }
        PlaylistSongPickerDialog(
            playlistName = pickerName,
            allTracks = tracks,
            initialSelectedIds = initialSelected,
            isDark = isDark,
            onDismiss = {
                pickerForNewName = null
                pickerPlaylistTarget = null
            },
            onSave = { selectedIds ->
                if (pickerForNewName != null) {
                    prefsManager.createCustomPlaylist(pickerForNewName!!, selectedIds.toList())
                } else if (pickerPlaylistTarget != null) {
                    prefsManager.updateCustomPlaylist(pickerPlaylistTarget!!.id, pickerPlaylistTarget!!.name, selectedIds.toList())
                }
                pickerForNewName = null
                pickerPlaylistTarget = null
            }
        )
    }
}

/**
 * Interactive Song Picker with Search, Multi-selection ticks, and Rounded Rectangle styling
 */
@Composable
private fun PlaylistSongPickerDialog(
    playlistName: String,
    allTracks: List<AudioItem>,
    initialSelectedIds: Set<Long>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (Set<Long>) -> Unit
) {
    val selectedIds = remember { mutableStateListOf<Long>().apply { addAll(initialSelectedIds) } }
    var searchQuery by remember { mutableStateOf("") }

    val filteredTracks = remember(allTracks, searchQuery) {
        if (searchQuery.isBlank()) allTracks
        else allTracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF262838) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111216)
    val textSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlistName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${selectedIds.size} songs selected",
                            fontSize = 13.sp,
                            color = MusicRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar with rounded rectangle
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs by title or artist...", color = textSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = textSecondary, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MusicRed,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6),
                        unfocusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Select All / Deselect All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available Songs (${filteredTracks.size})",
                        fontSize = 13.sp,
                        color = textSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = {
                            if (selectedIds.size == allTracks.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(allTracks.map { it.id })
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedIds.size == allTracks.size) "Deselect All" else "Select All",
                            fontSize = 13.sp,
                            color = MusicRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = cardBorder)

                // Songs List with Checkboxes
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp)
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        val isChecked = selectedIds.contains(track.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isChecked) selectedIds.remove(track.id)
                                    else selectedIds.add(track.id)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val context = LocalContext.current
                            val request = remember(track.id, track.albumArtUri, track.onlineArtUrl) {
                                ImageRequest.Builder(context)
                                    .data(track.albumArtUri ?: track.onlineArtUrl)
                                    .crossfade(150)
                                    .error(R.drawable.ic_default_album_art)
                                    .placeholder(R.drawable.ic_default_album_art)
                                    .fallback(R.drawable.ic_default_album_art)
                                    .build()
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    fontSize = 13.sp,
                                    color = textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedIds.add(track.id)
                                    else selectedIds.remove(track.id)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MusicRed,
                                    uncheckedColor = cardBorder,
                                    checkmarkColor = Color.White
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = cardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = textSecondary)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { onSave(selectedIds.toSet()) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MusicRed)
                    ) {
                        Text("Save Playlist (${selectedIds.size})", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
