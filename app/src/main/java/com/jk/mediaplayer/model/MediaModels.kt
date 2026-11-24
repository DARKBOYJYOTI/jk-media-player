package com.jk.mediaplayer.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Immutable
data class VideoItem(
    val id: Long,
    val title: String,
    val path: String,
    val uri: Uri,
    val duration: Long,
    val size: Long,
    val dateModified: Long,
    val folderName: String,
    val resolution: String = "",
    val resumePosition: Long = 0L,
    val isNew: Boolean = false,
    val isPortrait: Boolean = false
) {
    val durationFormatted: String
        get() = formatDuration(duration)

    val sizeFormatted: String
        get() = formatFileSize(size)

    val dateModifiedFormatted: String
        get() {
            if (dateModified <= 0L) return ""
            val timeMs = if (dateModified < 100000000000L) dateModified * 1000L else dateModified
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            return sdf.format(Date(timeMs))
        }

    val resolutionBadge: String?
        get() = getResolutionBadge(resolution)
}

@Immutable
data class VideoFolder(
    val name: String,
    val path: String,
    val videoCount: Int,
    val videos: List<VideoItem> = emptyList()
) {
    val totalDurationFormatted: String
        get() = formatDuration(videos.sumOf { it.duration })

    val totalSizeFormatted: String
        get() = formatFileSize(videos.sumOf { it.size })
}

@Immutable
data class AudioItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val uri: Uri,
    val albumArtUri: Uri? = null,
    val onlineArtUrl: String? = null,
    val folderName: String = "",
    val folderPath: String = "",
    val formatBadge: String = "Hi-Res Audio",
    val isFavorite: Boolean = false,
    val dateAdded: Long = 0L
) {
    val durationFormatted: String
        get() = formatDuration(duration)
}

@Immutable
data class AudioFolder(
    val name: String,
    val path: String,
    val tracks: List<AudioItem>
)

@Immutable
data class ArtistItem(
    val name: String,
    val trackCount: Int,
    val tracks: List<AudioItem>,
    val artistImageUrl: String? = null
)

@Immutable
data class AlbumItem(
    val name: String,
    val artist: String,
    val albumArtUri: Uri?,
    val tracks: List<AudioItem>
)

@Immutable
data class LyricsLine(
    val timeMs: Long,
    val text: String
)

@Immutable
data class CustomPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<Long>,
    val createdAt: Long = System.currentTimeMillis()
)

fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "00:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return String.format(java.util.Locale.US, "%.1f %s", size, units[unitIndex])
}

fun getResolutionBadge(resolution: String): String? {
    if (resolution.isBlank()) return null
    val parts = resolution.lowercase().replace("×", "x").split("x")
    if (parts.size < 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    val maxDim = kotlin.math.max(w, h)
    val minDim = kotlin.math.min(w, h)
    return when {
        maxDim >= 3840 || minDim >= 2160 -> "4K"
        maxDim >= 2560 || minDim >= 1440 -> "2K"
        maxDim >= 2000 || minDim >= 1200 -> "1.5K"
        maxDim >= 1920 || minDim >= 1080 -> "FHD"
        maxDim >= 1600 || minDim >= 900 -> "HD+"
        maxDim >= 1280 || minDim >= 720 -> "HD"
        minDim > 0 -> "SD"
        else -> null
    }
}
