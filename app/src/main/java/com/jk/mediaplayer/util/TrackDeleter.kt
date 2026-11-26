package com.jk.mediaplayer.util

import com.jk.mediaplayer.ui.common.AppToast

import android.app.RecoverableSecurityException
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.jk.mediaplayer.model.AudioItem
import java.io.File

object TrackDeleter {

    /**
     * Safely deletes an audio file from the device storage and MediaStore.
     * Handles Scoped Storage permissions via MediaStore.createDeleteRequest (Android 11+)
     * and RecoverableSecurityException (Android 10) seamlessly.
     */
    fun deleteTrack(
        context: Context,
        track: AudioItem,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onNeedsPermission: () -> Unit,
        onDeleted: () -> Unit
    ) {
        try {
            var directDeleted = false
            val file = File(track.path)
            if (file.exists()) {
                directDeleted = file.delete()
            }

            if (!directDeleted) {
                try {
                    val rows = context.contentResolver.delete(track.uri, null, null)
                    if (rows > 0) directDeleted = true
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        onNeedsPermission()
                        launcher.launch(
                            IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                        )
                        return
                    }
                }
            }

            if (directDeleted) {
                onDeleted()
                AppToast.show("'${track.title}' deleted successfully")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(track.uri))
                    onNeedsPermission()
                    launcher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } catch (e: Exception) {
                    AppToast.show("Cannot delete track: ${e.message}")
                }
            } else {
                AppToast.show("Could not delete track file")
            }
        } catch (e: Exception) {
            AppToast.show("Delete failed: ${e.message}")
        }
    }
}

