package com.jk.mediaplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.jk.mediaplayer.R
import com.jk.mediaplayer.ui.video.VideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoBackgroundService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSessionCompat? = null
    private val notificationChannelId = "jk_video_bg_channel"
    private val notificationId = 2002

    private var currentTitle: String = "Video Playback"
    private var currentPath: String = ""
    private var currentUri: String = ""
    private var currentBitmap: Bitmap? = null
    private var monitorJob: Job? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss =
                    VideoPlayerActivity.activeController?.isPlaying?.value == true
                VideoPlayerActivity.activeController?.pause()
                updatePlaybackState(false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    VideoPlayerActivity.activeController?.play()
                    updatePlaybackState(true)
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Exception) {
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VideoBackgroundService = this@VideoBackgroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        registerBecomingNoisyReceiver()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "JKVideoBackgroundSession").apply {
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            val openIntent = Intent(this@VideoBackgroundService, VideoPlayerActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_DEFAULT)
                setPackage(packageName)
                putExtra("EXTRA_FROM_BACKGROUND_NOTIFICATION", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this@VideoBackgroundService, 201, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            setSessionActivity(pendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    VideoPlayerActivity.activeController?.play()
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    VideoPlayerActivity.activeController?.pause()
                    updatePlaybackState(false)
                }

                override fun onSkipToNext() {
                    VideoPlayerActivity.activeInstance?.playNextVideo()
                }

                override fun onSkipToPrevious() {
                    VideoPlayerActivity.activeInstance?.playPreviousVideo()
                }

                override fun onSeekTo(pos: Long) {
                    VideoPlayerActivity.activeController?.seekTo(pos)
                    updatePlaybackState(VideoPlayerActivity.activeController?.isPlaying?.value == true, pos)
                }

                override fun onStop() {
                    stopBackgroundPlayback()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video Player"
                val path = intent.getStringExtra(EXTRA_PATH) ?: ""
                val uri = intent.getStringExtra(EXTRA_URI) ?: ""
                currentTitle = title
                currentPath = path
                currentUri = uri
                requestAudioFocus()
                val controller = VideoPlayerActivity.activeController
                if (controller?.isPlaying?.value != true) {
                    controller?.play()
                }
                updateMetadata(title, currentBitmap)
                startForegroundNotification()
                startStateMonitoring()
            }
            ACTION_TOGGLE -> {
                VideoPlayerActivity.activeController?.togglePlayPause()
            }
            ACTION_NEXT -> {
                VideoPlayerActivity.activeInstance?.playNextVideo()
            }
            ACTION_PREV -> {
                VideoPlayerActivity.activeInstance?.playPreviousVideo()
            }
            ACTION_STOP -> {
                stopBackgroundPlayback()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val isPlaying = VideoPlayerActivity.activeController?.isPlaying?.value ?: true
        updatePlaybackState(isPlaying)
        showNotification(isPlaying, currentBitmap)

        scope.launch(Dispatchers.IO) {
            val bitmap = loadVideoThumbnail(currentPath, currentUri)
            currentBitmap = bitmap
            withContext(Dispatchers.Main) {
                updateMetadata(currentTitle, bitmap)
                val playing = VideoPlayerActivity.activeController?.isPlaying?.value ?: true
                updatePlaybackState(playing)
                showNotification(playing, bitmap)
            }
        }
    }

    private fun startStateMonitoring() {
        monitorJob?.cancel()
        val controller = VideoPlayerActivity.activeController ?: return
        monitorJob = scope.launch {
            controller.isPlaying.collectLatest { playing ->
                updatePlaybackState(playing)
                showNotification(playing, currentBitmap)
            }
        }
    }

    private fun updateMetadata(title: String, bitmap: Bitmap?) {
        val duration = VideoPlayerActivity.activeController?.totalDuration?.value ?: 0L
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "JK Video Player")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Video Playback")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "JK Video Player")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        if (bitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
        }
        mediaSession?.setMetadata(builder.build())
    }

    private fun updatePlaybackState(isPlaying: Boolean, customPos: Long = -1L) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = if (customPos >= 0L) customPos else (VideoPlayerActivity.activeController?.currentTime?.value ?: 0L)

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position, if (isPlaying) 1.0f else 0.0f, android.os.SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun showNotification(isPlaying: Boolean, bitmap: Bitmap?) {
        val openIntent = Intent(this, VideoPlayerActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_FROM_BACKGROUND_NOTIFICATION", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 201, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession?.setSessionActivity(contentPendingIntent)

        val playPauseIcon = if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val playPauseAction = NotificationCompat.Action(
            playPauseIcon,
            if (isPlaying) "Pause" else "Play",
            buildServicePendingIntent(ACTION_TOGGLE, 202)
        )

        val nextAction = NotificationCompat.Action(
            R.drawable.ic_notif_next,
            "Next",
            buildServicePendingIntent(ACTION_NEXT, 203)
        )

        val prevAction = NotificationCompat.Action(
            R.drawable.ic_notif_prev,
            "Previous",
            buildServicePendingIntent(ACTION_PREV, 204)
        )

        val stopAction = NotificationCompat.Action(
            R.drawable.ic_notif_close,
            "Close",
            buildServicePendingIntent(ACTION_STOP, 205)
        )

        val builder = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(currentTitle)
            .setContentText("Background Audio Playback")
            .setSmallIcon(R.drawable.ic_launcher_video)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)

        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
        }

        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun buildServicePendingIntent(actionStr: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, VideoBackgroundService::class.java).apply { action = actionStr }
        return PendingIntent.getService(this, reqCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun loadVideoThumbnail(path: String, uriStr: String): Bitmap? {
        val targetPx = 512
        val uri = if (uriStr.isNotEmpty()) try { Uri.parse(uriStr) } catch (_: Exception) { null } else null

        // 1. Android ContentResolver loadThumbnail (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            try {
                val bmp = contentResolver.loadThumbnail(uri, android.util.Size(targetPx, targetPx), null)
                if (bmp.width > 0 && bmp.height > 0) {
                    return bmp
                }
            } catch (_: Throwable) {}
        }

        // 2. Check if a thumbnail is already generated in VideoThumbnailCache
        try {
            val cacheDir = File(cacheDir, "video_thumbs")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val files = cacheDir.listFiles { f -> f.isFile && f.extension.equals("jpg", ignoreCase = true) }
                if (files != null && files.isNotEmpty()) {
                    val safeHash = kotlin.math.abs((if (path.isNotEmpty()) path else uriStr).hashCode().toLong())
                    val matched = files.firstOrNull { it.name.contains(safeHash.toString()) }
                    if (matched != null && matched.length() > 0) {
                        val bmp = BitmapFactory.decodeFile(matched.absolutePath)
                        if (bmp != null) return bmp
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. ThumbnailUtils file-based thumbnail
        if (path.isNotEmpty() && File(path).exists()) {
            try {
                @Suppress("DEPRECATION")
                val bmp = android.media.ThumbnailUtils.createVideoThumbnail(path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    return bmp
                }
            } catch (_: Throwable) {}
        }

        // 4. MediaMetadataRetriever (embedded artwork, then frame at 1s - 2s)
        val mmr = MediaMetadataRetriever()
        return try {
            if (path.isNotEmpty() && File(path).exists()) {
                mmr.setDataSource(path)
            } else if (uri != null) {
                val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
                if (pfd != null) {
                    mmr.setDataSource(pfd.fileDescriptor)
                    pfd.close()
                } else {
                    mmr.setDataSource(this, uri)
                }
            } else {
                return null
            }

            // Embedded container cover picture (MP4/MKV artwork)
            val embedded = mmr.embeddedPicture
            if (embedded != null) {
                val bmp = BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
                if (bmp != null) return scaleDownIfNeeded(bmp, targetPx)
            }

            // Frame extraction
            val frame = mmr.getFrameAtTime(2_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: mmr.frameAtTime

            if (frame != null) {
                scaleDownIfNeeded(frame, targetPx)
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, targetPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        return if (longest > targetPx) {
            val scale = targetPx.toFloat() / longest.toFloat()
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }
    }

    private fun stopBackgroundPlayback() {
        val controller = VideoPlayerActivity.activeController
        controller?.mediaPlayer?.pause()
        VideoPlayerActivity.isBackgroundAudioRunning = false
        val activity = VideoPlayerActivity.activeInstance
        activity?.finish()
        // When the activity is already gone nobody else can release the engine.
        if (activity == null) {
            VideoPlayerActivity.activeController = null
            try { controller?.release() } catch (_: Exception) {}
        }
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Video Background Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active video background playback controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                VideoPlayerActivity.activeController?.pause()
                updatePlaybackState(false)
                showNotification(false, currentBitmap)
            }
        }
    }

    private fun registerBecomingNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    noisyReceiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(noisyReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the task from recents ends background video audio too.
        try { stopBackgroundPlayback() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        abandonAudioFocus()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        mediaSession?.isActive = false
        mediaSession?.release()
    }

    companion object {
        const val ACTION_START = "com.jk.mediaplayer.action.VIDEO_BG_START"
        const val ACTION_TOGGLE = "com.jk.mediaplayer.action.VIDEO_BG_TOGGLE"
        const val ACTION_NEXT = "com.jk.mediaplayer.action.VIDEO_BG_NEXT"
        const val ACTION_PREV = "com.jk.mediaplayer.action.VIDEO_BG_PREV"
        const val ACTION_STOP = "com.jk.mediaplayer.action.VIDEO_BG_STOP"

        const val EXTRA_TITLE = "extra_video_title"
        const val EXTRA_PATH = "extra_video_path"
        const val EXTRA_URI = "extra_video_uri"

        fun start(context: Context, title: String, path: String, uri: String) {
            val intent = Intent(context, VideoBackgroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_URI, uri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VideoBackgroundService::class.java)
            context.stopService(intent)
        }
    }
}
