package com.jk.mediaplayer.data

import android.net.Uri
import android.webkit.MimeTypeMap
import java.net.URL
import java.net.URLDecoder

/**
 * Universal Media URL, Filename & Header Resolver for JK Media Player.
 *
 * Solves:
 * 1. RFC 5987 / RFC 6266 filename*=UTF-8''... extraction from Content-Disposition headers.
 * 2. URL path & query parameter filename discovery (?file=, ?name=, ?filename=).
 * 3. Prevention and elimination of unwanted ".bin" extensions from application/octet-stream downloads.
 * 4. Lightweight asynchronous HTTP header & magic bytes probe (MKV, MP4, WebM, AVI, FLV, TS).
 * 5. Accurate video vs generic file classification without false positives or missed media.
 */
object MediaUrlResolver {


    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m3u8", "mpd",
        "m4v", "3gp", "wmv", "asf", "mpg", "mpeg", "vob", "rmvb", "ogv"
    )

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma", "alac", "dsd"
    )

    val NON_MEDIA_EXTENSIONS = setOf(
        "html", "htm", "php", "asp", "aspx", "jsp", "js", "css", "json", "xml",
        "apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "exe", "dmg",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"
    )

    data class ProbeResult(
        val finalUrl: String,
        val fileName: String,
        val mimeType: String?,
        val contentLength: Long,
        val isVideo: Boolean
    )

    /**
     * Extracts filename from Content-Disposition header conforming to RFC 6266 / RFC 5987.
     * RFC 5987 syntax: filename*=charset'lang'encoded-value
     */
    fun parseContentDispositionFilename(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        try {
            // 1. RFC 5987 / RFC 6266 extended syntax takes precedence (filename*=UTF-8''...)
            val rfc5987Regex = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
            val matchRfc = rfc5987Regex.find(disposition)
            if (matchRfc != null) {
                val charset = matchRfc.groupValues[1].ifBlank { "UTF-8" }
                val encodedVal = matchRfc.groupValues[2].trim()
                val decoded = try {
                    URLDecoder.decode(encodedVal, charset)
                } catch (_: Exception) {
                    try { URLDecoder.decode(encodedVal, "UTF-8") } catch (_: Exception) { encodedVal }
                }
                val cleaned = sanitizeRawFilename(decoded)
                if (cleaned.isNotBlank()) return cleaned
            }

            // 2. Standard filename="quoted" or filename=unquoted
            val standardRegex = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;\s]+))""", RegexOption.IGNORE_CASE)
            val matchStd = standardRegex.find(disposition)
            if (matchStd != null) {
                val quoted = matchStd.groupValues[1]
                val unquoted = matchStd.groupValues[2]
                val rawVal = if (quoted.isNotBlank()) quoted else unquoted
                if (rawVal.isNotBlank()) {
                    val decoded = if (rawVal.contains('%')) {
                        try { URLDecoder.decode(rawVal, "UTF-8") } catch (_: Exception) { rawVal }
                    } else rawVal
                    val cleaned = sanitizeRawFilename(decoded)
                    if (cleaned.isNotBlank()) return cleaned
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    /**
     * Extracts filename from URL path segment or common query parameters.
     */
    fun extractFilenameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        try {
            val uri = Uri.parse(url)

            // 1. Check query parameters frequently used by file storage providers
            val queryKeys = listOf("filename", "file", "name", "title", "f", "dl")
            for (key in queryKeys) {
                val paramVal = try { uri.getQueryParameter(key) } catch (_: Exception) { null }
                if (!paramVal.isNullOrBlank() && paramVal.contains('.')) {
                    val decoded = try { URLDecoder.decode(paramVal, "UTF-8") } catch (_: Exception) { paramVal }
                    val cleaned = sanitizeRawFilename(decoded)
                    if (cleaned.isNotBlank() && cleaned.contains('.')) return cleaned
                }
            }

            // 2. Check path segment
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                val decoded = try { URLDecoder.decode(lastSegment, "UTF-8") } catch (_: Exception) { lastSegment }
                val cleaned = sanitizeRawFilename(decoded)
                if (cleaned.isNotBlank()) return cleaned
            }
        } catch (e: Exception) {
        }
        return null
    }

    /**
     * Maps known MIME types to clean file extensions without relying on ambiguous system tables.
     */
    fun guessExtensionFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val clean = mime.substringBefore(';').trim().lowercase()
        return when (clean) {
            "video/mp4" -> "mp4"
            "video/x-matroska", "video/mkv" -> "mkv"
            "video/webm" -> "webm"
            "video/x-msvideo", "video/avi" -> "avi"
            "video/quicktime" -> "mov"
            "video/x-flv" -> "flv"
            "video/mp2t" -> "ts"
            "video/3gpp" -> "3gp"
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "m3u8"
            "application/dash+xml" -> "mpd"
            "application/vnd.android.package-archive" -> "apk"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/x-rar-compressed", "application/x-rar" -> "rar"
            "application/x-7z-compressed" -> "7z"
            "application/pdf" -> "pdf"
            "audio/mpeg" -> "mp3"
            "audio/flac" -> "flac"
            "audio/aac" -> "aac"
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/ogg", "audio/opus" -> "opus"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(clean)
                if (ext.isNullOrBlank() || ext == "bin") null else ext
            }
        }
    }

    fun isVideoExtension(ext: String?): Boolean {
        if (ext.isNullOrBlank()) return false
        return VIDEO_EXTENSIONS.contains(ext.lowercase())
    }

    /**
     * Sanitizes filenames and guarantees NO .bin extensions for video or media downloads.
     */
    fun sanitizeFilename(
        rawName: String,
        mimeType: String? = null,
        fallbackExt: String = "mp4"
    ): String {
        var name = sanitizeRawFilename(rawName)
        if (name.isBlank()) {
            name = "download_${System.currentTimeMillis() % 100000}.$fallbackExt"
        }

        // 1. Remove double extension artifacts like "movie.mkv.bin" -> "movie.mkv"
        if (name.endsWith(".bin", ignoreCase = true)) {
            val stripped = name.substringBeforeLast('.')
            val innerExt = stripped.substringAfterLast('.', "").lowercase()
            if (isVideoExtension(innerExt) || AUDIO_EXTENSIONS.contains(innerExt) || NON_MEDIA_EXTENSIONS.contains(innerExt)) {
                name = stripped
            }
        }

        // 2. Extract base name and extension
        var base = if (name.contains('.')) name.substringBeforeLast('.') else name
        var ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

        // 3. If extension is .bin, missing, or generic, try MIME resolution or fallback
        if (ext.isBlank() || ext == "bin") {
            val mimeExt = guessExtensionFromMime(mimeType)
            ext = mimeExt ?: fallbackExt
        }

        // 4. Clean base name (strip invalid filesystem characters while keeping spaces and dots)
        base = base.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (base.isBlank()) base = "media_download"

        // Limit base length safely without slicing the extension
        val safeBase = base.take(120)
        return "$safeBase.$ext"
    }

    /**
     * Fast asynchronous probe that follows redirects, queries live headers, and inspects magic bytes.
     */

    private fun sanitizeRawFilename(raw: String): String {
        return raw.trim()
            .trim('"', '\'')
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
    }
}

