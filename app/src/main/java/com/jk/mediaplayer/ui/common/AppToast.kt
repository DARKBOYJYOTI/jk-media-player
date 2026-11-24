package com.jk.mediaplayer.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Universal In-App Custom Toast:
 * - Guarantees a single active toast at any time (cancels previous immediately)
 * - Clean text only, no icons
 * - Rounded pill shape (24.dp)
 * - Beautiful Dark Mode (pure dark surface, white text) & Light Mode (white surface, dark text)
 * - Smooth entrance and exit animations
 */
object AppToast {
    data class ToastData(val id: Long, val message: String)

    private val _currentToast = MutableStateFlow<ToastData?>(null)
    val currentToast: StateFlow<ToastData?> = _currentToast.asStateFlow()

    private var dismissJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    fun show(message: String, durationMs: Long = 2000L) {
        if (message.isBlank()) return
        dismissJob?.cancel()
        val data = ToastData(System.currentTimeMillis(), message)
        _currentToast.value = data
        dismissJob = scope.launch {
            delay(durationMs)
            if (_currentToast.value?.id == data.id) {
                _currentToast.value = null
            }
        }
    }
}

@Composable
fun GlobalAppToastHost(
    bottomPadding: Dp = 152.dp
) {
    val toastData by AppToast.currentToast.collectAsState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var activeToast by remember { mutableStateOf<AppToast.ToastData?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(toastData) {
        if (toastData != null) {
            activeToast = toastData
            isVisible = true
        } else {
            isVisible = false
        }
    }

    if (activeToast != null) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bottomOffsetPx = with(density) { bottomPadding.toPx() } +
            WindowInsets.navigationBars.getBottom(density)
        Popup(
            alignment = Alignment.BottomCenter,
            // The popup window is sized to the toast itself and lifted by an offset. Using
            // padding inside a full-width popup made the window swallow every touch in the
            // bottom band of the screen while a toast was visible.
            offset = androidx.compose.ui.unit.IntOffset(0, -bottomOffsetPx.toInt()),
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false
            )
        ) {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.9f) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.9f) + slideOutVertically(tween(200)) { it / 2 }
                ) {
                    DisposableEffect(Unit) {
                        onDispose {
                            if (!isVisible) {
                                activeToast = null
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isDark) Color(0xFF242426) else Color(0xFFFFFFFF),
                        contentColor = if (isDark) Color.White else Color(0xFF141416),
                        shadowElevation = 8.dp,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .clip(RoundedCornerShape(24.dp))
                    ) {
                        Text(
                            text = activeToast?.message ?: "",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color.White else Color(0xFF141416),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
        }
    }
}
