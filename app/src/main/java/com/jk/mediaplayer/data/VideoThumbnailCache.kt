package com.jk.mediaplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.jk.mediaplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object VideoThumbnailCache {

    private fun getThumbDir(context: Context): File {
        val dir = File(context.cacheDir, "video_thumbs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getThumbnailFile(context: Context, video: VideoItem): File {
        val safeName = "vthumb_v2_${video.id}_${video.dateModified}.jpg"
        return File(getThumbDir(context), safeName)
    }


    suspend fun getOrGenerateThumbnail(context: Context, video: VideoItem): File? = withContext(Dispatchers.IO) {
        val targetFile = getThumbnailFile(context, video)
        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        val bitmap = extractVideoThumbnail(context, video)
        if (bitmap != null) {
            try {
                // Scale to max width 360 (clean, crisp, and memory efficient)
                val maxWidth = 360
                val scaled = if (bitmap.width > maxWidth) {
                    val height = (bitmap.height * maxWidth) / bitmap.width
                    Bitmap.createScaledBitmap(bitmap, maxWidth, height.coerceAtLeast(1), true)
                } else {
                    bitmap
                }

                val tempFile = File(getThumbDir(context), "${targetFile.name}.tmp")
                FileOutputStream(tempFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.flush()
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(targetFile)
                }
                return@withContext targetFile
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext null
    }

    /**
     * Multi-tier high-performance video thumbnail extractor:
     * 1. Android ContentResolver / MediaStore thumbnail (official system thumbnail)
     * 2. Embedded Cover Art / Poster (MP4/MKV container artwork)
     * 3. ThumbnailUtils video keyframe
     * 4. MediaMetadataRetriever representative frame at 10%-25% (avoiding 0ms black frames)
     */
    private fun extractVideoThumbnail(context: Context, video: VideoItem): Bitmap? {
        // Tier 1: ContentResolver loadThumbnail (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bmp = context.contentResolver.loadThumbnail(video.uri, Size(480, 320), null)
                if (bmp.width > 0 && bmp.height > 0) {
                    return bmp
                }
            } catch (_: Throwable) {}
        } else {
            // MediaStore.Video.Thumbnails for Android 7 - 9
            try {
                @Suppress("DEPRECATION")
                val bmp = MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    video.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    return bmp
                }
            } catch (_: Throwable) {}
        }

        // Tier 2: ThumbnailUtils file-based thumbnail
        if (video.path.isNotEmpty() && File(video.path).exists()) {
            try {
                @Suppress("DEPRECATION")
                val bmp = ThumbnailUtils.createVideoThumbnail(video.path, MediaStore.Video.Thumbnails.MINI_KIND)
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    return bmp
                }
            } catch (_: Throwable) {}
        }

        // Tier 3: MediaMetadataRetriever (embedded artwork, then frame at 1.5s - 2.5s)
        val retriever = MediaMetadataRetriever()
        try {
            if (video.path.isNotEmpty() && File(video.path).exists()) {
                retriever.setDataSource(video.path)
            } else {
                retriever.setDataSource(context, video.uri)
            }

            // 3a: Embedded picture/cover art
            val embeddedBytes = retriever.embeddedPicture
            if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(embeddedBytes, 0, embeddedBytes.size)
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    return bmp
                }
            }

            // 3b: Representative frame at 1.5s or 20% into video (never 0ms blank frames)
            val durationMs = if (video.duration > 0) video.duration
            else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val frameTimeUs = when {
                durationMs > 10_000L -> 1_500_000L // 1.5 seconds in
                durationMs > 2_000L -> (durationMs * 1000L) / 5 // 20% in
                else -> 500_000L // 0.5 second in
            }

            val bmp = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(frameTimeUs)
                ?: retriever.frameAtTime
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                return bmp
            }
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        return null
    }

    /** Clears old black 0ms thumbnails from legacy runs */
    fun clearLegacyThumbnails(context: Context) {
        try {
            val dir = getThumbDir(context)
            dir.listFiles()?.forEach { file ->
                if (!file.name.startsWith("vthumb_v2_")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }
}
