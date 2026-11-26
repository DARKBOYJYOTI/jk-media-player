package com.jk.mediaplayer.player

import android.content.Context
import java.io.File

/**
 * Temporary measurement-only overrides for the music audio path.
 *
 * The values are read from `Android/data/<package>/files/audio-ab.txt` so one diagnostic build
 * can run the whole A/B matrix without a rebuild. The file is only read when
 * `BuildConfig.AUDIO_DIAG` is set, so shipped builds never touch it.
 *
 * Format: one `key=value` per line, unknown keys ignored.
 *
 * ```
 * aout=android_audiotrack   # android_audiotrack | opensles
 * time_stretch=0            # 1 keeps pitch while changing tempo
 * file_caching=400          # milliseconds
 * resume_seek=0             # 1 seeks to the current position before play() on resume
 * verbose=1                 # 1 turns on libVLC verbose logging
 * ```
 */
internal data class AudioAbConfig(
    val aout: String = "android_audiotrack",
    val timeStretch: Boolean = false,
    val fileCachingMs: Int = 400,
    val resumeSeek: Boolean = false,
    val verbose: Boolean = false
) {
    fun summary(): String =
        "aout=$aout timeStretch=$timeStretch fileCaching=$fileCachingMs " +
            "resumeSeek=$resumeSeek verbose=$verbose"

    companion object {
        fun file(context: Context): File =
            File(context.getExternalFilesDir(null), "audio-ab.txt")

        fun load(context: Context): AudioAbConfig {
            val defaults = AudioAbConfig()
            val file = file(context)
            if (!file.isFile) return defaults
            return try {
                val values = HashMap<String, String>()
                file.forEachLine { raw ->
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEachLine
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@forEachLine
                    values[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
                AudioAbConfig(
                    aout = values["aout"]?.takeIf { it.isNotBlank() } ?: defaults.aout,
                    timeStretch = values["time_stretch"]?.toBooleanStrictOrNull()
                        ?: defaults.timeStretch,
                    fileCachingMs = values["file_caching"]?.toIntOrNull()
                        ?.takeIf { it in 100..10_000 } ?: defaults.fileCachingMs,
                    resumeSeek = values["resume_seek"]?.toBooleanStrictOrNull()
                        ?: defaults.resumeSeek,
                    verbose = values["verbose"]?.toBooleanStrictOrNull() ?: defaults.verbose
                )
            } catch (_: Throwable) {
                defaults
            }
        }
    }
}
