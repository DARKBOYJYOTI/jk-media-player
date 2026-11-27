package com.jk.mediaplayer.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jk.mediaplayer.ui.theme.MusicRed
import com.jk.mediaplayer.ui.theme.VideoIndigo
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

enum class OnboardingMode {
    MUSIC,
    VIDEO
}

private data class OnboardingSlide(
    val badge: String,
    val title: String,
    val subtitle: String,
    val primaryIcon: ImageVector,
    val secondaryIcon: ImageVector,
    val highlights: List<String>
)

/**
 * Full-screen first-launch onboarding slider tailored to either the Music or Video app.
 * Uses a flat, shadow-free surface design with each app's fixed brand accent color.
 */
@Composable
fun AppOnboardingSlider(
    mode: OnboardingMode,
    isDark: Boolean,
    onFinish: () -> Unit
) {
    val brandAccent = when (mode) {
        OnboardingMode.MUSIC -> MusicRed
        OnboardingMode.VIDEO -> VideoIndigo
    }

    val slides = remember(mode) {
        when (mode) {
            OnboardingMode.MUSIC -> listOf(
                OnboardingSlide(
                    badge = "JK MUSIC",
                    title = "Studio-Grade\nAudio Playback",
                    subtitle = "Play lossless FLAC, Opus, MP3, and WAV tracks with smooth fades and system DSP equalizer integration.",
                    primaryIcon = Icons.Default.Headphones,
                    secondaryIcon = Icons.Default.GraphicEq,
                    highlights = listOf("Hi-Res Audio", "Smooth Fades", "DSP EQ")
                ),
                OnboardingSlide(
                    badge = "SMART LIBRARY",
                    title = "Instant Search &\nDynamic Themes",
                    subtitle = "Browse by songs, albums, artists, and folders with adaptive album-art colors and zero-lag search.",
                    primaryIcon = Icons.Default.LibraryMusic,
                    secondaryIcon = Icons.Default.Album,
                    highlights = listOf("Folder View", "Adaptive Art", "Quick Picks")
                ),
                OnboardingSlide(
                    badge = "LIBRARY CONTROL",
                    title = "Blacklist Folders\n& Audio Files",
                    subtitle = "Hide unwanted folders or individual songs from your library and search with one tap, and restore them anytime in Settings.",
                    primaryIcon = Icons.Default.FolderOff,
                    secondaryIcon = Icons.Default.MusicOff,
                    highlights = listOf("Hide Folders", "Hide Songs", "Easy Restore")
                ),
                OnboardingSlide(
                    badge = "BUILT-IN TOOLS",
                    title = "Tag Editor &\nRingtone Cutter",
                    subtitle = "Edit track metadata, update cover art, view lyrics, and trim any song into a custom ringtone in seconds.",
                    primaryIcon = Icons.Default.ContentCut,
                    secondaryIcon = Icons.Default.Tune,
                    highlights = listOf("ID3 Tags", "Lyrics View", "Ringtone Maker")
                )
            )
            OnboardingMode.VIDEO -> listOf(
                OnboardingSlide(
                    badge = "JK VIDEO",
                    title = "Universal\nVideo Engine",
                    subtitle = "Play MKV, MP4, HEVC, and network streams smoothly with hardware decoding and multi-track subtitles.",
                    primaryIcon = Icons.Default.PlayCircleFilled,
                    secondaryIcon = Icons.Default.HighQuality,
                    highlights = listOf("HW Decode", "Multi-Audio", "Subtitles")
                ),
                OnboardingSlide(
                    badge = "THEATER CONTROLS",
                    title = "Precision Gestures\n& Picture Modes",
                    subtitle = "Swipe smoothly to adjust brightness, volume, and seek position with custom aspect ratios and color profiles.",
                    primaryIcon = Icons.Default.TouchApp,
                    secondaryIcon = Icons.Default.Subtitles,
                    highlights = listOf("Swipe Controls", "Custom Aspect", "Color Modes")
                ),
                OnboardingSlide(
                    badge = "LIBRARY CONTROL",
                    title = "Blacklist Folders\n& Video Files",
                    subtitle = "Hide private folders or individual videos from your library and search with one tap, and restore them anytime in Settings.",
                    primaryIcon = Icons.Default.FolderOff,
                    secondaryIcon = Icons.Default.Block,
                    highlights = listOf("Hide Folders", "Hide Videos", "Easy Restore")
                ),
                OnboardingSlide(
                    badge = "SEAMLESS PLAY",
                    title = "Folders & Background\nAudio Support",
                    subtitle = "Keep videos organized by folder, resume right where you left off, or switch to background audio anytime.",
                    primaryIcon = Icons.Default.Folder,
                    secondaryIcon = Icons.Default.Equalizer,
                    highlights = listOf("Instant Resume", "Background Play", "Folder View")
                )
            )
        }
    }

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val currentSlide = slides[pagerState.currentPage]
    val isLastPage = pagerState.currentPage == slides.lastIndex

    val bgColor = if (isDark) Color(0xFF090A0F) else Color(0xFFF8F9FC)
    val textPrimary = if (isDark) Color.White else Color(0xFF14161F)
    val textSecondary = if (isDark) Color(0xFF9EA4B8) else Color(0xFF5A6072)
    val cardSurface = if (isDark) Color(0xFF141620) else Color.White
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE4E7EF)

    val ringTransition = rememberInfiniteTransition(label = "ringPulse")
    val outerRingScale by ringTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outerRingScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Top bar with clean brand/section badge and optional Skip action.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = brandAccent.copy(alpha = if (isDark) 0.14f else 0.10f),
                    border = BorderStroke(1.dp, brandAccent.copy(alpha = 0.28f)),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(brandAccent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentSlide.badge,
                            color = brandAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !isLastPage,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, cardBorder),
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onFinish)
                    ) {
                        Text(
                            text = "Skip",
                            color = textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Main swipeable slide deck.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val slide = slides[page]
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
                    .coerceIn(0f, 1f)
                val contentScale = 1f - (pageOffset * 0.08f)
                val contentAlpha = 1f - (pageOffset * 0.35f)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = contentScale
                            scaleY = contentScale
                            alpha = contentAlpha
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Clean, flat geometric emblem with concentric hairline rings and zero shadows.
                    Box(
                        modifier = Modifier.size(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer hairline ring.
                        Box(
                            modifier = Modifier
                                .size(204.dp)
                                .graphicsLayer {
                                    scaleX = outerRingScale
                                    scaleY = outerRingScale
                                }
                                .clip(CircleShape)
                                .border(
                                    width = 1.dp,
                                    color = brandAccent.copy(alpha = if (isDark) 0.18f else 0.16f),
                                    shape = CircleShape
                                )
                        )

                        // Inner tinted flat circle.
                        Box(
                            modifier = Modifier
                                .size(164.dp)
                                .clip(CircleShape)
                                .background(brandAccent.copy(alpha = if (isDark) 0.10f else 0.08f))
                                .border(
                                    width = 1.dp,
                                    color = brandAccent.copy(alpha = if (isDark) 0.24f else 0.20f),
                                    shape = CircleShape
                                )
                        )

                        // Center flat icon squircle.
                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = cardSurface,
                            border = BorderStroke(1.5.dp, brandAccent.copy(alpha = if (isDark) 0.42f else 0.32f)),
                            shadowElevation = 0.dp,
                            modifier = Modifier.size(124.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = slide.primaryIcon,
                                    contentDescription = null,
                                    tint = brandAccent,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        }

                        // Cutout secondary accent badge using a solid background ring instead of drop shadow.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 30.dp, bottom = 30.dp)
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(bgColor)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(brandAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = slide.secondaryIcon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = slide.title,
                        color = textPrimary,
                        fontSize = 29.sp,
                        lineHeight = 35.sp,
                        letterSpacing = (-0.5).sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = slide.subtitle,
                        color = textSecondary,
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(26.dp))

                    // Clean flat feature highlight pills.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        slide.highlights.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = cardSurface,
                                border = BorderStroke(1.dp, cardBorder),
                                shadowElevation = 0.dp
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(brandAccent)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tag,
                                        color = textPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Expanding pill page indicators.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(slides.size) { index ->
                    val selected = index == pagerState.currentPage
                    val pillWidth by animateDpAsState(
                        targetValue = if (selected) 28.dp else 8.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "indicatorWidth"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(pillWidth)
                            .clip(CircleShape)
                            .background(
                                if (selected) brandAccent
                                else textSecondary.copy(alpha = 0.26f)
                            )
                    )
                }
            }

            // Flat primary Continue / Get Started CTA.
            Button(
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = brandAccent,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp
                ),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = isLastPage,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    label = "ctaLabel"
                ) { last ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (last) "Get Started" else "Continue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
