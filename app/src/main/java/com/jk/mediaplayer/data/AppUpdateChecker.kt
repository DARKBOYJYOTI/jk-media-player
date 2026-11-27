package com.jk.mediaplayer.data

import android.os.Build
import com.jk.mediaplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Describes an available GitHub release update with a direct browser download link. */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String,
    val releasePageUrl: String
)

/**
 * Lightweight GitHub Releases update checker.
 * Runs at most once per app process on a background thread and fails silently on network errors.
 */
object AppUpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/DARKBOYJYOTI/jk-media-player/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    private val checkedThisSession = AtomicBoolean(false)

    suspend fun checkForUpdateIfNeeded(
        currentVersion: String = BuildConfig.VERSION_NAME,
        onCheckCompleted: () -> Unit = {}
    ): AppUpdateInfo? {
        if (!checkedThisSession.compareAndSet(false, true)) return null
        return withContext(Dispatchers.IO) {
            fetchLatestUpdate(currentVersion, onCheckCompleted)
        }
    }

    private fun fetchLatestUpdate(
        currentVersion: String,
        onCheckCompleted: () -> Unit
    ): AppUpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(LATEST_RELEASE_API)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "JK-MediaPlayer/$currentVersion")
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // Record the daily check timestamp once a valid GitHub response is received,
            // so offline launches do not consume today's update check.
            onCheckCompleted()
            val json = JSONObject(body)
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                return null
            }
            val tagName = json.optString("tag_name", "").trim()
            val htmlUrl = json.optString("html_url", "").trim()
            if (tagName.isBlank() || htmlUrl.isBlank()) return null
            if (!isNewerVersion(remoteVersion = tagName, currentVersion = currentVersion)) {
                return null
            }
            val assets = json.optJSONArray("assets")
            val directApkUrl = selectBestApkUrl(assets, Build.SUPPORTED_ABIS) ?: htmlUrl
            AppUpdateInfo(
                currentVersion = normalizeDisplayVersion(currentVersion),
                latestVersion = normalizeDisplayVersion(tagName),
                downloadUrl = directApkUrl,
                releasePageUrl = htmlUrl
            )
        } catch (_: Throwable) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = parseVersionParts(remoteVersion)
        val currentParts = parseVersionParts(currentVersion)
        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun parseVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split('.').mapNotNull { it.trim().toIntOrNull() }
    }

    private fun normalizeDisplayVersion(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
    }

    /**
     * Picks the release APK matching the device's highest-priority ABI (for example arm64-v8a),
     * falling back to any release APK asset if an ABI-specific build is not present.
     */
    internal fun selectBestApkUrl(assets: JSONArray?, supportedAbis: Array<String>): String? {
        if (assets == null || assets.length() == 0) return null
        val apkAssets = mutableListOf<Pair<String, String>>()
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            val url = obj.optString("browser_download_url", "").trim()
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                apkAssets.add(name.lowercase() to url)
            }
        }
        if (apkAssets.isEmpty()) return null

        for (abi in supportedAbis) {
            val lowerAbi = abi.lowercase()
            // Match exact ABI token bounded by hyphen/dot/start/end so "x86" never matches "x86_64".
            val abiPattern = Regex("(^|[-.])${Regex.escape(lowerAbi)}([-.]|$)")
            val releaseMatch = apkAssets.firstOrNull { (name, _) ->
                abiPattern.containsMatchIn(name) && !name.contains("debug")
            }
            if (releaseMatch != null) return releaseMatch.second
            val anyAbiMatch = apkAssets.firstOrNull { (name, _) -> abiPattern.containsMatchIn(name) }
            if (anyAbiMatch != null) return anyAbiMatch.second
        }
        return apkAssets.firstOrNull { (name, _) -> !name.contains("debug") }?.second
            ?: apkAssets.first().second
    }
}
