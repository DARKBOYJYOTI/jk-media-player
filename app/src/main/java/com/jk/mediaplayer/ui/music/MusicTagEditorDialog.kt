package com.jk.mediaplayer.ui.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jk.mediaplayer.R
import com.jk.mediaplayer.data.AudioTagData
import com.jk.mediaplayer.data.AudioTagManager
import com.jk.mediaplayer.data.OnlineMetadataFetcher
import com.jk.mediaplayer.model.AudioItem
import com.jk.mediaplayer.ui.common.AppToast
import com.jk.mediaplayer.ui.theme.LightTextPrimary
import com.jk.mediaplayer.ui.theme.LightTextSecondary
import com.jk.mediaplayer.ui.theme.MusicRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Upper bound for a downloaded cover so a wrong content type cannot exhaust the heap. */
private const val MAX_ARTWORK_BYTES = 12L * 1024 * 1024

/**
 * Professional AMOLED / Light Mode Audio ID3 Tag Editor
 */
@Composable
fun MusicTagEditorDialog(
    track: AudioItem,
    onDismiss: () -> Unit,
    onTagsSaved: (AudioItem) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val dialogBg = if (isDark) Color(0xFF13151E) else Color(0xFFFFFFFF)
    val textPrimary = if (isDark) Color.White else LightTextPrimary
    val textSecondary = if (isDark) Color(0xFFAAAAAA) else LightTextSecondary
    val inputBg = if (isDark) Color(0xFF1B1E2B) else Color(0xFFF3F5F9)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    var isLoadingTags by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    // Tag Field States
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var trackNumber by remember { mutableStateOf("") }
    var trackTotal by remember { mutableStateOf("") }
    var discNumber by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }

    // Artwork State
    var originalArtworkBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedArtworkBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isArtworkRemoved by remember { mutableStateOf(false) }

    // Gallery Picker for Album Cover
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val maxDim = 1000
                            val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                val (w, h) = if (ratio > 1f) {
                                    Pair(maxDim, (maxDim / ratio).toInt())
                                } else {
                                    Pair((maxDim * ratio).toInt(), maxDim)
                                }
                                Bitmap.createScaledBitmap(bitmap, w, h, true)
                            } else {
                                bitmap
                            }
                            val baos = ByteArrayOutputStream()
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                            val compressed = baos.toByteArray()
                            withContext(Dispatchers.Main) {
                                selectedArtworkBytes = compressed
                                isArtworkRemoved = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        AppToast.show("Could not load image: ${e.message}")
                    }
                }
            }
        }
    }

    // Load initial tags on launch
    LaunchedEffect(track.path) {
        withContext(Dispatchers.IO) {
            val result = AudioTagManager.readTags(track.path)
            withContext(Dispatchers.Main) {
                isLoadingTags = false
                result.onSuccess { data ->
                    title = data.title.ifBlank { track.title }
                    artist = data.artist.ifBlank { track.artist }
                    album = data.album.ifBlank { track.album }
                    albumArtist = data.albumArtist
                    genre = data.genre
                    year = data.year
                    trackNumber = data.trackNumber
                    trackTotal = data.trackTotal
                    discNumber = data.discNumber
                    composer = data.composer
                    comment = data.comment
                    originalArtworkBytes = data.artworkBytes
                }.onFailure {
                    // Fallbacks already in place
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(24.dp)),
            color = dialogBg,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MusicRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = MusicRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Edit Tag",
                                color = textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = File(track.path).name,
                                color = textSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isSaving) onDismiss() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = borderColor)
                Spacer(modifier = Modifier.height(14.dp))

                if (isLoadingTags) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MusicRed)
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    ) {
                        // Section: Album Artwork
                        Text(
                            text = "ALBUM ARTWORK",
                            color = MusicRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Cover Preview Card
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = inputBg,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isArtworkRemoved) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.HideImage,
                                                contentDescription = null,
                                                tint = textSecondary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Removed",
                                                color = textSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    } else {
                                        val displayData: Any = selectedArtworkBytes
                                            ?: originalArtworkBytes
                                            ?: track
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(displayData)
                                                .crossfade(150)
                                                .error(R.drawable.ic_default_album_art)
                                                .placeholder(R.drawable.ic_default_album_art)
                                                .fallback(R.drawable.ic_default_album_art)
                                                .build(),
                                            contentDescription = "Cover Art",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Artwork Buttons
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { imagePickerLauncher.launch("image/*") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MusicRed.copy(alpha = 0.15f),
                                        contentColor = MusicRed
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddPhotoAlternate,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Change Cover",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (!isArtworkRemoved && (selectedArtworkBytes != null || originalArtworkBytes != null || track.albumArtUri != null)) {
                                    OutlinedButton(
                                        onClick = {
                                            isArtworkRemoved = true
                                            selectedArtworkBytes = null
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFFFF5252)
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Remove Cover",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                } else if (isArtworkRemoved) {
                                    OutlinedButton(
                                        onClick = {
                                            isArtworkRemoved = false
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = textPrimary
                                        ),
                                        border = BorderStroke(1.dp, borderColor),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Restore Cover",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "METADATA FIELDS",
                            color = MusicRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Title
                        TagInputField(
                            label = "Title",
                            value = title,
                            onValueChange = { title = it },
                            textColor = textPrimary,
                            bgColor = inputBg,
                            borderColor = borderColor
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Artist
                        TagInputField(
                            label = "Artist",
                            value = artist,
                            onValueChange = { artist = it },
                            textColor = textPrimary,
                            bgColor = inputBg,
                            borderColor = borderColor
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Album
                        TagInputField(
                            label = "Album",
                            value = album,
                            onValueChange = { album = it },
                            textColor = textPrimary,
                            bgColor = inputBg,
                            borderColor = borderColor
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Album Artist
                        TagInputField(
                            label = "Album Artist",
                            value = albumArtist,
                            onValueChange = { albumArtist = it },
                            textColor = textPrimary,
                            bgColor = inputBg,
                            borderColor = borderColor
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Genre & Year in 2 columns
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TagInputField(
                                    label = "Genre",
                                    value = genre,
                                    onValueChange = { genre = it },
                                    textColor = textPrimary,
                                    bgColor = inputBg,
                                    borderColor = borderColor
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TagInputField(
                                    label = "Year",
                                    value = year,
                                    onValueChange = { year = it },
                                    textColor = textPrimary,
                                    bgColor = inputBg,
                                    borderColor = borderColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Track No & Track Total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TagInputField(
                                    label = "Track No",
                                    value = trackNumber,
                                    onValueChange = { trackNumber = it },
                                    textColor = textPrimary,
                                    bgColor = inputBg,
                                    borderColor = borderColor
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TagInputField(
                                    label = "Total Tracks",
                                    value = trackTotal,
                                    onValueChange = { trackTotal = it },
                                    textColor = textPrimary,
                                    bgColor = inputBg,
                                    borderColor = borderColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Disc Number & Composer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TagInputField(
                                    label = "Disc No",
                                    value = discNumber,
                                    onValueChange = { discNumber = it },
                                    textColor = textPrimary,
                                    bgColor = inputBg,
                                    borderColor = borderColor
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TagInputField(
                                    label = "Composer",
                                    value = composer,
                                    onValueChange = { composer = it },
                                    textColor = textPrimary,
                                    bgColor = inputBg,
                                    borderColor = borderColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Comment
                        TagInputField(
                            label = "Comment",
                            value = comment,
                            onValueChange = { comment = it },
                            textColor = textPrimary,
                            bgColor = inputBg,
                            borderColor = borderColor,
                            singleLine = false
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = borderColor)
                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Buttons: Cancel & Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }

                    Button(
                        onClick = {
                            isSaving = true
                            coroutineScope.launch {
                                // Only a viewer choice replaces the cover. With no choice and no
                                // removal, a track that has no embedded cover keeps the online
                                // picture it already shows in the preview, and a track that has
                                // one is left exactly as it is.
                                val artworkToSave = when {
                                    isArtworkRemoved -> null
                                    selectedArtworkBytes != null -> selectedArtworkBytes
                                    originalArtworkBytes != null -> null
                                    else -> fetchOnlineArtworkBytes(context, track)
                                }

                                val updatedData = AudioTagData(
                                    title = title.trim(),
                                    artist = artist.trim(),
                                    album = album.trim(),
                                    albumArtist = albumArtist.trim(),
                                    genre = genre.trim(),
                                    year = year.trim(),
                                    trackNumber = trackNumber.trim(),
                                    trackTotal = trackTotal.trim(),
                                    discNumber = discNumber.trim(),
                                    composer = composer.trim(),
                                    comment = comment.trim(),
                                    hasArtwork = !isArtworkRemoved &&
                                        (artworkToSave != null || originalArtworkBytes != null)
                                )

                                val result = AudioTagManager.saveTags(
                                    context = context,
                                    track = track,
                                    tagData = updatedData,
                                    newArtworkBytes = artworkToSave,
                                    removeArtwork = isArtworkRemoved
                                )

                                isSaving = false
                                result.onSuccess { updatedTrack ->
                                    AppToast.show("Tags updated successfully")
                                    onTagsSaved(updatedTrack)
                                    onDismiss()
                                }.onFailure { error ->
                                    AppToast.show("Failed to update tags: ${error.localizedMessage ?: "Unknown error"}")
                                }
                            }
                        },
                        enabled = !isSaving && !isLoadingTags,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MusicRed),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Tags", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 3,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = textColor,
            unfocusedTextColor = textColor,
            focusedContainerColor = bgColor,
            unfocusedContainerColor = bgColor,
            focusedBorderColor = MusicRed,
            unfocusedBorderColor = borderColor,
            focusedLabelColor = MusicRed,
            unfocusedLabelColor = textColor.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Resolves the artwork the editor preview is already showing for this track and returns it as
 * JPEG bytes that can be embedded. The online lookup is the same one the image loader uses, so the
 * saved picture matches the preview instead of being a second, unrelated search.
 */
private suspend fun fetchOnlineArtworkBytes(context: Context, track: AudioItem): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            val candidates = LinkedHashSet<String>()
            track.onlineArtUrl?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            OnlineMetadataFetcher.resolveAlbumArt(
                context = context,
                artist = track.artist,
                album = track.album,
                title = track.title
            )?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }

            for (source in candidates) {
                val raw = readArtworkSource(source) ?: continue
                compressArtwork(raw)?.let { return@withContext it }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

/** Reads a cached cover from disk, or downloads one that only exists as a remote URL. */
private fun readArtworkSource(source: String): ByteArray? {
    if (source.startsWith("/") || source.startsWith("file:")) {
        val file = if (source.startsWith("file:")) {
            File(Uri.parse(source).path ?: return null)
        } else {
            File(source)
        }
        if (!file.exists() || file.length() <= 0L) return null
        return file.readBytes()
    }

    val connection = (URL(source).openConnection() as? HttpURLConnection) ?: return null
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        if (connection.responseCode !in 200..299) return null
        if (connection.contentLengthLong > MAX_ARTWORK_BYTES) return null

        connection.inputStream.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                total += read
                if (total > MAX_ARTWORK_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
    } catch (_: Throwable) {
        null
    } finally {
        connection.disconnect()
    }
}

/** Decodes to a bounded bitmap and re-encodes as JPEG so the tag always carries a known format. */
private fun compressArtwork(raw: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val maxDim = 1000
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        raw,
        0,
        raw.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    val bitmap = if (decoded.width > maxDim || decoded.height > maxDim) {
        val ratio = decoded.width.toFloat() / decoded.height.toFloat()
        val (w, h) = if (ratio > 1f) {
            Pair(maxDim, (maxDim / ratio).toInt())
        } else {
            Pair((maxDim * ratio).toInt(), maxDim)
        }
        Bitmap.createScaledBitmap(decoded, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
    } else {
        decoded
    }

    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    return output.toByteArray()
}

