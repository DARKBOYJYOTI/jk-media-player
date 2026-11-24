package com.jk.mediaplayer.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.jk.mediaplayer.service.DownloadService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED
}

class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var title: String,
    var filePath: String,
    initialTotalBytes: Long = -1L,
    initialDownloadedBytes: Long = 0L,
    initialSpeed: Long = 0L,
    initialStatus: DownloadStatus = DownloadStatus.QUEUED,
    initialErrorMessage: String? = null,
    var userAgent: String? = null,
    var cookies: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    var totalBytes by mutableLongStateOf(initialTotalBytes)
    var downloadedBytes by mutableLongStateOf(initialDownloadedBytes)
    var speedBytesPerSec by mutableLongStateOf(initialSpeed)
    var status by mutableStateOf(initialStatus)
    var errorMessage by mutableStateOf(initialErrorMessage)

    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progress * 100).toInt()

    val etaSeconds: Long
        get() {
            if (speedBytesPerSec <= 0L || totalBytes <= 0L || downloadedBytes >= totalBytes) return -1L
            val remaining = totalBytes - downloadedBytes
            return (remaining / speedBytesPerSec).coerceAtLeast(0L)
        }
}

/**
 * High-Performance Resumable Download Engine with:
 * - Segmented HTTP Range pause & resume
 * - Real-time smooth speed calculation (KB/s, MB/s)
 * - Dynamic URL update / link renewal for expired streaming tokens
 * - Storage deletion & MediaStore auto-indexing
 * - Persistent JSON history across app restarts
 */
object DownloadEngine {

    private const val TAG = "DownloadEngine"
    private const val PREFS_NAME = "jk_downloads"
    private const val KEY_DOWNLOADS_JSON = "downloads_list_json"

    val downloads = mutableStateListOf<DownloadTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        loadFromPrefs(context)
    }

    private fun loadFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DOWNLOADS_JSON, null) ?: return
            val array = JSONArray(jsonStr)
            downloads.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val statusStr = obj.optString("status", DownloadStatus.PAUSED.name)
                val status = try {
                    val parsed = DownloadStatus.valueOf(statusStr)
                    if (parsed == DownloadStatus.DOWNLOADING || parsed == DownloadStatus.QUEUED) DownloadStatus.PAUSED else parsed
                } catch (_: Exception) {
                    DownloadStatus.PAUSED
                }

                val task = DownloadTask(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    url = obj.optString("url", ""),
                    title = obj.optString("title", "download.mp4"),
                    filePath = obj.optString("filePath", ""),
                    initialTotalBytes = obj.optLong("totalBytes", -1L),
                    initialDownloadedBytes = obj.optLong("downloadedBytes", 0L),
                    initialStatus = status,
                    initialErrorMessage = if (obj.has("errorMessage") && !obj.isNull("errorMessage")) obj.getString("errorMessage") else null,
                    userAgent = if (obj.has("userAgent") && !obj.isNull("userAgent")) obj.getString("userAgent") else null,
                    cookies = if (obj.has("cookies") && !obj.isNull("cookies")) obj.getString("cookies") else null,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                // If file was already deleted from disk, update downloadedBytes
                val f = File(task.filePath)
                if (f.exists()) {
                    task.downloadedBytes = f.length()
                }
                downloads.add(task)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load downloads from prefs: ${e.message}")
        }
    }

    fun saveToPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val array = JSONArray()
            for (task in downloads) {
                val obj = JSONObject().apply {
                    put("id", task.id)
                    put("url", task.url)
                    put("title", task.title)
                    put("filePath", task.filePath)
                    put("totalBytes", task.totalBytes)
                    put("downloadedBytes", task.downloadedBytes)
                    put("status", task.status.name)
                    put("errorMessage", task.errorMessage)
                    put("userAgent", task.userAgent)
                    put("cookies", task.cookies)
                    put("createdAt", task.createdAt)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_DOWNLOADS_JSON, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save downloads to prefs: ${e.message}")
        }
    }

    fun enqueue(
        context: Context,
        url: String,
        title: String,
        userAgent: String? = null,
        cookies: String? = null
    ): DownloadTask {
        init(context)

        val cleanTitle = MediaUrlResolver.sanitizeFilename(title, null, "mp4")

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        // Generate unique destination file if file exists with different task
        var targetFile = File(downloadsDir, cleanTitle)
        var counter = 1
        while (targetFile.exists() && downloads.any { it.filePath == targetFile.absolutePath && it.status == DownloadStatus.COMPLETED }) {
            val base = cleanTitle.substringBeforeLast('.')
            val ext = cleanTitle.substringAfterLast('.', "mp4")
            targetFile = File(downloadsDir, "${base}_$counter.$ext")
            counter++
        }

        val task = DownloadTask(
            url = url,
            title = cleanTitle,
            filePath = targetFile.absolutePath,
            initialStatus = DownloadStatus.QUEUED,
            userAgent = userAgent,
            cookies = cookies
        )
        downloads.add(0, task)
        saveToPrefs(context)
        startDownloadJob(context, task)
        DownloadService.startOrUpdate(context)
        return task
    }

    fun pause(context: Context, taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        val task = downloads.find { it.id == taskId } ?: return
        task.status = DownloadStatus.PAUSED
        task.speedBytesPerSec = 0L
        saveToPrefs(context)
        DownloadService.startOrUpdate(context)
    }


    fun resume(context: Context, taskId: String) {
        val task = downloads.find { it.id == taskId } ?: return
        if (task.status == DownloadStatus.DOWNLOADING) return
        startDownloadJob(context, task)
        DownloadService.startOrUpdate(context)
    }


    fun retry(context: Context, taskId: String) {
        val task = downloads.find { it.id == taskId } ?: return
        task.errorMessage = null
        startDownloadJob(context, task)
    }


    fun cancel(context: Context, taskId: String, deleteFile: Boolean) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        val task = downloads.find { it.id == taskId }
        if (task != null) {
            if (deleteFile) {
                try {
                    val file = File(task.filePath)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting file: ${e.message}")
                }
            }
            downloads.remove(task)
            saveToPrefs(context)
            DownloadService.startOrUpdate(context)
        }
    }


    private fun startDownloadJob(context: Context, task: DownloadTask) {
        activeJobs[task.id]?.cancel()

        val job = engineScope.launch {
            withContext(Dispatchers.Main) {
                task.status = DownloadStatus.DOWNLOADING
                task.errorMessage = null
                task.speedBytesPerSec = 0L
            }

            var inputStream: InputStream? = null
            var outputStream: RandomAccessFile? = null
            var conn: HttpURLConnection? = null

            try {
                val file = File(task.filePath)
                val existingBytes = if (file.exists()) file.length() else 0L
                withContext(Dispatchers.Main) {
                    task.downloadedBytes = existingBytes
                }

                val targetUrl = URL(task.url)
                conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty(
                        "User-Agent",
                        task.userAgent ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    )
                    setRequestProperty("Connection", "Keep-Alive")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (!task.cookies.isNullOrBlank()) {
                        setRequestProperty("Cookie", task.cookies)
                    }
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val responseCode = conn.responseCode
                val isRangeAccepted = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                val isSuccess = (responseCode == HttpURLConnection.HTTP_OK)

                if (!isRangeAccepted && !isSuccess) {
                    if (responseCode == 403 || responseCode == 410) {
                        throw Exception("Download link expired ($responseCode). Tap to update URL.")
                    }
                    throw Exception("Server returned HTTP $responseCode")
                }

                // Check live server headers to fix .bin or generic file names
                val serverDisposition = conn.getHeaderField("Content-Disposition")
                val serverContentType = conn.contentType
                val finalUrl = conn.url.toString()

                var currentFile = file
                if (existingBytes == 0L) {
                    val parsedServerName = MediaUrlResolver.parseContentDispositionFilename(serverDisposition)
                        ?: MediaUrlResolver.extractFilenameFromUrl(finalUrl)
                    if (!parsedServerName.isNullOrBlank()) {
                        val cleanServerName = MediaUrlResolver.sanitizeFilename(parsedServerName, serverContentType)
                        if (task.title.endsWith(".bin", true) || task.title.startsWith("download", true) ||
                            (!task.title.endsWith(".mkv", true) && cleanServerName.endsWith(".mkv", true))
                        ) {
                            val newTarget = File(file.parentFile ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), cleanServerName)
                            if (newTarget.absolutePath != task.filePath) {
                                if (file.exists()) file.renameTo(newTarget)
                                currentFile = newTarget
                                withContext(Dispatchers.Main) {
                                    task.title = cleanServerName
                                    task.filePath = newTarget.absolutePath
                                }
                                saveToPrefs(context)
                            }
                        }
                    } else if (task.title.endsWith(".bin", true)) {
                        val replacementExt = MediaUrlResolver.guessExtensionFromMime(serverContentType) ?: "mp4"
                        val fixedTitle = task.title.substringBeforeLast('.') + ".$replacementExt"
                        val newTarget = File(file.parentFile ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fixedTitle)
                        if (newTarget.absolutePath != task.filePath) {
                            if (file.exists()) file.renameTo(newTarget)
                            currentFile = newTarget
                            withContext(Dispatchers.Main) {
                                task.title = fixedTitle
                                task.filePath = newTarget.absolutePath
                            }
                            saveToPrefs(context)
                        }
                    }
                }

                val contentLength = conn.contentLengthLong
                if (contentLength > 0L) {
                    val total = if (isRangeAccepted) existingBytes + contentLength else contentLength
                    withContext(Dispatchers.Main) {
                        task.totalBytes = total
                    }
                }

                outputStream = RandomAccessFile(currentFile, "rw")
                if (isRangeAccepted) {
                    outputStream.seek(existingBytes)
                } else {
                    outputStream.setLength(0L)
                    withContext(Dispatchers.Main) {
                        task.downloadedBytes = 0L
                    }
                }

                inputStream = conn.inputStream
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var downloadedSoFar = if (isRangeAccepted) existingBytes else 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastCalc = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) break

                    outputStream.write(buffer, 0, bytesRead)
                    downloadedSoFar += bytesRead
                    bytesSinceLastCalc += bytesRead

                    val now = System.currentTimeMillis()
                    val diff = now - lastSpeedCalcTime
                    if (diff >= 500) {
                        val speed = (bytesSinceLastCalc * 1000L) / diff
                        val curBytes = downloadedSoFar
                        bytesSinceLastCalc = 0L
                        lastSpeedCalcTime = now
                        withContext(Dispatchers.Main) {
                            task.downloadedBytes = curBytes
                            task.speedBytesPerSec = speed
                        }
                        DownloadService.startOrUpdate(context)
                    }
                }

                if (isActive) {
                    val finalBytes = downloadedSoFar
                    withContext(Dispatchers.Main) {
                        task.downloadedBytes = finalBytes
                        task.status = DownloadStatus.COMPLETED
                        task.speedBytesPerSec = 0L
                    }
                    saveToPrefs(context)
                    DownloadService.onTaskCompleted(context, task)
                    DownloadService.startOrUpdate(context)

                    // Index completed video into Android MediaStore
                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(currentFile.absolutePath),
                            null,
                            null
                        )
                    } catch (_: Exception) {}
                }
            } catch (ce: CancellationException) {
                // User paused or cancelled
                withContext(NonCancellable) {
                    task.status = DownloadStatus.PAUSED
                    task.speedBytesPerSec = 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error for ${task.title}: ${e.message}")
                withContext(NonCancellable) {
                    task.status = DownloadStatus.FAILED
                    task.errorMessage = e.message ?: "Download failed"
                    task.speedBytesPerSec = 0L
                }
            } finally {
                withContext(NonCancellable) {
                    try { inputStream?.close() } catch (_: Exception) {}
                    try { outputStream?.close() } catch (_: Exception) {}
                    try { conn?.disconnect() } catch (_: Exception) {}
                    activeJobs.remove(task.id)
                    saveToPrefs(context)
                    DownloadService.startOrUpdate(context)
                }
            }
        }

        activeJobs[task.id] = job
    }

    fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return ""
        return if (seconds < 60) {
            "${seconds}s left"
        } else if (seconds < 3600) {
            val mins = seconds / 60
            val secs = seconds % 60
            "${mins}m ${secs}s left"
        } else {
            val hrs = seconds / 3600
            val mins = (seconds % 3600) / 60
            "${hrs}h ${mins}m left"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val formatted = String.format(java.util.Locale.US, "%.1f", bytes / Math.pow(1024.0, digitGroups.toDouble()))
        return "$formatted ${units[digitGroups]}"
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        return "${formatBytes(bytesPerSec)}/s"
    }
}
