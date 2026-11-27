package com.jk.mediaplayer.ui.music

import com.jk.mediaplayer.ui.common.AboutTosHelpView
import com.jk.mediaplayer.ui.common.AppToast


import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import com.jk.mediaplayer.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jk.mediaplayer.data.OnlineMetadataFetcher
import com.jk.mediaplayer.data.MediaScanner
import com.jk.mediaplayer.data.PreferencesManager
import kotlin.math.roundToInt
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.service.AudioPlayerService
import com.jk.mediaplayer.ui.common.*
import com.jk.mediaplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentThemeMode: String,
    onThemeChange: (String) -> Unit,
    onRescanLibrary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tracks: List<AudioItem> = emptyList(),
    audioService: AudioPlayerService? = null
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    var filterShortAudio by remember { mutableStateOf(prefsManager.isFilterShortAudio()) }
    var showPlayerVolumeBar by remember { mutableStateOf(prefsManager.isShowPlayerVolumeBar()) }
    var musicVolumeBoostEnabled by remember { mutableStateOf(prefsManager.isMusicVolumeBoostEnabled()) }
    var networkIconEnabled by remember { mutableStateOf(prefsManager.isNetworkStreamIconEnabled()) }
    val settingsVersion = PreferencesManager.settingsVersion
    var musicLauncherLabel by remember(settingsVersion) { mutableStateOf(prefsManager.getMusicLauncherLabel()) }
    var useSystemFont by remember(settingsVersion) { mutableStateOf(prefsManager.isUseSystemFont()) }

    var activeBlacklistSubScreen by remember { mutableStateOf<String?>(null) }
    var settingsCategory by remember { mutableStateOf<String?>(null) }
    var aboutTab by remember { mutableIntStateOf(0) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    val blacklistVersion = PreferencesManager.blacklistVersion
    val musicBlacklistedFolders = remember(blacklistVersion) { prefsManager.getMusicBlacklistedFolders() }
    val musicBlacklistedFiles = remember(blacklistVersion) { prefsManager.getMusicBlacklistedFiles() }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF232532) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111216)
    val textSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)

    val scrollState = rememberScrollState()

    // Pulsing heart animation
    val infiniteTransition = rememberInfiniteTransition(label = "heartPulse")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    // Blinking heart animation
    val heartAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(modifier = modifier.fillMaxSize()) {
        BackHandler(enabled = activeBlacklistSubScreen == null) {
            if (settingsCategory != null) settingsCategory = null else onBack()
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = if (isDark) Color(0xFF0F1015) else Color.White,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                if (settingsCategory != null) settingsCategory = null else onBack()
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = settingsCategory ?: "Settings",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary
                                )
                                Text(
                                    text = if (settingsCategory == null) "Organized music settings" else "Back to settings",
                                    fontSize = 13.sp,
                                    color = textSecondary
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
                    }
                }
            }
        ) { innerPadding ->
            if (settingsCategory == "ABOUT") {
                AboutTosHelpView(
                    accentColor = MusicRed,
                    innerPadding = innerPadding,
                    initialTab = aboutTab
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                if (settingsCategory == null) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = cardBg,
                        border = BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_media_round),
                                contentDescription = "JK Media Player",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "JK Media Player",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "Version 1.0.0",
                                fontSize = 11.sp,
                                color = textSecondary
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Made with", fontSize = 13.sp, color = textSecondary)
                                Spacer(Modifier.width(5.dp))
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = MusicRed,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .graphicsLayer {
                                            alpha = heartAlpha
                                            scaleX = heartScale
                                            scaleY = heartScale
                                        }
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    "by Jyoti Karmakar",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textSecondary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "PREFERENCES",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MusicRed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                    )
                    val preferenceRows = listOf(
                        Triple("APPEARANCE", "Theme, launcher name, font, player volume bar", "APPEARANCE"),
                        Triple("SOUND", "System equalizer, 200% volume boost", "SOUND"),
                        Triple("LIBRARY", "Filters, blacklists, network stream", "LIBRARY"),
                        Triple("ABOUT", "About, terms of service, help", "ABOUT")
                    )
                    preferenceRows.forEach { (title, subtitle, key) ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable {
                                    settingsCategory = key
                                }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                                    Text(subtitle, fontSize = 13.sp, color = textSecondary)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = textSecondary)
                            }
                        }
                        Spacer(Modifier.height(9.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "QUICK ACTIONS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MusicRed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                    )
                    SettingsQuickAction(
                        title = "Rescan Library",
                        subtitle = "Scan device storage for new songs",
                        icon = Icons.Default.Refresh,
                        isDark = isDark,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClick = {
                            onRescanLibrary()
                            AppToast.show("Rescanning music library...")
                        }
                    )
                    Spacer(Modifier.height(9.dp))
                    SettingsQuickAction(
                        title = "Clear Temporary Storage",
                        subtitle = "Remove cached artwork, lyrics and thumbnails",
                        icon = Icons.Default.DeleteOutline,
                        isDark = isDark,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClick = { showClearCacheConfirm = true }
                    )
                } else when (settingsCategory) {
                    "APPEARANCE" -> {
                        SettingsGroup(cardBg, cardBorder) {
                            Text("Theme Mode", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6)).padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("SYSTEM" to "Auto", "LIGHT" to "Light", "DARK" to "Dark").forEach { (mode, label) ->
                                    val selected = currentThemeMode == mode
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MusicRed else Color.Transparent,
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable {
                                            onThemeChange(mode)
                                            prefsManager.saveMusicThemeMode(mode)
                                        }
                                    ) {
                                        Text(label, textAlign = TextAlign.Center, color = if (selected) Color.White else textSecondary,
                                            fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cardBorder)
                            Text("Launcher App Name", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                            Text("Home screen icon label: $musicLauncherLabel", fontSize = 13.sp, color = textSecondary)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6)).padding(4.dp)) {
                                listOf("JK Music", "Music").forEach { label ->
                                    val selected = musicLauncherLabel == label
                                    Surface(
                                        shape = RoundedCornerShape(10.dp), color = if (selected) MusicRed else Color.Transparent,
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable {
                                            musicLauncherLabel = label
                                            prefsManager.setMusicLauncherLabel(label)
                                            com.jk.mediaplayer.util.LauncherIconHelper.updateMusicLauncherLabel(context, label)
                                        }
                                    ) { Text(label, textAlign = TextAlign.Center, color = if (selected) Color.White else textSecondary, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) }
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cardBorder)
                            SettingsToggleRow("Use System Font", "Use device default font", useSystemFont, textPrimary, textSecondary, isDark) {
                                useSystemFont = it
                                prefsManager.setUseSystemFont(it)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cardBorder)
                            SettingsToggleRow("Player Volume Bar", "Show volume slider in player screen", showPlayerVolumeBar, textPrimary, textSecondary, isDark) {
                                showPlayerVolumeBar = it
                                prefsManager.setShowPlayerVolumeBar(it)
                            }
                        }
                    }
                    "SOUND" -> {
                        SettingsGroup(cardBg, cardBorder) {
                            SettingsNavigationRow(
                                title = "System Equalizer",
                                subtitle = "Open the device audio effects panel",
                                icon = Icons.Default.GraphicEq,
                                isDark = isDark,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary
                            ) {
                                val act = context as? android.app.Activity
                                if (act != null) {
                                    com.jk.mediaplayer.player.AudioSession.openSystemEqualizer(act) {
                                        AppToast.show("No system equalizer found. Configure audio effects in phone settings.", 2500L)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsGroup(cardBg, cardBorder) {
                            SettingsToggleRow(
                                title = "200% Volume Boost",
                                subtitle = "Software gain above full volume",
                                checked = musicVolumeBoostEnabled,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                isDark = isDark
                            ) {
                                musicVolumeBoostEnabled = it
                                prefsManager.setMusicVolumeBoostEnabled(it)
                            }
                        }
                    }
                    "LIBRARY" -> {
                        SettingsGroup(cardBg, cardBorder) {
                            SettingsToggleRow("Ignore audio under 30s", "Filter voice notes and notification sounds", filterShortAudio, textPrimary, textSecondary, isDark) {
                                filterShortAudio = it
                                prefsManager.setFilterShortAudio(it)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cardBorder)
                            SettingsNavigationRow("Blacklisted Folders", "${musicBlacklistedFolders.size} folder(s) blacklisted", Icons.Default.FolderOff, isDark, textPrimary, textSecondary) { activeBlacklistSubScreen = "FOLDERS" }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cardBorder)
                            SettingsNavigationRow("Blacklisted Files", "Manage songs hidden from library", Icons.Default.MusicOff, isDark, textPrimary, textSecondary) { activeBlacklistSubScreen = "FILES" }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cardBorder)
                            SettingsToggleRow("Network Stream in Header", "Show network streaming icon on header", networkIconEnabled, textPrimary, textSecondary, isDark) {
                                networkIconEnabled = it
                                prefsManager.setNetworkStreamIconEnabled(it)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
            if (showClearCacheConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearCacheConfirm = false },
                    title = { Text("Clear temporary storage?") },
                    text = { Text("This removes cached artwork, lyrics and thumbnails. Your music files and settings stay safe.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearCacheConfirm = false
                            try { context.cacheDir.deleteRecursively() } catch (_: Exception) {}
                            prefsManager.clearMetadataCache()
                            OnlineMetadataFetcher.clearMemoryCache()
                            AppToast.show("Temporary storage cleared")
                        }) { Text("Clear", color = MusicRed) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearCacheConfirm = false }) { Text("Cancel", color = textSecondary) }
                    }
                )
            }
        }
    }

    var lastBlacklistSubScreen by remember { mutableStateOf<String?>(null) }
    if (activeBlacklistSubScreen != null) {
        lastBlacklistSubScreen = activeBlacklistSubScreen
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = activeBlacklistSubScreen != null,
        enter = com.jk.mediaplayer.ui.common.PageTransitions.overlayDrillEnter(),
        exit = com.jk.mediaplayer.ui.common.PageTransitions.overlayDrillExit()
    ) {
        if (lastBlacklistSubScreen == "FOLDERS") {
        val folderCandidates = remember(tracks) {
            tracks.groupBy { it.folderPath.ifBlank { try { java.io.File(it.path).parent ?: "" } catch (_: Exception) { "" } } }
                .filter { it.key.isNotBlank() }
                .map { (fPath, fTracks) ->
                    val fName = fTracks.firstOrNull()?.folderName?.ifBlank { try { java.io.File(fPath).name } catch (_: Exception) { "Music" } } ?: "Music"
                    BlacklistCandidateItem(
                        id = fPath,
                        title = fName,
                        subtitle = "${fTracks.size} tracks \\u2022 $fPath",
                        path = fPath,
                        isFolder = true
                    )
                }.sortedBy { it.title.lowercase() }
        }
        BlacklistManagerScreen(
            title = "Blacklisted Folders",
            isFolderMode = true,
            isVideoMode = false,
            accentColor = MusicRed,
            blacklistedPaths = musicBlacklistedFolders,
            candidates = folderCandidates,
            onAddPaths = { paths ->
                prefsManager.addMusicBlacklistedFolders(paths)
                MediaScanner.cachedMusic = null
                onRescanLibrary()
                AppToast.show("Added ${paths.size} folder(s) to blacklist")
            },
            onRemovePath = { path ->
                prefsManager.removeMusicBlacklistedFolder(path)
                MediaScanner.cachedMusic = null
                onRescanLibrary()
                AppToast.show("Restored folder to library")
            },
            onClearAll = {
                prefsManager.clearMusicBlacklistedFolders()
                MediaScanner.cachedMusic = null
                onRescanLibrary()
                AppToast.show("Restored all folders to library")
            },
            onBack = { activeBlacklistSubScreen = null }
        )
        } else if (lastBlacklistSubScreen == "FILES") {
        val fileCandidates = remember(tracks) {
            tracks.map { track ->
                BlacklistCandidateItem(
                    id = track.path,
                    title = track.title,
                    subtitle = "${track.artist} \\u2022 ${track.path}",
                    path = track.path,
                    isFolder = false,
                    isVideo = false,
                    albumArtUri = track.albumArtUri
                )
            }.sortedBy { it.title.lowercase() }
        }
        BlacklistManagerScreen(
            title = "Blacklisted Files",
            isFolderMode = false,
            isVideoMode = false,
            accentColor = MusicRed,
            blacklistedPaths = musicBlacklistedFiles,
            candidates = fileCandidates,
            onAddPaths = { paths ->
                prefsManager.addMusicBlacklistedFiles(paths)
                MediaScanner.cachedMusic = null
                onRescanLibrary()
                AppToast.show("Added ${paths.size} song(s) to blacklist")
            },
            onRemovePath = { path ->
                prefsManager.removeMusicBlacklistedFile(path)
                MediaScanner.cachedMusic = null
                onRescanLibrary()
                AppToast.show("Restored song to library")
            },
            onClearAll = {
                prefsManager.clearMusicBlacklistedFiles()
                MediaScanner.cachedMusic = null
                onRescanLibrary()
                AppToast.show("Restored all songs to library")
            },
            onBack = { activeBlacklistSubScreen = null }
        )
        }
    }
}


@Composable
private fun SettingsGroup(
    background: Color,
    border: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = background,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    isDark: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
            Text(subtitle, fontSize = 13.sp, color = textSecondary)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MusicRed,
                uncheckedThumbColor = textSecondary,
                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
            )
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isDark: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = if (isDark) Color(0xFF222432) else Color(0xFFF0F2F6),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MusicRed, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
            Text(subtitle, fontSize = 13.sp, color = textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = textSecondary)
    }
}

@Composable
private fun SettingsQuickAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isDark: Boolean,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MusicRed.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MusicRed, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                Text(subtitle, fontSize = 13.sp, color = textSecondary)
            }
        }
    }
}
