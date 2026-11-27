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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LibraryMusic
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * Uses each app's fixed brand accent color across every slide.
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
                    subtitle = "Enjoy lossless FLAC, Opus, MP3, and WAV tracks with smooth crossfades and system DSP equalizer integration.",
                    primaryIcon = Icons.Default.Headphones,
                    secondaryIcon = Icons.Default.GraphicEq,
                    highlights = listOf("Hi-Res Audio", "Smooth Fades", "DSP Equalizer")
                ),
                OnboardingSlide(
                    badge = "SMART LIBRARY",
                    title = "Instant Search &\nDynamic Themes",
                    subtitle = "Browse by songs, albums, artists, and folders with adaptive album-art gradients and zero-lag search.",
                    primaryIcon = Icons.Default.LibraryMusic,
                    secondaryIcon = Icons.Default.Album,
                    highlights = listOf("Folder View", "Adaptive Art", "Quick Picks")
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
                    subtitle = "Play MKV, MP4, HEVC, and network streams effortlessly with hardware acceleration and multi-track subtitles.",
                    primaryIcon = Icons.Default.PlayCircleFilled,
                    secondaryIcon = Icons.Default.HighQuality,
                    highlights = listOf("HW Decode", "Multi-Audio", "Subtitles")
                ),
                OnboardingSlide(
                    badge = "THEATER CONTROLS",
                    title = "Precision Gestures\n& Picture Modes",
                    subtitle = "Swipe smoothly to adjust brightness, volume, and seek position with customizable aspect ratios and color profiles.",
                    primaryIcon = Icons.Default.TouchApp,
                    secondaryIcon = Icons.Default.Subtitles,
                    highlights = listOf("Swipe Controls", "Custom Aspect", "Vivid & Cinema")
                ),
                OnboardingSlide(
                    badge = "SEAMLESS PLAY",
                    title = "Folders & Background\nAudio Support",
                    subtitle = "Keep videos organized by folder, resume right where you left off, or switch to background audio anytime.",
                    primaryIcon = Icons.Default.Folder,
                    secondaryIcon = Icons.Default.Equalizer,
                    highlights = listOf("Instant Resume", "Background Play", "Folder Explorer")
                )
            )
        }
    }

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val currentSlide = slides[pagerState.currentPage]
    val isLastPage = pagerState.currentPage == slides.lastIndex

    val bgColor = if (isDark) Color(0xFF0A0A0F) else Color(0xFFF8F9FC)
    val textPrimary = if (isDark) Color.White else Color(0xFF14161F)
    val textSecondary = if (isDark) Color(0xFFA7ACBE) else Color(0xFF5A6072)
    val cardSurface = if (isDark) Color(0xFF151621) else Color.White
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    val pulseTransition = rememberInfiniteTransition(label = "heroPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Fixed ambient top radial glow matching the app's brand color.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            brandAccent.copy(alpha = if (isDark) 0.28f else 0.16f),
                            brandAccent.copy(alpha = if (isDark) 0.10f else 0.05f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Top bar with brand pill and optional Skip action.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = brandAccent.copy(alpha = if (isDark) 0.18f else 0.12f),
                    border = BorderStroke(1.dp, brandAccent.copy(alpha = 0.30f))
                ) {
                    Text(
                        text = currentSlide.badge,
                        color = brandAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                AnimatedVisibility(
                    visible = !isLastPage,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = "Skip",
                        color = textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onFinish)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
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
                val cardScale = 1f - (pageOffset * 0.10f)
                val cardAlpha = 1f - (pageOffset * 0.35f)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = cardScale
                            scaleY = cardScale
                            alpha = cardAlpha
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Layered hero artwork card.
                    Box(
                        modifier = Modifier.size(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(196.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                }
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            brandAccent.copy(alpha = if (isDark) 0.26f else 0.18f),
                                            brandAccent.copy(alpha = if (isDark) 0.10f else 0.06f)
                                        )
                                    )
                                )
                        )

                        Surface(
                            shape = RoundedCornerShape(34.dp),
                            color = cardSurface,
                            border = BorderStroke(1.dp, cardBorder),
                            shadowElevation = if (isDark) 12.dp else 8.dp,
                            modifier = Modifier.size(144.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                brandAccent.copy(alpha = if (isDark) 0.22f else 0.14f),
                                                Color.Transparent
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = slide.primaryIcon,
                                    contentDescription = null,
                                    tint = brandAccent,
                                    modifier = Modifier.size(68.dp)
                                )
                            }
                        }

                        // Floating secondary accent badge.
                        Surface(
                            shape = CircleShape,
                            color = brandAccent,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 22.dp, bottom = 22.dp)
                                .size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = slide.secondaryIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(34.dp))

                    Text(
                        text = slide.title,
                        color = textPrimary,
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = slide.subtitle,
                        color = textSecondary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Feature highlight pills.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        slide.highlights.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = cardSurface,
                                border = BorderStroke(1.dp, cardBorder)
                            ) {
                                Text(
                                    text = tag,
                                    color = textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Animated expanding pill page indicators.
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
                                else textSecondary.copy(alpha = 0.28f)
                            )
                    )
                }
            }

            // Primary Continue / Get Started CTA.
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
                shape = RoundedCornerShape(18.dp),
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
