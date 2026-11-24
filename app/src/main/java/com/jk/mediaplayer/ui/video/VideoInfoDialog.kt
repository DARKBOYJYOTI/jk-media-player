package com.jk.mediaplayer.ui.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jk.mediaplayer.data.TechnicalVideoSpecs
import com.jk.mediaplayer.data.VideoMetadataManager
import com.jk.mediaplayer.data.VideoThumbnailCache
import com.jk.mediaplayer.model.VideoItem
import com.jk.mediaplayer.ui.common.AppToast
import com.jk.mediaplayer.ui.theme.VideoIndigo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VideoInfoDialog(
    video: VideoItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    var techSpecs by remember(video.id) {
        mutableStateOf<TechnicalVideoSpecs?>(null)
    }

    LaunchedEffect(video.id) {
        withContext(Dispatchers.IO) {
            val specs = VideoMetadataManager.extractTechnicalSpecs(context, video)
            withContext(Dispatchers.Main) {
                techSpecs = specs
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isPortrait) 0.92f else 0.65f)
                .wrapContentHeight()
                .padding(vertical = if (isPortrait) 24.dp else 10.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF13151E),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header: Video Thumbnail + Title + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Video Thumbnail
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1F2230),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        val thumbFile = remember(video.id) {
                            VideoThumbnailCache.getThumbnailFile(context, video)
                        }
                        if (thumbFile.exists() && thumbFile.length() > 0) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(thumbFile)
                                    .crossfade(100)
                                    .build(),
                                contentDescription = video.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // Title & Quick Duration/Size
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = video.title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${video.durationFormatted}  •  ${video.sizeFormatted}",
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    thickness = 1.dp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable Technical Specs Details
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = if (isPortrait) 450.dp else 180.dp)
                        .verticalScroll(scrollState)
                ) {
                    val specs = techSpecs
                    val container = specs?.container?.ifEmpty { File(video.path).extension.uppercase() } ?: File(video.path).extension.uppercase()

                    TechSpecRow(label = "Format / Container", value = container.ifEmpty { "VIDEO" })
                    TechSpecDivider()
                    TechSpecRow(label = "Resolution", value = specs?.resolution?.ifEmpty { video.resolution } ?: video.resolution)
                    TechSpecDivider()
                    TechSpecRow(label = "Video Codec", value = specs?.videoCodec?.ifEmpty { "H.264" } ?: "Loading...")
                    TechSpecDivider()
                    TechSpecRow(label = "Audio Tracks", value = "${specs?.audioCodec ?: "AAC"} • ${specs?.audioChannels ?: "Stereo (2.0)"}")
                    TechSpecDivider()
                    TechSpecRow(label = "Duration", value = video.durationFormatted)
                    TechSpecDivider()
                    TechSpecRow(label = "File Size", value = video.sizeFormatted)

                    if (!specs?.bitrate.isNullOrBlank()) {
                        TechSpecDivider()
                        TechSpecRow(label = "Bitrate", value = specs!!.bitrate)
                    }

                    if (!specs?.frameRate.isNullOrBlank()) {
                        TechSpecDivider()
                        TechSpecRow(label = "Frame Rate", value = specs!!.frameRate)
                    }

                    if (video.dateModifiedFormatted.isNotBlank()) {
                        TechSpecDivider()
                        TechSpecRow(label = "Date Modified", value = video.dateModifiedFormatted)
                    }

                    TechSpecDivider()

                    // File Location Row with 1-Tap Copy
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "Location",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = video.path.ifEmpty { video.uri.toString() },
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("File Path", video.path)
                                    clipboard?.setPrimaryClip(clip)
                                    AppToast.show("File path copied to clipboard")
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Path",
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action: Close Button
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = VideoIndigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "Close",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TechSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TechSpecDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        thickness = 1.dp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
