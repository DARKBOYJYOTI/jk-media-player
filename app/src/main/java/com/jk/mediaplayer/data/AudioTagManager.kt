package com.jk.mediaplayer.data

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.jk.mediaplayer.model.AudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

data class AudioTagData(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val trackTotal: String = "",
    val discNumber: String = "",
    val composer: String = "",
    val comment: String = "",
    val hasArtwork: Boolean = false,
    val artworkBytes: ByteArray? = null,
    val mimeType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioTagData
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (albumArtist != other.albumArtist) return false
        if (genre != other.genre) return false
        if (year != other.year) return false
        if (trackNumber != other.trackNumber) return false
        if (trackTotal != other.trackTotal) return false
        if (discNumber != other.discNumber) return false
        if (composer != other.composer) return false
        if (comment != other.comment) return false
        if (hasArtwork != other.hasArtwork) return false
        if (artworkBytes != null) {
            if (other.artworkBytes == null) return false
            if (!artworkBytes.contentEquals(other.artworkBytes)) return false
        } else if (other.artworkBytes != null) return false
        return mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + albumArtist.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + year.hashCode()
        result = 31 * result + trackNumber.hashCode()
        result = 31 * result + trackTotal.hashCode()
        result = 31 * result + discNumber.hashCode()
        result = 31 * result + composer.hashCode()
        result = 31 * result + comment.hashCode()
        result = 31 * result + hasArtwork.hashCode()
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        return result
    }
}

object AudioTagManager {
    private const val TAG = "AudioTagManager"

    init {
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
        } catch (_: Throwable) {}
    }

    suspend fun readTags(filePath: String): Result<AudioTagData> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("File does not exist: $filePath"))
            }

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag

            val title = tag?.getFirst(FieldKey.TITLE) ?: ""
            val artist = tag?.getFirst(FieldKey.ARTIST) ?: ""
            val album = tag?.getFirst(FieldKey.ALBUM) ?: ""
            val albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST) ?: ""
            val genre = tag?.getFirst(FieldKey.GENRE) ?: ""
            val year = tag?.getFirst(FieldKey.YEAR) ?: ""
            val trackNumber = tag?.getFirst(FieldKey.TRACK) ?: ""
            val trackTotal = tag?.getFirst(FieldKey.TRACK_TOTAL) ?: ""
            val discNumber = tag?.getFirst(FieldKey.DISC_NO) ?: ""
            val composer = tag?.getFirst(FieldKey.COMPOSER) ?: ""
            val comment = tag?.getFirst(FieldKey.COMMENT) ?: ""

            val artwork = tag?.firstArtwork
            val hasArtwork = artwork != null && artwork.binaryData != null && artwork.binaryData.isNotEmpty()
            val artworkBytes = if (hasArtwork) artwork!!.binaryData else null
            val mimeType = artwork?.mimeType

            Result.success(
                AudioTagData(
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    genre = genre,
                    year = year,
                    trackNumber = trackNumber,
                    trackTotal = trackTotal,
                    discNumber = discNumber,
                    composer = composer,
                    comment = comment,
                    hasArtwork = hasArtwork,
                    artworkBytes = artworkBytes,
                    mimeType = mimeType
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error reading tags for $filePath", e)
            Result.failure(e)
        }
    }

    suspend fun saveTags(
        context: Context,
        track: AudioItem,
        tagData: AudioTagData,
        newArtworkBytes: ByteArray? = null,
        removeArtwork: Boolean = false
    ): Result<AudioItem> = withContext(Dispatchers.IO) {
        try {
            val file = File(track.path)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("File does not exist: ${track.path}"))
            }

            val audioFile = AudioFileIO.read(file)
            var tag = audioFile.tag
            if (tag == null) {
                tag = audioFile.createDefaultTag()
                audioFile.tag = tag
            }

            fun setOrDelete(key: FieldKey, value: String) {
                try {
                    if (value.isBlank()) {
                        tag.deleteField(key)
                    } else {
                        tag.setField(key, value.trim())
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not set tag field $key: ${e.message}")
                }
            }

            setOrDelete(FieldKey.TITLE, tagData.title)
            setOrDelete(FieldKey.ARTIST, tagData.artist)
            setOrDelete(FieldKey.ALBUM, tagData.album)
            setOrDelete(FieldKey.ALBUM_ARTIST, tagData.albumArtist)
            setOrDelete(FieldKey.GENRE, tagData.genre)
            setOrDelete(FieldKey.YEAR, tagData.year)
            setOrDelete(FieldKey.TRACK, tagData.trackNumber)
            setOrDelete(FieldKey.TRACK_TOTAL, tagData.trackTotal)
            setOrDelete(FieldKey.DISC_NO, tagData.discNumber)
            setOrDelete(FieldKey.COMPOSER, tagData.composer)
            setOrDelete(FieldKey.COMMENT, tagData.comment)

            if (removeArtwork) {
                try {
                    tag.deleteArtworkField()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error deleting artwork: ${e.message}")
                }
            } else if (newArtworkBytes != null && newArtworkBytes.isNotEmpty()) {
                try {
                    tag.deleteArtworkField()
                    val artwork = ArtworkFactory.getNew()
                    artwork.binaryData = newArtworkBytes
                    artwork.mimeType = "image/jpeg"
                    artwork.pictureType = PictureTypes.DEFAULT_ID
                    tag.setField(artwork)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error setting artwork: ${e.message}", e)
                }
            }

            audioFile.commit()

            // Invalidate local artwork cache for this track
            try {
                AudioAlbumArtCache.invalidateArtwork(context, track)
                coil.Coil.imageLoader(context).memoryCache?.clear()
            } catch (_: Throwable) {}

            // Broadcast MediaScanner update so Android system MediaStore picks up changes
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                null
            ) { path, uri ->
                Log.i(TAG, "MediaStore scan finished: $path -> $uri")
            }

            // Invalidate cached music so library view re-queries fresh metadata
            MediaScanner.cachedMusic = null

            val updatedTrack = track.copy(
                title = tagData.title.ifBlank { track.title },
                artist = tagData.artist.ifBlank { track.artist },
                album = tagData.album.ifBlank { track.album }
            )

            Result.success(updatedTrack)
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving tags for ${track.path}", e)
            Result.failure(e)
        }
    }
}

