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
    private val originalHeight: Int,
    private val frameRate: Int = 30
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
    private var startTimeUs = -1L
    private var lastPresentationTimeUs = 0L
    private var colorFormat = -1
    private val i420Mat = Mat()
    private var i420Bytes = ByteArray(0)
    private var nv12Bytes = ByteArray(0)

    private var isReleased = false

    fun start() {
        Log.d("VideoEncoder", "Starting encoder for $width x $height")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)

        // Find supported color format
        colorFormat = selectSupportedColorFormat(MediaFormat.MIMETYPE_VIDEO_AVC)
        Log.d("VideoEncoder", "Using color format: $colorFormat")

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
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
        startTimeUs = -1L
        lastPresentationTimeUs = 0L
        frameIndex = 0L
    }

    private fun selectSupportedColorFormat(mimeType: String): Int {
        val codec = MediaCodec.createEncoderByType(mimeType)
        return try {
            val capabilities = codec.codecInfo.getCapabilitiesForType(mimeType)
            var selectedFormat = capabilities.colorFormats[0]
            for (format in capabilities.colorFormats) {
                if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                    format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    selectedFormat = format
                    break
                }
            }
            selectedFormat
        } finally {
            codec.release()
        }
    }

    @Synchronized
    fun encodeFrame(rgbaMat: Mat) {
        val currentTimeUs = System.nanoTime() / 1000L
        if (startTimeUs < 0L) {
            startTimeUs = currentTimeUs
        }
        encodeFrame(rgbaMat, currentTimeUs - startTimeUs)
    }

    @Synchronized
    fun encodeFrame(rgbaMat: Mat, presentationTimeUs: Long) {
        if (isReleased) return

        // Ensure frame size matches encoder exactly
        val preparedMat = if (rgbaMat.cols() != width || rgbaMat.rows() != height) {
            val resized = Mat()
            Imgproc.resize(rgbaMat, resized, org.opencv.core.Size(width.toDouble(), height.toDouble()))
            resized
        } else {
            rgbaMat
        }

        // Convert RGBA to the required YUV format
        val yuvBytes = rgbaToYuv(preparedMat, colorFormat)

        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()

                inputBuffer?.put(yuvBytes)

                lastPresentationTimeUs = presentationTimeUs.coerceAtLeast(lastPresentationTimeUs)
                mediaCodec?.queueInputBuffer(inputBufferIndex, 0, yuvBytes.size, lastPresentationTimeUs, 0)
                frameIndex++
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error in encodeFrame: ${e.message}")
        }

        if (preparedMat !== rgbaMat) preparedMat.release()
        drainEncoder(false)
    }

    private fun rgbaToYuv(rgbaMat: Mat, format: Int): ByteArray {
        // OpenCV's COLOR_RGBA2YUV_I420 produces YUV Planar (I420)
        Imgproc.cvtColor(rgbaMat, i420Mat, Imgproc.COLOR_RGBA2YUV_I420)

        val size = (i420Mat.total() * i420Mat.channels()).toInt()
        if (i420Bytes.size != size) {
            i420Bytes = ByteArray(size)
        }
        i420Mat.get(0, 0, i420Bytes)

        return if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            // Convert I420 (Planar: YYYY... UU... VV...) to NV12 (Semi-Planar: YYYY... UVUV...)
            val ySize = width * height
            val nv12Size = ySize * 3 / 2
            if (nv12Bytes.size != nv12Size) {
                nv12Bytes = ByteArray(nv12Size)
            }

            // Y plane is identical
            System.arraycopy(i420Bytes, 0, nv12Bytes, 0, ySize)

            // Interleave U and V planes
            val uOffset = ySize
            val vOffset = ySize + (ySize / 4)
            var nvIndex = ySize
            for (i in 0 until ySize / 4) {
                nv12Bytes[nvIndex++] = i420Bytes[uOffset + i] // U
                nv12Bytes[nvIndex++] = i420Bytes[vOffset + i] // V
            }
            nv12Bytes
        } else {
            // For COLOR_FormatYUV420Planar or others, assume I420 Planar for now
            i420Bytes
        }
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
                        mediaCodec?.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            lastPresentationTimeUs + (1000000L / frameRate.coerceAtLeast(1)),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                        Log.d("VideoEncoder", "EOS signal queued")
                    } else {
                        attempts++
                        Thread.sleep(10) // Brief wait
                    }
                }
            }

            var noOutputCount = 0
            while (true) {
                val timeoutUs = if (endOfStream) 10000L else 0L
                val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: -1
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break
                    noOutputCount++
                    if (noOutputCount > 50) break
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    noOutputCount = 0
                    if (isMuxerStarted) break
                    val newFormat = mediaCodec?.outputFormat
                    trackIndex = mediaMuxer?.addTrack(newFormat!!) ?: -1
                    mediaMuxer?.start()
                    isMuxerStarted = true
                } else if (outputBufferIndex >= 0) {
                    noOutputCount = 0
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
            i420Mat.release()
            Log.d("VideoEncoder", "Encoder released")
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error releasing encoder: ${e.message}", e)
        }
    }
}
