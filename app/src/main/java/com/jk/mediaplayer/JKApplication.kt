package com.jk.mediaplayer

import android.app.Application
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.imageLoader
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.request.SuccessResult
import com.jk.mediaplayer.data.AudioAlbumArtCache
import com.jk.mediaplayer.data.OnlineMetadataFetcher
import com.jk.mediaplayer.data.VideoThumbnailCache
import com.jk.mediaplayer.model.AlbumItem
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bound on a single artwork lookup so a slow network can never hold a card hostage. */
private const val ARTWORK_FETCH_TIMEOUT_MS = 8_000L

class AudioAlbumArtInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        if (data is AudioItem || data is AlbumItem) {
            val cacheKey = request.memoryCacheKey
            if (request.memoryCachePolicy.readEnabled && cacheKey != null) {
                val cached = context.imageLoader.memoryCache?.get(cacheKey)
                if (cached != null) {
                    return SuccessResult(
                        drawable = BitmapDrawable(context.resources, cached.bitmap),
                        request = request,
                        dataSource = DataSource.MEMORY_CACHE,
                        memoryCacheKey = cacheKey
                    )
                }
            }
        }

        if (data is AudioItem) {
            // Coil 2 runs interceptors on Main.immediate by default, before the memory cache, so
            // every card entering composition used to touch disk here. Keep that off the UI thread.
            val embeddedFile = withContext(Dispatchers.IO) {
                val file = AudioAlbumArtCache.getOrExtractArtwork(context, data)
                if (file != null && file.exists() && file.length() > 0) file else null
            }
            if (embeddedFile != null) {
                val newRequest = request.newBuilder()
                    .data(embeddedFile)
                    .build()
                return chain.proceed(newRequest)
            }

            val resolved = resolveArtwork(
                knownPath = data.onlineArtUrl,
                artist = data.artist,
                album = data.album,
                title = data.title
            )
            if (resolved != null) {
                val newRequest = request.newBuilder()
                    .data(resolved)
                    .build()
                return chain.proceed(newRequest)
            }

            // 3. Fallback
            val fallbackRequest = request.newBuilder()
                .data(R.drawable.ic_default_album_art)
                .build()
            return chain.proceed(fallbackRequest)
        }

        if (data is AlbumItem) {
            val embeddedFile = withContext(Dispatchers.IO) {
                val firstCached = data.tracks.firstOrNull { AudioAlbumArtCache.isArtworkCached(context, it) }
                val file = if (firstCached != null) {
                    AudioAlbumArtCache.getCachedArtworkFile(context, firstCached)
                } else {
                    data.tracks.take(2).firstNotNullOfOrNull { AudioAlbumArtCache.getOrExtractArtwork(context, it) }
                }
                if (file != null && file.exists() && file.length() > 0) file else null
            }
            if (embeddedFile != null) {
                val newRequest = request.newBuilder()
                    .data(embeddedFile)
                    .build()
                return chain.proceed(newRequest)
            }

            val resolved = resolveArtwork(
                knownPath = null,
                artist = data.artist,
                album = data.name,
                title = ""
            )
            if (resolved != null) {
                val newRequest = request.newBuilder()
                    .data(resolved)
                    .build()
                return chain.proceed(newRequest)
            }

            // 3. Fallback
            val fallbackRequest = request.newBuilder()
                .data(R.drawable.ic_default_album_art)
                .build()
            return chain.proceed(fallbackRequest)
        }

        return chain.proceed(request)
    }

    /**
     * Resolves artwork that is not embedded in the file: local cache first (instant), then one
     * throttled web lookup. Without the lookup a card whose album only has online artwork kept
     * showing the placeholder until the track was played.
     */
    private suspend fun resolveArtwork(
        knownPath: String?,
        artist: String,
        album: String,
        title: String
    ): Any? {
        val cached = knownPath ?: withContext(Dispatchers.IO) {
            OnlineMetadataFetcher.getCachedAlbumArtUrl(context, artist, album, title)
        }
        if (!cached.isNullOrBlank()) return artworkModel(cached)

        val fetched = withContext(Dispatchers.IO) {
            withTimeoutOrNull(ARTWORK_FETCH_TIMEOUT_MS) {
                OnlineMetadataFetcher.resolveAlbumArt(context, artist, album, title)
            }
        }
        return if (!fetched.isNullOrBlank()) artworkModel(fetched) else null
    }

    private fun artworkModel(value: String): Any =
        if (value.startsWith("/") || value.startsWith("file:")) {
            File(value.removePrefix("file://"))
        } else value
}

class VideoThumbnailInterceptor(private val context: Context) : Interceptor {
    private val thumbDir by lazy {
        File(context.cacheDir, "video_thumbs").apply { if (!exists()) mkdirs() }
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        // If the request passes a VideoItem directly, generate or load official thumbnail
        if (data is VideoItem) {
            val thumbFile = VideoThumbnailCache.getOrGenerateThumbnail(context, data)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0) {
                val newRequest = request.newBuilder()
                    .data(thumbFile)
                    .build()
                return chain.proceed(newRequest)
            } else {
                val fallbackModel: Any = if (data.path.isNotEmpty()) File(data.path) else data.uri
                val newRequest = request.newBuilder()
                    .data(fallbackModel)
                    .build()
                return chain.proceed(newRequest)
            }
        }

        val videoKey = request.memoryCacheKey?.key
        if (videoKey != null && videoKey.startsWith("vthumb_")) {
            val cachedFile = File(thumbDir, "$videoKey.jpg")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                val newRequest = request.newBuilder()
                    .data(cachedFile)
                    .build()
                return chain.proceed(newRequest)
            }
        }

        return chain.proceed(request)
    }
}

class JKApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CoroutineScope(Dispatchers.IO).launch {
            com.jk.mediaplayer.data.MediaScanner.loadPersistedMusic(this@JKApplication)
            VideoThumbnailCache.clearLegacyThumbnails(this@JKApplication)
            com.jk.mediaplayer.data.VideoMetadataManager.cleanLegacyCache(this@JKApplication)
            // The old eager artist prefetch marked failed lookups as "checked, no image" for the
            // whole library. Clear those markers once so on-demand fetching can retry them.
            val prefs = com.jk.mediaplayer.data.PreferencesManager(this@JKApplication)
            if (prefs.getArtistCacheVersion() < 2) {
                prefs.clearFailedArtistImages()
                prefs.setArtistCacheVersion(2)
            }
            // Pre-warm LibVLC native libraries and plugin/fontconfig environment off the main
            // thread so the first video playback after a fresh install starts immediately.
            try {
                val warmVlc = org.videolan.libvlc.LibVLC(
                    this@JKApplication,
                    arrayListOf("--no-stats", "--no-video-title-show")
                )
                warmVlc.release()
            } catch (_: Throwable) {
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoThumbnailInterceptor(this@JKApplication))
                add(AudioAlbumArtInterceptor(this@JKApplication))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("media_thumb_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }

    companion object {
        lateinit var instance: JKApplication
            private set
    }
}
