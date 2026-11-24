package com.jk.mediaplayer.ui.common

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/**
 * Data model for selectable candidates in the Blacklist Picker Dialog
 */
data class BlacklistCandidateItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val path: String,
    val isFolder: Boolean,
    val isVideo: Boolean = false,
    val albumArtUri: Uri? = null
)

/**
 * Confirmation dialog shown before blacklisting any folder or file from 3-dot menus
 */
@Composable
fun BlacklistConfirmDialog(
    title: String,
    itemName: String,
    accentColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF262838) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111216)
    val textSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = cardBg,
        titleContentColor = textPrimary,
        textContentColor = textSecondary,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column {
                Text(
                    text = "Are you sure you want to blacklist \"$itemName\"?",
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "It will be hidden from your library. You can un-blacklist or restore it at any time from Settings > Blacklist.",
                    fontSize = 13.sp,
                    color = textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
            ) {
                Text("Blacklist", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", color = textSecondary)
            }
        }
    )
}

/**
 * Interactive Multi-select Picker Dialog with Search and Checkboxes
 */
@Composable
fun BlacklistPickerDialog(
    title: String,
    candidates: List<BlacklistCandidateItem>,
    isFolderMode: Boolean,
    accentColor: Color,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirmAdd: (List<String>) -> Unit
) {
    val selectedPaths = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }

    val filteredCandidates = remember(candidates, searchQuery) {
        if (searchQuery.isBlank()) candidates
        else candidates.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.subtitle.contains(searchQuery, ignoreCase = true) ||
            it.path.contains(searchQuery, ignoreCase = true)
        }
    }

    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF262838) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111216)
    val textSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${selectedPaths.size} selected",
                            fontSize = 13.sp,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (isFolderMode) "Search folders..." else "Search files...",
                            color = textSecondary,
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6),
                        unfocusedContainerColor = if (isDark) Color(0xFF1E202C) else Color(0xFFF3F4F6)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Select All / Deselect All Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available (${filteredCandidates.size})",
                        fontSize = 13.sp,
                        color = textSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = {
                            if (selectedPaths.size == filteredCandidates.size) {
                                selectedPaths.clear()
                            } else {
                                selectedPaths.clear()
                                selectedPaths.addAll(filteredCandidates.map { it.path })
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (selectedPaths.size == filteredCandidates.size && filteredCandidates.isNotEmpty()) "Deselect All" else "Select All",
                            color = accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // List of selectable items
                if (filteredCandidates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching items found" else "No available items to blacklist",
                            color = textSecondary,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(filteredCandidates, key = { it.id }) { item ->
                            val isSelected = selectedPaths.contains(item.path)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isSelected) {
                                            selectedPaths.remove(item.path)
                                        } else {
                                            selectedPaths.add(item.path)
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedPaths.add(item.path) else selectedPaths.remove(item.path)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = accentColor,
                                        checkmarkColor = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isDark) Color(0xFF222432) else Color(0xFFF0F2F6),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (item.isFolder) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = textPrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        } else if (item.isVideo) {
                                            Icon(
                                                imageVector = Icons.Default.VideoFile,
                                                contentDescription = null,
                                                tint = textPrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        } else {
                                            if (item.albumArtUri != null) {
                                                AsyncImage(
                                                    model = item.albumArtUri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.MusicNote,
                                                    contentDescription = null,
                                                    tint = textPrimary,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.subtitle,
                                        fontSize = 11.sp,
                                        color = textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = textSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirmAdd(selectedPaths.toList())
                        },
                        enabled = selectedPaths.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            disabledContainerColor = accentColor.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = if (selectedPaths.isEmpty()) "Add to Blacklist" else "Add (${selectedPaths.size}) to Blacklist",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Full Screen View for Managing Blacklisted Folders or Files
 */
@Composable
fun BlacklistManagerScreen(
    title: String,
    isFolderMode: Boolean,
    isVideoMode: Boolean,
    accentColor: Color,
    blacklistedPaths: Set<String>,
    candidates: List<BlacklistCandidateItem>,
    onAddPaths: (List<String>) -> Unit,
    onRemovePath: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (isDark) Color(0xFF090A0F) else Color(0xFFF7F8FA)
    val cardBg = if (isDark) Color(0xFF14151B) else Color.White
    val cardBorder = if (isDark) Color(0xFF232532) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111216)
    val textSecondary = if (isDark) Color(0xFFA0A3B5) else Color(0xFF6B7280)

    BackHandler {
        onBack()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                color = if (isDark) Color(0xFF0F1015) else Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = textPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = if (blacklistedPaths.isEmpty()) "No items blacklisted" else "${blacklistedPaths.size} hidden from library",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }

                        val neutralBtnBg = if (isDark) Color(0xFF222432) else Color(0xFFF0F2F6)
                        val neutralBtnTint = if (isDark) Color.White else Color(0xFF1E2029)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (blacklistedPaths.isNotEmpty()) {
                                Surface(
                                    shape = CircleShape,
                                    color = neutralBtnBg,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .clickable { showClearConfirm = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = "Clear All",
                                            tint = neutralBtnTint,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            // Header "+" Add Icon Button
                            Surface(
                                shape = CircleShape,
                                color = neutralBtnBg,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable { showPicker = true }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add to Blacklist",
                                        tint = neutralBtnTint,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (blacklistedPaths.isEmpty()) {
                // Empty State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.12f),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isFolderMode) Icons.Default.FolderOff else Icons.Default.Block,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = if (isFolderMode) "No Blacklisted Folders" else "No Blacklisted Files",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isFolderMode) {
                            "Blacklisted folders and their contents will be completely hidden from your library."
                        } else {
                            "Blacklisted files will be hidden from songs, search, and playlists."
                        },
                        fontSize = 13.sp,
                        color = textSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { showPicker = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFolderMode) "Add Folders" else "Add Files",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                // Blacklisted items list (deduplicated by normalized path)
                val displayPaths = remember(blacklistedPaths) {
                    blacklistedPaths
                        .map { com.jk.mediaplayer.data.PreferencesManager.normalizePath(it) }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                        .sorted()
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayPaths, key = { it }) { path ->
                        val file = File(path)
                        val displayName = file.name.ifEmpty { path }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val matchedCandidate = remember(path, candidates) {
                                    candidates.firstOrNull { it.path.equals(path, ignoreCase = true) }
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isDark) Color(0xFF222432) else Color(0xFFF0F2F6),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isFolderMode) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else if (isVideoMode) {
                                            Icon(
                                                imageVector = Icons.Default.VideoFile,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            val artModel = matchedCandidate?.albumArtUri ?: File(path)
                                            var imageLoadFailed by remember(path) { mutableStateOf(false) }
                                            if (!imageLoadFailed) {
                                                AsyncImage(
                                                    model = artModel,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    onError = { imageLoadFailed = true },
                                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.MusicNote,
                                                    contentDescription = null,
                                                    tint = accentColor,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayName,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = path,
                                        fontSize = 11.sp,
                                        color = textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRemovePath(path) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Restore to library",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Picker Dialog
    if (showPicker) {
        // Filter out already blacklisted paths from candidates
        val unblacklistedCandidates = candidates.filterNot { blacklistedPaths.contains(it.path) }
        BlacklistPickerDialog(
            title = if (isFolderMode) "Select Folders to Blacklist" else if (isVideoMode) "Select Videos to Blacklist" else "Select Songs to Blacklist",
            candidates = unblacklistedCandidates,
            isFolderMode = isFolderMode,
            accentColor = accentColor,
            isDark = isDark,
            onDismiss = { showPicker = false },
            onConfirmAdd = { selected ->
                showPicker = false
                if (selected.isNotEmpty()) {
                    onAddPaths(selected)
                }
            }
        )
    }

    // Clear All Confirmation Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = cardBg,
            titleContentColor = textPrimary,
            textContentColor = textSecondary,
            title = { Text("Restore All Items?", fontWeight = FontWeight.Bold) },
            text = { Text("All blacklisted items will be restored and displayed in your library again.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearAll()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Restore All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = textSecondary)
                }
            }
        )
    }
}

