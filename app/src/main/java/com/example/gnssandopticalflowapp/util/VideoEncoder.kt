package com.example.gnssandopticalflowapp.util

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class VideoEncoder(
    private val outputPath: String,
    private val originalWidth: Int,
    private val originalHeight: Int
) {
    // Aligned dimensions (even numbers)
    private val width = if (originalWidth % 2 == 0) originalWidth else originalWidth - 1
    private val height = if (originalHeight % 2 == 0) originalHeight else originalHeight - 1
    
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var isMuxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameIndex = 0L
    private var colorFormat = -1

    private var isReleased = false

    fun start() {
        Log.d("VideoEncoder", "Starting encoder for $width x $height")
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        
        // Find supported color format
        colorFormat = selectSupportedColorFormat(MediaFormat.MIMETYPE_VIDEO_AVC)
        Log.d("VideoEncoder", "Using color format: $colorFormat")
        
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            Log.d("VideoEncoder", "MediaCodec started successfully")
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to init MediaCodec: ${e.message}")
            throw e
        }

        mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        isReleased = false
    }

    private fun selectSupportedColorFormat(mimeType: String): Int {
        val capabilities = MediaCodec.createEncoderByType(mimeType).codecInfo.getCapabilitiesForType(mimeType)
        for (format in capabilities.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return format
            }
        }
        return capabilities.colorFormats[0] // Fallback
    }

    @Synchronized
    fun encodeFrame(rgbaMat: Mat) {
        if (isReleased) return

        // Ensure frame size matches encoder exactly
        val preparedMat = if (rgbaMat.cols() != width || rgbaMat.rows() != height) {
            val resized = Mat()
            Imgproc.resize(rgbaMat, resized, org.opencv.core.Size(width.toDouble(), height.toDouble()))
            resized
        } else {
            rgbaMat
        }

        val yuvMat = Mat()
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            Imgproc.cvtColor(preparedMat, yuvMat, Imgproc.COLOR_RGBA2YUV_I420)
        } else {
            Imgproc.cvtColor(preparedMat, yuvMat, Imgproc.COLOR_RGBA2YUV_I420)
        }
        
        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                
                val size = (yuvMat.total() * yuvMat.channels()).toInt()
                val bytes = ByteArray(size)
                yuvMat.get(0, 0, bytes)
                inputBuffer?.put(bytes)
                
                val presentationTimeUs = frameIndex * 1000000L / 30
                mediaCodec?.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0)
                frameIndex++
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error in encodeFrame: ${e.message}")
        }
        
        if (preparedMat != rgbaMat) preparedMat.release()
        yuvMat.release()
        drainEncoder(false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        try {
            if (endOfStream) {
                // Properly signal EOS for ByteBuffer input - loop until successful
                var eosQueued = false
                var attempts = 0
                while (!eosQueued && attempts < 10) {
                    val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                    if (inputBufferIndex >= 0) {
                        mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                        Log.d("VideoEncoder", "EOS signal queued")
                    } else {
                        attempts++
                        Thread.sleep(10) // Brief wait
                    }
                }
            }

            while (true) {
                val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (isMuxerStarted) break
                    val newFormat = mediaCodec?.outputFormat
                    trackIndex = mediaMuxer?.addTrack(newFormat!!) ?: -1
                    mediaMuxer?.start()
                    isMuxerStarted = true
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                        if (isMuxerStarted) {
                            outputBuffer?.position(bufferInfo.offset)
                            outputBuffer?.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer?.writeSampleData(trackIndex, outputBuffer!!, bufferInfo)
                        }
                    }

                    mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d("VideoEncoder", "End of stream reached")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error in drainEncoder: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        if (isReleased) return
        isReleased = true
        try {
            Log.d("VideoEncoder", "Releasing encoder...")
            drainEncoder(true)
            mediaCodec?.stop()
            mediaCodec?.release()
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            Log.d("VideoEncoder", "Encoder released")
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error releasing encoder: ${e.message}", e)
        }
    }
}
