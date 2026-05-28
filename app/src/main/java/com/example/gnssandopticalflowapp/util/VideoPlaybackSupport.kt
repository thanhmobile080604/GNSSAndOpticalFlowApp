package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

object VideoPlaybackSupport {
    fun canDecode(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val videoFormat = (0 until extractor.trackCount)
                .asSequence()
                .map { index -> extractor.getTrackFormat(index) }
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                ?: return false

            canDecode(videoFormat)
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect playback support for $uri: ${e.message}", e)
            true
        } finally {
            extractor.release()
        }
    }

    private fun canDecode(format: MediaFormat): Boolean {
        return try {
            val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format)
            if (decoderName.isNullOrBlank()) {
                Log.w(TAG, "No decoder supports video format: $format")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decoder support check failed for $format: ${e.message}", e)
            false
        }
    }

    private const val TAG = "VideoPlaybackSupport"
}
