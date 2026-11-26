package com.jk.mediaplayer.data

import android.content.Context
import android.util.LruCache
import com.jk.mediaplayer.model.LyricsLine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

object OnlineMetadataFetcher {

    /** Upper bound on search queries one album lookup may cost. */
    private const val MAX_ALBUM_ART_QUERIES = 4

    // Memory caches
    private val albumArtMemoryCache = LruCache<String, String>(200)
    private val artistImageMemoryCache = LruCache<String, String>(200)
    private val lyricsMemoryCache = LruCache<String, List<LyricsLine>>(100)
    private val trackArtistMemoryCache = LruCache<String, String>(200)

    // Negative caches (avoid re-querying failed lookups in the same session)
    private val negativeAlbumArt = Collections.synchronizedSet(HashSet<String>())
    private val negativeArtist = Collections.synchronizedSet(HashSet<String>())
    private val negativeLyrics = Collections.synchronizedSet(HashSet<String>())
    private val negativeArtistNames = Collections.synchronizedSet(HashSet<String>())

    // Throttled concurrency: prevent network saturation & UI jitter during fast list scrolls
    private val artistFetchSemaphore = kotlinx.coroutines.sync.Semaphore(2)

    // Album artwork lookups: at most two in flight, and never two for the same album.
    private val albumArtFetchSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val inFlightAlbumArtFetches =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    private val albumArtFetchScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    fun clearMemoryCache() {
        albumArtMemoryCache.evictAll()
        artistImageMemoryCache.evictAll()
        lyricsMemoryCache.evictAll()
        trackArtistMemoryCache.evictAll()
        negativeAlbumArt.clear()
        negativeArtist.clear()
        negativeLyrics.clear()
        negativeArtistNames.clear()
    }

    fun getCachedArtistName(context: Context?, title: String): String? {
        val cleanT = cleanTrackTitle(title).lowercase()
        if (cleanT.isBlank()) return null
        trackArtistMemoryCache.get(cleanT)?.let { return it }
        if (negativeArtistNames.contains(cleanT)) return null
        if (context != null) {
            val prefs = PreferencesManager(context)
            val cached = prefs.getOnlineArtist(cleanT)
            if (cached != null) {
                trackArtistMemoryCache.put(cleanT, cached)
                return cached
            } else {
                negativeArtistNames.add(cleanT)
            }
        }
        return null
    }

    fun saveCachedArtistName(context: Context?, title: String, artistName: String) {
        val cleanT = cleanTrackTitle(title).lowercase()
        val cleanA = artistName.trim()
        if (cleanT.isBlank() || cleanA.isBlank() || cleanA.equals("Unknown Artist", true) || cleanA.equals("<unknown>", true)) return
        trackArtistMemoryCache.put(cleanT, cleanA)
        if (context != null) {
            PreferencesManager(context).saveOnlineArtist(cleanT, cleanA)
        }
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private fun sha256Hex(input: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val chars = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                chars[i * 2] = HEX_CHARS[b ushr 4]
                chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            String(chars)
        } catch (_: Exception) {
            (input.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        }
    }

    private fun getArtistCacheDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "artist_images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getLocalArtistFile(context: Context, cleanArtist: String): java.io.File {
        val safeName = "art_${sha256Hex(cleanArtist.lowercase().trim())}.jpg"
        return java.io.File(getArtistCacheDir(context), safeName)
    }

    private fun downloadImageToFile(imageUrl: String, targetFile: java.io.File): Boolean {
        var connection: java.net.HttpURLConnection? = null
        val tempFile = java.io.File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            val url = java.net.URL(imageUrl)
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; JKMediaPlayer/1.0)")
            }
            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (targetFile.exists()) targetFile.delete()
                    return tempFile.renameTo(targetFile)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
        return false
    }

    fun getCachedArtistImageUrl(context: Context?, artist: String): String? {
        val cleanArtist = cleanArtistName(artist)
        if (cleanArtist.isBlank()) return null

        artistImageMemoryCache.get(cleanArtist)?.let { return it }
        if (negativeArtist.contains(cleanArtist)) return null

        if (context != null) {
            val localFile = getLocalArtistFile(context, cleanArtist)
            if (localFile.exists() && localFile.length() > 0) {
                val path = localFile.absolutePath
                artistImageMemoryCache.put(cleanArtist, path)
                return path
            }
            val prefs = PreferencesManager(context)
            prefs.getArtistImageUrl(cleanArtist)?.let {
                artistImageMemoryCache.put(cleanArtist, it)
                return it
            }
            // A cache miss is not a negative result: do not poison the lookup, otherwise the
            // on-demand fetch below can never run.
        }
        return null
    }

    fun hasAlbumArtChecked(context: Context?, artist: String, album: String, title: String): Boolean {
        val key = buildAlbumArtCacheKey(artist, album, title)
        if (key.isBlank()) return true
        if (albumArtMemoryCache.get(key) != null) return true
        if (negativeAlbumArt.contains(key)) return true
        if (context != null) {
            val prefs = PreferencesManager(context)
            if (prefs.hasAlbumArtChecked(key)) return true
        }
        return false
    }

    fun isGenericAlbumName(album: String): Boolean {
        val lower = album.trim().lowercase()
        return lower.isBlank() ||
                lower == "<unknown>" ||
                lower == "unknown album" ||
                lower == "external audio" ||
                lower == "download" ||
                lower == "downloads" ||
                lower == "music" ||
                lower == "quick share" ||
                lower == "whatsapp audio" ||
                lower == "telegram audio" ||
                lower == "audio" ||
                lower == "bluetooth" ||
                lower == "seal" ||
                lower == "internal storage" ||
                lower == "sd card"
    }

    private fun getAlbumArtCacheDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "album_art")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLocalAlbumArtFile(context: Context, cacheKey: String): java.io.File {
        val safeName = cacheKey.replace(Regex("""[^\w\s\u0980-\u09FF\u0600-\u06FF\u4E00-\u9FFF]"""), "_").trim().take(40)
        val hash = sha256Hex(cacheKey.lowercase().trim()).take(16)
        return java.io.File(getAlbumArtCacheDir(context), "${safeName}_$hash.jpg")
    }

    /**
     * Artwork belongs to the album, not to the track. The library already groups albums by album
     * name, so keying on the album makes every surface agree: the shelf card, the song row, the
     * mini player and the full player resolve to the same picture.
     *
     * Generic album names (Unknown Album, Download, WhatsApp Audio, ...) carry no album identity,
     * so those fall back to artist + title and keep per-track artwork.
     */
    fun buildAlbumArtCacheKey(artist: String, album: String, title: String): String {
        val a = cleanArtistName(artist).lowercase()
        val alb = if (isGenericAlbumName(album)) "" else album.trim().lowercase()
        val t = cleanTrackTitle(title).lowercase()
        return when {
            alb.isNotBlank() -> "album_$alb"
            a.isNotBlank() && t.isNotBlank() -> "${a}_$t"
            t.isNotBlank() -> t
            a.isNotBlank() -> a
            else -> ""
        }
    }

    fun getCachedAlbumArtUrl(context: Context?, artist: String, album: String, title: String = ""): String? {
        val key = buildAlbumArtCacheKey(artist, album, title)
        if (key.isBlank()) return null

        // 1. Check in-memory LRU cache first (0ms instantaneous lookup)
        albumArtMemoryCache.get(key)?.let { return it }
        if (negativeAlbumArt.contains(key)) return null

        // 2. Check local disk cache if context available
        if (context != null) {
            val localFile = getLocalAlbumArtFile(context, key)
            if (localFile.exists() && localFile.length() > 0) {
                val path = localFile.absolutePath
                albumArtMemoryCache.put(key, path)
                return path
            }

            val prefs = PreferencesManager(context)
            if (prefs.hasAlbumArtChecked(key)) {
                val cached = prefs.getAlbumArtUrl(key)
                if (cached != null) {
                    albumArtMemoryCache.put(key, cached)
                    return cached
                } else {
                    negativeAlbumArt.add(key)
                    return null
                }
            }
        }
        return null
    }

    /**
     * Single entry point for artwork that may not be on disk yet: local cache first, then a
     * throttled, deduplicated network lookup. Every caller (image loader, player, service) uses
     * this so one album never ends up with two different pictures or two concurrent requests.
     */
    suspend fun resolveAlbumArt(
        context: Context?,
        artist: String,
        album: String,
        title: String = ""
    ): String? = withContext(Dispatchers.IO) {
        getCachedAlbumArtUrl(context, artist, album, title)?.let { return@withContext it }
        val key = buildAlbumArtCacheKey(artist, album, title)
        if (key.isBlank()) return@withContext null
        if (hasAlbumArtChecked(context, artist, album, title)) return@withContext null

        val fresh = albumArtFetchScope.async(start = CoroutineStart.LAZY) {
            try {
                albumArtFetchSemaphore.withPermit {
                    fetchAlbumArtUrl(context, artist, album, title)
                }
            } finally {
                inFlightAlbumArtFetches.remove(key)
            }
        }
        val existing = inFlightAlbumArtFetches.putIfAbsent(key, fresh)
        if (existing != null) {
            fresh.cancel()
            return@withContext try {
                existing.await()
            } catch (_: Exception) {
                null
            }
        }
        fresh.start()
        try {
            fresh.await()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchAlbumArtUrl(artist: String, album: String): String? {
        return fetchAlbumArtUrl(null, artist, album, "")
    }

    suspend fun fetchAlbumArtUrl(
        context: Context?,
        artist: String,
        album: String,
        title: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val cleanA = cleanArtistName(artist)
        val cleanT = cleanTrackTitle(title)
        val isGenericAlb = isGenericAlbumName(album)
        val cleanAlb = if (isGenericAlb) "" else album.trim()

        val cacheKey = buildAlbumArtCacheKey(cleanA, cleanAlb, cleanT)
        if (cacheKey.isBlank()) return@withContext null

        if (context != null) {
            val localFile = getLocalAlbumArtFile(context, cacheKey)
            if (localFile.exists() && localFile.length() > 0) {
                val path = localFile.absolutePath
                albumArtMemoryCache.put(cacheKey, path)
                return@withContext path
            }
        }

        albumArtMemoryCache.get(cacheKey)?.let { return@withContext it }

        if (context != null) {
            val prefs = PreferencesManager(context)
            val cached = prefs.getAlbumArtUrl(cacheKey)
            if (cached != null) {
                albumArtMemoryCache.put(cacheKey, cached)
                return@withContext cached
            }
        }

        if (negativeAlbumArt.contains(cacheKey)) {
            return@withContext null
        }

        // Generate candidate search queries in order of relevance. Album artwork is looked up by
        // the album first, because the cache key is album scoped.
        val queries = mutableListOf<String>()
        if (cleanA.isNotBlank() && cleanAlb.isNotBlank()) {
            queries.add("$cleanA $cleanAlb")
        }
        if (cleanAlb.isNotBlank()) {
            queries.add(cleanAlb)
        }
        if (cleanA.isNotBlank() && cleanT.isNotBlank()) {
            queries.add("$cleanA $cleanT")
        }
        if (cleanT.isNotBlank()) {
            queries.add(cleanT)
        }
        if (cleanA.isNotBlank()) {
            queries.add(cleanA)
        }

        // A lookup that never answers is not proof that no artwork exists, so a network failure is
        // reported out of band and never stored as a negative result.
        var networkFailure = false
        val onNetworkFailure = { networkFailure = true }
        var attempts = 0

        for (rawQ in queries.distinct()) {
            val q = rawQ.trim()
            if (q.isBlank()) continue
            // Bound the work one album can cost, so a shelf of unknown albums cannot flood the
            // public metadata APIs and trip their rate limits.
            if (attempts >= MAX_ALBUM_ART_QUERIES) break
            attempts++
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")

                // 1. iTunes Song Search (Best match for individual tracks)
                val songEndpoint = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=1"
                val songRes = executeHttpGet(songEndpoint, onNetworkFailure)
                if (songRes != null) {
                    val json = JSONObject(songRes)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        val fetchedArtist = item.optString("artistName").trim()
                        if (fetchedArtist.isNotBlank()) {
                            saveCachedArtistName(context, cleanT, fetchedArtist)
                        }
                        val art100 = item.optString("artworkUrl100")
                        if (art100.isNotBlank()) {
                            val hdArt = art100.replace("100x100bb", "600x600bb")
                            val artToSave = if (context != null) {
                                val localFile = getLocalAlbumArtFile(context, cacheKey)
                                if (downloadImageToFile(hdArt, localFile)) {
                                    localFile.absolutePath
                                } else hdArt
                            } else hdArt

                            albumArtMemoryCache.put(cacheKey, artToSave)
                            if (context != null) {
                                PreferencesManager(context).saveAlbumArtUrl(cacheKey, artToSave)
                            }
                            return@withContext artToSave
                        }
                    }
                }

                // 2. iTunes Album Search
                val albumEndpoint = "https://itunes.apple.com/search?term=$encoded&entity=album&limit=1"
                val albumRes = executeHttpGet(albumEndpoint, onNetworkFailure)
                if (albumRes != null) {
                    val json = JSONObject(albumRes)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        val fetchedArtist = item.optString("artistName").trim()
                        if (fetchedArtist.isNotBlank()) {
                            saveCachedArtistName(context, cleanT, fetchedArtist)
                        }
                        val art100 = item.optString("artworkUrl100")
                        if (art100.isNotBlank()) {
                            val hdArt = art100.replace("100x100bb", "600x600bb")
                            val artToSave = if (context != null) {
                                val localFile = getLocalAlbumArtFile(context, cacheKey)
                                if (downloadImageToFile(hdArt, localFile)) {
                                    localFile.absolutePath
                                } else hdArt
                            } else hdArt

                            albumArtMemoryCache.put(cacheKey, artToSave)
                            if (context != null) {
                                PreferencesManager(context).saveAlbumArtUrl(cacheKey, artToSave)
                            }
                            return@withContext artToSave
                        }
                    }
                }

                // 3. Deezer Track Search Fallback
                val deezerEndpoint = "https://api.deezer.com/search?q=$encoded&limit=1"
                val deezerRes = executeHttpGet(deezerEndpoint, onNetworkFailure)
                if (deezerRes != null) {
                    val json = JSONObject(deezerRes)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val item = data.getJSONObject(0)
                        val artistObj = item.optJSONObject("artist")
                        val deezerArtist = artistObj?.optString("name")?.trim()
                        if (!deezerArtist.isNullOrBlank()) {
                            saveCachedArtistName(context, cleanT, deezerArtist)
                        }
                        val albumObj = item.optJSONObject("album")
                        val pic = albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                            ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
                            ?: albumObj?.optString("cover_medium")?.takeIf { it.isNotBlank() }
                        if (pic != null && !pic.contains("/images/cover//")) {
                            val artToSave = if (context != null) {
                                val localFile = getLocalAlbumArtFile(context, cacheKey)
                                if (downloadImageToFile(pic, localFile)) {
                                    localFile.absolutePath
                                } else pic
                            } else pic

                            albumArtMemoryCache.put(cacheKey, artToSave)
                            if (context != null) {
                                PreferencesManager(context).saveAlbumArtUrl(cacheKey, artToSave)
                            }
                            return@withContext artToSave
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Cache negative lookup so we do not hammer network repeatedly. A lookup that failed for
        // network reasons stays retryable.
        if (!networkFailure) {
            negativeAlbumArt.add(cacheKey)
            if (context != null) {
                PreferencesManager(context).saveAlbumArtUrl(cacheKey, "NONE")
            }
        }
        null
    }

    suspend fun fetchArtistImageUrl(context: Context?, artist: String): String? = withContext(Dispatchers.IO) {
        val cleanArtist = cleanArtistName(artist)
        if (cleanArtist.isBlank() || cleanArtist.equals("<unknown>", true) || cleanArtist.equals("Unknown Artist", true)) {
            return@withContext null
        }

        // Check local disk file first
        if (context != null) {
            val localFile = getLocalArtistFile(context, cleanArtist)
            if (localFile.exists() && localFile.length() > 0) {
                val path = localFile.absolutePath
                artistImageMemoryCache.put(cleanArtist, path)
                return@withContext path
            }
        }

        // Check memory cache
        artistImageMemoryCache.get(cleanArtist)?.let { return@withContext it }

        // Persistent cache: a saved file path is authoritative. A bare "checked, no image"
        // marker is ignored so a previously failed lookup can succeed on a later run.
        if (context != null) {
            val cached = PreferencesManager(context).getArtistImageUrl(cleanArtist)
            if (cached != null) {
                artistImageMemoryCache.put(cleanArtist, cached)
                return@withContext cached
            }
        }

        if (negativeArtist.contains(cleanArtist)) {
            return@withContext null
        }

        // Throttle to max 2 concurrent network lookups to prevent UI stutter/jank
        artistFetchSemaphore.withPermit {
            // Re-check memory after waiting for semaphore
            if (context != null) {
                val localFile = getLocalArtistFile(context, cleanArtist)
                if (localFile.exists() && localFile.length() > 0) {
                    val path = localFile.absolutePath
                    artistImageMemoryCache.put(cleanArtist, path)
                    return@withPermit path
                }
            }
            artistImageMemoryCache.get(cleanArtist)?.let { return@withPermit it }
            if (negativeArtist.contains(cleanArtist)) return@withPermit null

            try {
                // 1. Deezer public artist search API
                val encoded = URLEncoder.encode(cleanArtist, "UTF-8")
                val url = "https://api.deezer.com/search/artist?q=$encoded&limit=1"
                val response = executeHttpGet(url)
                if (response != null) {
                    val json = JSONObject(response)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val item = data.getJSONObject(0)
                        val pic = item.optString("picture_xl").takeIf { it.isNotBlank() }
                            ?: item.optString("picture_big").takeIf { it.isNotBlank() }
                            ?: item.optString("picture_medium").takeIf { it.isNotBlank() }
                        if (pic != null && !pic.contains("/images/artist//")) {
                            if (context != null) {
                                val localFile = getLocalArtistFile(context, cleanArtist)
                                if (downloadImageToFile(pic, localFile)) {
                                    val path = localFile.absolutePath
                                    artistImageMemoryCache.put(cleanArtist, path)
                                    PreferencesManager(context).saveArtistImageUrl(cleanArtist, path)
                                    return@withPermit path
                                }
                            }
                            artistImageMemoryCache.put(cleanArtist, pic)
                            if (context != null) {
                                PreferencesManager(context).saveArtistImageUrl(cleanArtist, pic)
                            }
                            return@withPermit pic
                        }
                    }
                }

                // 2. Fallback: iTunes top album artwork for artist
                val itunesUrl = "https://itunes.apple.com/search?term=$encoded&entity=album&limit=1"
                val itunesRes = executeHttpGet(itunesUrl)
                if (itunesRes != null) {
                    val itunesJson = JSONObject(itunesRes)
                    val results = itunesJson.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        val art = item.optString("artworkUrl100")
                        if (art.isNotBlank()) {
                            val hdArt = art.replace("100x100bb", "600x600bb")
                            if (context != null) {
                                val localFile = getLocalArtistFile(context, cleanArtist)
                                if (downloadImageToFile(hdArt, localFile)) {
                                    val path = localFile.absolutePath
                                    artistImageMemoryCache.put(cleanArtist, path)
                                    PreferencesManager(context).saveArtistImageUrl(cleanArtist, path)
                                    return@withPermit path
                                }
                            }
                            artistImageMemoryCache.put(cleanArtist, hdArt)
                            if (context != null) {
                                PreferencesManager(context).saveArtistImageUrl(cleanArtist, hdArt)
                            }
                            return@withPermit hdArt
                        }
                    }
                }

                // If not found, persist "NONE" so we never re-query network again!
                negativeArtist.add(cleanArtist)
                if (context != null) {
                    PreferencesManager(context).saveArtistImageUrl(cleanArtist, "NONE")
                }
            } catch (e: Exception) {
                // Network failures must stay retryable: never persist a permanent "NONE" here.
            }
            null
        }
    }

    fun getCachedLyrics(context: Context?, artist: String, title: String): List<LyricsLine>? {
        val cacheKey = "${artist.trim().lowercase()}_${title.trim().lowercase()}"
        lyricsMemoryCache.get(cacheKey)?.let { return it }

        if (context != null) {
            val prefs = PreferencesManager(context)
            prefs.getLyrics(artist, title)?.let { raw ->
                val parsed = if (raw.contains("[")) parseLrcLyrics(raw) else parsePlainLyrics(raw)
                if (parsed.isNotEmpty()) {
                    lyricsMemoryCache.put(cacheKey, parsed)
                    return parsed
                }
            }
        }
        return null
    }

    suspend fun fetchLyrics(
        context: Context?,
        artist: String,
        title: String,
        durationMs: Long = 0L
    ): List<LyricsLine> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val cacheKey = "${artist.trim().lowercase()}_${title.trim().lowercase()}"

        // Memory cache check
        lyricsMemoryCache.get(cacheKey)?.let { return@withContext it }

        // Disk cache check
        if (context != null) {
            val prefs = PreferencesManager(context)
            prefs.getLyrics(artist, title)?.let { raw ->
                val parsed = if (raw.contains("[")) parseLrcLyrics(raw) else parsePlainLyrics(raw)
                if (parsed.isNotEmpty()) {
                    lyricsMemoryCache.put(cacheKey, parsed)
                    return@withContext parsed
                }
            }
        }

        if (negativeLyrics.contains(cacheKey)) {
            return@withContext emptyList()
        }

        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanChannelName(artist.replace("Unknown Artist", "").trim())

        try {
            // 1. Try LRCLIB for real-time synchronized LRC lyrics
            val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val lrclibUrl = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle"
            val response = executeHttpGet(lrclibUrl)
            if (response != null) {
                val json = JSONObject(response)
                val syncedLyrics = json.optString("syncedLyrics")
                if (syncedLyrics.isNotEmpty()) {
                    val parsed = parseLrcLyrics(syncedLyrics)
                    if (parsed.isNotEmpty()) {
                        lyricsMemoryCache.put(cacheKey, parsed)
                        if (context != null) {
                            PreferencesManager(context).saveLyrics(artist, title, syncedLyrics)
                        }
                        return@withContext parsed
                    }
                }
                val plainLyrics = json.optString("plainLyrics")
                if (plainLyrics.isNotEmpty()) {
                    val parsed = parsePlainLyrics(plainLyrics)
                    if (parsed.isNotEmpty()) {
                        lyricsMemoryCache.put(cacheKey, parsed)
                        if (context != null) {
                            PreferencesManager(context).saveLyrics(artist, title, plainLyrics)
                        }
                        return@withContext parsed
                    }
                }
            }

            // 1b. LRCLIB search with a duration match. YouTube Music titles and channel names
            // rarely match the catalogue exactly, so the exact lookup above often misses.
            if (cleanTitle.isNotEmpty()) {
                val searchUrl = "https://lrclib.net/api/search?q=" +
                    URLEncoder.encode("$cleanTitle $cleanArtist".trim(), "UTF-8")
                val searchResponse = executeHttpGet(searchUrl)
                if (searchResponse != null) {
                    val entries = JSONArray(searchResponse)
                    val best = (0 until entries.length())
                        .mapNotNull { entries.optJSONObject(it) }
                        .filter {
                            it.optString("syncedLyrics").isNotEmpty() ||
                                it.optString("plainLyrics").isNotEmpty()
                        }
                        .maxByOrNull { candidate ->
                            val candidateDuration = (candidate.optDouble("duration", 0.0) * 1000.0).toLong()
                            if (durationMs <= 0L || candidateDuration <= 0L) {
                                1L
                            } else {
                                (30_000L - kotlin.math.abs(candidateDuration - durationMs)).coerceAtLeast(0L)
                            }
                        }
                    if (best != null) {
                        val synced = best.optString("syncedLyrics")
                        val plain = best.optString("plainLyrics")
                        val parsed = if (synced.isNotEmpty()) parseLrcLyrics(synced) else parsePlainLyrics(plain)
                        if (parsed.isNotEmpty()) {
                            lyricsMemoryCache.put(cacheKey, parsed)
                            if (context != null) {
                                PreferencesManager(context)
                                    .saveLyrics(artist, title, synced.ifEmpty { plain })
                            }
                            return@withContext parsed
                        }
                    }
                }
            }

            // 2. Fallback to Lyrics.ovh
            if (cleanArtist.isNotEmpty()) {
                val ovhUrl = "https://api.lyrics.ovh/v1/$encodedArtist/$encodedTitle"
                val ovhResponse = executeHttpGet(ovhUrl)
                if (ovhResponse != null) {
                    val ovhJson = JSONObject(ovhResponse)
                    val lyricsText = ovhJson.optString("lyrics")
                    if (lyricsText.isNotEmpty()) {
                        val parsed = parsePlainLyrics(lyricsText)
                        if (parsed.isNotEmpty()) {
                            lyricsMemoryCache.put(cacheKey, parsed)
                            if (context != null) {
                                PreferencesManager(context).saveLyrics(artist, title, lyricsText)
                            }
                            return@withContext parsed
                        }
                    }
                }
            }

            negativeLyrics.add(cacheKey)
        } catch (e: Exception) {
            negativeLyrics.add(cacheKey)
        }
        emptyList()
    }

    private val artistSplitRegex = Regex("""\s*(?:,|&|/|;|\\|(?i:\b(?:feat\.?|ft\.?|featuring|with|vs\.?)\b))\s*""")

    fun cleanArtistName(artist: String): String {
        val first = artist.split(artistSplitRegex).firstOrNull() ?: artist
        return first.trim().trim('"', '\'', '(', ')', '[', ']', '{', '}')
    }

    /**
     * Turns a YouTube channel name into the artist a lyrics catalogue knows:
     * "Artist - Topic" and "ArtistVEVO" both become "Artist".
     */
    fun cleanChannelName(artist: String): String {
        var name = artist.trim()
        name = name.replace(Regex("(?i)\\s*-\\s*topic$"), "")
        name = name.replace(Regex("(?i)\\s*vevo$"), "")
        name = name.replace(Regex("(?i)\\s+(official|music|records|channel)$"), "")
        return cleanArtistName(name)
    }

    private fun parseLrcLyrics(lrc: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        val timestampRegex = Regex("\\[(\\d{1,2}):(\\d{2})[.:](\\d{2,3})\\]")
        lrc.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val matches = timestampRegex.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val text = trimmed.substring(matches.last().range.last + 1).trim()
                if (text.isNotEmpty()) {
                    matches.forEach { match ->
                        val (minStr, secStr, msStr) = match.destructured
                        val minutes = minStr.toLongOrNull() ?: 0L
                        val seconds = secStr.toLongOrNull() ?: 0L
                        val ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10 else msStr.toLongOrNull() ?: 0L
                        val totalMs = (minutes * 60 + seconds) * 1000 + ms
                        lines.add(LyricsLine(totalMs, text))
                    }
                }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun parsePlainLyrics(plain: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        var fakeTime = 0L
        plain.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                lines.add(LyricsLine(fakeTime, trimmed))
                fakeTime += 3500L
            }
        }
        return lines
    }

    fun cleanTrackTitle(title: String): String {
        return title
            .replace('_', ' ')
            .replace(Regex("(?i)\\.(mp3|m4a|flac|wav|ogg|opus|webm|aac|wma)"), "")
            .replace(Regex("(?i)\\[.*?\\]"), "")
            .replace(Regex("(?i)\\(.*?\\)"), "")
            .replace(Regex("(?i)\\b(256kbps|320kbps|128kbps|kbps|full video|video song|official video|official audio|full song|superhit|hindi song|bengali song|eid 2019|cover by|cover song|audio song|original audio)\\b"), "")
            .replace(Regex("(?i)feat\\..*"), "")
            .replace(Regex("(?i)ft\\..*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun executeHttpGet(urlString: String, onFailure: () -> Unit = {}): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; JKMediaPlayer/1.0)")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                response.toString()
            } else {
                onFailure()
                null
            }
        } catch (e: Exception) {
            onFailure()
            null
        } finally {
            connection?.disconnect()
        }
    }
}
