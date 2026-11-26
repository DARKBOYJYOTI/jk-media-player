package com.jk.mediaplayer.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIconHelper {

    private const val MUSIC_ALIAS_DEFAULT = "com.jk.mediaplayer.MusicLauncher"
    private const val MUSIC_ALIAS_MUSIC = "com.jk.mediaplayer.MusicLauncher_Music"

    private const val VIDEO_ALIAS_DEFAULT = "com.jk.mediaplayer.VideoLauncher"
    private const val VIDEO_ALIAS_VIDEOS = "com.jk.mediaplayer.VideoLauncher_Videos"

    fun getActualMusicLauncherLabel(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val musicAlias = ComponentName(pkg, MUSIC_ALIAS_MUSIC)
            val state = pm.getComponentEnabledSetting(musicAlias)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                "Music"
            } else if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                "JK Music"
            } else {
                val defaultAlias = ComponentName(pkg, MUSIC_ALIAS_DEFAULT)
                val defaultState = pm.getComponentEnabledSetting(defaultAlias)
                if (defaultState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    "Music"
                } else {
                    "JK Music"
                }
            }
        } catch (_: Exception) {
            "JK Music"
        }
    }

    fun getActualVideoLauncherLabel(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val videoAlias = ComponentName(pkg, VIDEO_ALIAS_VIDEOS)
            val state = pm.getComponentEnabledSetting(videoAlias)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                "Videos"
            } else if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                "JK Videos"
            } else {
                val defaultAlias = ComponentName(pkg, VIDEO_ALIAS_DEFAULT)
                val defaultState = pm.getComponentEnabledSetting(defaultAlias)
                if (defaultState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    "Videos"
                } else {
                    "JK Videos"
                }
            }
        } catch (_: Exception) {
            "JK Videos"
        }
    }

    fun updateMusicLauncherLabel(context: Context, name: String) {
        val targetAlias = if (name.trim() == "Music") MUSIC_ALIAS_MUSIC else MUSIC_ALIAS_DEFAULT
        val allMusicAliases = listOf(MUSIC_ALIAS_DEFAULT, MUSIC_ALIAS_MUSIC)
        applyAlias(context, targetAlias, allMusicAliases)
    }

    fun updateVideoLauncherLabel(context: Context, name: String) {
        val targetAlias = if (name.trim() == "Videos") VIDEO_ALIAS_VIDEOS else VIDEO_ALIAS_DEFAULT
        val allVideoAliases = listOf(VIDEO_ALIAS_DEFAULT, VIDEO_ALIAS_VIDEOS)
        applyAlias(context, targetAlias, allVideoAliases)
    }

    private fun applyAlias(context: Context, targetAlias: String, allAliases: List<String>) {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            for (alias in allAliases) {
                try {
                    val state = if (alias == targetAlias) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    pm.setComponentEnabledSetting(
                        ComponentName(pkg, alias),
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }
}
