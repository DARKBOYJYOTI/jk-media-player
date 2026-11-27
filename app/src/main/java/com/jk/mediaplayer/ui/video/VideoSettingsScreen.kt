package com.jk.mediaplayer.ui.video

import com.jk.mediaplayer.ui.common.AboutTosHelpView
import com.jk.mediaplayer.ui.common.AppToast

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.jk.mediaplayer.data.MediaScanner
import com.jk.mediaplayer.data.PreferencesManager
import com.jk.mediaplayer.model.VideoFolder
import com.jk.mediaplayer.ui.common.*
import com.jk.mediaplayer.ui.theme.VideoIndigo
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSettingsScreen(
    currentThemeMode: String,
    onThemeChange: (String) -> Unit,
    onRescanLibrary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    videoFolders: List<VideoFolder> = emptyList()
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    var volBoostEnabled by remember { mutableStateOf(prefsManager.isVolumeBoostEnabled()) }
    var lowBatteryWarningEnabled by remember { mutableStateOf(prefsManager.isLowBatteryWarningEnabled()) }
    var autoPipEnabled by remember { mutableStateOf(prefsManager.isAutoPipEnabled()) }
    var networkIconEnabled by remember { mutableStateOf(prefsManager.isNetworkStreamIconEnabled()) }
    val settingsVersion = PreferencesManager.settingsVersion
    var videoLauncherLabel by remember(settingsVersion) { mutableStateOf(prefsManager.getVideoLauncherLabel()) }
    var settingsCategory by remember { mutableStateOf<String?>(null) }
    var aboutTab by remember { mutableIntStateOf(0) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var useSystemFont by remember(settingsVersion) { mutableStateOf(prefsManager.isUseSystemFont()) }

    var activeBlacklistSubScreen by remember { mutableStateOf<String?>(null) }
    val blacklistVersion = PreferencesManager.blacklistVersion
    val videoBlacklistedFolders = remember(blacklistVersion) { prefsManager.getVideoBlacklistedFolders() }
    val videoBlacklistedFiles = remember(blacklistVersion) { prefsManager.getVideoBlacklistedFiles() }

    val videoQualityVersion = PreferencesManager.videoQualityVersion

    val videoHwDecoder = remember(videoQualityVersion) { prefsManager.getVideoHwDecoder() }
    val videoHighColor = remember(videoQualityVersion) { prefsManager.isVideoHighColorQuality() }
    val videoDeinterlace = remember(videoQualityVersion) { prefsManager.getVideoDeinterlaceMode() }
    val videoEnhancerMode = remember(videoQualityVersion) { prefsManager.getVideoEnhancerMode() }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF232532) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111216)
    val textSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)


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
            IconButton(onClick = { if (settingsCategory != null) settingsCategory = null else onBack() }) {
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
                                    text = if (settingsCategory == null) "Organized video settings" else "Back to settings",
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
            androidx.compose.animation.AnimatedContent(
                targetState = settingsCategory,
                transitionSpec = {
                    if (initialState == null && targetState != null) {
                        com.jk.mediaplayer.ui.common.PageTransitions.drillForwardTransform()
                    } else if (initialState != null && targetState == null) {
                        com.jk.mediaplayer.ui.common.PageTransitions.drillBackwardTransform()
                    } else {
                        com.jk.mediaplayer.ui.common.PageTransitions.drillForwardTransform()
                    }
                },
                label = "videoSettingsCategoryTransition"
            ) { activeCategory ->
            if (activeCategory == "ABOUT") {
                AboutTosHelpView(
                    accentColor = VideoIndigo,
                    innerPadding = innerPadding,
                    initialTab = aboutTab
                )
            } else {
                val pageScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                        .verticalScroll(pageScrollState)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                        if (activeCategory == null) {
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
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        "JK Media Player",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        "Version 1.0.0",
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Made with", fontSize = 13.sp, color = textSecondary)
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = VideoIndigo,
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
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "PREFERENCES",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = VideoIndigo,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                            )
                            val preferenceRows = listOf(
                                Triple("APPEARANCE", "Theme, launcher name, font", "APPEARANCE"),
                                Triple("VIDEO QUALITY", "Picture profile, decoder, deinterlace", "DISPLAY"),
                                Triple("SOUND", "System equalizer, 200% volume boost", "SOUND"),
                                Triple("PLAYBACK", "Battery warning, picture in picture", "PLAYBACK"),
                                Triple("LIBRARY", "Blacklists, network stream", "LIBRARY"),
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
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                                            Text(subtitle, fontSize = 13.sp, color = textSecondary)
                                        }
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = textSecondary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(9.dp))
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "QUICK ACTIONS",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = VideoIndigo,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                            )
                            VideoSettingsQuickAction(
                                title = "Rescan Library",
                                subtitle = "Scan device storage for new videos",
                                icon = Icons.Default.Refresh,
                                cardBg = cardBg,
                                cardBorder = cardBorder,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary
                            ) {
                                onRescanLibrary()
                                AppToast.show("Video library rescanned")
                            }
                            Spacer(modifier = Modifier.height(9.dp))
                            VideoSettingsQuickAction(
                                title = "Clear Temporary Storage",
                                subtitle = "Remove cached thumbnails and previews",
                                icon = Icons.Default.DeleteOutline,
                                cardBg = cardBg,
                                cardBorder = cardBorder,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary
                            ) {
                                showClearCacheConfirm = true
                            }
                        }

                        if (activeCategory == "APPEARANCE") {            // SECTION 1: APPEARANCE
            Text(
                text = "APPEARANCE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VideoIndigo,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDark) Color(0xFF222432) else Color(0xFFF0F2F6),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DarkMode,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF90CAF9) else Color(0xFF4B5563),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Theme Mode",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = when (currentThemeMode) {
                                    "DARK" -> "Dark Mode (AMOLED)"
                                    "LIGHT" -> "White Mode (Light)"
                                    else -> "Following system setting"
                                },
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3-Option Segmented Pill Selector (Auto / Light / Dark)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val options = listOf(
                            Triple("SYSTEM", "Auto", Icons.Default.SettingsSuggest),
                            Triple("LIGHT", "Light", Icons.Default.LightMode),
                            Triple("DARK", "Dark", Icons.Default.DarkMode)
                        )
                        options.forEach { (mode, label, icon) ->
                            val isSelected = currentThemeMode == mode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) VideoIndigo else Color.Transparent,
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onThemeChange(mode)
                                        prefsManager.saveVideoThemeMode(mode)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.White else textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else textSecondary
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = cardBorder
                    )

                    // Launcher App Name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Label,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Launcher App Name",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Home screen icon label: $videoLauncherLabel",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2-Option Segmented Pill Selector (JK Videos / Videos)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val videoPresets = listOf("JK Videos", "Videos")
                        videoPresets.forEach { preset ->
                            val isSelected = videoLauncherLabel == preset
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) VideoIndigo else Color.Transparent,
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (videoLauncherLabel != preset) {
                                            videoLauncherLabel = preset
                                            prefsManager.setVideoLauncherLabel(preset)
                                            com.jk.mediaplayer.util.LauncherIconHelper.updateVideoLauncherLabel(context, preset)
                                            AppToast.show("Launcher name updated to $preset")
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = preset,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else textSecondary
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = cardBorder
                    )

                    // Use System Font
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDark) Color(0xFF222432) else Color(0xFFF0F2F6),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.FontDownload,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use System Font",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = if (useSystemFont) "Using device default font" else "Using Plus Jakarta Sans",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Switch(
                            checked = useSystemFont,
                            onCheckedChange = {
                                useSystemFont = it
                                prefsManager.setUseSystemFont(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VideoIndigo,
                                uncheckedThumbColor = textSecondary,
                                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

                        }


                        if (activeCategory == "DISPLAY") {
// SECTION: VIDEO QUALITY & DISPLAY ENHANCER
            Text(
                text = "VIDEO QUALITY & DISPLAY ENHANCER",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VideoIndigo,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Display Quality Profile
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.HighQuality,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Display Picture Profile",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = when (videoEnhancerMode) {
                                    "VIVID" -> "Vivid (AMOLED Pop - Vibrant colors & contrast)"
                                    "CINEMA" -> "Cinema (Deep blacks & film warmth)"
                                    else -> "Natural (Standard accurate colors)"
                                },
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3-Option Segmented Pill Selector (Vivid / Cinema / Natural)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val profiles = listOf(
                            Pair("VIVID", "Vivid"),
                            Pair("CINEMA", "Cinema"),
                            Pair("STANDARD", "Natural")
                        )
                        profiles.forEach { (mode, label) ->
                            val isSelected = videoEnhancerMode == mode
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (isSelected) VideoIndigo else Color.Transparent,
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        if (videoEnhancerMode != mode) {
                                            val msg = prefsManager.applyVideoProfileChange(mode, label)
                                            AppToast.show(msg)
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else textSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Hardware Decoding Engine
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Hardware Decoding Engine",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = when (videoHwDecoder) {
                                    "FORCE_HW" -> "Direct MediaCodec HW (Low battery drain)"
                                    "SOFTWARE" -> "Software Decoding (Fallback mode)"
                                    else -> "Automatic (MediaCodec HW + SW fallback)"
                                },
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3-Option Segmented Pill Selector (Auto / Force HW / Software)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val hwModes = listOf(
                            Pair("AUTO", "Auto"),
                            Pair("FORCE_HW", "Force HW"),
                            Pair("SOFTWARE", "Software")
                        )
                        hwModes.forEach { (mode, label) ->
                            val isSelected = videoHwDecoder == mode
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (isSelected) VideoIndigo else Color.Transparent,
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        if (videoHwDecoder != mode) {
                                            val msg = prefsManager.applyVideoHwDecoderChange(mode, label)
                                            AppToast.show(msg)
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else textSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // 32-Bit High Fidelity True Color (RV32) Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ColorLens,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "32-Bit True Color (RV32)",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Prevents gradient banding in dark scenes & AMOLEDs",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Switch(
                            checked = videoHighColor,
                            onCheckedChange = { checked ->
                                val msg = prefsManager.applyVideoHighColorChange(checked)
                                AppToast.show(msg)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VideoIndigo,
                                uncheckedThumbColor = textSecondary,
                                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Deinterlacing Filter
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Deinterlacing Filter",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Removes comb artifacts on broadcast & vintage videos",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4-Option Segmented Pill Selector (Auto / Yadif / Blend / Off)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF0F1015) else Color(0xFFF3F4F6))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val deintOptions = listOf(
                            Pair("AUTO", "Auto"),
                            Pair("YADIF", "Yadif"),
                            Pair("BLEND", "Blend"),
                            Pair("OFF", "Off")
                        )
                        deintOptions.forEach { (mode, label) ->
                            val isSelected = videoDeinterlace == mode
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (isSelected) VideoIndigo else Color.Transparent,
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        if (videoDeinterlace != mode) {
                                            val msg = prefsManager.applyVideoDeinterlaceChange(mode, label)
                                            AppToast.show(msg)
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else textSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Reset Video to Default Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (prefsManager.resetVideoQualityToDefaults()) {
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
                            tint = textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Reset Video to Default",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

                        }

                        if (activeCategory == "SOUND") {
                            Text(
                                text = "SOUND",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = VideoIndigo,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = cardBg,
                                border = BorderStroke(1.dp, cardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // System Equalizer Row
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                val act = context as? android.app.Activity
                                                if (act != null) {
                                                    com.jk.mediaplayer.player.AudioSession.openSystemEqualizer(act) {
                                                        AppToast.show("No system equalizer found. Configure audio effects in phone settings.", 2500L)
                                                    }
                                                }
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = VideoIndigo.copy(alpha = 0.15f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.GraphicEq,
                                                    contentDescription = null,
                                                    tint = VideoIndigo,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "System Equalizer",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = textPrimary
                                            )
                                            Text(
                                                text = "Open the device audio effects panel",
                                                fontSize = 13.sp,
                                                color = textSecondary
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = textSecondary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = cardBorder)
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 200% Volume Boost Switch
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = VideoIndigo.copy(alpha = 0.15f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                    contentDescription = null,
                                                    tint = VideoIndigo,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "200% Volume Boost",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = textPrimary
                                            )
                                            Text(
                                                text = "Software gain above full volume",
                                                fontSize = 13.sp,
                                                color = textSecondary
                                            )
                                        }
                                        Switch(
                                            checked = volBoostEnabled,
                                            onCheckedChange = { checked ->
                                                volBoostEnabled = checked
                                                prefsManager.saveVolumeBoostEnabled(checked)
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = VideoIndigo,
                                                uncheckedThumbColor = textSecondary,
                                                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (activeCategory == "PLAYBACK") {
            // SECTION 2: PLAYBACK & CONTROLS
            Text(
                text = "PLAYBACK & CONTROLS",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VideoIndigo,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Low Battery Warning Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.BatteryAlert,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Low Battery Warning",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Alert with sound & dimming at 25%, 15% and 5% battery",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Switch(
                            checked = lowBatteryWarningEnabled,
                            onCheckedChange = { checked ->
                                lowBatteryWarningEnabled = checked
                                prefsManager.setLowBatteryWarningEnabled(checked)
                                AppToast.show(if (checked) "Low battery warning enabled" else "Low battery warning disabled")
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VideoIndigo,
                                uncheckedThumbColor = textSecondary,
                                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto Picture-in-Picture Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Picture-in-Picture",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Enter PiP when minimizing",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Switch(
                            checked = autoPipEnabled,
                            onCheckedChange = { checked ->
                                autoPipEnabled = checked
                                prefsManager.setAutoPipEnabled(checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VideoIndigo,
                                uncheckedThumbColor = textSecondary,
                                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

                        }

                        if (activeCategory == "LIBRARY") {
            // SECTION 3: LIBRARY
            Text(
                text = "LIBRARY",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VideoIndigo,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {


                    // Blacklisted Folders row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                activeBlacklistSubScreen = "FOLDERS"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.FolderOff,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blacklisted Folders",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = if (videoBlacklistedFolders.isEmpty()) "Manage folders hidden from library" else "${videoBlacklistedFolders.size} folder(s) blacklisted",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Blacklisted Files row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                activeBlacklistSubScreen = "FILES"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.VideocamOff,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blacklisted Files",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = if (videoBlacklistedFiles.isEmpty()) "Manage videos hidden from library" else "${videoBlacklistedFiles.size} video(s) blacklisted",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Network Stream in Header Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Network Stream in Header",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Show network streaming icon on header",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }
                        Switch(
                            checked = networkIconEnabled,
                            onCheckedChange = { checked ->
                                networkIconEnabled = checked
                                prefsManager.setNetworkStreamIconEnabled(checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VideoIndigo,
                                uncheckedThumbColor = textSecondary,
                                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
                            )
                        )
                    }
                }
            }


                        }
            if (showClearCacheConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearCacheConfirm = false },
                    title = { Text("Clear temporary storage?") },
                    text = { Text("This removes cached thumbnails and previews. Your videos and settings stay safe.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearCacheConfirm = false
                            try { context.cacheDir.deleteRecursively() } catch (_: Exception) {}
                            AppToast.show("Temporary storage cleared")
                        }) { Text("Clear", color = VideoIndigo) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearCacheConfirm = false }) { Text("Cancel", color = textSecondary) }
                    }
                )
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
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
        val folderCandidates = remember(videoFolders) {
            videoFolders.map { folder ->
                BlacklistCandidateItem(
                    id = folder.path,
                    title = folder.name,
                    subtitle = "${folder.videoCount} videos \u2022 ${folder.path}",
                    path = folder.path,
                    isFolder = true
                )
            }.sortedBy { it.title.lowercase() }
        }
        BlacklistManagerScreen(
            title = "Blacklisted Folders",
            isFolderMode = true,
            isVideoMode = true,
            accentColor = VideoIndigo,
            blacklistedPaths = videoBlacklistedFolders,
            candidates = folderCandidates,
            onAddPaths = { paths ->
                prefsManager.addVideoBlacklistedFolders(paths)
                MediaScanner.cachedVideoFolders = null
                onRescanLibrary()
                AppToast.show("Added ${paths.size} folder(s) to blacklist")
            },
            onRemovePath = { path ->
                prefsManager.removeVideoBlacklistedFolder(path)
                MediaScanner.cachedVideoFolders = null
                onRescanLibrary()
                AppToast.show("Restored folder to library")
            },
            onClearAll = {
                prefsManager.clearVideoBlacklistedFolders()
                MediaScanner.cachedVideoFolders = null
                onRescanLibrary()
                AppToast.show("Restored all folders to library")
            },
            onBack = { activeBlacklistSubScreen = null }
        )
        } else if (lastBlacklistSubScreen == "FILES") {
        val allVideos = remember(videoFolders) { videoFolders.flatMap { it.videos } }
        val fileCandidates = remember(allVideos) {
            allVideos.map { video ->
                BlacklistCandidateItem(
                    id = video.path,
                    title = video.title,
                    subtitle = "${video.sizeFormatted} \u2022 ${video.durationFormatted} \u2022 ${video.path}",
                    path = video.path,
                    isFolder = false,
                    isVideo = true
                )
            }.sortedBy { it.title.lowercase() }
        }
        BlacklistManagerScreen(
            title = "Blacklisted Files",
            isFolderMode = false,
            isVideoMode = true,
            accentColor = VideoIndigo,
            blacklistedPaths = videoBlacklistedFiles,
            candidates = fileCandidates,
            onAddPaths = { paths ->
                prefsManager.addVideoBlacklistedFiles(paths)
                MediaScanner.cachedVideoFolders = null
                onRescanLibrary()
                AppToast.show("Added ${paths.size} video(s) to blacklist")
            },
            onRemovePath = { path ->
                prefsManager.removeVideoBlacklistedFile(path)
                MediaScanner.cachedVideoFolders = null
                onRescanLibrary()
                AppToast.show("Restored video to library")
            },
            onClearAll = {
                prefsManager.clearVideoBlacklistedFiles()
                MediaScanner.cachedVideoFolders = null
                onRescanLibrary()
                AppToast.show("Restored all videos to library")
            },
            onBack = { activeBlacklistSubScreen = null }
        )
        }
    }
}
}


@Composable
private fun VideoSettingsQuickAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = VideoIndigo.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = VideoIndigo, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                Text(subtitle, fontSize = 13.sp, color = textSecondary)
            }
        }
    }
}

@Composable
private fun VideoSettingsToggleRow(
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
                checkedTrackColor = VideoIndigo,
                uncheckedThumbColor = textSecondary,
                uncheckedTrackColor = if (isDark) Color(0xFF2A2D3A) else Color(0xFFE2E4EB)
            )
        )
    }
}
