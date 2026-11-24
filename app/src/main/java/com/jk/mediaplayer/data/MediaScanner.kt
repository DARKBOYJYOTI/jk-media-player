package com.jk.mediaplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.model.VideoFolder
import com.jk.mediaplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaScanner {

    @Volatile
    var cachedMusic: List<AudioItem>? = null

    @Volatile
    var cachedVideoFolders: List<VideoFolder>? = null

    private const val MUSIC_CACHE_FILE = "music_library_cache.json"

    /** Restores the last scanned library so the first frame shows content instead of a spinner. */
    fun loadPersistedMusic(context: Context): List<AudioItem>? {
        val file = File(context.filesDir, MUSIC_CACHE_FILE)
        if (!file.exists()) return null
        return try {
            val array = org.json.JSONArray(file.readText())
            val list = ArrayList<AudioItem>(array.length())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                list.add(
                    AudioItem(
                        id = item.optLong("id"),
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        duration = item.optLong("duration"),
                        path = item.optString("path"),
                        uri = Uri.parse(item.optString("uri")),
                        albumArtUri = item.optString("albumArtUri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                        onlineArtUrl = item.optString("onlineArtUrl").takeIf { it.isNotBlank() },
                        folderName = item.optString("folderName"),
                        folderPath = item.optString("folderPath"),
                        formatBadge = item.optString("formatBadge", "Hi-Res Audio"),
                        isFavorite = item.optBoolean("isFavorite"),
                        dateAdded = item.optLong("dateAdded")
                    )
                )
            }
            cachedMusic = list
            list
        } catch (_: Exception) {
            null
        }
    }

    private fun persistMusic(context: Context, tracks: List<AudioItem>) {
        try {
            val array = org.json.JSONArray()
            tracks.forEach { track ->
                array.put(
                    org.json.JSONObject().apply {
                        put("id", track.id)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("album", track.album)
                        put("duration", track.duration)
                        put("path", track.path)
                        put("uri", track.uri.toString())
                        put("albumArtUri", track.albumArtUri?.toString() ?: "")
                        put("onlineArtUrl", track.onlineArtUrl ?: "")
                        put("folderName", track.folderName)
                        put("folderPath", track.folderPath)
                        put("formatBadge", track.formatBadge)
                        put("isFavorite", track.isFavorite)
                        put("dateAdded", track.dateAdded)
                    }
                )
            }
            File(context.filesDir, MUSIC_CACHE_FILE).writeText(array.toString())
        } catch (_: Exception) {
        }
    }

    private val noMediaCache = HashMap<String, Boolean>()

    private fun isExcludedOrNoMedia(path: String): Boolean {
        if (path.isBlank()) return true
        val file = File(path)
        var current: File? = file.parentFile
        while (current != null && current.path != "/" && current.path.isNotEmpty()) {
            val dirPath = current.absolutePath
            val cached = noMediaCache[dirPath]
            if (cached != null) {
                if (cached) return true
            } else {
                val isHidden = current.name.startsWith(".")
                val hasNoMedia = isHidden || try {
                    File(current, ".nomedia").exists()
                } catch (_: Exception) {
                    false
                }
                noMediaCache[dirPath] = hasNoMedia
                if (hasNoMedia) return true
            }
            current = current.parentFile
        }
        return false
    }

    suspend fun scanVideos(context: Context): List<VideoFolder> = withContext(Dispatchers.IO) {
        noMediaCache.clear()
        val videosList = mutableListOf<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.ORIENTATION else MediaStore.Video.Media._ID,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.RESOLUTION else MediaStore.Video.Media._ID
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val orientCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)
                } else {
                    cursor.getColumnIndex("orientation")
                }
                val resCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION)
                } else -1

                val prefs = PreferencesManager(context)
                val blacklistedFolders = prefs.getVideoBlacklistedFolders()
                val blacklistedFiles = prefs.getVideoBlacklistedFiles()

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol) ?: ""
                    if (path.isEmpty() || isExcludedOrNoMedia(path)) {
                        continue
                    }
                    if (PreferencesManager.isPathBlacklisted(path, blacklistedFolders, blacklistedFiles)) {
                        continue
                    }
                    val rawTitle = cursor.getString(titleCol)
                    val dispName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val fileName = if (path.isNotEmpty()) File(path).name else null
                    val title = when {
                        !dispName.isNullOrBlank() -> dispName
                        !fileName.isNullOrBlank() -> fileName
                        !rawTitle.isNullOrBlank() -> rawTitle
                        else -> "Video $id"
                    }
                    val duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    val dateModified = cursor.getLong(dateCol)
                    val folderName = if (bucketCol != -1 && !cursor.getString(bucketCol).isNullOrEmpty()) {
                        cursor.getString(bucketCol)
                    } else {
                        File(path).parentFile?.name ?: "Internal Storage"
                    }
                    val videoWidth = if (widthCol != -1) cursor.getInt(widthCol) else 0
                    val videoHeight = if (heightCol != -1) cursor.getInt(heightCol) else 0
                    val orientation = if (orientCol != -1) cursor.getInt(orientCol) else 0
                    val isRotated = (orientation == 90 || orientation == 270)
                    val effWidth = if (isRotated) videoHeight else videoWidth
                    val effHeight = if (isRotated) videoWidth else videoHeight

                    var resolution = if (resCol != -1) cursor.getString(resCol) ?: "" else ""
                    if (resolution.isBlank() && effWidth > 0 && effHeight > 0) {
                        resolution = "${effWidth}x${effHeight}"
                    }
                    val isPortrait = (effHeight > effWidth && effWidth > 0)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    val resumePosition = prefs.getVideoResumePosition(path)
                    val isNew = prefs.isVideoNew(path, dateModified)

                    videosList.add(
                        VideoItem(
                            id = id,
                            title = title,
                            path = path,
                            uri = contentUri,
                            duration = duration,
                            size = size,
                            dateModified = dateModified,
                            folderName = folderName,
                            resolution = resolution,
                            resumePosition = resumePosition,
                            isNew = isNew,
                            isPortrait = isPortrait
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }



        // Group into directory-based video folders by real parent path, so two folders with the
        // same display name on different volumes stay separate.
        val grouped = videosList.groupBy { video ->
            try {
                File(video.path).parent ?: video.folderName
            } catch (_: Exception) {
                video.folderName
            }
        }
        val result = grouped.map { (folderPath, videos) ->
            val folderName = videos.firstOrNull()?.folderName
                ?: File(folderPath).name
                ?: folderPath
            VideoFolder(
                name = folderName,
                path = folderPath,
                videoCount = videos.size,
                videos = videos.sortedBy { it.title.lowercase() }
            )
        }.sortedBy { it.name.lowercase() }
        cachedVideoFolders = result
        result
    }

    suspend fun scanMusic(context: Context): List<AudioItem> = withContext(Dispatchers.IO) {
        noMediaCache.clear()
        val audioList = mutableListOf<AudioItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val audiobookClause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            " OR ${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0"
        } else {
            ""
        }
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_RINGTONE} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0$audiobookClause)"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val videoExtensions = setOf(
            "mp4", "mkv", "avi", "flv", "mov", "wmv", "3gp", "webm", "ts", "m4v", "mpeg", "mpg",
            "vob", "ogv", "m2ts", "mts", "divx", "rmvb", "asf", "3g2", "m2v", "f4v"
        )
        val validAudioExtensions = setOf(
            "mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma", "alac",
            "dsd", "dff", "dsf", "ape", "aiff", "mid", "midi", "amr", "mka", "wv", "ac3", "dts", "eac3"
        )

        val prefs = PreferencesManager(context)
        val filterShort = prefs.isFilterShortAudio()
        val minDuration = if (filterShort) 30000L else 5000L
        val blacklistedFolders = prefs.getMusicBlacklistedFolders()
        val blacklistedFiles = prefs.getMusicBlacklistedFiles()

        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    val rawArtist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val artist = sanitizeArtistName(rawArtist)
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val duration = cursor.getLong(durationCol)
                    val path = cursor.getString(dataCol) ?: ""
                    if (path.isEmpty() || isExcludedOrNoMedia(path)) {
                        continue
                    }
                    if (PreferencesManager.isPathBlacklisted(path, blacklistedFolders, blacklistedFiles)) {
                        continue
                    }
                    val albumId = cursor.getLong(albumIdCol)

                    val ext = File(path).extension.lowercase()
                    // Strict audio check: Exclude any video file and require valid audio extension
                    if (ext in videoExtensions || ext !in validAudioExtensions) {
                        continue
                    }

                    // Exclude notification sounds and audio under threshold (default 30 seconds), unless created by JK Ringtone Cutter
                    val isJkRingtone = path.contains("jkmediaplayer", ignoreCase = true) || title.contains("(ringtone)", ignoreCase = true)
                    if (!isJkRingtone && duration < minDuration) {
                        continue
                    }

                    // Album Art Uri (falls back to AudioAlbumArtCache embedded ID3 resolver)
                    val albumArtUri = if (albumId > 0) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        )
                    } else null
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    val dateAdded = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else (try { File(path).lastModified() / 1000L } catch (e: Exception) { 0L })
                    val folderPath = try { File(path).parent ?: "" } catch (e: Exception) { "" }
                    val folderName = try { File(path).parentFile?.name ?: "Music" } catch (e: Exception) { "Music" }
                    audioList.add(
                        AudioItem(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            uri = contentUri,
                            albumArtUri = albumArtUri,
                            folderName = folderName,
                            folderPath = folderPath,
                            formatBadge = "",
                            dateAdded = dateAdded
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cachedMusic = audioList
        persistMusic(context, audioList)
        audioList
    }

    private fun sanitizeArtistName(raw: String): String {
        if (raw.isBlank() || raw.equals("<unknown>", ignoreCase = true)) return "Unknown Artist"
        return raw
            .replace('\uFF0C', ',')
            .replace('\u3001', ',')
            .replace('\uFE50', ',')
            .replace('\u3000', ' ')
            .replace('\u00A0', ' ')
            .replace(Regex("""\s*,\s*"""), ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Unknown Artist" }
    }
}
