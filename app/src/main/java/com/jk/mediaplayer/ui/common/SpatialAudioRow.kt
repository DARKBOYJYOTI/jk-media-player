package com.jk.mediaplayer.ui.common

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Read-only view of the platform spatial audio state.
 *
 * Spatializer#setEnabled is a privileged system API, so an app cannot toggle it. We surface
 * the real state and send the user to the system sound settings instead of pretending a
 * switch works.
 */
@Composable
fun BluetoothNamePermissionEffect(request: Boolean, onResult: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !request) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { onResult() }

    androidx.compose.runtime.LaunchedEffect(request, asked) {
        val granted = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!asked && !granted) {
            asked = true
            launcher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}

@Composable
fun SystemSpatialAudioCard(
    accentColor: androidx.compose.ui.graphics.Color,
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: androidx.compose.ui.graphics.Color,
    textPrimary: androidx.compose.ui.graphics.Color,
    textSecondary: androidx.compose.ui.graphics.Color
) {
    val context = LocalContext.current
    var supported by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@DisposableEffect onDispose { }
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@DisposableEffect onDispose { }
        val spatializer = try { am.spatializer } catch (_: Throwable) { null }
            ?: return@DisposableEffect onDispose { }

        fun refresh() {
            try {
                supported = spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
                available = spatializer.isAvailable
                enabled = spatializer.isEnabled
            } catch (_: Throwable) {
                supported = false
            }
        }
        refresh()

        val listener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) {
                refresh()
            }

            override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) {
                refresh()
            }
        }
        try {
            spatializer.addOnSpatializerStateChangedListener(context.mainExecutor, listener)
        } catch (_: Throwable) {
            return@DisposableEffect onDispose { }
        }
        onDispose {
            try {
                spatializer.removeOnSpatializerStateChangedListener(listener)
            } catch (_: Throwable) {
            }
        }
    }

    val isUnsupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !supported
    val status = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Requires Android 13 or newer"
        !supported -> "Not supported on this device"
        enabled && available -> "On • Platform spatial audio active"
        enabled -> "On • Unavailable for the current output"
        else -> "Off • Tap to open system sound settings"
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isUnsupported) Modifier
                else Modifier.clickable { openSoundSettings(context) }
            )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "System Spatial Audio",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
                Text(status, fontSize = 13.sp, color = textSecondary)
            }
        }
    }
}

private fun openSoundSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }
}
