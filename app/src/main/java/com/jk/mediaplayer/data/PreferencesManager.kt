package com.jk.mediaplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.jk.mediaplayer.model.CustomPlaylist

enum class VideoSortField {
    NAME, DATE, SIZE
}

class PreferencesManager(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("jk_player_prefs", Context.MODE_PRIVATE)

    companion object {
        var watchedVersion by mutableIntStateOf(0)
        var playlistVersion by mutableIntStateOf(0)
        var musicVolumeBoostVersion by mutableIntStateOf(0)
        var settingsVersion by mutableIntStateOf(0)
        var videoQualityVersion by mutableIntStateOf(0)
        var blacklistVersion by mutableIntStateOf(0)
        private val isNewCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        @SuppressLint("SdCardPath")
        fun normalizePath(path: String): String {
            if (path.isBlank()) return ""
            var p = path.trim().replace('\\', '/')
            while (p.contains("//")) {
                p = p.replace("//", "/")
            }
            if (p.endsWith("/") && p.length > 1) {
                p = p.substring(0, p.length - 1)
            }
            val primaryAliases = listOf(
                "/data/media/0",
                "/storage/self/primary",
                "/storage/emulated/legacy",
                "/mnt/sdcard",
                "/sdcard"
            )
            for (alias in primaryAliases) {
                if (p.equals(alias, ignoreCase = true)) {
                    p = "/storage/emulated/0"
                    break
                } else if (p.startsWith("$alias/", ignoreCase = true)) {
                    p = "/storage/emulated/0" + p.substring(alias.length)
                    break
                }
            }
            return p
        }

        fun isPathInsideFolder(filePath: String, folderPath: String): Boolean {
            if (filePath.isBlank() || folderPath.isBlank()) return false
            val normFile = normalizePath(filePath)
            val normFolder = normalizePath(folderPath)
            if (normFile.equals(normFolder, ignoreCase = true)) return true
            val prefix = if (normFolder.endsWith("/")) normFolder else "$normFolder/"
            if (normFile.startsWith(prefix, ignoreCase = true)) return true

            // Raw paths fallback
            val rawFile = filePath.replace('\\', '/').trimEnd('/')
            val rawFolder = folderPath.replace('\\', '/').trimEnd('/')
            if (rawFile.equals(rawFolder, ignoreCase = true)) return true
            if (rawFile.startsWith("$rawFolder/", ignoreCase = true)) return true

            return false
        }

        fun isFileBlacklisted(filePath: String, blacklistedFiles: Set<String>): Boolean {
            if (filePath.isBlank() || blacklistedFiles.isEmpty()) return false
            val normFile = normalizePath(filePath)
            val rawFile = filePath.replace('\\', '/').trimEnd('/')
            for (bf in blacklistedFiles) {
                if (bf.isBlank()) continue
                if (rawFile.equals(bf, ignoreCase = true)) return true
                if (normFile.equals(normalizePath(bf), ignoreCase = true)) return true
            }
            try {
                val cFile = normalizePath(java.io.File(filePath).canonicalPath)
                for (bf in blacklistedFiles) {
                    val cBf = normalizePath(java.io.File(bf).canonicalPath)
                    if (cFile.equals(cBf, ignoreCase = true)) return true
                }
            } catch (_: Exception) {}
            return false
        }

        fun isPathBlacklisted(filePath: String, blacklistedFolders: Set<String>, blacklistedFiles: Set<String>): Boolean {
            if (filePath.isBlank()) return false
            if (isFileBlacklisted(filePath, blacklistedFiles)) return true
            for (bf in blacklistedFolders) {
                if (isPathInsideFolder(filePath, bf)) return true
            }
            return false
        }
    }

    fun saveVideoResumePosition(path: String, positionMs: Long) {
        prefs.edit()
            .putLong("resume_$path", positionMs)
            .putBoolean("watched_$path", true)
            .apply()
        isNewCache[path] = false
        watchedVersion++
    }

    fun markVideoWatched(path: String) {
        if (path.isNotBlank()) {
            prefs.edit().putBoolean("watched_$path", true).apply()
            isNewCache[path] = false
            watchedVersion++
        }
    }

    fun getVideoResumePosition(path: String): Long {
        return prefs.getLong("resume_$path", 0L)
    }

    fun isVideoNew(path: String, dateModified: Long): Boolean {
        if (path.isBlank()) return false
        // 1. Fast path: check age first. If older than 14 days (95%+ of library), return false instantly with 0 disk/SharedPreferences queries!
        val twoWeeksAgo = System.currentTimeMillis() / 1000 - (14 * 24 * 60 * 60)
        if (dateModified <= twoWeeksAgo) return false

        // 2. In-memory cache hit: 0 latency
        isNewCache[path]?.let { return it }

        // 3. Fallback: check SharedPreferences once and cache in RAM
        val watched = prefs.getBoolean("watched_$path", false)
        if (watched) {
            isNewCache[path] = false
            return false
        }
        val resume = prefs.getLong("resume_$path", 0L)
        if (resume > 0L) {
            isNewCache[path] = false
            return false
        }
        isNewCache[path] = true
        return true
    }



    fun saveVolumeBoostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vol_boost_enabled", enabled).apply()
    }

    fun isVolumeBoostEnabled(): Boolean {
        return prefs.getBoolean("vol_boost_enabled", true)
    }

    fun getThemeMode(): String {
        return prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
    }

    fun saveMusicThemeMode(mode: String) {
        prefs.edit().putString("theme_mode_music", mode).apply()
    }

    fun getMusicThemeMode(): String {
        return prefs.getString("theme_mode_music", null) ?: getThemeMode()
    }

    fun saveVideoThemeMode(mode: String) {
        prefs.edit().putString("theme_mode_video", mode).apply()
    }

    fun getVideoThemeMode(): String {
        return prefs.getString("theme_mode_video", null) ?: getThemeMode()
    }

    fun isUseSystemFont(): Boolean {
        return prefs.getBoolean("pref_use_system_font", false)
    }

    fun setUseSystemFont(use: Boolean) {
        prefs.edit().putBoolean("pref_use_system_font", use).apply()
        settingsVersion++
    }

    // Last played video path for Resume FAB
    fun saveLastPlayedVideoPath(path: String) {
        prefs.edit().putString("last_played_video_path", path).apply()
    }

    fun getLastPlayedVideoPath(): String? {
        return prefs.getString("last_played_video_path", null)
    }

    // Per-video or per-folder aspect ratio mode (Fit, Stretch, Zoom)
    fun saveVideoResizeMode(path: String, mode: String) {
        prefs.edit().putString("resize_mode_$path", mode).apply()
    }

    fun getVideoResizeMode(path: String): String? {
        return prefs.getString("resize_mode_$path", null)
    }

    fun saveDefaultResizeMode(mode: String) {
        prefs.edit().putString("default_video_resize_mode", mode).apply()
    }

    fun getDefaultResizeMode(): String? {
        return prefs.getString("default_video_resize_mode", null)
    }

    // Video Player Persistent Brightness & Volume
    fun saveVideoBrightness(brightness: Float) {
        prefs.edit().putFloat("video_brightness", brightness).apply()
    }

    fun getVideoBrightness(): Float {
        return prefs.getFloat("video_brightness", -1f)
    }

    enum class AudioOutputRoute {
        SPEAKER,
        HEADPHONES,
        BLUETOOTH
    }

    fun saveVideoVolume(volume: Int) {
        prefs.edit().putInt("video_volume", volume).apply()
    }

    fun getVideoVolume(): Int {
        return prefs.getInt("video_volume", -1)
    }

    fun saveVideoVolumeForRoute(route: AudioOutputRoute, volume: Int) {
        prefs.edit()
            .putInt("video_volume_${route.name}", volume)
            .putInt("video_volume", volume)
            .apply()
    }

    fun getVideoVolumeForRoute(route: AudioOutputRoute): Int {
        val key = "video_volume_${route.name}"
        if (prefs.contains(key)) {
            return prefs.getInt(key, -1).coerceIn(0, 200)
        }
        // Safe defaults when no volume has been explicitly recorded yet
        return when (route) {
            AudioOutputRoute.SPEAKER -> prefs.getInt("video_volume", 80).coerceIn(0, 200)
            AudioOutputRoute.HEADPHONES -> 60 // Safe listening default for initial connection
            AudioOutputRoute.BLUETOOTH -> 65  // Safe listening default for initial connection
        }
    }

    // 12-Hour vs 24-Hour Clock Format for Video Player HUD


    // Filter short audio under 30 seconds (Default: Enabled)
    fun isFilterShortAudio(): Boolean {
        return prefs.getBoolean("filter_short_audio", true)
    }

    fun setFilterShortAudio(enabled: Boolean) {
        prefs.edit().putBoolean("filter_short_audio", enabled).apply()
    }

    // Album art persistent cache
    fun saveAlbumArtUrl(cacheKey: String, url: String) {
        val key = "album_art_" + cacheKey.trim().lowercase().hashCode()
        prefs.edit().putString(key, url).apply()
    }

    fun hasAlbumArtChecked(cacheKey: String): Boolean {
        val key = "album_art_" + cacheKey.trim().lowercase().hashCode()
        return prefs.contains(key)
    }

    fun getAlbumArtUrl(cacheKey: String): String? {
        val key = "album_art_" + cacheKey.trim().lowercase().hashCode()
        val res = prefs.getString(key, null)
        return if (res == "NONE" || res.isNullOrBlank()) null else res
    }

    // Online Track Artist persistent cache
    fun saveOnlineArtist(trackTitleKey: String, artist: String) {
        val key = "online_artist_" + trackTitleKey.trim().lowercase().hashCode()
        prefs.edit().putString(key, artist).apply()
    }


    fun getOnlineArtist(trackTitleKey: String): String? {
        val key = "online_artist_" + trackTitleKey.trim().lowercase().hashCode()
        val res = prefs.getString(key, null)
        return if (res == "NONE" || res.isNullOrBlank()) null else res
    }

    // Artist image persistent cache
    fun saveArtistImageUrl(artist: String, url: String) {
        val key = "artist_art_" + artist.trim().lowercase()
        prefs.edit().putString(key, url).apply()
    }

    fun getArtistImageUrl(artist: String): String? {
        val key = "artist_art_" + artist.trim().lowercase()
        val res = prefs.getString(key, null)
        return if (res == "NONE" || res.isNullOrBlank()) null else res
    }

    /** Bumped when the negative artist-image cache must be retried once. */
    fun getArtistCacheVersion(): Int = prefs.getInt("artist_art_version", 0)

    fun setArtistCacheVersion(version: Int) {
        prefs.edit().putInt("artist_art_version", version).apply()
    }

    /** Drops only the "checked, no image" markers; downloaded artist files are kept. */
    fun clearFailedArtistImages() {
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("artist_art_") && value == "NONE") editor.remove(key)
        }
        editor.apply()
    }

    // Lyrics persistent cache (disk backed so SharedPreferences stays small)
    private fun lyricsFile(artist: String, title: String): java.io.File {
        val key = (artist.trim().lowercase() + "_" + title.trim().lowercase()).hashCode()
        return java.io.File(java.io.File(appContext.filesDir, "lyrics"), "$key.lrc")
    }

    fun saveLyrics(artist: String, title: String, rawLyrics: String) {
        try {
            val file = lyricsFile(artist, title)
            file.parentFile?.mkdirs()
            file.writeText(rawLyrics)
        } catch (_: Exception) {
            // Fall back to the preference store if the disk write fails.
            val key = "lyrics_" + (artist.trim().lowercase() + "_" + title.trim().lowercase()).hashCode()
            prefs.edit().putString(key, rawLyrics).apply()
        }
    }

    fun getLyrics(artist: String, title: String): String? {
        val key = "lyrics_" + (artist.trim().lowercase() + "_" + title.trim().lowercase()).hashCode()
        return try {
            val file = lyricsFile(artist, title)
            if (file.exists()) {
                file.readText()
            } else {
                // Migrate a value written by an older build.
                prefs.getString(key, null)?.also { legacy ->
                    try {
                        file.parentFile?.mkdirs()
                        file.writeText(legacy)
                        prefs.edit().remove(key).apply()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            prefs.getString(key, null)
        }
    }

    // Liked / Favorite tracks persistent storage
    fun toggleFavorite(trackId: Long): Boolean {
        val current = getFavorites().toMutableSet()
        val idStr = trackId.toString()
        val isFav = if (current.contains(idStr)) {
            current.remove(idStr)
            false
        } else {
            current.add(idStr)
            true
        }
        prefs.edit().putStringSet("favorite_tracks", current).apply()
        playlistVersion++
        return isFav
    }

    fun isFavorite(trackId: Long): Boolean {
        return getFavorites().contains(trackId.toString())
    }

    fun getFavorites(): Set<String> {
        return prefs.getStringSet("favorite_tracks", emptySet()) ?: emptySet()
    }

    fun isShowPlayerVolumeBar(): Boolean {
        return prefs.getBoolean("show_player_volume_bar", true)
    }

    fun setShowPlayerVolumeBar(show: Boolean) {
        prefs.edit().putBoolean("show_player_volume_bar", show).apply()
        settingsVersion++
    }

    fun isMusicVolumeBoostEnabled(): Boolean {
        return prefs.getBoolean("music_volume_boost_enabled", false)
    }

    fun setMusicVolumeBoostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("music_volume_boost_enabled", enabled).apply()
        musicVolumeBoostVersion++
    }

    fun isLowBatteryWarningEnabled(): Boolean {
        return prefs.getBoolean("low_battery_warning_enabled", true)
    }

    fun setLowBatteryWarningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("low_battery_warning_enabled", enabled).apply()
    }

    // Video View Mode & Sorting Persistence
    fun getVideoViewMode(folderPath: String? = null): Boolean {
        if (folderPath != null && prefs.contains("video_view_mode_$folderPath")) {
            return prefs.getBoolean("video_view_mode_$folderPath", false)
        }
        return prefs.getBoolean("video_view_mode_global", false) // false = List, true = Grid
    }

    fun saveVideoViewMode(isGrid: Boolean, folderPath: String? = null, applyToFolderOnly: Boolean = true) {
        val editor = prefs.edit()
        if (applyToFolderOnly && folderPath != null) {
            editor.putBoolean("video_view_mode_$folderPath", isGrid)
        } else {
            editor.putBoolean("video_view_mode_global", isGrid)
            if (folderPath != null) {
                editor.putBoolean("video_view_mode_$folderPath", isGrid)
            }
        }
        editor.apply()
    }

    fun getVideoSortField(folderPath: String? = null): VideoSortField {
        val raw = if (folderPath != null && prefs.contains("video_sort_field_$folderPath")) {
            prefs.getString("video_sort_field_$folderPath", null)
        } else {
            prefs.getString("video_sort_field_global", null)
        }
        return try {
            if (raw != null) VideoSortField.valueOf(raw) else VideoSortField.NAME
        } catch (_: Exception) {
            VideoSortField.NAME
        }
    }

    fun getVideoSortAscending(folderPath: String? = null): Boolean {
        if (folderPath != null && prefs.contains("video_sort_asc_$folderPath")) {
            return prefs.getBoolean("video_sort_asc_$folderPath", true)
        }
        return prefs.getBoolean("video_sort_asc_global", true) // default true (A to Z)
    }

    fun saveVideoSort(
        sortField: VideoSortField,
        ascending: Boolean,
        folderPath: String? = null,
        applyToFolderOnly: Boolean = true
    ) {
        val editor = prefs.edit()
        if (applyToFolderOnly && folderPath != null) {
            editor.putString("video_sort_field_$folderPath", sortField.name)
            editor.putBoolean("video_sort_asc_$folderPath", ascending)
        } else {
            editor.putString("video_sort_field_global", sortField.name)
            editor.putBoolean("video_sort_asc_global", ascending)
            if (folderPath != null) {
                editor.putString("video_sort_field_$folderPath", sortField.name)
                editor.putBoolean("video_sort_asc_$folderPath", ascending)
            }
        }
        editor.apply()
    }

fun clearMetadataCache() {
        val editor = prefs.edit()
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith("artist_art_") || key.startsWith("lyrics_") || key.startsWith("album_art_")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    // Custom Playlists (JSON-backed persistence)
    fun getCustomPlaylists(): List<CustomPlaylist> {
        val jsonStr = prefs.getString("custom_playlists_json", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<CustomPlaylist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val trackIdsArr = obj.getJSONArray("trackIds")
                val trackIds = mutableListOf<Long>()
                for (j in 0 until trackIdsArr.length()) {
                    trackIds.add(trackIdsArr.getLong(j))
                }
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                list.add(CustomPlaylist(id, name, trackIds, createdAt))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomPlaylists(list: List<CustomPlaylist>) {
        try {
            val arr = org.json.JSONArray()
            for (pl in list) {
                val obj = org.json.JSONObject()
                obj.put("id", pl.id)
                obj.put("name", pl.name)
                val trackIdsArr = org.json.JSONArray()
                pl.trackIds.forEach { trackIdsArr.put(it) }
                obj.put("trackIds", trackIdsArr)
                obj.put("createdAt", pl.createdAt)
                arr.put(obj)
            }
            prefs.edit().putString("custom_playlists_json", arr.toString()).apply()
            playlistVersion++
        } catch (e: Exception) {
        }
    }

    fun createCustomPlaylist(name: String, trackIds: List<Long>): CustomPlaylist {
        val list = getCustomPlaylists().toMutableList()
        val newPl = CustomPlaylist(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            trackIds = trackIds,
            createdAt = System.currentTimeMillis()
        )
        list.add(newPl)
        saveCustomPlaylists(list)
        return newPl
    }

    fun updateCustomPlaylist(id: String, name: String, trackIds: List<Long>) {
        val list = getCustomPlaylists().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            val existing = list[index]
            list[index] = existing.copy(name = name.trim(), trackIds = trackIds)
            saveCustomPlaylists(list)
        }
    }

    fun renameCustomPlaylist(id: String, newName: String) {
        val list = getCustomPlaylists().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            val existing = list[index]
            list[index] = existing.copy(name = newName.trim())
            saveCustomPlaylists(list)
        }
    }

    fun deleteCustomPlaylist(id: String) {
        val list = getCustomPlaylists().toMutableList()
        list.removeAll { it.id == id }
        saveCustomPlaylists(list)
    }

    fun addTrackToCustomPlaylist(playlistName: String, trackId: Long) {
        val list = getCustomPlaylists().toMutableList()
        val index = list.indexOfFirst { it.name.equals(playlistName.trim(), ignoreCase = true) }
        if (index >= 0) {
            val existing = list[index]
            if (!existing.trackIds.contains(trackId)) {
                list[index] = existing.copy(trackIds = existing.trackIds + trackId)
                saveCustomPlaylists(list)
            }
        } else {
            createCustomPlaylist(playlistName.trim(), listOf(trackId))
        }
    }

    fun isAutoPipEnabled(): Boolean = prefs.getBoolean("auto_pip_enabled", false)

    fun setAutoPipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_pip_enabled", enabled).apply()
        settingsVersion++
    }

    fun isNetworkStreamIconEnabled(): Boolean = prefs.getBoolean("show_network_stream_icon", false)

    fun setNetworkStreamIconEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("show_network_stream_icon", enabled).apply()
        settingsVersion++
    }

    fun getMusicLauncherLabel(): String {
        val actual = com.jk.mediaplayer.util.LauncherIconHelper.getActualMusicLauncherLabel(appContext)
        val saved = prefs.getString("music_launcher_label", null)
        if (saved != actual) {
            prefs.edit().putString("music_launcher_label", actual).apply()
        }
        return actual
    }

    fun setMusicLauncherLabel(name: String) {
        val trimmed = if (name.trim() == "Music") "Music" else "JK Music"
        prefs.edit().putString("music_launcher_label", trimmed).apply()
        settingsVersion++
    }

    fun getVideoLauncherLabel(): String {
        val actual = com.jk.mediaplayer.util.LauncherIconHelper.getActualVideoLauncherLabel(appContext)
        val saved = prefs.getString("video_launcher_label", null)
        if (saved != actual) {
            prefs.edit().putString("video_launcher_label", actual).apply()
        }
        return actual
    }

    fun setVideoLauncherLabel(name: String) {
        val trimmed = if (name.trim() == "Videos") "Videos" else "JK Videos"
        prefs.edit().putString("video_launcher_label", trimmed).apply()
        settingsVersion++
    }

    // Video Quality Enhancer Preferences (Hardware Decoder, 32-Bit RV32 True Color, Deinterlace, Display Enhancer)
    fun getVideoHwDecoder(): String = prefs.getString("video_hw_decoder", "AUTO") ?: "AUTO"
    fun setVideoHwDecoder(mode: String) {
        prefs.edit().putString("video_hw_decoder", mode).apply()
        videoQualityVersion++
    }

    fun isVideoHighColorQuality(): Boolean = prefs.getBoolean("video_high_color", true)
    fun setVideoHighColorQuality(enabled: Boolean) {
        prefs.edit().putBoolean("video_high_color", enabled).apply()
        videoQualityVersion++
    }

    fun getVideoDeinterlaceMode(): String = prefs.getString("video_deinterlace_mode", "AUTO") ?: "AUTO"
    fun setVideoDeinterlaceMode(mode: String) {
        prefs.edit().putString("video_deinterlace_mode", mode).apply()
        videoQualityVersion++
    }

    fun getVideoEnhancerMode(): String = prefs.getString("video_enhancer_mode", "STANDARD") ?: "STANDARD"
    fun setVideoEnhancerMode(mode: String) {
        prefs.edit().putString("video_enhancer_mode", mode).apply()
        videoQualityVersion++
    }

    // --- Blacklist Management (Folders and Files) ---
    // Music Blacklists
    fun getMusicBlacklistedFolders(): Set<String> {
        return prefs.getStringSet("music_blacklisted_folders", emptySet())?.toSet() ?: emptySet()
    }

    fun addMusicBlacklistedFolder(path: String) {
        if (path.isBlank()) return
        val current = getMusicBlacklistedFolders().toMutableSet()
        val norm = normalizePath(path)
        current.add(path)
        current.add(norm)
        try {
            val canonical = java.io.File(path).canonicalPath
            current.add(canonical)
            current.add(normalizePath(canonical))
        } catch (_: Exception) {}
        prefs.edit().putStringSet("music_blacklisted_folders", HashSet(current)).apply()
        blacklistVersion++
    }

    fun addMusicBlacklistedFolders(paths: Collection<String>) {
        val current = getMusicBlacklistedFolders().toMutableSet()
        paths.forEach { path ->
            if (path.isNotBlank()) {
                val norm = normalizePath(path)
                current.add(path)
                current.add(norm)
                try {
                    val canonical = java.io.File(path).canonicalPath
                    current.add(canonical)
                    current.add(normalizePath(canonical))
                } catch (_: Exception) {}
            }
        }
        prefs.edit().putStringSet("music_blacklisted_folders", HashSet(current)).apply()
        blacklistVersion++
    }

    fun removeMusicBlacklistedFolder(path: String) {
        val current = getMusicBlacklistedFolders().toMutableSet()
        val norm = normalizePath(path)
        current.removeAll { it.equals(path, ignoreCase = true) || normalizePath(it).equals(norm, ignoreCase = true) }
        try {
            val canon = normalizePath(java.io.File(path).canonicalPath)
            current.removeAll { normalizePath(it).equals(canon, ignoreCase = true) }
        } catch (_: Exception) {}
        prefs.edit().putStringSet("music_blacklisted_folders", HashSet(current)).apply()
        blacklistVersion++
    }

    fun clearMusicBlacklistedFolders() {
        prefs.edit().remove("music_blacklisted_folders").apply()
        blacklistVersion++
    }

    fun getMusicBlacklistedFiles(): Set<String> {
        return prefs.getStringSet("music_blacklisted_files", emptySet())?.toSet() ?: emptySet()
    }

    fun addMusicBlacklistedFile(path: String) {
        if (path.isBlank()) return
        val current = getMusicBlacklistedFiles().toMutableSet()
        val norm = normalizePath(path)
        current.add(path)
        current.add(norm)
        try {
            val canonical = java.io.File(path).canonicalPath
            current.add(canonical)
            current.add(normalizePath(canonical))
        } catch (_: Exception) {}
        prefs.edit().putStringSet("music_blacklisted_files", HashSet(current)).apply()
        blacklistVersion++
    }

    fun addMusicBlacklistedFiles(paths: Collection<String>) {
        val current = getMusicBlacklistedFiles().toMutableSet()
        paths.forEach { path ->
            if (path.isNotBlank()) {
                val norm = normalizePath(path)
                current.add(path)
                current.add(norm)
                try {
                    val canonical = java.io.File(path).canonicalPath
                    current.add(canonical)
                    current.add(normalizePath(canonical))
                } catch (_: Exception) {}
            }
        }
        prefs.edit().putStringSet("music_blacklisted_files", HashSet(current)).apply()
        blacklistVersion++
    }

    fun removeMusicBlacklistedFile(path: String) {
        val current = getMusicBlacklistedFiles().toMutableSet()
        val norm = normalizePath(path)
        current.removeAll { it.equals(path, ignoreCase = true) || normalizePath(it).equals(norm, ignoreCase = true) }
        try {
            val canon = normalizePath(java.io.File(path).canonicalPath)
            current.removeAll { normalizePath(it).equals(canon, ignoreCase = true) }
        } catch (_: Exception) {}
        prefs.edit().putStringSet("music_blacklisted_files", HashSet(current)).apply()
        blacklistVersion++
    }

    fun clearMusicBlacklistedFiles() {
        prefs.edit().remove("music_blacklisted_files").apply()
        blacklistVersion++
    }

    // Video Blacklists
    fun getVideoBlacklistedFolders(): Set<String> {
        return prefs.getStringSet("video_blacklisted_folders", emptySet())?.toSet() ?: emptySet()
    }

    fun addVideoBlacklistedFolder(path: String) {
        if (path.isBlank()) return
        val current = getVideoBlacklistedFolders().toMutableSet()
        val norm = normalizePath(path)
        current.add(path)
        current.add(norm)
        try {
            val canonical = java.io.File(path).canonicalPath
            current.add(canonical)
            current.add(normalizePath(canonical))
        } catch (_: Exception) {}
        prefs.edit().putStringSet("video_blacklisted_folders", HashSet(current)).apply()
        blacklistVersion++
    }

    fun addVideoBlacklistedFolders(paths: Collection<String>) {
        val current = getVideoBlacklistedFolders().toMutableSet()
        paths.forEach { path ->
            if (path.isNotBlank()) {
                val norm = normalizePath(path)
                current.add(path)
                current.add(norm)
                try {
                    val canonical = java.io.File(path).canonicalPath
                    current.add(canonical)
                    current.add(normalizePath(canonical))
                } catch (_: Exception) {}
            }
        }
        prefs.edit().putStringSet("video_blacklisted_folders", HashSet(current)).apply()
        blacklistVersion++
    }

    fun removeVideoBlacklistedFolder(path: String) {
        val current = getVideoBlacklistedFolders().toMutableSet()
        val norm = normalizePath(path)
        current.removeAll { it.equals(path, ignoreCase = true) || normalizePath(it).equals(norm, ignoreCase = true) }
        try {
            val canon = normalizePath(java.io.File(path).canonicalPath)
            current.removeAll { normalizePath(it).equals(canon, ignoreCase = true) }
        } catch (_: Exception) {}
        prefs.edit().putStringSet("video_blacklisted_folders", HashSet(current)).apply()
        blacklistVersion++
    }

    fun clearVideoBlacklistedFolders() {
        prefs.edit().remove("video_blacklisted_folders").apply()
        blacklistVersion++
    }

    fun getVideoBlacklistedFiles(): Set<String> {
        return prefs.getStringSet("video_blacklisted_files", emptySet())?.toSet() ?: emptySet()
    }

    fun addVideoBlacklistedFile(path: String) {
        if (path.isBlank()) return
        val current = getVideoBlacklistedFiles().toMutableSet()
        val norm = normalizePath(path)
        current.add(path)
        current.add(norm)
        try {
            val canonical = java.io.File(path).canonicalPath
            current.add(canonical)
            current.add(normalizePath(canonical))
        } catch (_: Exception) {}
        prefs.edit().putStringSet("video_blacklisted_files", HashSet(current)).apply()
        blacklistVersion++
    }

    fun addVideoBlacklistedFiles(paths: Collection<String>) {
        val current = getVideoBlacklistedFiles().toMutableSet()
        paths.forEach { path ->
            if (path.isNotBlank()) {
                val norm = normalizePath(path)
                current.add(path)
                current.add(norm)
                try {
                    val canonical = java.io.File(path).canonicalPath
                    current.add(canonical)
                    current.add(normalizePath(canonical))
                } catch (_: Exception) {}
            }
        }
        prefs.edit().putStringSet("video_blacklisted_files", HashSet(current)).apply()
        blacklistVersion++
    }

    fun removeVideoBlacklistedFile(path: String) {
        val current = getVideoBlacklistedFiles().toMutableSet()
        val norm = normalizePath(path)
        current.removeAll { it.equals(path, ignoreCase = true) || normalizePath(it).equals(norm, ignoreCase = true) }
        try {
            val canon = normalizePath(java.io.File(path).canonicalPath)
            current.removeAll { normalizePath(it).equals(canon, ignoreCase = true) }
        } catch (_: Exception) {}
        prefs.edit().putStringSet("video_blacklisted_files", HashSet(current)).apply()
        blacklistVersion++
    }

    fun clearVideoBlacklistedFiles() {
        prefs.edit().remove("video_blacklisted_files").apply()
        blacklistVersion++
    }

    // Track play tracking for "Recently Played" and "Most Played"
    fun recordTrackPlayed(trackId: Long) {
        if (trackId <= 0L) return
        val now = System.currentTimeMillis()
        val playCount = prefs.getInt("track_play_count_$trackId", 0) + 1

        val rawRecent = prefs.getString("recently_played_track_ids", "") ?: ""
        val list = if (rawRecent.isNotBlank()) {
            rawRecent.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it != trackId }.toMutableList()
        } else {
            mutableListOf()
        }
        list.add(0, trackId)
        val trimmed = list.take(100)

        prefs.edit()
            .putInt("track_play_count_$trackId", playCount)
            .putLong("track_last_played_$trackId", now)
            .putString("recently_played_track_ids", trimmed.joinToString(","))
            .apply()
        playlistVersion++
    }

    fun getRecentlyPlayedTrackIds(): List<Long> {
        val rawRecent = prefs.getString("recently_played_track_ids", "") ?: ""
        if (rawRecent.isBlank()) return emptyList()
        return rawRecent.split(",").mapNotNull { it.trim().toLongOrNull() }
    }


    /** Batch-extract all track play counts in a single in-memory pass to eliminate SharedPreferences loop storms. */
    fun getAllTrackPlayCounts(): Map<Long, Int> {
        val map = HashMap<Long, Int>()
        val prefix = "track_play_count_"
        try {
            val all = prefs.all
            for ((key, value) in all) {
                if (key.startsWith(prefix) && value is Int) {
                    val id = key.substring(prefix.length).toLongOrNull()
                    if (id != null) {
                        map[id] = value
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    fun getMostPlayedTrackIds(allTrackIds: Collection<Long>, limit: Int = 100): List<Long> {
        val playCounts = getAllTrackPlayCounts()
        return allTrackIds
            .mapNotNull { id ->
                val count = playCounts[id] ?: 0
                if (count > 0) id to count else null
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Last playback session, so the mini player can come back after the app is closed.
     * Only identifiers are stored; the tracks themselves are rebuilt from the media library.
     */
    fun savePlaybackSession(trackId: Long?, playlistIds: List<Long>, index: Int, source: String) {
        if (trackId == null) return
        prefs.edit()
            .putLong("playback_track_id", trackId)
            .putString("playback_playlist_ids", playlistIds.joinToString(","))
            .putInt("playback_index", index)
            .putString("playback_source", source)
            .apply()
    }

    fun getPlaybackSession(): PlaybackSession? {
        val trackId = prefs.getLong("playback_track_id", -1L)
        if (trackId <= 0L) return null
        val ids = prefs.getString("playback_playlist_ids", null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()
        return PlaybackSession(
            trackId = trackId,
            playlistIds = ids,
            index = prefs.getInt("playback_index", 0),
            source = prefs.getString("playback_source", "All Songs") ?: "All Songs"
        )
    }

    fun isMusicOnboardingCompleted(): Boolean =
        prefs.getBoolean("music_onboarding_completed", false)

    fun setMusicOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("music_onboarding_completed", completed).apply()
    }

    fun isVideoOnboardingCompleted(): Boolean =
        prefs.getBoolean("video_onboarding_completed", false)

    fun setVideoOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("video_onboarding_completed", completed).apply()
    }

    fun isUpdateSnoozed(nowMs: Long = System.currentTimeMillis()): Boolean {
        val snoozedUntil = prefs.getLong("update_snoozed_until_ms", 0L)
        return snoozedUntil > nowMs
    }

    fun snoozeUpdateFor7Days(nowMs: Long = System.currentTimeMillis()) {
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        prefs.edit().putLong("update_snoozed_until_ms", nowMs + sevenDaysMs).apply()
    }

}

data class PlaybackSession(
    val trackId: Long,
    val playlistIds: List<Long>,
    val index: Int,
    val source: String
)

