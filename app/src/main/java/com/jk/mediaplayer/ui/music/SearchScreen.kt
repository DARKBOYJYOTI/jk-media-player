package com.jk.mediaplayer.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jk.mediaplayer.R
import com.jk.mediaplayer.data.YouTubeTrack
import com.jk.mediaplayer.model.AlbumItem
import com.jk.mediaplayer.model.ArtistItem
import com.jk.mediaplayer.model.AudioFolder
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.model.formatDuration
import com.jk.mediaplayer.ui.theme.*
import kotlinx.coroutines.delay


/**
 * Search helpers. Every comparison runs on normalized text (lowercase, accents and punctuation
 * removed) and returns a score instead of a boolean, so results can be ranked.
 */
private val SEARCH_SPACE = Regex("\\s+")

private fun normalizeSearchText(raw: String): String {
    if (raw.isEmpty()) return ""
    val decomposed = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
    val out = StringBuilder(decomposed.length)
    var pendingSpace = false
    for (ch in decomposed) {
        when {
            Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
            ch.isLetterOrDigit() -> {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                out.append(ch.lowercaseChar())
                pendingSpace = false
            }
            else -> pendingSpace = true
        }
    }
    return out.toString()
}

private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > 3) return Int.MAX_VALUE
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = cur
        cur = tmp
    }
    return prev[b.length]
}

private fun isSubsequenceWithinSpan(query: String, target: String): Boolean {
    var qi = 0
    var first = -1
    for (i in target.indices) {
        if (target[i] == query[qi]) {
            if (first < 0) first = i
            qi++
            if (qi == query.length) return (i - first + 1) <= query.length * 3
        }
    }
    return false
}

/** 0 = no match, higher is a better match. Inputs must already be normalized. */
private fun searchScore(query: String, target: String): Int {
    if (query.isEmpty() || target.isEmpty()) return 0
    if (target == query) return 1000
    if (target.startsWith(query)) return 850
    if (target.split(SEARCH_SPACE).any { it.startsWith(query) }) return 760
    if (target.contains(query)) return 660
    val tokens = query.split(SEARCH_SPACE).filter { it.isNotBlank() }
    if (tokens.size > 1 && tokens.all { target.contains(it) }) return 560
    val tolerance = when {
        query.length >= 7 -> 2
        query.length >= 4 -> 1
        else -> 0
    }
    if (tolerance > 0) {
        val words = target.split(SEARCH_SPACE)
        if (words.any { word ->
                kotlin.math.abs(word.length - query.length) <= tolerance &&
                    editDistance(query, word) <= tolerance
            }
        ) {
            return 480
        }
        if (editDistance(query, target) <= tolerance) return 460
    }
    if (query.length >= 3 && isSubsequenceWithinSpan(query, target)) return 300
    return 0
}

private data class SearchTrackEntry(
    val track: AudioItem,
    val title: String,
    val artists: List<String>,
    val album: String,
    val plays: Int
)

private data class SearchAlbumEntry(val album: AlbumItem, val name: String, val artist: String)

private data class SearchArtistEntry(val artist: ArtistItem, val name: String)

private data class SearchFolderEntry(val folder: AudioFolder, val name: String)
@Composable
fun SearchScreen(
    tracks: List<AudioItem>,
    albums: List<AlbumItem>,
    artists: List<ArtistItem>,
    folders: List<AudioFolder>,
    currentTrack: AudioItem?,
    isPlaying: Boolean = false,
    onTrackClick: (List<AudioItem>, Int) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onFolderClick: (AudioFolder) -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {},
    ytSearch: suspend (String) -> List<YouTubeTrack> = { emptyList() },
    onPlayYouTube: (List<YouTubeTrack>, Int) -> Unit = { _, _ -> },
    onYouTubePlayNext: (YouTubeTrack) -> Unit = {},
    onYouTubeAddToQueue: (YouTubeTrack) -> Unit = {},
    bottomContentPadding: Dp = 24.dp,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var ytResults by remember { mutableStateOf<List<YouTubeTrack>>(emptyList()) }
    var ytLoading by remember { mutableStateOf(false) }
    var ytError by remember { mutableStateOf<String?>(null) }
    var ytQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Remote search is debounced and runs for the All tab and the YT Music chip, so the local
    // library search stays instant and offline. Switching between the two reuses the last result.
    LaunchedEffect(searchQuery, selectedFilter) {
        val query = searchQuery.trim()
        val wantsRemote = selectedFilter == "All" || selectedFilter == "YT Music"
        if (!wantsRemote || query.length < 2) {
            ytResults = emptyList()
            ytError = null
            ytLoading = false
            ytQuery = ""
            return@LaunchedEffect
        }
        if (query == ytQuery && ytResults.isNotEmpty()) return@LaunchedEffect
        ytLoading = true
        ytError = null
        delay(400)
        val found = ytSearch(query)
        ytQuery = query
        ytResults = found
        ytLoading = false
        if (found.isEmpty()) ytError = "No YouTube Music results for \"$query\""
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val searchPrefs = remember(context) { com.jk.mediaplayer.data.PreferencesManager(context) }

    // The field stays instant; the scoring pass runs on a slightly settled query.
    var settledQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            settledQuery = searchQuery
        } else {
            kotlinx.coroutines.delay(130)
            settledQuery = searchQuery
        }
    }

    // Normalized index: built once per library, reused for every keystroke.
    val trackIndex = remember(tracks, searchPrefs) {
        val playCounts = searchPrefs.getAllTrackPlayCounts()
        tracks.map { track ->
            val credits = splitArtistNames(track.artist)
                .map { normalizeSearchText(it) }
                .filter { it.isNotBlank() }
            SearchTrackEntry(
                track = track,
                title = normalizeSearchText(track.title),
                artists = credits.ifEmpty { listOf(normalizeSearchText(track.artist)) },
                album = normalizeSearchText(track.album),
                plays = playCounts[track.id] ?: 0
            )
        }
    }
    val albumIndex = remember(albums) {
        albums.map { SearchAlbumEntry(it, normalizeSearchText(it.name), normalizeSearchText(it.artist)) }
    }
    val artistIndex = remember(artists) {
        artists.map { SearchArtistEntry(it, normalizeSearchText(it.name)) }
    }
    val folderIndex = remember(folders) {
        folders.map { SearchFolderEntry(it, normalizeSearchText(it.name)) }
    }

    val normalizedQuery = remember(settledQuery) { normalizeSearchText(settledQuery) }

    val filteredTracks = remember(trackIndex, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            emptyList()
        } else {
            trackIndex.mapNotNull { entry ->
                val titleScore = searchScore(normalizedQuery, entry.title) * 100
                val artistScore = (entry.artists.maxOfOrNull { searchScore(normalizedQuery, it) } ?: 0) * 85
                val albumScore = searchScore(normalizedQuery, entry.album) * 60
                val best = maxOf(titleScore, artistScore, albumScore)
                if (best <= 0) null else best to entry
            }
                .sortedWith(
                    compareByDescending<Pair<Int, SearchTrackEntry>> { it.first }
                        .thenByDescending { it.second.plays }
                        .thenBy { it.second.track.title.lowercase() }
                )
                .take(300)
                .map { it.second.track }
        }
    }

    val filteredAlbums = remember(albumIndex, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            emptyList()
        } else {
            albumIndex.mapNotNull { entry ->
                val score = maxOf(
                    searchScore(normalizedQuery, entry.name) * 100,
                    searchScore(normalizedQuery, entry.artist) * 80
                )
                if (score <= 0) null else score to entry
            }
                .sortedWith(
                    compareByDescending<Pair<Int, SearchAlbumEntry>> { it.first }
                        .thenBy { it.second.name.lowercase() }
                )
                .take(100)
                .map { it.second.album }
        }
    }

    val filteredArtists = remember(artistIndex, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            emptyList()
        } else {
            artistIndex.mapNotNull { entry ->
                val score = searchScore(normalizedQuery, entry.name) * 100
                if (score <= 0) null else score to entry
            }
                .sortedWith(
                    compareByDescending<Pair<Int, SearchArtistEntry>> { it.first }
                        .thenBy { it.second.name.lowercase() }
                )
                .take(100)
                .map { it.second.artist }
        }
    }

    val filteredFolders = remember(folderIndex, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            emptyList()
        } else {
            folderIndex.mapNotNull { entry ->
                val score = searchScore(normalizedQuery, entry.name) * 100
                if (score <= 0) null else score to entry
            }
                .sortedWith(
                    compareByDescending<Pair<Int, SearchFolderEntry>> { it.first }
                        .thenBy { it.second.name.lowercase() }
                )
                .take(100)
                .map { it.second.folder }
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val bgColor = if (isDark) Color(0xFF0A0A0C) else LightBackground
    val pillBg = if (isDark) Color(0xFF1E1E24) else LightSurface
    val pillBorder = if (isDark) Color(0xFF2E2E36) else LightBorder
    val cardBg = if (isDark) Color(0xFF1E1E24) else LightSurfaceVariant
    val idleIconBg = if (isDark) Color(0xFF181820) else LightSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        // Top Search Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.clip(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = textColorPrimary
                )
            }

            // Search Input Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = pillBg,
                border = BorderStroke(1.dp, pillBorder),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = textColorSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = LocalTextStyle.current.copy(
                            color = textColorPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Normal,
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                        ),
                        cursorBrush = SolidColor(MusicRed),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search songs, albums, artists...",
                                        color = textColorSecondary.copy(alpha = 0.6f),
                                        fontSize = 17.sp,
                                        style = LocalTextStyle.current.copy(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = textColorSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Category Filter Chips Row
        val categories = listOf("All", "Songs", "Albums", "Artists", "Folders", "YT Music")
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            items(categories) { category ->
                val isSelected = selectedFilter == category
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) (if (isDark) Color.White else MusicRed) else (if (isDark) Color(0xFF1C1C22) else LightSurface),
                    border = BorderStroke(1.dp, if (isSelected) (if (isDark) Color.White else MusicRed) else (if (isDark) Color(0xFF2C2C34) else LightBorder)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { selectedFilter = category }
                ) {
                    Text(
                        text = category,
                        color = if (isSelected) (if (isDark) Color.Black else Color.White) else textColorPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Search Results Content
        if (selectedFilter == "YT Music") {
            YouTubeResultsSection(
                query = searchQuery,
                loading = ytLoading,
                error = ytError,
                results = ytResults,
                isDark = isDark,
                textColorPrimary = textColorPrimary,
                textColorSecondary = textColorSecondary,
                onPlay = onPlayYouTube,
                onPlayNext = onYouTubePlayNext,
                onAddToQueue = onYouTubeAddToQueue,
                bottomContentPadding = bottomContentPadding
            )
        } else if (searchQuery.isBlank()) {
            // Idle State: suggestions / quick start (Positioned near the top so keyboard never covers it)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(36.dp))
                Surface(
                    shape = CircleShape,
                    color = idleIconBg,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MusicRed.copy(alpha = 0.8f),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Find your music",
                    color = textColorPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Search by song name, artist, album, or folder",
                    color = textColorSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            val totalResults = filteredTracks.size + filteredAlbums.size + filteredArtists.size + filteredFolders.size
            // The All tab also carries a YouTube Music block, so an empty local library is not
            // "no results" while the remote section has something to show.
            val hasYouTubeSection = selectedFilter == "All" && (ytLoading || ytResults.isNotEmpty())
            if (totalResults == 0 && !hasYouTubeSection) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No results for \"$searchQuery\"",
                        color = textColorPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Check spelling or try another keyword",
                        color = textColorSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding)
                ) {
                    // 1. ALL or SONGS
                    if ((selectedFilter == "All" || selectedFilter == "Songs") && filteredTracks.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Songs (${filteredTracks.size})",
                                    color = textColorPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Play all",
                                    color = MusicRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onTrackClick(filteredTracks, 0) }
                                        .padding(4.dp)
                                )
                            }
                        }

                        val tracksToShow = if (selectedFilter == "All") filteredTracks.take(6) else filteredTracks
                        itemsIndexed(tracksToShow) { index, track ->
                            SearchTrackRow(
                                track = track,
                                isPlayingThis = track.id == currentTrack?.id,
                                isPlaying = isPlaying,
                                onClick = { onTrackClick(filteredTracks, index) },
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onGoToAlbum = { albTrack ->
                                    val alb = albums.firstOrNull { it.name.equals(albTrack.album, ignoreCase = true) }
                                    if (alb != null) onAlbumClick(alb)
                                },
                                onGoToArtist = { artTrack ->
                                    val art = resolveArtist(artTrack.artist, artists, tracks)
                                    onArtistClick(art)
                                },
                                onShowDetails = onShowDetails,
                                onOpenTagEditor = onOpenTagEditor,
                                onOpenRingtoneCutter = onOpenRingtoneCutter,
                                onDeleteTrack = onDeleteTrack,
                                onBlacklistTrack = onBlacklistTrack
                            )
                        }
                    }

                    // 2. ALL or ALBUMS
                    if ((selectedFilter == "All" || selectedFilter == "Albums") && filteredAlbums.isNotEmpty()) {
                        item {
                            Text(
                                text = "Albums (${filteredAlbums.size})",
                                color = textColorPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }

                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(filteredAlbums, key = { "${it.name}|${it.artist}" }) { album ->
                                    Column(
                                        modifier = Modifier
                                            .width(130.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onAlbumClick(album) }
                                    ) {
                                        val albumRequest = remember(album.name, album.albumArtUri) {
                                            ImageRequest.Builder(context)
                                                .data(album)
                                                .crossfade(true)
                                                .error(R.drawable.ic_default_album_art)
                                                .placeholder(R.drawable.ic_default_album_art)
                                                .build()
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = cardBg,
                                            modifier = Modifier.size(130.dp)
                                        ) {
                                            AsyncImage(
                                                model = albumRequest,
                                                contentDescription = album.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = album.name,
                                            color = textColorPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${album.tracks.size} songs",
                                            color = textColorSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. ALL or ARTISTS
                    if ((selectedFilter == "All" || selectedFilter == "Artists") && filteredArtists.isNotEmpty()) {
                        item {
                            Text(
                                text = "Artists (${filteredArtists.size})",
                                color = textColorPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }

                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(filteredArtists, key = { it.name }) { artist ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(96.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onArtistClick(artist) }
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = cardBg,
                                            modifier = Modifier.size(80.dp)
                                        ) {
                                            val fetchedArtistImage = rememberArtistImage(artist.name)
                                            val artistImage = fetchedArtistImage
                                                ?: artist.tracks.firstOrNull { it.albumArtUri != null }?.albumArtUri
                                            val artistRequest = remember(artist.name, artistImage) {
                                                ImageRequest.Builder(context)
                                                    .data(artistImage)
                                                    .crossfade(true)
                                                    .error(R.drawable.ic_default_album_art)
                                                    .placeholder(R.drawable.ic_default_album_art)
                                                    .build()
                                            }
                                            AsyncImage(
                                                model = artistRequest,
                                                contentDescription = artist.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = artist.name,
                                            color = textColorPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. ALL or FOLDERS
                    if ((selectedFilter == "All" || selectedFilter == "Folders") && filteredFolders.isNotEmpty()) {
                        item {
                            Text(
                                text = "Folders (${filteredFolders.size})",
                                color = textColorPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }

                        items(filteredFolders, key = { it.path }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onFolderClick(folder) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = cardBg,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MusicRed,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.name,
                                        color = textColorPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${folder.tracks.size} tracks",
                                        color = textColorSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    // 5. ALL: YouTube Music block under the local results
                    if (selectedFilter == "All" && (ytLoading || ytResults.isNotEmpty())) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "YT Music",
                                    color = textColorPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!ytLoading && ytResults.isNotEmpty()) {
                                    Text(
                                        text = "See all",
                                        color = MusicRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedFilter = "YT Music" }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                        if (ytLoading && ytResults.isEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MusicRed
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Searching YouTube Music…",
                                        color = textColorSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        val previewCount = minOf(5, ytResults.size)
                        itemsIndexed(ytResults.take(previewCount)) { index, track ->
                            YouTubeTrackRow(
                                track = track,
                                isDark = isDark,
                                textColorPrimary = textColorPrimary,
                                textColorSecondary = textColorSecondary,
                                onClick = { onPlayYouTube(ytResults, index) },
                                onPlayNext = { onYouTubePlayNext(track) },
                                onAddToQueue = { onYouTubeAddToQueue(track) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchTrackRow(
    track: AudioItem,
    isPlayingThis: Boolean,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onPlayNext: (AudioItem) -> Unit = {},
    onAddToQueue: (AudioItem) -> Unit = {},
    onGoToAlbum: (AudioItem) -> Unit = {},
    onGoToArtist: (AudioItem) -> Unit = {},
    onShowDetails: (AudioItem) -> Unit = {},
    onOpenTagEditor: (AudioItem) -> Unit = {},
    onOpenRingtoneCutter: (AudioItem) -> Unit = {},
    onDeleteTrack: (AudioItem) -> Unit = {},
    onBlacklistTrack: (AudioItem) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColorPrimary = if (isDark) Color.White else LightTextPrimary
    val textColorSecondary = if (isDark) Color.White.copy(alpha = 0.55f) else LightTextSecondary
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val trackRequest = remember(track.id, track.albumArtUri) {
            ImageRequest.Builder(context)
                .data(track)
                .crossfade(true)
                .error(R.drawable.ic_default_album_art)
                .placeholder(R.drawable.ic_default_album_art)
                .build()
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isDark) Color(0xFF1E1E24) else LightSurfaceVariant,
            modifier = Modifier.size(46.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = trackRequest,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
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
            val cleanTitle = track.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a")
            Text(
                text = cleanTitle,
                color = if (isPlayingThis) MusicRed else textColorPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val cleanArtist = if (track.artist == "<unknown>" || track.artist.isBlank()) "Unknown Artist" else track.artist
            Text(
                text = "$cleanArtist • ${formatDuration(track.duration)}",
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

            SongOptionsMenu(
                track = track,
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                isCurrentTrack = isPlayingThis,
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onGoToAlbum = { onGoToAlbum(track) },
                onGoToArtist = { onGoToArtist(track) },
                onShowDetails = { onShowDetails(track) },
                onOpenTagEditor = { onOpenTagEditor(track) },
                onOpenRingtoneCutter = { onOpenRingtoneCutter(track) },
                onDelete = { onDeleteTrack(track) },
                onBlacklist = { onBlacklistTrack(track) }
            )
        }
    }
}

/**
 * YouTube Music results for the search screen.
 *
 * The section owns no player state: tapping a row hands the whole result list back to the
 * activity so the queue and the local library stay in one place.
 */
@Composable
private fun YouTubeResultsSection(
    query: String,
    loading: Boolean,
    error: String?,
    results: List<YouTubeTrack>,
    isDark: Boolean,
    textColorPrimary: Color,
    textColorSecondary: Color,
    onPlay: (List<YouTubeTrack>, Int) -> Unit,
    onPlayNext: (YouTubeTrack) -> Unit,
    onAddToQueue: (YouTubeTrack) -> Unit,
    bottomContentPadding: Dp = 24.dp
) {
    when {
        query.trim().length < 2 -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = "Search YouTube Music",
                    color = textColorPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Type at least 2 characters to play a song online",
                    color = textColorSecondary,
                    fontSize = 13.sp
                )
            }
        }

        loading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MusicRed
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Searching YouTube Music…",
                    color = textColorSecondary,
                    fontSize = 13.sp
                )
            }
        }

        error != null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = error,
                    color = textColorPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Check your connection or try another keyword",
                    color = textColorSecondary,
                    fontSize = 13.sp
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "YT Music (${results.size})",
                            color = textColorPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Play all",
                            color = MusicRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPlay(results, 0) }
                                .padding(4.dp)
                        )
                    }
                }
                itemsIndexed(results) { index, track ->
                    YouTubeTrackRow(
                        track = track,
                        isDark = isDark,
                        textColorPrimary = textColorPrimary,
                        textColorSecondary = textColorSecondary,
                        onClick = { onPlay(results, index) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeTrackRow(
    track: YouTubeTrack,
    isDark: Boolean,
    textColorPrimary: Color,
    textColorSecondary: Color,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val artRequest = remember(track.videoId, track.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(track.thumbnailUrl)
            .crossfade(true)
            .error(R.drawable.ic_default_album_art)
            .placeholder(R.drawable.ic_default_album_art)
            .fallback(R.drawable.ic_default_album_art)
            .build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isDark) Color(0xFF1E1E24) else LightSurfaceVariant,
            modifier = Modifier.size(46.dp)
        ) {
            AsyncImage(
                model = artRequest,
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = textColorPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${track.artist} • ${formatDuration(track.durationMs)}",
                color = textColorSecondary,
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
                    .size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = textColorSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = {
                        showMenu = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        showMenu = false
                        onAddToQueue()
                    }
                )
            }
        }
    }
}
