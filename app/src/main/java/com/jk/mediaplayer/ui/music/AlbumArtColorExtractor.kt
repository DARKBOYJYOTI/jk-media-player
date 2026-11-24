package com.jk.mediaplayer.ui.music

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.jk.mediaplayer.model.AudioItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AlbumArtColorExtractor {
    private val colorCache = LruCache<String, Color>(150)
    private val localArtExistsCache = LruCache<Long, Boolean>(500)
    private val inFlightExtractions = ConcurrentHashMap<String, Deferred<Color>>()
    private val extractionScope = CoroutineScope(Dispatchers.IO)

    private fun getDiskPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("album_art_color_cache", Context.MODE_PRIVATE)

    fun getCachedColor(context: Context, track: AudioItem, onlineArtUrl: String? = track.onlineArtUrl): Color? {
        val effectiveOnlineArt = onlineArtUrl
            ?: track.onlineArtUrl
            ?: com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedAlbumArtUrl(context, track.artist, track.album, track.title)
        val cacheKey = "${track.id}_${track.albumArtUri}_$effectiveOnlineArt"
        
        // 1. Check in-memory LRU cache (0ms)
        colorCache.get(cacheKey)?.let { return it }

        // 2. Check persistent disk cache (fast indexed SharedPreferences)
        try {
            val prefs = getDiskPrefs(context)
            val persistentKey = "color_${track.id}"
            if (prefs.contains(persistentKey)) {
                val argb = prefs.getInt(persistentKey, 0)
                if (argb != 0) {
                    val color = Color(argb)
                    colorCache.put(cacheKey, color)
                    return color
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun getInitialDominantColor(context: Context, track: AudioItem, onlineArtUrl: String? = track.onlineArtUrl): Color {
        return getCachedColor(context, track, onlineArtUrl) ?: fallbackColor(track)
    }

    fun hasValidLocalAlbumArt(context: Context, track: AudioItem): Boolean {
        localArtExistsCache.get(track.id)?.let { return it }

        if (com.jk.mediaplayer.data.AudioAlbumArtCache.isArtworkCached(context, track)) {
            localArtExistsCache.put(track.id, true)
            return true
        }
        localArtExistsCache.put(track.id, false)
        return false
    }

    suspend fun extractDominantColor(
        context: Context,
        track: AudioItem,
        onlineArtUrl: String? = track.onlineArtUrl
    ): Color {
        val effectiveOnlineArt = onlineArtUrl
            ?: track.onlineArtUrl
            ?: com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedAlbumArtUrl(context, track.artist, track.album, track.title)
        val cacheKey = "${track.id}_${track.albumArtUri}_$effectiveOnlineArt"
        
        // Return immediately if already available in memory or disk cache
        getCachedColor(context, track, effectiveOnlineArt)?.let { return it }

        // Deduplicate in-flight extractions: if multiple callers (e.g. MainActivity, MiniPlayer, NowPlayingSheet)
        // request the same track concurrently on song play, only ONE worker decodes and analyzes the bitmap.
        val deferred = inFlightExtractions.compute(cacheKey) { _, existing ->
            if (existing != null && existing.isActive) {
                existing
            } else {
                extractionScope.async {
                    try {
                        doExtractDominantColor(context, track, effectiveOnlineArt, cacheKey)
                    } finally {
                        inFlightExtractions.remove(cacheKey)
                    }
                }
            }
        }

        return deferred?.await() ?: doExtractDominantColor(context, track, effectiveOnlineArt, cacheKey)
    }

    private suspend fun doExtractDominantColor(
        context: Context,
        track: AudioItem,
        onlineArtUrl: String?,
        cacheKey: String
    ): Color = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            track.albumArtUri?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        // High-speed downsampling: Palette only requires a tiny 64x64/128x128 swatch
                        inSampleSize = 8
                    }
                    bitmap = BitmapFactory.decodeStream(stream, null, options)
                }
            }
            if (bitmap == null && track.path.isNotEmpty() && File(track.path).exists()) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(track.path)
                    val raw = mmr.embeddedPicture
                    if (raw != null) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 8
                        }
                        bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
                    }
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
            if (bitmap == null && !onlineArtUrl.isNullOrEmpty()) {
                val loader = coil.Coil.imageLoader(context)
                val request = coil.request.ImageRequest.Builder(context)
                    .data(onlineArtUrl)
                    .size(64, 64)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? coil.request.SuccessResult)?.drawable
                bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }
        } catch (_: Exception) {}

        val extractedColor = if (bitmap != null) {
            val palette = Palette.from(bitmap!!).maximumColorCount(12).generate()
            val swatch = palette.vibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch
                ?: palette.darkVibrantSwatch
            if (swatch != null) {
                Color(swatch.rgb)
            } else {
                fallbackColor(track)
            }
        } else {
            fallbackColor(track)
        }

        colorCache.put(cacheKey, extractedColor)
        
        // Persist to disk so future launches load in 0ms without re-extraction
        try {
            val prefs = getDiskPrefs(context)
            prefs.edit().putInt("color_${track.id}", extractedColor.toArgb()).apply()
        } catch (_: Exception) {}

        extractedColor
    }

    private val elegantPalette = listOf(
        Color(0xFF1E2838), // Deep Slate Navy
        Color(0xFF261D33), // Royal Dark Violet
        Color(0xFF1B2B2B), // Deep Forest Teal
        Color(0xFF2A202A), // Deep Plum
        Color(0xFF1A2633), // Midnight Steel
        Color(0xFF282836)  // Dark Graphite
    )

    private fun fallbackColor(track: AudioItem): Color {
        val hash = kotlin.math.abs((track.title.hashCode() * 31 + track.artist.hashCode()))
        return elegantPalette[hash % elegantPalette.size]
    }
}
