package com.jk.mediaplayer.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jk.mediaplayer.R
import com.jk.mediaplayer.ui.theme.LightTextPrimary
import com.jk.mediaplayer.ui.theme.LightTextSecondary
import kotlinx.coroutines.launch

/**
 * Modern, professional, and comprehensive About, Terms of Service (TOS), and Help screen.
 * Supports horizontal left/right swipe navigation via HorizontalPager and full vertical scrolling on each page.
 */
@Composable
fun AboutTosHelpView(
    accentColor: Color,
    innerPadding: PaddingValues,
    initialTab: Int = 0
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textPrimary = if (isDark) Color.White else LightTextPrimary
    val textSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val cardBg = if (isDark) Color(0xFF14161F) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color(0xFF262838) else Color(0xFFE2E4EC)
    val surfaceVariant = if (isDark) Color(0xFF1B1D2A) else Color(0xFFF2F4F8)

    val tabs = listOf("About", "TOS", "Help")
    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, tabs.size - 1),
        pageCount = { tabs.size }
    )
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        // Top TabRow synced with HorizontalPager
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = accentColor,
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = accentColor,
                        height = 3.dp
                    )
                }
            },
            divider = {
                HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
            }
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = pagerState.currentPage == index
                Tab(
                    selected = selected,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) accentColor else textSecondary
                        )
                    }
                )
            }
        }

        // HorizontalPager: Left & Right Swipe Navigation
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> AboutPageContent(
                    accentColor = accentColor,
                    isDark = isDark,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    cardBg = cardBg,
                    cardBorder = cardBorder,
                    surfaceVariant = surfaceVariant
                )
                1 -> TosPageContent(
                    accentColor = accentColor,
                    isDark = isDark,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    cardBg = cardBg,
                    cardBorder = cardBorder,
                    surfaceVariant = surfaceVariant
                )
                2 -> HelpPageContent(
                    accentColor = accentColor,
                    isDark = isDark,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    cardBg = cardBg,
                    cardBorder = cardBorder,
                    surfaceVariant = surfaceVariant
                )
            }
        }
    }
}

/* =========================================================================================
 * 1. ABOUT PAGE CONTENT
 * ========================================================================================= */
@Composable
private fun AboutPageContent(
    accentColor: Color,
    isDark: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    cardBorder: Color,
    surfaceVariant: Color
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Header Branding Card
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = accentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.35f)),
                    modifier = Modifier.size(76.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_media_round),
                            contentDescription = "JK Media Player",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "JK Media Player",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.15f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "v${com.jk.mediaplayer.BuildConfig.VERSION_NAME}",
                            color = accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "Release Build • API 24–35",
                        color = textSecondary,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "A high-performance multimedia player built from the ground up with Jetpack Compose Material 3 and the native LibVLC NDK core. Engineered for bit-perfect audio reproduction, universal video decoding, zero-latency playback, and complete user privacy.",
                    fontSize = 13.sp,
                    color = textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Core Architectural Highlights Section
        Text(
            text = "Core Architecture & Engineering",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )

        FeatureHighlightCard(
            icon = Icons.Default.Code,
            iconTint = accentColor,
            title = "Universal LibVLC NDK Engine",
            description = "Direct hardware and software decoding for all multimedia formats: MKV, MP4, AVI, FLV, WMV, MOV, TS, WebM, FLAC, AC3, DTS, TrueHD, ALAC, DSD, and ISO streams.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        Spacer(Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.Headphones,
            iconTint = Color(0xFF00B0FF),
            title = "Direct Android AudioTrack Mixer",
            description = "Raw 24-bit/32-bit direct audio pipeline bypassing redundant software distortion, ensuring bit-perfect output with zero phase shift and synchronized Bluetooth audio.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        Spacer(Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            iconTint = Color(0xFFFF5252),
            title = "200% Hybrid Volume Boost",
            description = "Seamless two-stage volume architecture: 0%–100% native Android system media volume, smoothly transitioning to 101%–200% LibVLC software preamp boost.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        Spacer(Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.PlayArrow,
            iconTint = Color(0xFF69F0AE),
            title = "Dual-Launcher Task Separation",
            description = "Two dedicated, independent launcher activities (Music Launcher & Video Launcher) sharing one APK and state, separated by distinct taskAffinity in Android recents.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        Spacer(Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.Security,
            iconTint = Color(0xFFFFD700),
            title = "100% Offline-First Privacy",
            description = "Zero analytics, zero tracking cookies, zero ads, and zero telemetry. All media indexing, ID3 editing, and keyframe caching happen exclusively on your device hardware.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        Spacer(Modifier.height(18.dp))

        // Open Source & Licensing
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Gavel,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Open-Source & Licensing",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "JK Media Player is distributed under the GNU General Public License v3.0 (GPL-3.0). LibVLC is licensed under LGPL-2.1+.\n\nBuilt with Kotlin 2.0.21, Jetpack Compose Material 3, LibVLC Android NDK 3.6.5, Coil 2.7.0, and AndroidX Media.\n\nTypeface: Inter by Rasmus Andersson, licensed under the SIL Open Font License 1.1. Full licence text ships with the source in licenses/Inter-OFL.txt.\n\nCopyright © 2026 JK Media Player Project. All rights reserved.",
                    fontSize = 13.sp,
                    color = textSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/* =========================================================================================
 * 2. TOS & PRIVACY POLICY PAGE CONTENT (Advanced Lawyer Grade)
 * ========================================================================================= */
@Composable
private fun TosPageContent(
    accentColor: Color,
    isDark: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    cardBorder: Color,
    surfaceVariant: Color
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Document Header
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Terms of Service & Privacy Policy",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Last Revised: November 2026 • Effective Version ${com.jk.mediaplayer.BuildConfig.VERSION_NAME}",
                    fontSize = 13.sp,
                    color = accentColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Please read these Terms of Service (\"Terms\") and Privacy Policy thoroughly. By downloading, accessing, installing, or using JK Media Player (\"the Application\"), you acknowledge and unconditionally agree to be bound by all provisions set forth herein.",
                    fontSize = 13.sp,
                    color = textSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Section 1: Open-Source License
        LegalSectionCard(
            number = "1",
            title = "Open-Source License & Grant of Rights",
            content = "JK Media Player is free and open-source software distributed under the terms of the GNU General Public License version 3.0 (GPL-3.0). You are granted a worldwide, royalty-free, non-exclusive license to use, inspect, run, modify, and redistribute the Application's source code, provided all derivative works remain subject to the identical GPL-3.0 licensing requirements. Media playback engines utilizing LibVLC remain compliant with LGPL-2.1+.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        // Section 2: Zero Telemetry Privacy Policy
        LegalSectionCard(
            number = "2",
            title = "Zero-Telemetry & Privacy Guarantee",
            content = "Privacy is a fundamental principle of this Application:\n" +
                    "• NO DATA COLLECTION: The Application does NOT harvest, log, monitor, track, store, or transmit any personally identifiable information (PII), device identifiers, advertising IDs, IP addresses, browsing patterns, or media playback records.\n" +
                    "• LOCAL PROCESSING: All media scanner algorithms, ID3 tag extraction, audio waveform processing, and thumbnail caching are executed 100% on-device hardware.\n" +
                    "• NO THIRD-PARTY ANALYTICS: The Application contains zero analytical SDKs, zero advertising trackers, and zero crash-reporting telemetry.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        // Section 3: Media Ownership
        LegalSectionCard(
            number = "3",
            title = "Media Ownership & User Responsibility",
            content = "The Application operates strictly as a local media player and stream viewer. The developers and contributors do NOT host, license, index, upload, or distribute any media content. You expressly acknowledge and warrant that you possess the lawful right, authorization, and license to play, convert, trim, or view any audio, video, or stream content processed by the Application. The authors disclaim all liability regarding copyright infringement arising from user content.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        // Section 4: Public APIs & Streaming
        LegalSectionCard(
            number = "4",
            title = "Online Metadata & Network Streams",
            content = "When enabled by the user, optional online features query publicly available endpoints (e.g., LRCLIB for synchronized lyrics, iTunes Search for album artwork). These connections are transmitted via encrypted HTTPS and consist solely of song title and artist queries. No device fingerprinting occurs.\n" +
                    "When user enters a custom network stream (HTTP, HLS, RTSP, RTMP), network connections are established directly between your device and the target host without intermediary proxying.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        // Section 5: High Volume Preamp Warning
        LegalSectionCard(
            number = "5",
            title = "High Volume Pre-Amplification & Health Warning",
            content = "HEARING HEALTH & HARDWARE DISCLAIMER: The Application includes software pre-amplification technology capable of boosting audio output up to 200%. Listening to audio at excessive volume levels for extended periods may cause irreversible hearing impairment, tinnitus, or damage device loudspeakers and connected audio hardware. You expressly assume all risks and responsibilities associated with utilizing the volume boost functionality.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        // Section 6: Disclaimer of Warranties
        LegalSectionCard(
            number = "6",
            title = "Disclaimer of Warranties & Limitation of Liability",
            content = "THE APPLICATION IS PROVIDED ON AN \"AS IS\" AND \"AS AVAILABLE\" BASIS WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE, AND NON-INFRINGEMENT. IN NO EVENT SHALL THE DEVELOPERS, COPYRIGHT HOLDERS, OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(28.dp))
    }
}

/* =========================================================================================
 * 3. HELP & FAQ PAGE CONTENT
 * ========================================================================================= */
@Composable
private fun HelpPageContent(
    accentColor: Color,
    isDark: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    cardBorder: Color,
    surfaceVariant: Color
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Help Banner
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "User Guide & Gestures",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Master your player with simple intuitive gestures, discover hidden features, and resolve common questions.",
                    fontSize = 13.sp,
                    color = textSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Section: Fullscreen Video Player Gestures
        Text(
            text = "Video Player Gestures",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )

        GestureHelpCard(
            title = "Brightness Control (Left Half)",
            subtitle = "Swipe vertically on the left half of the screen to adjust display brightness from 0% to 100%. The HUD percentage pill appears on the right edge so your finger never blocks the view.",
            icon = Icons.Default.BrightnessMedium,
            badge = "Left Edge",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        GestureHelpCard(
            title = "Volume & 200% LibVLC Boost (Right Half)",
            subtitle = "Swipe vertically on the right half to adjust media volume (0%–100%). Continuing upward engages LibVLC's software pre-amplification boost up to 200% with a glowing red indicator.",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            badge = "Right Edge",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = Color(0xFFFF5252)
        )

        Spacer(Modifier.height(10.dp))

        GestureHelpCard(
            title = "Double-Tap 10s Seeking",
            subtitle = "Double-tap the left third of the screen to jump backward 10 seconds; double-tap the right third to fast-forward 10 seconds. Multiple taps stack sequentially (±20s, ±30s).",
            icon = Icons.Default.FastForward,
            badge = "Double Tap",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = Color(0xFF00B0FF)
        )

        Spacer(Modifier.height(10.dp))

        GestureHelpCard(
            title = "Aspect Ratio Toggle (Fit / Stretch / Zoom)",
            subtitle = "Tap the aspect ratio button in the video controls to cycle through Fit (native letterboxed), Stretch (fill container), and Zoom (crop to fill full screen without distortion).",
            icon = Icons.Default.AspectRatio,
            badge = "1-Tap",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = Color(0xFF69F0AE)
        )

        Spacer(Modifier.height(10.dp))

        GestureHelpCard(
            title = "Headphone Background Audio Mode",
            subtitle = "Tap the headphone icon in the video player top bar to switch into background audio playback. Video decodes audio seamlessly in the background with lockscreen controls.",
            icon = Icons.Default.Headphones,
            badge = "Top Bar",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = Color(0xFFFFD700)
        )

        Spacer(Modifier.height(18.dp))

        // Section: Music Player Gestures
        Text(
            text = "Music Player Features",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )

        GestureHelpCard(
            title = "Now Playing & Floating MiniPlayer",
            subtitle = "Tap the floating pill MiniPlayer to expand into fullscreen Now Playing. Drag down smoothly anywhere on the sheet to collapse back to the MiniPlayer.",
            icon = Icons.Default.MusicNote,
            badge = "Swipe Down",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        GestureHelpCard(
            title = "Up Next Queue Management",
            subtitle = "Swipe UP on the Now Playing sheet to view upcoming queue tracks. Tap the ≡ handle or long-press any track to move up/down or remove from queue.",
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            badge = "Swipe Up",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = Color(0xFF00B0FF)
        )

        Spacer(Modifier.height(10.dp))

        GestureHelpCard(
            title = "Synchronized Karaoke Lyrics",
            subtitle = "Tap the [Lyrics] pill on the Now Playing screen. Tap any lyric line in the synchronized feed to immediately seek playback to that exact vocal timestamp.",
            icon = Icons.Default.Mic,
            badge = "Interactive",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = Color(0xFFFF5270)
        )

        Spacer(Modifier.height(18.dp))

        // Section: FAQ
        Text(
            text = "Frequently Asked Questions",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )

        FaqAccordionCard(
            question = "Why are my newly downloaded songs or videos not showing?",
            answer = "Pull down on the library screen to trigger a fast scan, or open Settings -> Library -> Scan Media to refresh Android MediaStore indexes.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        FaqAccordionCard(
            question = "How does the 200% Volume Boost work?",
            answer = "Swiping up on the right side of the video player reaches 100% Android system volume. Continuing to swipe up activates LibVLC's software pre-amplifier up to 200%. The HUD pill glows red to indicate boost mode.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        FaqAccordionCard(
            question = "How do I hide private or WhatsApp folders?",
            answer = "Go to Settings -> Library -> Blacklist Folders, select the folders you want to hide, and tap Save. Blacklisted directories are strictly omitted from scans and searches.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(10.dp))

        FaqAccordionCard(
            question = "Can I stream IPTV links or direct network streams?",
            answer = "Yes! Tap the Network Stream button in the top bar or in Settings to play direct HTTP, HLS (.m3u8), RTSP, or RTMP video/audio streams with full LibVLC hardware acceleration.",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        )

        Spacer(Modifier.height(28.dp))
    }
}

/* =========================================================================================
 * HELPER CARDS & ACCORDIONS
 * ========================================================================================= */
@Composable
private fun FeatureHighlightCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = iconTint.copy(alpha = 0.12f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = textSecondary,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun LegalSectionCard(
    number: String,
    title: String,
    content: String,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = number,
                            color = accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = content,
                fontSize = 13.sp,
                color = textSecondary,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun GestureHelpCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.12f),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = badge,
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = textSecondary,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun FaqAccordionCard(
    question: String,
    answer: String,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(1.dp, if (expanded) accentColor.copy(alpha = 0.4f) else cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = question,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = if (expanded) accentColor else textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = answer,
                        fontSize = 13.sp,
                        color = textSecondary,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
