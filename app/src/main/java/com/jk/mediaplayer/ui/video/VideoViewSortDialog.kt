package com.jk.mediaplayer.ui.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jk.mediaplayer.data.VideoSortField
import com.jk.mediaplayer.ui.theme.VideoIndigo

@Composable
fun VideoViewSortDialog(
    currentIsGrid: Boolean,
    currentSortField: VideoSortField,
    currentSortAscending: Boolean,
    folderName: String? = null,
    onDismiss: () -> Unit,
    onApply: (isGrid: Boolean, sortField: VideoSortField, sortAscending: Boolean, applyToFolderOnly: Boolean) -> Unit
) {
    var selectedIsGrid by remember { mutableStateOf(currentIsGrid) }
    var selectedSortField by remember { mutableStateOf(currentSortField) }
    var selectedSortAscending by remember { mutableStateOf(currentSortAscending) }
    var applyToFolderOnly by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header Row: Title & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = VideoIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = VideoIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "View & Sort",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // SECTION 1: VIEW MODE (List vs Grid)
                Text(
                    text = "VIEW MODE",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = VideoIndigo,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // List Option
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (!selectedIsGrid) VideoIndigo else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = if (!selectedIsGrid) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedIsGrid = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = null,
                                tint = if (!selectedIsGrid) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "List",
                                fontSize = 15.sp,
                                fontWeight = if (!selectedIsGrid) FontWeight.Bold else FontWeight.Medium,
                                color = if (!selectedIsGrid) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Grid Option
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedIsGrid) VideoIndigo else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = if (selectedIsGrid) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedIsGrid = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = null,
                                tint = if (selectedIsGrid) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Grid",
                                fontSize = 15.sp,
                                fontWeight = if (selectedIsGrid) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedIsGrid) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // SECTION 2: SORT BY (Name, Date, Size)
                Text(
                    text = "SORT BY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = VideoIndigo,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                val sortFields = listOf(
                    Triple(VideoSortField.NAME, "Name (Title)", Icons.Default.SortByAlpha),
                    Triple(VideoSortField.DATE, "Date (Modified)", Icons.Default.CalendarToday),
                    Triple(VideoSortField.SIZE, "File Size", Icons.Default.DataUsage)
                )

                sortFields.forEach { (field, label, icon) ->
                    val isSelected = selectedSortField == field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedSortField = field }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedSortField = field },
                            colors = RadioButtonDefaults.colors(selectedColor = VideoIndigo)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) VideoIndigo else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // SECTION 3: ORDER (Ascending vs Descending)
                Text(
                    text = "ORDER",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = VideoIndigo,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                val ascLabel = when (selectedSortField) {
                    VideoSortField.NAME -> "A to Z (Ascending)"
                    VideoSortField.DATE -> "Oldest first (Ascending)"
                    VideoSortField.SIZE -> "Smallest first (Ascending)"
                }
                val descLabel = when (selectedSortField) {
                    VideoSortField.NAME -> "Z to A (Descending)"
                    VideoSortField.DATE -> "Newest first (Descending)"
                    VideoSortField.SIZE -> "Largest first (Descending)"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Ascending button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedSortAscending) VideoIndigo.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (selectedSortAscending) VideoIndigo else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedSortAscending = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                tint = if (selectedSortAscending) VideoIndigo else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = ascLabel,
                                fontSize = 13.sp,
                                fontWeight = if (selectedSortAscending) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedSortAscending) VideoIndigo else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Descending button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (!selectedSortAscending) VideoIndigo.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (!selectedSortAscending) VideoIndigo else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedSortAscending = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = if (!selectedSortAscending) VideoIndigo else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = descLabel,
                                fontSize = 13.sp,
                                fontWeight = if (!selectedSortAscending) FontWeight.Bold else FontWeight.Medium,
                                color = if (!selectedSortAscending) VideoIndigo else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 4: SCOPE CHECKBOX (Default: Checked)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { applyToFolderOnly = !applyToFolderOnly }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = applyToFolderOnly,
                        onCheckedChange = { applyToFolderOnly = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = VideoIndigo,
                            checkmarkColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (folderName != null) "Apply to \"$folderName\" folder only" else "Apply to this section only",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (applyToFolderOnly) "Other folders will retain their own view settings" else "This will become the default view for all folders",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Footer Action Buttons: Cancel and Apply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onApply(selectedIsGrid, selectedSortField, selectedSortAscending, applyToFolderOnly)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VideoIndigo)
                    ) {
                        Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

