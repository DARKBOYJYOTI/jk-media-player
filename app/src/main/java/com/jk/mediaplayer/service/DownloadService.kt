package com.jk.mediaplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jk.mediaplayer.R
import com.jk.mediaplayer.data.DownloadEngine
import com.jk.mediaplayer.data.DownloadStatus
import com.jk.mediaplayer.data.DownloadTask
import com.jk.mediaplayer.ui.video.VideoActivity
import com.jk.mediaplayer.ui.video.VideoPlayerActivity

/**
 * High-Performance Background Download Service for JK Media Player.
 *
 * Features:
 * - Runs as an Android Foreground Service with DATA_SYNC foregroundServiceType.
 * - Manages CPU PARTIAL_WAKE_LOCK to prevent the OS from killing background downloads.
 * - Live Quick Settings / Notification Panel integration:
 *   - Current downloading filename, speed in MB/s, downloaded/total GB and percentage bar.
 *   - Quick-action buttons directly on notification: [Pause] and [Cancel].
 *   - One-tap intent to open the in-app Download Manager.
 *   - Separate completion notification with 1-tap playback in LibVLC VideoPlayerActivity.
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "jk_media_downloads"
        const val ONGOING_NOTIFICATION_ID = 9001

        const val ACTION_START_OR_UPDATE = "com.jk.mediaplayer.action.DOWNLOAD_UPDATE"
        const val ACTION_PAUSE = "com.jk.mediaplayer.action.DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.jk.mediaplayer.action.DOWNLOAD_RESUME"
        const val ACTION_CANCEL = "com.jk.mediaplayer.action.DOWNLOAD_CANCEL"
        const val ACTION_STOP = "com.jk.mediaplayer.action.DOWNLOAD_STOP"
        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"

        fun startOrUpdate(context: Context) {
            val hasActive = DownloadEngine.downloads.any {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
            }
            if (hasActive) {
                try {
                    val intent = Intent(context, DownloadService::class.java).apply {
                        action = ACTION_START_OR_UPDATE
                    }
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                }
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, DownloadService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
            }
        }

        fun onTaskCompleted(context: Context, task: DownloadTask) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                createChannel(nm)

                val playIntent = Intent(context, VideoPlayerActivity::class.java).apply {
                    putExtra("EXTRA_VIDEO_PATH", task.filePath)
                    putExtra("EXTRA_VIDEO_TITLE", task.title)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingPlay = PendingIntent.getActivity(
                    context,
                    task.id.hashCode() + 1,
                    playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download Complete")
                    .setContentText("${task.title} • ${DownloadEngine.formatBytes(task.downloadedBytes)}")
                    .setContentIntent(pendingPlay)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

                nm.notify(task.id.hashCode(), notif)
            } catch (e: Exception) {
            }
        }

        private fun createChannel(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(CHANNEL_ID)
                if (existing == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Ongoing media download progress and status"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(notificationManager)

        // Android OS requires startForeground to be called immediately when started via startForegroundService
        val activeTasks = DownloadEngine.downloads.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }
        val initialNotif = if (activeTasks.isNotEmpty()) {
            buildOngoingNotification(activeTasks)
        } else {
            buildPlaceholderNotification()
        }
        startForegroundCompat(initialNotif)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        when (action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadEngine.pause(this, taskId)
                }
            }
            ACTION_RESUME -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadEngine.resume(this, taskId)
                }
            }
            ACTION_CANCEL -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadEngine.cancel(this, taskId, deleteFile = true)
                }
            }
            ACTION_START_OR_UPDATE -> {
                // Refresh progress notification
            }
        }

        updateNotification()
        return START_NOT_STICKY
    }

    private fun updateNotification() {
        val activeTasks = DownloadEngine.downloads.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }

        if (activeTasks.isEmpty()) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        acquireWakeLock()
        val notification = buildOngoingNotification(activeTasks)
        notificationManager.notify(ONGOING_NOTIFICATION_ID, notification)
    }

    private fun buildPlaceholderNotification(): android.app.Notification {
        val openIntent = Intent(this, VideoActivity::class.java).apply {
            putExtra("EXTRA_OPEN_DOWNLOADS", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("JK Media Player Downloads")
            .setContentText("Managing downloads...")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()
    }

    private fun buildOngoingNotification(activeTasks: List<DownloadTask>): android.app.Notification {
        val primaryTask = activeTasks.first()
        val totalActive = activeTasks.size

        // Intent to open VideoActivity Downloads
        val openIntent = Intent(this, VideoActivity::class.java).apply {
            putExtra("EXTRA_OPEN_DOWNLOADS", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Pause action
        val pauseIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_PAUSE
            putExtra(EXTRA_TASK_ID, primaryTask.id)
        }
        val pendingPause = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel action
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, primaryTask.id)
        }
        val pendingCancel = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val downloadedStr = DownloadEngine.formatBytes(primaryTask.downloadedBytes)
        val totalStr = if (primaryTask.totalBytes > 0) DownloadEngine.formatBytes(primaryTask.totalBytes) else "..."
        val speedStr = DownloadEngine.formatSpeed(primaryTask.speedBytesPerSec)
        val etaStr = DownloadEngine.formatEta(primaryTask.etaSeconds)
        val etaPart = if (etaStr.isNotEmpty()) " • $etaStr" else ""

        val notifTitle = if (totalActive > 1) {
            "Downloading ($totalActive files): ${primaryTask.title}"
        } else {
            primaryTask.title
        }

        val notifText = "$downloadedStr / $totalStr (${primaryTask.progressPercent}%) • $speedStr$etaPart"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pendingPause)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancel)

        if (primaryTask.totalBytes > 0L) {
            builder.setProgress(100, primaryTask.progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ONGOING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(ONGOING_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JKMediaPlayer:DownloadWakeLock")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L) // 6 hours safety timeout
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

