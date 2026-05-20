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
    private var codecName: String = ""

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
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec = codec
            codecName = codec.name ?: ""
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            Log.d("VideoEncoder", "MediaCodec started successfully (codec: $codecName)")
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

    private fun shouldSwapUV(): Boolean {
        val name = codecName.lowercase()
        return name.contains("exynos") || name.contains("sec.") || (name.contains("samsung") && !name.contains("qcom") && !name.contains("qti"))
    }

    private fun selectSupportedColorFormat(mimeType: String): Int {
        val codec = MediaCodec.createEncoderByType(mimeType)
        return try {
            val capabilities = codec.codecInfo.getCapabilitiesForType(mimeType)
            // Prioritize flexible format to avoid NV12/NV21 swaps on Exynos
            for (format in capabilities.colorFormats) {
                if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    return format
                }
            }
            // Fallback
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

        val preparedMat = if (rgbaMat.cols() != width || rgbaMat.rows() != height) {
            val resized = Mat()
            Imgproc.resize(rgbaMat, resized, org.opencv.core.Size(width.toDouble(), height.toDouble()))
            resized
        } else {
            rgbaMat
        }

        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputBufferIndex >= 0) {
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    val inputImage = mediaCodec?.getInputImage(inputBufferIndex)
                    if (inputImage != null) {
                        Imgproc.cvtColor(preparedMat, i420Mat, Imgproc.COLOR_RGBA2YUV_I420)
                        val size = (i420Mat.total() * i420Mat.channels()).toInt()
                        if (i420Bytes.size != size) i420Bytes = ByteArray(size)
                        i420Mat.get(0, 0, i420Bytes)
                        
                        val yPlane = inputImage.planes[0]
                        val uPlane = inputImage.planes[1]
                        val vPlane = inputImage.planes[2]
                        
                        val ySize = width * height
                        val yBuffer = yPlane.buffer
                        val yStart = yBuffer.position()
                        
                        if (yPlane.rowStride == width) {
                            yBuffer.position(yStart)
                            yBuffer.put(i420Bytes, 0, ySize)
                        } else {
                            for (r in 0 until height) {
                                yBuffer.position(yStart + r * yPlane.rowStride)
                                yBuffer.put(i420Bytes, r * width, width)
                            }
                        }
                        
                        val uBuffer = uPlane.buffer
                        val vBuffer = vPlane.buffer
                        val uRowStride = uPlane.rowStride
                        val vRowStride = vPlane.rowStride
                        val uPixelStride = uPlane.pixelStride
                        val vPixelStride = vPlane.pixelStride
                        
                        val uStart = uBuffer.position()
                        val vStart = vBuffer.position()
                        
                        var uSrc = ySize
                        var vSrc = ySize + (ySize / 4)
                        
                        val swapUV = shouldSwapUV()
                        for (r in 0 until height / 2) {
                            val uRowPos = r * uRowStride
                            val vRowPos = r * vRowStride
                            var uPos = uRowPos
                            var vPos = vRowPos
                            for (c in 0 until width / 2) {
                                val uVal = i420Bytes[uSrc++]
                                val vVal = i420Bytes[vSrc++]
                                if (swapUV) {
                                    uBuffer.put(uStart + uPos, vVal)
                                    vBuffer.put(vStart + vPos, uVal)
                                } else {
                                    uBuffer.put(uStart + uPos, uVal)
                                    vBuffer.put(vStart + vPos, vVal)
                                }
                                uPos += uPixelStride
                                vPos += vPixelStride
                            }
                        }
                    }
                } else {
                    val yuvBytes = rgbaToYuv(preparedMat, colorFormat)
                    val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(yuvBytes)
                }

                lastPresentationTimeUs = presentationTimeUs.coerceAtLeast(lastPresentationTimeUs)
                mediaCodec?.queueInputBuffer(inputBufferIndex, 0,
                    if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) 
                        (width * height * 3 / 2) 
                    else 
                        (width * height * 3 / 2), // Size parameter is mostly ignored for Image API, but safe to pass standard size
                    lastPresentationTimeUs, 0)
                frameIndex++
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error in encodeFrame: ${e.message}")
        }

        if (preparedMat !== rgbaMat) preparedMat.release()
        drainEncoder(false)
    }

    private fun rgbaToYuv(rgbaMat: Mat, format: Int): ByteArray {
        Imgproc.cvtColor(rgbaMat, i420Mat, Imgproc.COLOR_RGBA2YUV_I420)

        val size = (i420Mat.total() * i420Mat.channels()).toInt()
        if (i420Bytes.size != size) {
            i420Bytes = ByteArray(size)
        }
        i420Mat.get(0, 0, i420Bytes)

        val swapUV = shouldSwapUV()
        return if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            val ySize = width * height
            val nv12Size = ySize * 3 / 2
            if (nv12Bytes.size != nv12Size) {
                nv12Bytes = ByteArray(nv12Size)
            }

            System.arraycopy(i420Bytes, 0, nv12Bytes, 0, ySize)

            val uOffset = ySize
            val vOffset = ySize + (ySize / 4)
            var nvIndex = ySize
            for (i in 0 until ySize / 4) {
                val uVal = i420Bytes[uOffset + i]
                val vVal = i420Bytes[vOffset + i]
                if (swapUV) {
                    nv12Bytes[nvIndex++] = vVal
                    nv12Bytes[nvIndex++] = uVal
                } else {
                    nv12Bytes[nvIndex++] = uVal
                    nv12Bytes[nvIndex++] = vVal
                }
            }
            nv12Bytes
        } else if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            if (swapUV) {
                val ySize = width * height
                val uOffset = ySize
                val vOffset = ySize + (ySize / 4)
                val halfSize = ySize / 4
                val yuvCopy = ByteArray(size)
                System.arraycopy(i420Bytes, 0, yuvCopy, 0, ySize)
                System.arraycopy(i420Bytes, uOffset, yuvCopy, vOffset, halfSize)
                System.arraycopy(i420Bytes, vOffset, yuvCopy, uOffset, halfSize)
                yuvCopy
            } else {
                i420Bytes
            }
        } else {
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
