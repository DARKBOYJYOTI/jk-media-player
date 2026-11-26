package com.jk.mediaplayer.data

import android.content.Context
import android.net.Uri
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.data.potoken.PoTokenProviderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A song returned by YouTube Music search. */
data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String?
)

/** A resolved, playable audio URL together with the user agent that produced it. */
data class ResolvedStream(
    val url: String,
    val userAgent: String
)

/**
 * YouTube Music search and audio stream resolution.
 *
 * Every call runs off the main thread and fails soft: a network or extraction problem returns
 * null or an empty list so the caller can show a message instead of crashing the player.
 * Resolved URLs are cached for four hours because YouTube expires them.
 */
object YouTubeMusic {

    const val PATH_PREFIX = "ytm:"

    /** Ids start above any realistic MediaStore id so the queue can never confuse the two. */
    private const val REMOTE_ID_BASE = 2_000_000_000L
    private const val REMOTE_ID_MASK = 0x1FFFFFFFL
    private const val STREAM_CACHE_TTL_MS = 4L * 60L * 60L * 1000L
    private const val MAX_STREAM_CANDIDATES = 4

    /**
     * googlevideo rejects requests whose user agent does not match the client that produced the
     * URL. Extraction runs through the web client, so the stream is fetched with a web agent.
     */
    private const val STREAM_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val initialized = AtomicBoolean(false)
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    private data class CachedStream(val stream: ResolvedStream, val expiresAt: Long)

    private data class Candidate(val url: String, val label: String)

    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) {
        ensureInitialized()
    }

    private fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            NewPipe.init(
                YouTubeDownloader.Builder().build(),
                Localization("en"),
                ContentCountry("US")
            )
            // Many videos expose no playable audio without a BotGuard token; the provider mints
            // one on demand and returns null when the device WebView cannot do it.
            YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)
        } catch (_: Throwable) {
            // Allow a later call to retry instead of leaving the app permanently uninitialised.
            initialized.set(false)
        }
    }

    /** Deterministic queue id for a YouTube video. */
    fun remoteTrackId(videoId: String): Long =
        REMOTE_ID_BASE + (videoId.hashCode().toLong() and REMOTE_ID_MASK)

    /** Video id carried by a remote track path, or null for local files. */
    fun videoIdOf(path: String): String? {
        if (!path.startsWith(PATH_PREFIX)) return null
        return path.removePrefix(PATH_PREFIX).takeIf { it.isNotBlank() }
    }

    /** Search YouTube Music songs. Returns an empty list on any failure. */
    suspend fun search(query: String, limit: Int = 30): List<YouTubeTrack> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            try {
                ensureInitialized()
                val service = ServiceList.YouTube
                val handler = service.searchQHFactory.fromQuery(
                    trimmed,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    ""
                )
                val extractor = service.getSearchExtractor(handler)
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    // One malformed row must not sink the whole result page.
                    .mapNotNull { item -> runCatching { item.toTrackOrNull() }.getOrNull() }
                    .take(limit)
            } catch (t: Throwable) {
                emptyList()
            }
        }

    /**
     * Resolve a playable audio URL for a video id. Returns null on any failure.
     *
     * [skipUrls] is used by playback failover: a URL that reaches the player but never starts is
     * passed back here so the next best candidate is tried instead of skipping the whole song.
     */
    suspend fun resolveAudio(
        videoId: String,
        skipUrls: Set<String> = emptySet()
    ): ResolvedStream? =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank()) return@withContext null
            val now = System.currentTimeMillis()
            if (skipUrls.isEmpty()) {
                streamCache[videoId]?.let { cached ->
                    if (cached.expiresAt > now) return@withContext cached.stream
                    streamCache.remove(videoId)
                }
            }
            try {
                ensureInitialized()
                val resolveStart = System.currentTimeMillis()
                val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance()
                    .fromUrl("https://www.youtube.com/watch?v=$videoId")
                val extractor = YoutubeStreamExtractor(
                    ServiceList.YouTube,
                    linkHandler
                )
                extractor.fetchPage()
                val audioStreams = extractor.audioStreams.filter { it.isUrl && it.content.isNotBlank() }
                // Progressive HTTP answers a plain range request, which LibVLC opens far more
                // reliably than a fragmented DASH track. Playback stalls on the highest bitrate
                // DASH entry were exactly the songs that needed several play/pause taps.
                val ordered = audioStreams.sortedWith(
                    compareBy<org.schabi.newpipe.extractor.stream.AudioStream> {
                        if (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) 0 else 1
                    }.thenByDescending { it.averageBitrate }
                )
                val candidates = buildList {
                    ordered.forEach {
                        add(Candidate(it.content, "${it.deliveryMethod}:${it.averageBitrate}kbps"))
                    }
                    extractor.hlsUrl?.takeIf { it.isNotBlank() }?.let { add(Candidate(it, "HLS")) }
                }.distinctBy { it.url }
                if (candidates.isEmpty()) {
                    return@withContext null
                }
                // YouTube hands out URLs that can answer 403 for a client mismatch, and some
                // videos only work on a lower bitrate format. Probe until one really responds.
                for (candidate in candidates.take(MAX_STREAM_CANDIDATES)) {
                    if (candidate.url in skipUrls) continue
                    if (isReachable(candidate.url)) {
                        // Play the URL with the user agent the extractor used: googlevideo ties
                        // the signature to it, and a mismatch is throttled or rejected.
                        val userAgent = YouTubeDownloader.rememberedUserAgent() ?: STREAM_USER_AGENT
                        val resolved = ResolvedStream(candidate.url, userAgent)
                        if (skipUrls.isEmpty()) {
                            streamCache[videoId] = CachedStream(resolved, now + STREAM_CACHE_TTL_MS)
                        }
                        return@withContext resolved
                    }
                }
                null
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * Cheap pre-flight so an unplayable URL never reaches the player as a silent failure.
     * A HEAD is enough for googlevideo; servers that reject HEAD are retried with a one byte GET.
     */
    private fun isReachable(url: String): Boolean {
        return try {
            val head = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", STREAM_USER_AGENT)
                .head()
                .build()
            PROBE_CLIENT.newCall(head).execute().use { response ->
                if (response.isSuccessful) return true
                if (response.code != 405 && response.code != 403) return false
            }
            val probe = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", STREAM_USER_AGENT)
                .header("Range", "bytes=0-0")
                .build()
            PROBE_CLIENT.newCall(probe).execute().use { response ->
                response.isSuccessful || response.code == 206
            }
        } catch (t: Throwable) {
            false
        }
    }

    private val PROBE_CLIENT: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Queue path for a YouTube video. Remote entries are recognised by this prefix. */
    fun remotePath(videoId: String): String = PATH_PREFIX + videoId

    /** Build the queue entry for a search result. */
    fun toAudioItem(track: YouTubeTrack): AudioItem {
        val remotePath = remotePath(track.videoId)
        return AudioItem(
            id = remoteTrackId(track.videoId),
            title = track.title,
            artist = track.artist,
            album = "YouTube Music",
            duration = track.durationMs,
            path = remotePath,
            uri = Uri.parse(remotePath),
            onlineArtUrl = track.thumbnailUrl,
            folderName = "YouTube Music"
        )
    }

    fun clearCache() {
        streamCache.clear()
    }

    /** Cover URL that always exists for a video id, used when the search result has none. */
    fun thumbnailFallback(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    /**
     * Artwork for a queue entry that arrived without a URL. The path carries the video id, so a
     * playable thumbnail can always be rebuilt. Local files return null.
     */
    fun artworkFallbackFor(path: String): String? =
        videoIdOf(path)?.let(::thumbnailFallback)

    /**
     * YouTube Music serves square cover art from the googleusercontent CDN with the size baked
     * into the URL (`=w60-h60-l90-rj`, older `=s120-c`). Search results often carry the 60px
     * variant, which looks blurry once it fills the player. Rewriting the size parameter asks the
     * same CDN image for a sharp 544px square. Other hosts are returned unchanged.
     */
    fun upgradeArtwork(url: String): String {
        if (url.isBlank()) return url
        if (!url.contains("googleusercontent.com") && !url.contains("ggpht.com")) return url
        val resized = THUMB_SIZE_PARAM.replace(url, "=w544-h544")
        if (resized != url) return resized
        return THUMB_SIZE_PATH.replace(url, "/w544-h544")
    }

    private val THUMB_SIZE_PARAM = Regex("=[sw]\\d+(?:-h\\d+)?")
    private val THUMB_SIZE_PATH = Regex("/[sw]\\d+-h\\d+")

    private fun StreamInfoItem.toTrackOrNull(): YouTubeTrack? {
        // A live broadcast has no fixed duration or seekable stream; keep it out of a music list.
        val type = runCatching { streamType }.getOrNull()
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) return null
        val id = VIDEO_ID_PATTERN.find(url)?.groupValues?.getOrNull(1) ?: return null
        val name = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val seconds = runCatching { duration }.getOrDefault(0L).coerceAtLeast(0L)
        val artist = runCatching { uploaderName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Unknown artist"
        // A thumbnail failure must not drop the song from the results.
        val thumbnail = runCatching { thumbnails }.getOrNull()
            .orEmpty()
            .let(::bestArtwork)
            ?: thumbnailFallback(id)
        return YouTubeTrack(
            videoId = id,
            title = name,
            artist = artist,
            durationMs = seconds * 1000L,
            thumbnailUrl = thumbnail
        )
    }

    /**
     * Chooses the sharpest available cover. YouTube Music returns square images, so the largest
     * area wins; when the sizes are unknown the last entry is used, which is the ascending list's
     * biggest item. The chosen URL is then upgraded to a large square.
     */
    private fun bestArtwork(images: List<org.schabi.newpipe.extractor.Image>): String? {
        val usable = images.filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return null
        val sized = usable.filter { it.height > 0 && it.width > 0 }
        val chosen = if (sized.isNotEmpty()) {
            sized.maxByOrNull { it.height.toLong() * it.width.toLong() }
        } else {
            usable.lastOrNull()
        }
        return chosen?.url?.let(::upgradeArtwork)
    }

    private val VIDEO_ID_PATTERN = Regex("[?&]v=([A-Za-z0-9_-]{11})")
}
