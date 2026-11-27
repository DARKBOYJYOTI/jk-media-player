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
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import com.jk.mediaplayer.data.MediaScanner
import com.jk.mediaplayer.data.PreferencesManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.jk.mediaplayer.R
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.player.AudioSession
import com.jk.mediaplayer.player.LibVlcController
import com.jk.mediaplayer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AudioPlayerService : Service() {

    private val binder = LocalBinder()
    private val prefsManager by lazy { PreferencesManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var playerController: LibVlcController? = null
        private set



    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JKMediaPlayer:AudioPlaybackWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
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
        } catch (e: Exception) {
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private val notificationChannelId = "jk_audio_channel"
    private val notificationId = 1001

    private val _currentTrack = MutableStateFlow<AudioItem?>(null)
    val currentTrack: StateFlow<AudioItem?> = _currentTrack.asStateFlow()

    private val _playlist = MutableStateFlow<List<AudioItem>>(emptyList())
    val playlist: StateFlow<List<AudioItem>> = _playlist.asStateFlow()

    private val _playbackSource = MutableStateFlow<String>("All Songs")
    val playbackSource: StateFlow<String> = _playbackSource.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteTrackIds: StateFlow<Set<String>> = _favoriteTrackIds.asStateFlow()

    private var currentIndex = 0

    /** Shuffle "previous" needs real history, random previous is wrong. */
    private val playHistory = ArrayDeque<Int>()

    /** Tapping previous after this point restarts the current track. */
    private val previousRestartThresholdMs = 3000L
    private var currentArtBitmap: Bitmap? = null
    /** Last rendered notification state; identical states skip the rebuild and startForeground(). */
    private var lastNotificationKey: String? = null

    val repeatMode = MutableStateFlow(0) // 0 = Off, 1 = Repeat All, 2 = Repeat One
    val isShuffle = MutableStateFlow(false)
    val vlcVolume = MutableStateFlow(100)

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearPlaybackError() {
        _playbackError.value = null
        playerController?.clearError()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlayerService = this@AudioPlayerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        playerController = LibVlcController(this, enableAudioSession = false).apply {
            onEndReached = {
                acquireWakeLock()
                scope.launch(Dispatchers.Main.immediate) {
                    onTrackEnded()
                }
            }
            onError = { msg ->
                scope.launch(Dispatchers.Main) {
                    _playbackError.value = msg
                }
            }
        }
        createNotificationChannel()
        setupMediaSession()
        _favoriteTrackIds.value = prefsManager.getFavorites()
        registerBecomingNoisyReceiver()

        scope.launch {
            playerController?.isPlaying?.collect { isPlaying ->
                if (isPlaying) {
                    acquireWakeLock()
                } else {
                    // Release the partial wake lock when paused so the device can enter deep sleep.
                    releaseWakeLock()
                }
                updatePlaybackState(isPlaying)
                _currentTrack.value?.let { showNotification(it, isPlaying, currentArtBitmap) }
            }
        }

        scope.launch {
            playerController?.encounteredError?.collect { err ->
                if (err != null) {
                    _playbackError.value = err
                }
            }
        }

        scope.launch {
            playerController?.totalDuration?.collect { dur ->
                if (dur > 0L) {
                    val cur = _currentTrack.value
                    if (cur != null && cur.duration != dur) {
                        val updated = cur.copy(duration = dur)
                        _currentTrack.value = updated
                        updateTrackMetadataAndNotification(updated, playerController?.isPlaying?.value == true)
                    }
                }
            }
        }

        restoreLastSession()

        // PlaybackState is a snapshot, so the QS / lock screen seek bar only stays accurate while
        // the service republishes the live position. One tick per second is enough for the system
        // controls and is cheap.
        scope.launch {
            while (isActive) {
                if (playerController?.isPlaying?.value == true) {
                    updatePlaybackState(true, playerController?.currentTime?.value ?: 0L)
                }
                delay(1000L)
            }
        }
    }

    /**
     * Rebuilds the last queue from disk when the process was killed. Nothing starts playing: the
     * mini player simply reappears on the previous track, paused, until the user hits play.
     */
    private fun restoreLastSession() {
        scope.launch(Dispatchers.IO) {
            if (_currentTrack.value != null) return@launch
            val session = prefsManager.getPlaybackSession() ?: return@launch
            val library = MediaScanner.cachedMusic ?: MediaScanner.scanMusic(this@AudioPlayerService)
            val byId = library.associateBy { it.id }
            val track = byId[session.trackId] ?: return@launch
            val restoredQueue = session.playlistIds.mapNotNull { byId[it] }
            val queue = if (restoredQueue.any { it.id == track.id }) restoredQueue else listOf(track)
            withContext(Dispatchers.Main) {
                if (_currentTrack.value != null) return@withContext
                _playlist.value = queue
                currentIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                if (session.source.isNotBlank()) _playbackSource.value = session.source
                _currentTrack.value = track
                updatePlaybackState(false)
                showNotification(track, false)
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "JKMediaSession").apply {
            // Required for headset/Bluetooth buttons and for OEM media surfaces (OriginOS island,
            // EMUI capsule) that only bind sessions advertising media button handling.
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            try {
                val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setClass(this@AudioPlayerService, androidx.media.session.MediaButtonReceiver::class.java)
                }
                setMediaButtonReceiver(
                    PendingIntent.getBroadcast(
                        this@AudioPlayerService,
                        0,
                        mediaButtonIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } catch (_: Exception) {
            }
            // System media UIs (QS, lock screen, OEM islands) open the full player on tap.
            setSessionActivity(
                PendingIntent.getActivity(
                    this@AudioPlayerService,
                    202,
                    Intent(this@AudioPlayerService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(EXTRA_OPEN_NOW_PLAYING, true)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (playerController?.isPlaying?.value == true) return
                    resumeOnFocusGain = false
                    lastPlayStartTime = System.currentTimeMillis()
                    requestAudioFocus()
                    playerController?.play()
                }

                override fun onPause() {
                    resumeOnFocusGain = false
                    playerController?.pause()
                }

                override fun onSkipToNext() {
                    playNext()
                }

                override fun onSkipToPrevious() {
                    playPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    playerController?.seekTo(pos)
                    updatePlaybackState(playerController?.isPlaying?.value == true, pos)
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == ACTION_LIKE) {
                        toggleLike()
                    }
                }
            })
            isActive = true
        }
    }

    private var audioManager: AudioManager? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false
    private var lastPlayStartTime = 0L
    private val STARTUP_FOCUS_GRACE_MS = 6_000L

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val msSinceStart = System.currentTimeMillis() - lastPlayStartTime
        // Vendor ROMs and Bluetooth route changes send a transient loss or duck while the media
        // notification/artwork is rebuilt. Acting on it is heard as the song stopping and starting
        // again, so the whole startup window is left alone.
        val withinStartupGrace = msSinceStart < STARTUP_FOCUS_GRACE_MS
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeOnFocusGain = false
                playerController?.pause()
                publishPausedByFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (withinStartupGrace) {
                    return@OnAudioFocusChangeListener
                }
                hasAudioFocus = false
                val wasPlaying = playerController?.isPlaying?.value == true
                resumeOnFocusGain = wasPlaying
                playerController?.pause()
                if (wasPlaying) publishPausedByFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A duck is not a pause: keep the track running and only dip the level a little.
                if (withinStartupGrace) {
                    return@OnAudioFocusChangeListener
                }
                playerController?.rampVolume(70, 120L)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                playerController?.rampVolume(vlcVolume.value, 80L)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    playerController?.play()
                }
            }
        }
    }

    /** Keeps QS and the notification in step when audio focus, not the user, paused playback. */
    private fun publishPausedByFocus() {
        updatePlaybackState(false)
        _currentTrack.value?.let { showNotification(it, false, currentArtBitmap) }
    }

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Build the request once: creating a new AudioFocusRequest for every resume makes
            // Android (and MIUI) renegotiate the audio route, which delayed the first sound.
            val focusRequest = audioFocusRequest ?: android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        hasAudioFocus = false
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun playTrackList(tracks: List<AudioItem>, startIndex: Int = 0, source: String? = null) {
        if (tracks.isEmpty()) return
        _playlist.value = tracks
        if (!source.isNullOrBlank()) {
            _playbackSource.value = source
        } else {
            val first = tracks.firstOrNull()
            _playbackSource.value = when {
                first != null && first.folderName.isNotBlank() && !first.folderName.equals("Music", ignoreCase = true) -> first.folderName
                first != null && first.album.isNotBlank() && first.album != "Unknown Album" -> first.album
                else -> "All Songs"
            }
        }
        currentIndex = startIndex.coerceIn(0, tracks.size - 1)
        playCurrent()
    }

    fun updatePlaylistSilently(tracks: List<AudioItem>) {
        if (tracks.isEmpty()) return
        _playlist.value = tracks
    }

    fun playTrack(track: AudioItem) {
        val list = _playlist.value.toMutableList()
        val index = list.indexOfFirst { it.id == track.id }
        if (index != -1) {
            currentIndex = index
        } else {
            list.add(track)
            _playlist.value = list
            currentIndex = list.size - 1
        }
        playCurrent()
    }

    /**
     * Plays the queue entry at [index]. Index based so duplicated tracks in the queue
     * resolve to the exact row the user swiped to instead of the first id match.
     */
    fun playTrackAt(index: Int, track: AudioItem? = null) {
        val tracks = _playlist.value
        if (index in tracks.indices && (track == null || tracks[index].id == track.id)) {
            currentIndex = index
            playCurrent()
        } else if (track != null) {
            playTrack(track)
        }
    }

    fun playStream(url: String, username: String? = null, password: String? = null) {
        val streamUri = Uri.parse(url)
        val host = streamUri.host ?: "Online Stream"
        val title = streamUri.lastPathSegment?.substringBeforeLast('.') ?: host
        val streamItem = AudioItem(
            id = System.currentTimeMillis(),
            title = if (title.isNotBlank()) title else "Live Audio Stream",
            artist = host,
            album = "Network Stream",
            duration = 0L,
            path = url,
            uri = streamUri,
            folderName = "Network Streams"
        )
        _playlist.value = listOf(streamItem)
        currentIndex = 0
        _currentTrack.value = streamItem
        lastPlayStartTime = System.currentTimeMillis()
        resumeOnFocusGain = false
        playerController?.resetProgress()
        requestAudioFocus()
        playerController?.playMedia(url, streamUri, 0L, username, password)
        updateTrackMetadataAndNotification(streamItem, isPlaying = true)
    }

    private fun playCurrent() {
        acquireWakeLock()
        val tracks = _playlist.value
        if (tracks.isEmpty() || currentIndex !in tracks.indices) return
        val track = tracks[currentIndex]
        _currentTrack.value = track
        lastPlayStartTime = System.currentTimeMillis()

        prefsManager.recordTrackPlayed(track.id)
        prefsManager.savePlaybackSession(track.id, tracks.map { it.id }, currentIndex, _playbackSource.value)
        // This explicit track start supersedes any pending focus-gain resume from old media.
        resumeOnFocusGain = false
        // Clear the previous track's position so the UI never shows a stale time/progress
        playerController?.resetProgress()
        if (playHistory.lastOrNull() != currentIndex) {
            playHistory.addLast(currentIndex)
            if (playHistory.size > 120) playHistory.removeFirst()
        }
        requestAudioFocus()
        playerController?.playMedia(track.path, track.uri)
        updateTrackMetadataAndNotification(track, isPlaying = true)

        // Asynchronously fetch online artwork and artist if local art is absent or artist is unknown
        val isArtistUnknown = track.artist.isBlank() || track.artist == "<unknown>" || track.artist.equals("Unknown Artist", ignoreCase = true)
        if ((track.onlineArtUrl.isNullOrEmpty() && !com.jk.mediaplayer.ui.music.AlbumArtColorExtractor.hasValidLocalAlbumArt(this, track)) || isArtistUnknown) {
            scope.launch(Dispatchers.IO) {
                val cached = com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedAlbumArtUrl(this@AudioPlayerService, track.artist, track.album, track.title)
                val artUrl = cached ?: com.jk.mediaplayer.data.OnlineMetadataFetcher.fetchAlbumArtUrl(this@AudioPlayerService, track.artist, track.album, track.title)
                val onlineArtist = com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedArtistName(this@AudioPlayerService, track.title)
                val finalArtist = if (isArtistUnknown && !onlineArtist.isNullOrBlank()) onlineArtist else track.artist
                val finalArt = if (!artUrl.isNullOrBlank()) artUrl else track.onlineArtUrl

                if (finalArt != track.onlineArtUrl || finalArtist != track.artist) {
                    val updated = track.copy(onlineArtUrl = finalArt, artist = finalArtist)
                    withContext(Dispatchers.Main) {
                        if (_currentTrack.value?.id == track.id) {
                            _currentTrack.value = updated
                            val pl = _playlist.value.toMutableList()
                            val idx = pl.indexOfFirst { it.id == track.id }
                            if (idx >= 0) {
                                pl[idx] = updated
                                _playlist.value = pl
                            }
                            updateTrackMetadataAndNotification(updated, playerController?.isPlaying?.value == true)
                        }
                    }
                }
            }
        }
    }

    private fun updateTrackMetadataAndNotification(track: AudioItem, isPlaying: Boolean) {
        scope.launch(Dispatchers.IO) {
            val isUnknown = track.artist.isBlank() || track.artist == "<unknown>" || track.artist.equals("Unknown Artist", ignoreCase = true)
            val displayArtist = if (isUnknown) {
                com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedArtistName(this@AudioPlayerService, track.title) ?: track.artist
            } else track.artist

            val bitmap = loadAlbumArtBitmap(track)
            currentArtBitmap = bitmap

            // Build MediaMetadataCompat on IO thread to keep Binder serialization off Choreographer
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.duration)
            if (bitmap != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                // Some OEM media surfaces read ART / DISPLAY_ICON instead of ALBUM_ART.
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            }
            track.albumArtUri?.let {
                metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString())
                metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it.toString())
            }
            val metadata = metadataBuilder.build()

            withContext(Dispatchers.Main) {
                mediaSession?.setMetadata(metadata)
                showNotification(track.copy(artist = displayArtist), isPlaying, bitmap)
                // Republish after the metadata lands so lockscreen / QS controls pick up the
                // fresh like state (the custom action icon lives in PlaybackState).
                updatePlaybackState(playerController?.isPlaying?.value ?: isPlaying)
            }
        }
    }

    /**
     * Decodes album art downsampled to notification / lockscreen size (256px).
     * Keeping this at 256px eliminates GC churn, visible swipe stutter,
     * and oversized Binder IPC transactions while retaining crisp notification artwork.
     */
    private suspend fun loadAlbumArtBitmap(track: AudioItem): Bitmap? {
        val targetPx = 256
        try {
            val cachedFile = com.jk.mediaplayer.data.AudioAlbumArtCache.getCachedArtworkFile(this, track)
            if (cachedFile != null && cachedFile.exists()) {
                val bytes = cachedFile.readBytes()
                decodeByteArrayDownsampled(bytes, targetPx)?.let { return it }
            }

            track.albumArtUri?.let { artUri ->
                val bytes = contentResolver.openInputStream(artUri)?.use { it.readBytes() }
                if (bytes != null) {
                    decodeByteArrayDownsampled(bytes, targetPx)?.let { return it }
                }
            }
            val mmr = MediaMetadataRetriever()
            try {
                if (track.path.isNotEmpty() && File(track.path).exists()) {
                    mmr.setDataSource(track.path)
                } else {
                    mmr.setDataSource(this, track.uri)
                }
                val raw = mmr.embeddedPicture
                if (raw != null) {
                    decodeByteArrayDownsampled(raw, targetPx)?.let { return it }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
            val onlineArt = track.onlineArtUrl
                ?: com.jk.mediaplayer.data.OnlineMetadataFetcher.getCachedAlbumArtUrl(this, track.artist, track.album, track.title)
            if (!onlineArt.isNullOrEmpty()) {
                val model: Any = if (onlineArt.startsWith("/") || onlineArt.startsWith("file:")) {
                    File(onlineArt.removePrefix("file://"))
                } else onlineArt
                val loader = coil.Coil.imageLoader(this)
                val request = coil.request.ImageRequest.Builder(this)
                    .data(model)
                    .size(targetPx, targetPx)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? coil.request.SuccessResult)?.drawable
                val b = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (b != null) return b
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decodeByteArrayDownsampled(raw: ByteArray, targetPx: Int): Bitmap? {
        // First pass reads bounds only (cheap), second pass decodes at a sampled size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > targetPx) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }

    fun toggleRepeatMode() {
        repeatMode.value = (repeatMode.value + 1) % 3
        // Publish to the system session so QS / lockscreen show the right repeat icon.
        mediaSession?.setRepeatMode(
            when (repeatMode.value) {
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            }
        )
        updatePlaybackState(playerController?.isPlaying?.value == true)
    }

    fun toggleShuffle() {
        isShuffle.value = !isShuffle.value
        mediaSession?.setShuffleMode(
            if (isShuffle.value) PlaybackStateCompat.SHUFFLE_MODE_ALL
            else PlaybackStateCompat.SHUFFLE_MODE_NONE
        )
        updatePlaybackState(playerController?.isPlaying?.value == true)
    }

    private fun onTrackEnded() {
        val tracks = _playlist.value
        if (tracks.isEmpty()) return
        // Ignore switch-generated end events, but scale the guard for short tracks so their
        // legitimate EndReached event is not discarded by a fixed 1.5 second delay.
        val durationMs = playerController?.totalDuration?.value
            ?.takeIf { it > 0L }
            ?: _currentTrack.value?.duration
            ?: 0L
        val guardMs = if (durationMs > 0L) minOf(1500L, durationMs * 4L / 5L) else 1500L
        if (System.currentTimeMillis() - lastPlayStartTime < guardMs) {
            return
        }
        when (repeatMode.value) {
            2 -> { // Repeat One: replay the same track from beginning
                playCurrent()
            }
            1 -> { // Repeat All: loop infinitely
                playNext()
            }
            else -> { // Off
                if (isShuffle.value) {
                    playNext()
                } else if (currentIndex < tracks.size - 1) {
                    playNext()
                } else {
                    // Reached end of playlist
                    playerController?.seekTo(0L)
                    updatePlaybackState(false, 0L)
                }
            }
        }
    }

    fun seekTo(timeMs: Long) {
        playerController?.seekTo(timeMs)
        updatePlaybackState(playerController?.isPlaying?.value == true, timeMs)
    }

    fun seekBy(deltaMs: Long) {
        playerController?.seekBy(deltaMs)
        updatePlaybackState(playerController?.isPlaying?.value == true)
    }

    fun setVolume(volume: Int) {
        val applied = volume.coerceIn(0, 200)
        vlcVolume.value = applied
        playerController?.setVolume(applied)
    }

    fun getVolume(): Int {
        return vlcVolume.value
    }

    fun toggleLike() {
        val track = _currentTrack.value ?: return
        toggleFavorite(track.id)
    }

    fun toggleFavorite(trackId: Long) {
        prefsManager.toggleFavorite(trackId)
        _favoriteTrackIds.value = prefsManager.getFavorites()
        val curTrack = _currentTrack.value
        if (curTrack != null && curTrack.id == trackId) {
            updateTrackMetadataAndNotification(curTrack, playerController?.isPlaying?.value == true)
        }
        updatePlaybackState(playerController?.isPlaying?.value == true)
    }

    fun addToQueue(track: AudioItem) {
        val list = _playlist.value.toMutableList()
        list.add(track)
        _playlist.value = list
    }

    fun playNextInQueue(track: AudioItem) {
        val list = _playlist.value.toMutableList()
        val curTrackId = _currentTrack.value?.id
        val existingIndex = list.indexOfFirst { it.id == track.id }
        if (existingIndex >= 0) {
            list.removeAt(existingIndex)
        }
        val curIdx = if (curTrackId != null) list.indexOfFirst { it.id == curTrackId } else currentIndex
        val insertIdx = if (curIdx >= 0) (curIdx + 1).coerceAtMost(list.size) else (currentIndex + 1).coerceAtMost(list.size)
        list.add(insertIdx, track)
        if (curIdx >= 0) {
            currentIndex = curIdx
        }
        _playlist.value = list
    }

    fun playNext() {
        val tracks = _playlist.value
        if (tracks.isEmpty()) return
        if (tracks.size == 1) {
            playerController?.seekTo(0L)
            if (playerController?.isPlaying?.value != true) {
                playerController?.play()
            }
            return
        }
        if (isShuffle.value && tracks.size > 1) {
            var nextIndex = currentIndex
            var attempts = 0
            while (nextIndex == currentIndex && attempts < 10) {
                nextIndex = kotlin.random.Random.nextInt(tracks.size)
                attempts++
            }
            currentIndex = nextIndex
        } else if (currentIndex < tracks.size - 1) {
            currentIndex++
        } else if (repeatMode.value != 0) {
            // Repeat All (and Repeat One) wrap back to the first track
            currentIndex = 0
        } else {
            // End of queue with repeat off: keep the last track, no wrap around
            return
        }
        playCurrent()
    }

    fun playPrevious() {
        val tracks = _playlist.value
        if (tracks.isEmpty()) return
        if (tracks.size == 1) {
            playerController?.seekTo(0L)
            if (playerController?.isPlaying?.value != true) {
                playerController?.play()
            }
            return
        }
        // Live position first: the cached tick can be stale right after a track change
        val currentPos = playerController?.currentPositionMs() ?: 0L
        if (currentPos > previousRestartThresholdMs) {
            playerController?.seekTo(0L)
            return
        }
        if (isShuffle.value && playHistory.size >= 2) {
            playHistory.removeLast() // drop current entry
            val previous = playHistory.removeLast()
            if (previous in tracks.indices) {
                currentIndex = previous
                playCurrent()
                return
            }
        }
        currentIndex = when {
            currentIndex - 1 >= 0 -> currentIndex - 1
            // Repeat All / Repeat One wrap to the last track, plain "Off" just restarts this one
            repeatMode.value != 0 -> tracks.size - 1
            else -> {
                playerController?.seekTo(0L)
                return
            }
        }
        playCurrent()
    }

    fun removeFromPlaylist(trackId: Long) {
        val list = _playlist.value.toMutableList()
        val removeIndex = list.indexOfFirst { it.id == trackId }
        if (removeIndex == -1) return
        val isCurrent = removeIndex == currentIndex
        list.removeAt(removeIndex)
        _playlist.value = list
        if (list.isEmpty()) {
            _currentTrack.value = null
            playerController?.stop()
            updatePlaybackState(false)
        } else if (isCurrent) {
            currentIndex = removeIndex.coerceIn(0, list.size - 1)
            playCurrent()
        } else if (removeIndex < currentIndex) {
            currentIndex--
        }
    }

    fun movePlaylistItem(fromIndex: Int, toIndex: Int) {
        val list = _playlist.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val cur = currentIndex
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _playlist.value = list
        // Index based update: looking the track up by id used to snap the current index to the
        // first duplicate of the same song after a reorder.
        currentIndex = when {
            cur == fromIndex -> toIndex
            fromIndex < cur && toIndex >= cur -> cur - 1
            fromIndex > cur && toIndex <= cur -> cur + 1
            else -> cur
        }
    }

    fun togglePlayPause() {
        // A direct user command supersedes any automatic focus-gain resume, including pause.
        resumeOnFocusGain = false
        // Restored session: the track is shown in the mini player but no media was ever loaded.
        if (playerController?.mediaPlayer?.media == null && _currentTrack.value != null) {
            playCurrent()
            return
        }
        val isCurrentlyPlaying = playerController?.isPlaying?.value == true
        if (isCurrentlyPlaying) {
            // Keep audio focus while paused. Abandoning it made Android/MIUI tear the audio route
            // down (Bluetooth especially), so the next play needed ~0.5s before any sound.
            playerController?.togglePlayPause()
        } else {
            lastPlayStartTime = System.currentTimeMillis()
            requestAudioFocus()
            if (playerController?.hasEnded == true || playerController?.playerState == 6) {
                playCurrent()
            } else {
                playerController?.togglePlayPause()
            }
        }
    }

    private fun updatePlaybackState(
        isPlaying: Boolean,
        customPosition: Long = -1L,
        buffering: Boolean = false
    ) {
        val state = when {
            buffering -> PlaybackStateCompat.STATE_BUFFERING
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val position = if (customPosition >= 0L) customPosition else (playerController?.currentTime?.value ?: 0L)
        val track = _currentTrack.value
        val isFav = if (track != null) prefsManager.isFavorite(track.id) else false
        val likeIcon = if (isFav) R.drawable.ic_notif_favorite else R.drawable.ic_notif_favorite_border
        val likeCustomAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_LIKE,
            if (isFav) "Unlike" else "Like",
            likeIcon
        ).build()

        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, if (isPlaying) 1.0f else 0.0f, android.os.SystemClock.elapsedRealtime())
        builder.addCustomAction(likeCustomAction)

        mediaSession?.isActive = true
        mediaSession?.setPlaybackState(builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active music playback notification"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(track: AudioItem, isPlaying: Boolean, artBitmap: Bitmap? = currentArtBitmap) {
        // Rebuilding the notification on every isPlaying emission called startForeground() and
        // re-read the favourite state on the main thread for nothing. The key covers everything
        // the visible card renders, so identical states skip the whole update.
        val isFav = prefsManager.isFavorite(track.id)
        val notificationKey = buildString {
            append(track.id).append('|')
            append(track.title).append('|')
            append(track.artist).append('|')
            append(track.album).append('|')
            append(isPlaying).append('|')
            append(isFav).append('|')
            append(artBitmap?.let { System.identityHashCode(it) } ?: 0)
        }
        if (notificationKey == lastNotificationKey) return
        lastNotificationKey = notificationKey

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Tapping the QS / lock screen player should land directly on the full player.
            putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val playPauseAction = NotificationCompat.Action(
            playPauseIcon,
            if (isPlaying) "Pause" else "Play",
            buildMediaPendingIntent(ACTION_TOGGLE)
        )

        val nextAction = NotificationCompat.Action(
            R.drawable.ic_notif_next,
            "Next",
            buildMediaPendingIntent(ACTION_NEXT)
        )

        val prevAction = NotificationCompat.Action(
            R.drawable.ic_notif_prev,
            "Previous",
            buildMediaPendingIntent(ACTION_PREV)
        )

        val likeIcon = if (isFav) R.drawable.ic_notif_favorite else R.drawable.ic_notif_favorite_border
        val likeAction = NotificationCompat.Action(
            likeIcon,
            if (isFav) "Unlike" else "Like",
            buildMediaPendingIntent(ACTION_LIKE)
        )

        val builder = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album)
            // Monochrome status bar icon: origin-style media surfaces reject/blank adaptive
            // launcher icons used as small icons.
            .setSmallIcon(R.drawable.ic_notif_play)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // TRANSPORT category and single-alert updates help OEM media surfaces recognise and
            // refresh the player instead of caching the first frame.
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setColorized(false)
            .setShowWhen(false)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(likeAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // Keep the media card alive while paused: several OEM media surfaces (OriginOS
            // Origin Island included) drop non-ongoing media notifications when playback stops.
            .setOngoing(true)

        if (artBitmap != null) {
            builder.setLargeIcon(artBitmap)
        }

        val notification: Notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun buildMediaPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlayerService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_LIKE -> toggleLike()
        }
        return START_NOT_STICKY
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (playerController?.isPlaying?.value == true) {
                    playerController?.togglePlayPause()
                }
            }
        }
    }

    private var isNoisyReceiverRegistered = false

    private fun registerBecomingNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
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
                isNoisyReceiverRegistered = true
            } catch (_: Exception) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app out of recents must stop playback and the service.
        // Without this the foreground service kept the process (and audio) alive indefinitely.
        try { playerController?.stop() } catch (_: Exception) {}
        abandonAudioFocus()
        AudioSession.close(this)
        mediaSession?.isActive = false
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        // A restarted service must post its notification again.
        lastNotificationKey = null
        AudioSession.close(this)
        abandonAudioFocus()
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
        mediaSession?.isActive = false
        mediaSession?.release()
        playerController?.release()
        playerController = null
        releaseWakeLock()
    }

    companion object {
        @Volatile
        var instance: AudioPlayerService? = null
            private set

        const val ACTION_TOGGLE = "com.jk.mediaplayer.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.jk.mediaplayer.ACTION_NEXT"
        const val ACTION_PREV = "com.jk.mediaplayer.ACTION_PREV"
        const val ACTION_LIKE = "com.jk.mediaplayer.ACTION_LIKE"
    const val EXTRA_OPEN_NOW_PLAYING = "com.jk.mediaplayer.EXTRA_OPEN_NOW_PLAYING"
    }
}
