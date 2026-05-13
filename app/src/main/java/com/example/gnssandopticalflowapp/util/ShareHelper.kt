package com.example.gnssandopticalflowapp.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {

    fun shareFiles(context: Context, files: List<File>): Boolean {
        return try {
            val shareableFiles = files.filter { it.exists() && it.isFile }
            if (shareableFiles.isEmpty()) return false

            val videoUris = shareableFiles.map { file ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }

            val chooserIntent = Intent.createChooser(
                buildShareIntent(videoUris),
                "Share files using"
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooserIntent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun buildShareIntent(videoUris: List<Uri>): Intent {
        return Intent().apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (videoUris.size == 1) {
                action = Intent.ACTION_SEND
                type = getMimeType(videoUris.first())
                putExtra(Intent.EXTRA_STREAM, videoUris.first())
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(videoUris))
            }
        }
    }

    private fun getMimeType(uri: Uri): String {
        return when (uri.path?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg", "png", "webp" -> "image/*"
            "mp4", "mov", "mkv" -> "video/*"
            else -> "*/*"
        }
    }
}
