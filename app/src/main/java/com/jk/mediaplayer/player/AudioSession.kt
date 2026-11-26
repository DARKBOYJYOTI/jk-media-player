package com.jk.mediaplayer.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect

/**
 * Global AudioSession manager that generates and holds a single, static audio session ID
 * for the application's music playback.
 *
 * This audio session ID is passed to LibVLC via `--audiotrack-session-id=<id>` so that
 * the underlying native AudioTrack is bound to this ID. External system equalizers
 * (Wavelet, Poweramp Equalizer, Equalizer FX, system DSP) can then attach to the audio stream.
 */
object AudioSession {
    @Volatile private var _id = 0
    @Volatile private var isSessionActive = false

    fun id(context: Context): Int {
        if (_id == 0) {
            synchronized(this) {
                if (_id == 0) {
                    val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    _id = am.generateAudioSessionId()
                }
            }
        }
        return _id
    }

    /**
     * Broadcasts ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION once so external equalizer apps
     * hook into this session without repeatedly interrupting or glitching running audio.
     */
    fun open(context: Context) {
        if (isSessionActive) {
            return // Already active, avoid re-triggering AudioFlinger effect pipeline interruption
        }
        val sessionId = id(context)
        if (sessionId == 0) return
        try {
            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            context.applicationContext.sendBroadcast(intent)
            isSessionActive = true
        } catch (e: Exception) {
        }
    }

    /**
     * Broadcasts ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION when playback is stopped
     * or the audio service is destroyed.
     */
    fun close(context: Context) {
        if (!isSessionActive) return
        isSessionActive = false
        val sessionId = id(context)
        if (sessionId == 0) return
        try {
            val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            }
            context.applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
        }
    }

    /**
     * Launches the system audio equalizer control panel (ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).
     * If no system equalizer or third-party EQ app is installed, invokes [onFallback].
     */
    fun openSystemEqualizer(activity: Activity, onFallback: () -> Unit = {}) {
        val sessionId = id(activity)
        // The session is only announced when the user asks for effects: an automatic broadcast at
        // every start made OEM speaker DSPs attach mid-stream, which was heard as a click.
        open(activity)
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, activity.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivityForResult(intent, 0)
        } else {
            onFallback()
        }
    }
}
