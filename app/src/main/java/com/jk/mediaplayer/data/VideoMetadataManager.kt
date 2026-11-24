package com.jk.mediaplayer.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.jk.mediaplayer.model.VideoItem
import java.io.File

data class TechnicalVideoSpecs(
    val resolution: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val audioChannels: String = "",
    val frameRate: String = "",
    val bitrate: String = "",
    val fileSize: String = "",
    val duration: String = "",
    val container: String = "",
    val filePath: String = "",
    val dateModified: String = ""
)

object VideoMetadataManager {

    /**
     * Cleans up any leftover online metadata and poster cache directories
     */
    fun cleanLegacyCache(context: Context) {
        try {
            val metaDir = File(context.filesDir, "video_metadata")
            if (metaDir.exists()) metaDir.deleteRecursively()
            val posterDir = File(context.filesDir, "video_posters")
            if (posterDir.exists()) posterDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    /**
     * Extracts full on-device technical media specifications using native Android MediaMetadataRetriever and MediaExtractor
     */
    fun extractTechnicalSpecs(context: Context, video: VideoItem): TechnicalVideoSpecs {
        val mmr = MediaMetadataRetriever()
        var videoCodec = ""
        var audioCodec = ""
        var audioChannels = ""
        var frameRate = ""
        var bitrate = ""
        var width = ""
        var height = ""

        try {
            if (video.path.isNotEmpty() && File(video.path).exists()) {
                mmr.setDataSource(video.path)
            } else {
                mmr.setDataSource(context, video.uri)
            }

            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
            val rawBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            if (rawBitrate != null && rawBitrate > 0) {
                bitrate = "${rawBitrate / 1000} kbps"
            }
            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            videoCodec = mime

            val captureFps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            if (!captureFps.isNullOrBlank()) {
                frameRate = "$captureFps fps"
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        // Deeper inspection using MediaExtractor for precise track codecs and channel configuration
        try {
            val extractor = MediaExtractor()
            if (video.path.isNotEmpty() && File(video.path).exists()) {
                extractor.setDataSource(video.path)
            } else {
                extractor.setDataSource(context, video.uri, null)
            }

            val numTracks = extractor.trackCount
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("video/")) {
                    videoCodec = cleanCodecName(trackMime)
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE) && frameRate.isBlank()) {
                        frameRate = "${format.getInteger(MediaFormat.KEY_FRAME_RATE)} fps"
                    }
                } else if (trackMime.startsWith("audio/")) {
                    audioCodec = cleanCodecName(trackMime)
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        audioChannels = when (channels) {
                            1 -> "Mono (1.0)"
                            2 -> "Stereo (2.0)"
                            6 -> "5.1 Surround"
                            8 -> "7.1 Surround"
                            else -> "$channels Channels"
                        }
                    }
                }
            }
            extractor.release()
        } catch (_: Exception) {}

        val res = if (width.isNotBlank() && height.isNotBlank()) "$width × $height" else video.resolution
        val containerExt = if (video.path.isNotEmpty()) File(video.path).extension.uppercase() else "VIDEO"

        return TechnicalVideoSpecs(
            resolution = if (res.isNotBlank()) res else "Auto",
            videoCodec = if (videoCodec.isNotBlank()) videoCodec else "H.264 (AVC)",
            audioCodec = if (audioCodec.isNotBlank()) audioCodec else "AAC",
            audioChannels = if (audioChannels.isNotBlank()) audioChannels else "Stereo (2.0)",
            frameRate = if (frameRate.isNotBlank()) frameRate else "24.0 fps",
            bitrate = if (bitrate.isNotBlank()) bitrate else "Variable",
            fileSize = video.sizeFormatted,
            duration = video.durationFormatted,
            container = containerExt,
            filePath = video.path.ifEmpty { video.uri.toString() },
            dateModified = video.dateModifiedFormatted
        )
    }

    private fun cleanCodecName(mime: String): String {
        return when {
            mime.contains("avc", ignoreCase = true) || mime.contains("h264", ignoreCase = true) -> "H.264 (AVC)"
            mime.contains("hevc", ignoreCase = true) || mime.contains("h265", ignoreCase = true) -> "H.265 (HEVC)"
            mime.contains("av01", ignoreCase = true) || mime.contains("av1", ignoreCase = true) -> "AV1"
            mime.contains("vp9", ignoreCase = true) -> "VP9"
            mime.contains("vp8", ignoreCase = true) -> "VP8"
            mime.contains("mp4v", ignoreCase = true) -> "MPEG-4"
            mime.contains("aac", ignoreCase = true) -> "AAC Audio"
            mime.contains("ac3", ignoreCase = true) -> "AC3 Audio"
            mime.contains("eac3", ignoreCase = true) -> "Digital Plus (E-AC3)"
            mime.contains("dts", ignoreCase = true) -> "DTS Digital Surround"
            mime.contains("opus", ignoreCase = true) -> "Opus Audio"
            mime.contains("vorbis", ignoreCase = true) -> "Vorbis"
            mime.contains("flac", ignoreCase = true) -> "FLAC Lossless"
            else -> mime.removePrefix("video/").removePrefix("audio/").uppercase()
        }
    }
}
