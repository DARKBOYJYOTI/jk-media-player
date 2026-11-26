package com.jk.mediaplayer.ui.music


import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.jk.mediaplayer.ui.theme.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jk.mediaplayer.R
import com.jk.mediaplayer.model.AudioItem

@Composable
fun MiniPlayer(
    track: AudioItem?,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playbackTime: () -> Long = { 0L },
    totalDurationMs: Long = 0L,
    onPrevious: () -> Unit = {},
    canGoNext: Boolean = true,
    canGoPrevious: Boolean = true,
    isLoading: Boolean = false
) {
    MiniPlayerContent(
        track = track,
        isPlaying = isPlaying,
        onTogglePlayPause = onTogglePlayPause,
        onNext = onNext,
        onClick = onClick,
        modifier = modifier,
        playbackTime = playbackTime,
        totalDurationMs = totalDurationMs,
        onPrevious = onPrevious,
        canGoNext = canGoNext,
        canGoPrevious = canGoPrevious,
        isLoading = isLoading
    )
}

@Composable
fun MiniPlayerContent(
    track: AudioItem?,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playbackTime: () -> Long = { 0L },
    totalDurationMs: Long = 0L,
    onPrevious: () -> Unit = {},
    canGoNext: Boolean = true,
    canGoPrevious: Boolean = true,
    // Read inside the draw lambda so drag frames do not recompose mini content.
    contentAlpha: () -> Float = { 1f },
    // The hero artwork replaces mini artwork immediately when expansion starts.
    miniArtworkAlpha: () -> Float = { 1f },
    // Same resolved tint used by the unified window background.
    backgroundColor: Color? = null,
    // True while a YouTube Music stream is being resolved, so the card shows progress
    // instead of pretending the song is ready to pause.
    isLoading: Boolean = false
) {
    if (track == null) return

    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val resolvedBackground = backgroundColor ?: if (isDark) Color(0xFF212124) else Color.White
    // Text follows the unified window tint, keeping labels readable at every drag position.
    val useDarkText = resolvedBackground.luminance() > 0.5f
    val textColorPrimary = if (useDarkText) Color(0xFF141416) else Color.White
    val textColorSecondary = if (useDarkText) Color(0xFF5F6068) else Color(0xC9FFFFFF)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha() }
        ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Square Album Artwork
                    val artRequest = remember(track.id, track.onlineArtUrl) {
                        ImageRequest.Builder(context)
                            .data(track)
                            .crossfade(180)
                            .error(R.drawable.ic_default_album_art)
                            .placeholder(R.drawable.ic_default_album_art)
                            .fallback(R.drawable.ic_default_album_art)
                            .build()
                    }
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .graphicsLayer { alpha = miniArtworkAlpha() }
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF282828) else Color(0xFFECEEF2))
                    ) {
                        AsyncImage(
                            model = artRequest,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title & Artist Info
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                    ) {
                        val cleanTitle = track.title.removeSuffix(".mp3").removeSuffix(".flac").removeSuffix(".m4a").removeSuffix(".opus").replace('_', ' ')
                        val isArtistUnknown = track.artist == "<unknown>" || track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true)
                        val cachedOnlineArtist = remember(track.title) {
                            if (isArtistUnknown) com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedArtistName(context, track.title) else null
                        }
                        val cleanArtist = if (isArtistUnknown) (cachedOnlineArtist ?: "Unknown Artist") else track.artist

                        Text(
                            text = cleanTitle,
                            color = textColorPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = cleanArtist,
                            color = textColorSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Previous Track Button
                    IconButton(
                        onClick = onPrevious,
                        enabled = canGoPrevious,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = textColorPrimary.copy(alpha = if (canGoPrevious) 1f else 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Play / Pause Button (Clean white/dark icon)
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = textColorPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = textColorPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Next Track Button
                    IconButton(
                        onClick = onNext,
                        enabled = canGoNext,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = textColorPrimary.copy(alpha = if (canGoNext) 1f else 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                }

                // Sleek Bottom Progress Line (Adaptive contrast, smoothly interpolated).
                // Drawn as an overlay: the 48dp minimum touch targets of the buttons already fill
                // the 58dp card, so an inline line was clipped and never visible.
                MiniPlayerProgressLine(
                    playbackTime = playbackTime,
                    totalDurationMs = totalDurationMs,
                    fallbackDurationMs = track.duration,
                    isDark = isDark,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = miniArtworkAlpha() }
                )
            }
        }
}

/**
 * Reads the playback position inside its own scope so the mini player card itself is not
 * recomposed on every player tick. The fraction is animated between ticks so the line glides
 * instead of stepping once per TimeChanged event.
 */
@Composable
private fun MiniPlayerProgressLine(
    playbackTime: () -> Long,
    totalDurationMs: Long,
    fallbackDurationMs: Long,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(if (isDark) Color.White.copy(alpha = 0.15f) else Color(0x14000000))
            .drawBehind {
                val cur = playbackTime()
                val safeTotal = if (totalDurationMs > 0L) totalDurationMs else fallbackDurationMs.coerceAtLeast(1L)
                val frac = (cur.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
                val w = size.width * frac
                val progressColor = if (isDark) Color.White else Color(0xFF1E2029)
                if (w > 0f) drawRect(color = progressColor, size = Size(w, size.height))
            }
    )
}
