package com.jk.mediaplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.jk.mediaplayer.model.AudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AudioAlbumArtCache {

    // Fast in-memory negative cache: avoids repeatedly launching MediaMetadataRetriever on files with no ID3 artwork
    private val noArtworkCache = LruCache<Long, Boolean>(2000)

    // Concurrency throttle: prevents native libstagefright thread starvation by limiting concurrent MMR extractions
    private val extractionSemaphore = Semaphore(2)

    private fun getArtDir(context: Context): File {
        val dir = File(context.cacheDir, "audio_art")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getArtworkFile(context: Context, track: AudioItem): File {
        val safeId = if (track.id != 0L) track.id else track.path.hashCode().toLong()
        return File(getArtDir(context), "art_${safeId}.jpg")
    }

    fun isArtworkCached(context: Context, track: AudioItem): Boolean {
        val file = getArtworkFile(context, track)
        return file.exists() && file.length() > 0
    }

    /**
     * Drops the cached extract and the negative marker for one track.
     *
     * The extractor remembers tracks without artwork, so a cover that was just written into the
     * file would stay invisible until the next launch without this reset.
     */
    fun invalidateArtwork(context: Context, track: AudioItem) {
        val safeId = if (track.id != 0L) track.id else track.path.hashCode().toLong()
        noArtworkCache.remove(safeId)
        val cached = getArtworkFile(context, track)
        if (cached.exists()) cached.delete()
    }

    fun getCachedArtworkFile(context: Context, track: AudioItem): File? {
        val targetFile = getArtworkFile(context, track)
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }
        return null
    }

    /**
     * Extracts embedded artwork from the audio file and saves it to local cache.
     */
    suspend fun getOrExtractArtwork(context: Context, track: AudioItem): File? = withContext(Dispatchers.IO) {
        val safeId = if (track.id != 0L) track.id else track.path.hashCode().toLong()
        if (noArtworkCache.get(safeId) == true) {
            return@withContext null
        }

        val targetFile = getArtworkFile(context, track)
        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        // Try extracting embedded ID3 picture with concurrency throttling
        if (track.path.isNotEmpty() && File(track.path).exists()) {
            val extracted = extractionSemaphore.withPermit {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(track.path)
                    val rawBytes = mmr.embeddedPicture
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                        val targetPx = 512
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
                            sample *= 2
                        }
                        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                        if (bitmap != null) {
                            val tempFile = File(getArtDir(context), "${targetFile.name}.tmp")
                            FileOutputStream(tempFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                out.flush()
                            }
                            if (tempFile.exists() && tempFile.length() > 0) {
                                if (targetFile.exists()) targetFile.delete()
                                if (tempFile.renameTo(targetFile)) {
                                    return@withPermit targetFile
                                }
                            }
                        }
                    }
                    null
                } catch (_: Exception) {
                    null
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
            if (extracted != null) return@withContext extracted
        }
        noArtworkCache.put(safeId, true)
        null
    }
}

