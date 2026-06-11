package com.example.gnssandopticalflowapp.function.video.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingProgressText
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.model.VideoProgressMetadata
import com.example.gnssandopticalflowapp.function.optical_flow.classes.AIRaftOpticalFlow
import com.example.gnssandopticalflowapp.function.optical_flow.classes.FailoverOpticalFlow
import com.example.gnssandopticalflowapp.function.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.function.optical_flow.classes.FrameStrideOpticalFlow
import com.example.gnssandopticalflowapp.function.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.FrameProgressAwareOpticalFlow
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.util.VideoEncoder
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class LocalVideoProcessor(
    context: Context,
    private val callbacks: Callbacks
) {
    private val appContext = context.applicationContext

    interface Callbacks {
        fun isActive(): Boolean
        fun currentPercent(): Int
        fun postStatus(status: String)
        fun postPercent(percent: Int)
    }

    fun process(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean {
        callbacks.postStatus("Processing on-device ${localAlgorithmName(options)}...")
        return processVideoWithOpenCv(sourceFile, outputFile, options) ||
            retryWithCleanOutput(outputFile) {
                processVideoWithFrameBatch(sourceFile, outputFile, options)
            } ||
            retryWithCleanOutput(outputFile) {
                processVideoWithTimeSeek(sourceFile, outputFile, options)
            }
    }

    private fun retryWithCleanOutput(
        outputFile: File,
        block: () -> Boolean
    ): Boolean {
        if (!callbacks.isActive()) return false
        outputFile.delete()
        return block()
    }

    private fun processVideoWithOpenCv(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean {
        val capture = VideoCapture(sourceFile.absolutePath)
        if (!capture.isOpened) {
            capture.release()
            Log.w(TAG, "OpenCV VideoCapture could not open video, falling back.")
            return false
        }

        val width = validDimension(capture.get(Videoio.CAP_PROP_FRAME_WIDTH))
        val height = validDimension(capture.get(Videoio.CAP_PROP_FRAME_HEIGHT))
        if (width <= 0 || height <= 0) {
            capture.release()
            Log.w(TAG, "OpenCV VideoCapture returned invalid video size.")
            return false
        }

        val metadata = readVideoProgressMetadata(sourceFile)
        val fps = validFps(capture.get(Videoio.CAP_PROP_FPS), metadata.fps)
        val frameDurationUs = frameDurationUs(fps)
        val totalFrames = validFrameCount(capture.get(Videoio.CAP_PROP_FRAME_COUNT))
            .takeIf { it > 0L }
            ?: estimatedTotalFrames(metadata, fps)
        val opticalFlow = createOpticalFlow(options)
        val sourceMat = Mat()
        val rgbaMat = Mat()
        val orientedMat = Mat()
        var encoder: VideoEncoder? = null
        var rotationDegrees = 0
        var framesProcessed = 0L

        try {
            while (callbacks.isActive() && capture.read(sourceMat)) {
                if (sourceMat.empty()) break

                convertCaptureFrameToRgba(sourceMat, rgbaMat)
                if (encoder == null) {
                    rotationDegrees = rotationForDecodedFrame(rgbaMat.cols(), rgbaMat.rows(), metadata)
                    encoder = VideoEncoder(
                        outputFile.absolutePath,
                        displayWidth(rgbaMat.cols(), rgbaMat.rows(), rotationDegrees),
                        displayHeight(rgbaMat.cols(), rgbaMat.rows(), rotationDegrees),
                        encoderFrameRate(fps)
                    ).also { it.start() }
                }

                val frameForProcessing = rotateFrameForDisplay(rgbaMat, orientedMat, rotationDegrees)
                val activeEncoder = encoder
                showFrameProcessingStageIfNeeded(opticalFlow, options, framesProcessed + 1L, totalFrames)
                encodeProcessedFrame(
                    opticalFlow = opticalFlow,
                    rgbaMat = frameForProcessing,
                    options = options,
                    encoder = activeEncoder,
                    presentationTimeUs = framesProcessed * frameDurationUs
                )

                framesProcessed++
                updateProgressIfNeeded(framesProcessed, totalFrames)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV processing failed: ${e.message}", e)
            return false
        } finally {
            sourceMat.release()
            rgbaMat.release()
            orientedMat.release()
            capture.release()
            encoder?.release()
        }

        return framesProcessed > 0 && outputFile.length() > 100
    }

    private fun processVideoWithFrameBatch(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourceFile.absolutePath)
            val metadata = readVideoProgressMetadata(sourceFile)
            val frameCount = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return false

            val firstFrame = findFirstReadableFrame(retriever, metadata.durationMs * 1000L) ?: return false
            val rotationDegrees = rotationForDecodedFrame(firstFrame.width, firstFrame.height, metadata)
            val width = displayWidth(firstFrame.width, firstFrame.height, rotationDegrees)
            val height = displayHeight(firstFrame.width, firstFrame.height, rotationDegrees)
            firstFrame.recycle()

            val fps = validFps(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull(),
                fallback = 30.0
            )
            val frameDurationUs = frameDurationUs(fps)
            val encoder = VideoEncoder(outputFile.absolutePath, width, height, encoderFrameRate(fps))
            val opticalFlow = createOpticalFlow(options)
            val rgbaMat = Mat()
            val orientedMat = Mat()
            var framesProcessed = 0L

            try {
                encoder.start()
                while (callbacks.isActive() && framesProcessed < frameCount) {
                    val requestCount = minOf(BATCH_FRAME_SIZE.toLong(), frameCount - framesProcessed).toInt()
                    val bitmaps = safeGetFramesAtIndex(retriever, framesProcessed.toInt(), requestCount)
                    if (bitmaps.isEmpty()) break

                    for (bitmap in bitmaps) {
                        try {
                            Utils.bitmapToMat(bitmap, rgbaMat)
                            val frameForProcessing = rotateFrameForDisplay(rgbaMat, orientedMat, rotationDegrees)
                            showFrameProcessingStageIfNeeded(opticalFlow, options, framesProcessed + 1L, frameCount)
                            encodeProcessedFrame(
                                opticalFlow = opticalFlow,
                                rgbaMat = frameForProcessing,
                                options = options,
                                encoder = encoder,
                                presentationTimeUs = framesProcessed * frameDurationUs
                            )
                            framesProcessed++
                            updateProgressIfNeeded(framesProcessed, frameCount)
                        } finally {
                            bitmap.recycle()
                        }
                        if (!callbacks.isActive()) break
                    }
                }
            } finally {
                rgbaMat.release()
                orientedMat.release()
                encoder.release()
            }

            framesProcessed > 0 && outputFile.length() > 100
        } catch (e: Exception) {
            Log.e(TAG, "Batch frame processing failed: ${e.message}", e)
            false
        } finally {
            retriever.release()
        }
    }

    private fun processVideoWithTimeSeek(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourceFile.absolutePath)
            val metadata = readVideoProgressMetadata(sourceFile)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val fps = validFps(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull(),
                fallback = metadata.fps
            )
            val frameDurationUs = frameDurationUs(fps)
            val durationUs = ((durationMs.takeIf { it > 0L } ?: metadata.durationMs) * 1000L)
                .takeIf { it > 0L }
                ?: (metadata.frameCount.takeIf { it > 0L }?.times(frameDurationUs) ?: frameDurationUs)
            val firstFrame = findFirstReadableFrame(retriever, durationUs) ?: return false
            val rotationDegrees = rotationForDecodedFrame(firstFrame.width, firstFrame.height, metadata)
            val width = displayWidth(firstFrame.width, firstFrame.height, rotationDegrees)
            val height = displayHeight(firstFrame.width, firstFrame.height, rotationDegrees)
            firstFrame.recycle()

            val totalFrames = (durationUs / frameDurationUs).coerceAtLeast(1L)
            val encoder = VideoEncoder(outputFile.absolutePath, width, height, encoderFrameRate(fps))
            val opticalFlow = createOpticalFlow(options)
            val rgbaMat = Mat()
            val orientedMat = Mat()
            var currentTimeUs = 0L
            var framesProcessed = 0L

            try {
                encoder.start()
                while (callbacks.isActive() && currentTimeUs < durationUs) {
                    val bitmap = safeGetFrameAtTime(retriever, currentTimeUs)
                    if (bitmap != null) {
                        try {
                            Utils.bitmapToMat(bitmap, rgbaMat)
                            val frameForProcessing = rotateFrameForDisplay(rgbaMat, orientedMat, rotationDegrees)
                            showFrameProcessingStageIfNeeded(opticalFlow, options, framesProcessed + 1L, totalFrames)
                            encodeProcessedFrame(
                                opticalFlow = opticalFlow,
                                rgbaMat = frameForProcessing,
                                options = options,
                                encoder = encoder,
                                presentationTimeUs = framesProcessed * frameDurationUs
                            )
                            framesProcessed++
                            updateProgressIfNeeded(framesProcessed, totalFrames)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    currentTimeUs += frameDurationUs
                }
            } finally {
                rgbaMat.release()
                orientedMat.release()
                encoder.release()
            }

            framesProcessed > 0 && outputFile.length() > 100
        } catch (e: Exception) {
            Log.e(TAG, "Time seek processing failed: ${e.message}", e)
            false
        } finally {
            retriever.release()
        }
    }

    private fun createOpticalFlow(options: VideoProcessOptions): OpticalFlow {
        if (options.useAi) {
            val fallback = createConfiguredFarneback(options)
            val requireAiModel = options.processingMode == VideoProcessOptions.ProcessingMode.OFFLINE
            if (!AIRaftOpticalFlow.isModelAvailable(appContext)) {
                callbacks.postStatus(
                    if (requireAiModel) "AI model missing" else "AI model missing; using Farneback"
                )
                if (requireAiModel) throw AIRaftOpticalFlow.ModelMissingException()
                return fallback
            }

            val aiFlow = AIRaftOpticalFlow(appContext, callbacks::postStatus).apply {
                setSensitivity(options.sensitivity)
                setMovingMode(options.isMoving)
                setVisualizationMode(currentAiVisualizationMode(options))
            }
            Thread {
                try {
                    aiFlow.prepare()
                } catch (e: Exception) {
                    Log.w("AI-RAFT", "prepare() failed: ${e.message}")
                }
            }.start()
            if (requireAiModel) {
                return FrameStrideOpticalFlow(aiFlow, AI_FRAME_STRIDE, "AI-RAFT")
            }

            val failover = FailoverOpticalFlow(aiFlow, fallback, "AI-RAFT") { error ->
                callbacks.postStatus("AI failed; using Farneback")
                Log.e("AI-RAFT", "Falling back to Farneback: ${error.message}", error)
            }
            return FrameStrideOpticalFlow(failover, AI_FRAME_STRIDE, "AI-RAFT")
        }

        return if (options.useFarneback) {
            createConfiguredFarneback(options)
        } else {
            KLT().apply {
                setSensitivity(options.sensitivity)
                setMovingMode(options.isMoving)
            }
        }
    }

    private fun createConfiguredFarneback(options: VideoProcessOptions): Farneback {
        return Farneback().apply {
            setSensitivity(options.sensitivity)
            setMovingMode(options.isMoving)
            setVisualizationMode(currentFarnebackVisualizationMode(options))
        }
    }

    private fun currentFarnebackVisualizationMode(options: VideoProcessOptions): Farneback.VisualizationMode {
        return if (options.useFarnebackHeatmap) Farneback.VisualizationMode.HEATMAP else Farneback.VisualizationMode.VECTORS
    }

    private fun currentAiVisualizationMode(options: VideoProcessOptions): AIRaftOpticalFlow.VisualizationMode {
        return if (options.useFarnebackHeatmap) AIRaftOpticalFlow.VisualizationMode.HEATMAP else AIRaftOpticalFlow.VisualizationMode.VECTORS
    }

    private fun encodeProcessedFrame(
        opticalFlow: OpticalFlow,
        rgbaMat: Mat,
        options: VideoProcessOptions,
        encoder: VideoEncoder,
        presentationTimeUs: Long
    ) {
        if (rgbaMat.empty()) return

        val activeRoi = activeRoi(rgbaMat, options)
        val processingFrame = activeRoi?.rect?.let { rgbaMat.submat(it) } ?: rgbaMat
        val originalRoiFrame = activeRoi?.mask?.let { processingFrame.clone() }
        try {
            val output = opticalFlow.run(processingFrame)
            restoreOutsideRoiMask(processingFrame, originalRoiFrame, activeRoi?.mask)

            val outFrame = if (activeRoi != null) rgbaMat else output?.ofFrame ?: rgbaMat
            encoder.encodeFrame(outFrame, presentationTimeUs)
            if (outFrame !== rgbaMat) outFrame.release()
        } finally {
            if (processingFrame !== rgbaMat) processingFrame.release()
            originalRoiFrame?.release()
            activeRoi?.mask?.release()
        }
    }

    private fun activeRoi(frame: Mat, options: VideoProcessOptions): ActiveRoi? {
        val normalized = options.roi ?: return null
        val frameCols = frame.cols().coerceAtLeast(1)
        val frameRows = frame.rows().coerceAtLeast(1)
        val viewHeight = 1.0
        val viewWidth = normalized.viewAspectRatio.toDouble().coerceAtLeast(0.01)
        val scale = max(viewWidth / frameCols.toDouble(), viewHeight / frameRows.toDouble())
        val offsetX = ((frameCols * scale) - viewWidth) / 2.0
        val offsetY = ((frameRows * scale) - viewHeight) / 2.0

        fun mapX(normalizedX: Float): Int {
            return (((normalizedX * viewWidth) + offsetX) / scale).roundToInt()
        }

        fun mapY(normalizedY: Float): Int {
            return (((normalizedY * viewHeight) + offsetY) / scale).roundToInt()
        }

        val left = mapX(normalized.left).coerceIn(0, frameCols - 1)
        val top = mapY(normalized.top).coerceIn(0, frameRows - 1)
        val right = mapX(normalized.right).coerceIn(left + 1, frameCols)
        val bottom = mapY(normalized.bottom).coerceIn(top + 1, frameRows)
        val width = right - left
        val height = bottom - top
        if (width < MIN_ROI_FRAME_SIZE || height < MIN_ROI_FRAME_SIZE) return null

        val rect = Rect(left, top, width, height)
        return ActiveRoi(
            rect = rect,
            mask = createRoiMask(normalized, frameCols, frameRows, rect, ::mapX, ::mapY)
        )
    }


    private fun createRoiMask(
        normalized: VideoProcessOptions.NormalizedRoi,
        frameCols: Int,
        frameRows: Int,
        rect: Rect,
        mapX: (Float) -> Int,
        mapY: (Float) -> Int
    ): Mat? {
        if (normalized.pathPoints.size < 3) return null

        val polygon = normalized.pathPoints.map { point ->
            Point(
                (mapX(point.x).coerceIn(0, frameCols - 1) - rect.x).toDouble(),
                (mapY(point.y).coerceIn(0, frameRows - 1) - rect.y).toDouble()
            )
        }
        val mask = Mat.zeros(rect.height, rect.width, CvType.CV_8UC1)
        val polygonMat = MatOfPoint(*polygon.toTypedArray())
        Imgproc.fillPoly(mask, listOf(polygonMat), Scalar(255.0))
        polygonMat.release()
        return mask
    }

    private fun restoreOutsideRoiMask(processingFrame: Mat, originalRoiFrame: Mat?, mask: Mat?) {
        if (originalRoiFrame == null || mask == null) return

        val inverseMask = Mat()
        try {
            Core.bitwise_not(mask, inverseMask)
            originalRoiFrame.copyTo(processingFrame, inverseMask)
        } finally {
            inverseMask.release()
        }
    }

    private fun showFrameProcessingStageIfNeeded(
        opticalFlow: OpticalFlow,
        options: VideoProcessOptions,
        frameNumber: Long,
        totalFrames: Long
    ) {
        if (!options.useAi) return
        (opticalFlow as? FrameProgressAwareOpticalFlow)?.updateFrameProgress(frameNumber, totalFrames)
        if (frameNumber > 3L && frameNumber % 10L != 0L) return

        callbacks.postPercent(progressPercent(frameNumber, totalFrames))
    }

    private fun updateProgressIfNeeded(framesProcessed: Long, totalFrames: Long) {
        if (totalFrames <= 0L) return
        val shouldUpdate = framesProcessed <= 3L || framesProcessed % PROGRESS_UPDATE_INTERVAL_FRAMES == 0L
        if (!shouldUpdate) return

        callbacks.postPercent(progressPercent(framesProcessed, totalFrames))
    }

    private fun progressPercent(framesProcessed: Long, totalFrames: Long): Int {
        if (totalFrames <= 0L) return callbacks.currentPercent()

        val progress = ((framesProcessed.toDouble() / totalFrames.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(
                VideoProcessingProgressText.DEFAULT_PERCENT,
                VideoProcessingProgressText.COMPLETE_PERCENT
            )
        return if (framesProcessed > 0L && progress == 0) 1 else progress
    }

    private fun convertCaptureFrameToRgba(sourceMat: Mat, rgbaMat: Mat) {
        when (sourceMat.channels()) {
            4 -> Imgproc.cvtColor(sourceMat, rgbaMat, Imgproc.COLOR_BGRA2RGBA)
            3 -> Imgproc.cvtColor(sourceMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            1 -> Imgproc.cvtColor(sourceMat, rgbaMat, Imgproc.COLOR_GRAY2RGBA)
            else -> sourceMat.copyTo(rgbaMat)
        }
    }

    private fun rotateFrameForDisplay(input: Mat, output: Mat, rotationDegrees: Int): Mat {
        when (normalizeRotationDegrees(rotationDegrees)) {
            90 -> Core.rotate(input, output, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(input, output, Core.ROTATE_180)
            270 -> Core.rotate(input, output, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return input
        }
        return output
    }

    private fun findFirstReadableFrame(
        retriever: MediaMetadataRetriever,
        durationUs: Long
    ): Bitmap? {
        val safeDurationUs = durationUs.coerceAtLeast(0L)
        val probeTimes = linkedSetOf(
            0L,
            1L,
            33_333L,
            100_000L,
            500_000L
        )
        if (safeDurationUs > 1L) {
            probeTimes.add((safeDurationUs / 2L).coerceAtLeast(0L))
            probeTimes.add((safeDurationUs - 1L).coerceAtLeast(0L))
        }

        for (timeUs in probeTimes) {
            safeGetFrameAtTime(retriever, timeUs)?.let { return it }
        }
        return null
    }

    private fun safeGetFrameAtTime(
        retriever: MediaMetadataRetriever,
        timeUs: Long
    ): Bitmap? {
        val safeTimeUs = timeUs.coerceAtLeast(0L)
        val options = intArrayOf(
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_CLOSEST,
            MediaMetadataRetriever.OPTION_NEXT_SYNC,
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
        )

        for (option in options) {
            try {
                retriever.getFrameAtTime(safeTimeUs, option)?.let { return it }
            } catch (_: Exception) {
                // Try the next retrieval mode.
            }
        }
        return null
    }

    private fun safeGetFramesAtIndex(
        retriever: MediaMetadataRetriever,
        startIndex: Int,
        frameCount: Int
    ): List<Bitmap> {
        return try {
            retriever.getFramesAtIndex(startIndex, frameCount)
        } catch (e: Exception) {
            Log.w(TAG, "Frame batch unavailable at index $startIndex: ${e.message}")
            emptyList()
        }
    }

    private fun readVideoProgressMetadata(sourceFile: File): VideoProgressMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourceFile.absolutePath)
            VideoProgressMetadata(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                fps = validFps(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()),
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotationDegrees = normalizeRotationDegrees(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read video progress metadata: ${e.message}")
            VideoProgressMetadata(durationMs = 0L, frameCount = 0L, fps = 30.0)
        } finally {
            retriever.release()
        }
    }

    private fun estimatedTotalFrames(metadata: VideoProgressMetadata, fps: Double): Long {
        if (metadata.frameCount > 0L) return metadata.frameCount
        if (metadata.durationMs <= 0L) return 0L
        return ((metadata.durationMs * 1000L) / frameDurationUs(fps)).coerceAtLeast(1L)
    }

    private fun rotationForDecodedFrame(frameWidth: Int, frameHeight: Int, metadata: VideoProgressMetadata): Int {
        val rotationDegrees = normalizeRotationDegrees(metadata.rotationDegrees)
        if (rotationDegrees == 0) return 0

        val swapsAxes = rotationDegrees == 90 || rotationDegrees == 270
        if (swapsAxes &&
            metadata.width > 0 &&
            metadata.height > 0 &&
            frameWidth == metadata.height &&
            frameHeight == metadata.width
        ) {
            return 0
        }
        return rotationDegrees
    }

    private fun validFps(rawFps: Double?, fallback: Double = 30.0) =
        rawFps.takeIf { it != null && it.isFinite() && it >= 1.0 && it <= 120.0 }
            ?: fallback.takeIf { it.isFinite() && it >= 1.0 && it <= 120.0 }
            ?: 30.0

    private fun encoderFrameRate(fps: Double) = fps.roundToInt().coerceIn(1, 60)

    private fun frameDurationUs(fps: Double) = (1000000.0 / validFps(fps)).roundToLong().coerceAtLeast(1L)

    private fun validDimension(value: Double) = value.takeIf { it.isFinite() && it > 0.0 }?.roundToInt() ?: 0

    private fun validFrameCount(value: Double) = value.takeIf { it.isFinite() && it > 0.0 }?.roundToLong() ?: 0L

    private fun displayWidth(width: Int, height: Int, rotationDegrees: Int) =
        if (normalizeRotationDegrees(rotationDegrees) in setOf(90, 270)) height else width

    private fun displayHeight(width: Int, height: Int, rotationDegrees: Int) =
        if (normalizeRotationDegrees(rotationDegrees) in setOf(90, 270)) width else height

    private fun normalizeRotationDegrees(rotationDegrees: Int): Int {
        return when (val normalized = ((rotationDegrees % 360) + 360) % 360) {
            90, 180, 270 -> normalized
            else -> 0
        }
    }

    private fun localAlgorithmName(options: VideoProcessOptions): String {
        return when {
            options.useAi -> "AI model"
            options.useFarneback -> "Farneback (OpenCV)"
            else -> "KLT (OpenCV)"
        }
    }

    private data class ActiveRoi(val rect: Rect, val mask: Mat?)

    private companion object {
        private const val TAG = "LOCAL-VIDEO"
        private const val BATCH_FRAME_SIZE = 8
        private const val PROGRESS_UPDATE_INTERVAL_FRAMES = 30L
        private const val MIN_ROI_FRAME_SIZE = 32
        private const val AI_FRAME_STRIDE = 1
    }
}
