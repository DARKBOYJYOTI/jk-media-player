package com.jk.mediaplayer.ui.music


import android.media.MediaPlayer
import android.media.MediaMetadataRetriever

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jk.mediaplayer.R
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.ui.common.AppToast
import com.jk.mediaplayer.ui.common.WriteSettingsPermissionModal
import com.jk.mediaplayer.ui.theme.LightSurfaceVariant
import com.jk.mediaplayer.ui.theme.LightTextPrimary
import com.jk.mediaplayer.ui.theme.LightTextSecondary
import com.jk.mediaplayer.ui.theme.MusicRed
import com.jk.mediaplayer.util.RingtoneHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingtoneCutterDialog(
    track: AudioItem,
    onRescanLibrary: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var hasWritePermission by remember {
        mutableStateOf(RingtoneHelper.checkWriteSettingsPermission(context))
    }
    var continueSaveOnly by remember { mutableStateOf(false) }

    // Prompt for Modify System Settings before opening the ringtone cutter when not yet granted.
    if (!hasWritePermission && !continueSaveOnly) {
        WriteSettingsPermissionModal(
            accentColor = MusicRed,
            isDark = isDark,
            onPermissionGranted = { hasWritePermission = true },
            onContinueWithoutPermission = { continueSaveOnly = true },
            onDismiss = onDismiss
        )
        return
    }

    val dialogBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBg = if (isDark) Color(0xFF1C1D26) else Color(0xFFF3F4F7)
    val cardBorder = if (isDark) Color(0xFF2B2D3A) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else LightTextPrimary
    val textSecondary = if (isDark) Color(0xFFA5A9B8) else LightTextSecondary

    // Measure exact actual audio duration (never stretch short 10s audio to 30s)
    val actualDurationMs = remember(track.id, track.path) {
        var dur = track.duration
        try {
            val mmr = MediaMetadataRetriever()
            if (track.path.isNotEmpty() && File(track.path).exists()) {
                mmr.setDataSource(track.path)
            } else {
                mmr.setDataSource(context, track.uri)
            }
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (d != null && d > 0L) {
                dur = d
            }
            mmr.release()
        } catch (_: Exception) {}
        dur.coerceAtLeast(1000L)
    }

    // Selection range (Default: 0 to 30s, or full track length if shorter than 30s)
    var startMs by remember(actualDurationMs) { mutableLongStateOf(0L) }
    var endMs by remember(actualDurationMs) { mutableLongStateOf(minOf(30000L, actualDurationMs)) }

    // Fade In / Fade Out (0 = Off, 1s, 2s) - defaults to Off for bit-perfect instant cut
    var fadeInSec by remember { mutableIntStateOf(0) }
    var fadeOutSec by remember { mutableIntStateOf(0) }

    // Waveform bars profile
    val waveformBars = remember(track.id, actualDurationMs) {
        RingtoneHelper.generateWaveform(actualDurationMs, barCount = 65, seed = track.id)
    }

    // Preview Player State
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var currentPreviewPosMs by remember { mutableLongStateOf(0L) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Target Selection Modal & Permission Dialog State
    var showTargetSheet by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf<RingtoneHelper.RingtoneTarget?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var processStatus by remember { mutableStateOf("") }

    // Helper to start or resume preview playback
    fun togglePreview() {
        if (isPreviewPlaying) {
            previewPlayer?.pause()
            isPreviewPlaying = false
        } else {
            try {
                if (previewPlayer == null) {
                    val player = MediaPlayer()
                    if (track.path.isNotEmpty() && File(track.path).exists()) {
                        player.setDataSource(track.path)
                    } else {
                        player.setDataSource(context, track.uri)
                    }
                    player.prepare()
                    player.seekTo(startMs.toInt())
                    previewPlayer = player
                } else {
                    val pos = previewPlayer?.currentPosition?.toLong() ?: 0L
                    if (pos < startMs || pos >= endMs) {
                        previewPlayer?.seekTo(startMs.toInt())
                    }
                }
                previewPlayer?.start()
                isPreviewPlaying = true
            } catch (e: Exception) {
                AppToast.show("Cannot preview: ${e.message}")
                isPreviewPlaying = false
            }
        }
    }

    // Clean up MediaPlayer on dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                previewPlayer?.stop()
                previewPlayer?.release()
                previewPlayer = null
            } catch (_: Exception) {}
        }
    }

    // Preview position ticker
    LaunchedEffect(isPreviewPlaying) {
        while (isPreviewPlaying) {
            val player = previewPlayer
            if (player != null && isPreviewPlaying) {
                try {
                    val pos = player.currentPosition.toLong()
                    currentPreviewPosMs = pos
                    if (pos >= endMs) {
                        player.seekTo(startMs.toInt())
                    }
                } catch (_: Exception) {}
            }
            delay(50)
        }
    }

    // Function to execute the trim and apply target
    fun executeTrimAndApply(target: RingtoneHelper.RingtoneTarget) {
        coroutineScope.launch {
            isProcessing = true
            processStatus = "Trimming audio segment..."

            val croppedFile = RingtoneHelper.trimAudioWithFade(
                context = context,
                sourcePath = track.path,
                sourceUri = track.uri,
                startMs = startMs,
                endMs = endMs,
                fadeInSeconds = fadeInSec,
                fadeOutSeconds = fadeOutSec,
                targetFormat = "MP3" // Strictly universal MP3 format
            )

            if (croppedFile == null) {
                isProcessing = false
                AppToast.show("Failed to trim audio. Please try again.")
                return@launch
            }

            val cutDurationMs = (endMs - startMs).coerceAtLeast(1000L)
            processStatus = "Saving to Ringtones directory..."
            val cropResult = RingtoneHelper.saveRingtoneToLibrary(
                context = context,
                file = croppedFile,
                baseTitle = track.title,
                durationMs = cutDurationMs,
                format = "MP3"
            )
            onRescanLibrary()

            if (cropResult?.uri != null) {
                if (target != RingtoneHelper.RingtoneTarget.SAVE_ONLY) {
                    processStatus = "Setting as ${target.label}..."
                    val applied = RingtoneHelper.applyRingtoneTarget(
                        context = context,
                        ringtoneUri = cropResult.uri,
                        target = target
                    )
                    if (applied) {
                        AppToast.show("${target.label} set successfully!", 3000L)
                    } else {
                        AppToast.show("Saved to 'Ringtones' playlist! Grant permission to set as ringtone.", 3500L)
                    }
                } else {
                    AppToast.show("Saved to Ringtones folder & 'Ringtones' playlist!", 3000L)
                }
            } else {
                AppToast.show("Ringtone saved to device storage!")
            }

            isProcessing = false
            showTargetSheet = false
            try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
            onDismiss()
        }
    }

    // Handler when user selects a target destination
    fun onTargetSelected(target: RingtoneHelper.RingtoneTarget) {
        if (target != RingtoneHelper.RingtoneTarget.SAVE_ONLY) {
            // Check Modify System Settings permission before applying
            if (!RingtoneHelper.checkWriteSettingsPermission(context)) {
                pendingTarget = target
                showPermissionDialog = true
                return
            }
        }
        executeTrimAndApply(target)
    }

    Dialog(
        onDismissRequest = {
            if (!isProcessing) {
                try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = dialogBg,
            border = BorderStroke(1.dp, cardBorder),
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MusicRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = null,
                                    tint = MusicRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Ringtone Cutter",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                            Text(
                                text = "Cut & Set as System Ringtone (MP3)",
                                fontSize = 11.sp,
                                color = textSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                            onDismiss()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Track Info Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDark) Color(0xFF222222) else LightSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(track.albumArtUri ?: track.onlineArtUrl)
                                    .crossfade(150)
                                    .error(R.drawable.ic_default_album_art)
                                    .fallback(R.drawable.ic_default_album_art)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${track.artist} • ${formatPreciseTime(actualDurationMs)}",
                                fontSize = 13.sp,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // INTERACTIVE AUDIO WAVEFORM VISUALIZER
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(105.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .pointerInput(actualDurationMs) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                    val tapMs = (frac * actualDurationMs).toLong()
                                    val distToStart = kotlin.math.abs(tapMs - startMs)
                                    val distToEnd = kotlin.math.abs(tapMs - endMs)
                                    if (distToStart < distToEnd) {
                                        startMs = tapMs.coerceAtMost(endMs - 500L)
                                    } else {
                                        endMs = tapMs.coerceAtLeast(startMs + 500L)
                                    }
                                    previewPlayer?.seekTo(tapMs.toInt())
                                }
                            }
                    ) {
                        val activeStartFrac = (startMs.toFloat() / actualDurationMs.toFloat()).coerceIn(0f, 1f)
                        val activeEndFrac = (endMs.toFloat() / actualDurationMs.toFloat()).coerceIn(0f, 1f)
                        val previewFrac = (currentPreviewPosMs.toFloat() / actualDurationMs.toFloat()).coerceIn(0f, 1f)

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val barCount = waveformBars.size
                            val spacing = 2.dp.toPx()
                            val barWidth = ((w - (barCount - 1) * spacing) / barCount).coerceAtLeast(2f)

                            // 1. Draw highlighted active cut zone
                            val activeLeft = w * activeStartFrac
                            val activeWidth = (w * (activeEndFrac - activeStartFrac)).coerceAtLeast(0f)
                            drawRoundRect(
                                color = MusicRed.copy(alpha = 0.12f),
                                topLeft = Offset(activeLeft, 0f),
                                size = Size(activeWidth, h),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )

                            // 2. Waveform bars
                            for (i in 0 until barCount) {
                                val barFrac = i.toFloat() / barCount.toFloat()
                                val isInSelection = barFrac in activeStartFrac..activeEndFrac
                                val barHeightRatio = waveformBars[i]
                                val barH = (h * 0.85f * barHeightRatio).coerceAtLeast(4.dp.toPx())
                                val topY = (h - barH) / 2f
                                val leftX = i * (barWidth + spacing)

                                val barColor = if (isInSelection) {
                                    MusicRed
                                } else {
                                    if (isDark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.16f)
                                }

                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(leftX, topY),
                                    size = Size(barWidth, barH),
                                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                                )
                            }

                            // 3. Animated Preview Needle
                            if (isPreviewPlaying && previewFrac in 0f..1f) {
                                val needleX = w * previewFrac
                                drawLine(
                                    color = Color.White,
                                    start = Offset(needleX, 0f),
                                    end = Offset(needleX, h),
                                    strokeWidth = 2.dp.toPx()
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(needleX, 3.5.dp.toPx())
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Dual-Thumb Range Slider
                RangeSlider(
                    value = startMs.toFloat()..endMs.toFloat(),
                    onValueChange = { range ->
                        startMs = range.start.toLong().coerceIn(0L, (actualDurationMs - 500L).coerceAtLeast(0L))
                        endMs = range.endInclusive.toLong().coerceIn(startMs + 500L, actualDurationMs)
                    },
                    valueRange = 0f..actualDurationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MusicRed,
                        activeTrackColor = MusicRed,
                        inactiveTrackColor = if (isDark) Color(0xFF2B2D3A) else Color(0xFFE5E7EB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Clean Time Display Row: [START] [DURATION] [END]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(text = "START", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        Text(
                            text = formatPreciseTime(startMs),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                    }

                    // Duration badge in center
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MusicRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, MusicRed.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "${((endMs - startMs) / 1000f).roundToInt()}s Duration",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MusicRed,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "END", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        Text(
                            text = formatPreciseTime(endMs),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // FADE IN / FADE OUT (Compact Card)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Fade In Chips
                        Column {
                            Text(text = "Fade In", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0 to "Off", 1 to "1s", 2 to "2s").forEach { (sec, label) ->
                                    val isSelected = fadeInSec == sec
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) MusicRed else (if (isDark) Color(0xFF2B2D3A) else Color(0xFFE5E7EB)),
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { fadeInSec = sec }
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else textSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Fade Out Chips
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Fade Out", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0 to "Off", 1 to "1s", 2 to "2s").forEach { (sec, label) ->
                                    val isSelected = fadeOutSec == sec
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) MusicRed else (if (isDark) Color(0xFF2B2D3A) else Color(0xFFE5E7EB)),
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { fadeOutSec = sec }
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else textSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // PRIMARY ACTION BUTTONS: [Preview Button] & [Set as Ringtone Button]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Preview Play/Pause
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = cardBg,
                        border = BorderStroke(1.dp, cardBorder),
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { togglePreview() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPreviewPlaying) "Pause" else "Play",
                                tint = MusicRed,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // Main Set as Ringtone Action Button
                    Button(
                        onClick = {
                            try { previewPlayer?.pause(); isPreviewPlaying = false } catch (_: Exception) {}
                            showTargetSheet = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MusicRed),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Set as Ringtone",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    // TARGET SELECTION MODAL SHEET
    if (showTargetSheet) {
        Dialog(
            onDismissRequest = { if (!isProcessing) showTargetSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = dialogBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Choose Ringtone Target",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        if (!isProcessing) {
                            IconButton(onClick = { showTargetSheet = false }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isProcessing) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MusicRed, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = processStatus,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        val targets = listOf(
                            Triple(RingtoneHelper.RingtoneTarget.PHONE_SIM_1, "Phone Ringtone (SIM 1 / All)", Icons.Default.SimCard),
                            Triple(RingtoneHelper.RingtoneTarget.PHONE_SIM_2, "Phone Ringtone (SIM 2)", Icons.Default.SimCardAlert),
                            Triple(RingtoneHelper.RingtoneTarget.NOTIFICATION, "Notification Sound", Icons.Default.Notifications),
                            Triple(RingtoneHelper.RingtoneTarget.ALARM, "Alarm Sound", Icons.Default.Alarm),
                            Triple(RingtoneHelper.RingtoneTarget.SAVE_ONLY, "Save to Ringtones Folder Only", Icons.Default.Folder)
                        )

                        targets.forEach { (target, label, icon) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = cardBg,
                                border = BorderStroke(1.dp, cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onTargetSelected(target) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MusicRed.copy(alpha = 0.15f),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = MusicRed,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // SYSTEM SETTINGS PERMISSION PROMPT DIALOG
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = dialogBg,
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MusicRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Permission Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
            },
            text = {
                Text(
                    text = "To set this audio as your system ringtone, Android requires 'Modify system settings' permission. Please allow it in the next screen.",
                    fontSize = 15.sp,
                    color = textSecondary,
                    lineHeight = 24.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        RingtoneHelper.requestWriteSettingsPermission(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MusicRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Allow in Settings", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        // If user cancels permission, fallback to saving to Ringtones folder directly
                        pendingTarget?.let { executeTrimAndApply(RingtoneHelper.RingtoneTarget.SAVE_ONLY) }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Only", color = textPrimary)
                }
            }
        )
    }
}

private fun formatPreciseTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000L) / 100
    return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
}
