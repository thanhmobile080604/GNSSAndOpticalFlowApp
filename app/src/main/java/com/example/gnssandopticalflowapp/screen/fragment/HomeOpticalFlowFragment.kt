package com.example.gnssandopticalflowapp.screen.fragment

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.model.VideoProgressMetadata
import com.example.gnssandopticalflowapp.optical_flow.classes.AIRaftOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.classes.FailoverOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.optical_flow.classes.FrameStrideOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.interfaces.FrameProgressAwareOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.screen.dialog.VideoProcessOptionsDialog
import com.example.gnssandopticalflowapp.util.VideoEncoder
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import com.example.gnssandopticalflowapp.video.VideoProcessingBus
import com.example.gnssandopticalflowapp.video.VideoProcessingForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class HomeOpticalFlowFragment : BaseFragment<FragmentHomeOpticalFlowBinding>(FragmentHomeOpticalFlowBinding::inflate) {
    private val batchFrameSize = 8
    private val progressUpdateIntervalFrames = 30L

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            showVideoProcessOptions(uri)
        }
    }

    override fun FragmentHomeOpticalFlowBinding.initView() {
    }

    override fun FragmentHomeOpticalFlowBinding.initListener() {
        btnFunc1.setSingleClick {
            navigateTo(R.id.cameraOpticalFlowFragment)
        }

        btnFunc2.setSingleClick {
            if (isProcessingVideo()) {
                showProcessingToast()
                return@setSingleClick
            }
            videoPickerLauncher.launch("video/*")
        }

        btnFunc3.setSingleClick {
            navigateTo(R.id.videoListFragment)
        }

        btnFunc4.setSingleClick {
            navigateTo(R.id.analyticsListFragment)
        }
    }

    private fun showVideoProcessOptions(uri: Uri) {
        VideoProcessOptionsDialog.show(childFragmentManager, uri) { options ->
            handleVideoSelection(uri, options)
        }
    }

    private fun handleVideoSelection(uri: Uri, options: VideoProcessOptions) {
        if (isProcessingVideo()) {
            showProcessingToast()
            return
        }

        val appContext = safeContext().applicationContext
        showUploadLoading("Copying video...")

        val uploadJob = mainViewModel.videoProcessingScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = appContext.cacheDir
                val videosDir = File(cacheDir, "videos")
                if (!videosDir.exists()) videosDir.mkdirs()
                
                val sourceFile = File(videosDir, "temp_source_${System.currentTimeMillis()}.mp4")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                VideoProcessingBus.postProcessing("Starting background processing...")
                ContextCompat.startForegroundService(
                    appContext,
                    VideoProcessingForegroundService.processIntent(
                        context = appContext,
                        sourcePath = sourceFile.absolutePath,
                        options = options
                    )
                )
                
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    dismissUploadLoading()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismissUploadLoading()
                }
            }
        }
        mainViewModel.videoUploadJob = uploadJob
        uploadJob.invokeOnCompletion {
            if (mainViewModel.videoUploadJob === uploadJob) {
                mainViewModel.videoUploadJob = null
            }
        }
    }

    private suspend fun processVideoOffline(
        sourceFile: File,
        options: VideoProcessOptions,
        appContext: Context
    ) {
        withContext(Dispatchers.Main) {
            showUploadLoading("Preparing video...")
        }

        val outputFile = File(sourceFile.parentFile, "processed_${System.currentTimeMillis()}.mp4")

        val processed = try {
            processVideoWithOpenCv(sourceFile, outputFile, options, appContext)
                || retryWithCleanOutput(outputFile) {
                    processVideoWithTimeSeek(sourceFile, outputFile, options, appContext)
                }
                || retryWithCleanOutput(outputFile) {
                    processVideoWithFrameBatch(sourceFile, outputFile, options, appContext)
                }
        } finally {
            sourceFile.delete()
        }

        if (processed && isProcessingVideo()) {
            MediaScannerConnection.scanFile(appContext, arrayOf(outputFile.absolutePath), null) { _, _ -> }

            withContext(Dispatchers.Main) {
                showProcessingProgress(100)
                MediaStorageUtil.addVideo(appContext, outputFile.absolutePath)
                mainViewModel.videoLibraryUpdated.value = System.currentTimeMillis()
                kotlinx.coroutines.delay(500)
                mainViewModel.processedVideoPathToOpen.value = outputFile.absolutePath
            }
        } else {
            Log.d("VIDEO-PROCESS", "Processing cancelled.")
            outputFile.delete()
            withContext(Dispatchers.Main) {
                dismissUploadLoading()
            }
        }
    }

    private suspend fun retryWithCleanOutput(
        outputFile: File,
        block: suspend () -> Boolean
    ): Boolean {
        if (!isProcessingVideo()) return false
        outputFile.delete()
        return block()
    }

    private suspend fun processVideoWithOpenCv(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions,
        appContext: Context
    ): Boolean {
        val capture = VideoCapture(sourceFile.absolutePath)
        if (!capture.isOpened) {
            capture.release()
            Log.w("VIDEO-PROCESS", "OpenCV VideoCapture could not open video, falling back.")
            return false
        }

        val width = validDimension(capture.get(Videoio.CAP_PROP_FRAME_WIDTH))
        val height = validDimension(capture.get(Videoio.CAP_PROP_FRAME_HEIGHT))
        if (width <= 0 || height <= 0) {
            capture.release()
            Log.w("VIDEO-PROCESS", "OpenCV VideoCapture returned invalid video size.")
            return false
        }

        val metadata = readVideoProgressMetadata(sourceFile)
        val fps = validFps(capture.get(Videoio.CAP_PROP_FPS), metadata.fps)
        val frameDurationUs = frameDurationUs(fps)
        val totalFrames = validFrameCount(capture.get(Videoio.CAP_PROP_FRAME_COUNT))
            .takeIf { it > 0L }
            ?: estimatedTotalFrames(metadata, fps)
        showProcessingStage("Preparing ${algorithmLabel(options)}...")
        val opticalFlow = createOpticalFlow(options, appContext)
        val sourceMat = Mat()
        val rgbaMat = Mat()
        val orientedMat = Mat()
        var encoder: VideoEncoder? = null
        var rotationDegrees = 0
        var framesProcessed = 0L

        try {
            while (isProcessingVideo() && capture.read(sourceMat)) {
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
                val activeEncoder = encoder ?: continue
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
            Log.e("VIDEO-PROCESS", "OpenCV processing failed: ${e.message}", e)
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

    private suspend fun processVideoWithFrameBatch(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions,
        appContext: Context
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
            showProcessingStage("Preparing ${algorithmLabel(options)}...")
            val opticalFlow = createOpticalFlow(options, appContext)
            val rgbaMat = Mat()
            val orientedMat = Mat()
            var framesProcessed = 0L

            try {
                encoder.start()
                while (isProcessingVideo() && framesProcessed < frameCount) {
                    val requestCount = minOf(batchFrameSize.toLong(), frameCount - framesProcessed).toInt()
                    val bitmaps = safeGetFramesAtIndex(
                        retriever = retriever,
                        startIndex = framesProcessed.toInt(),
                        frameCount = requestCount
                    )
                    if (bitmaps.isNullOrEmpty()) break

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

                        if (!isProcessingVideo()) break
                    }
                }
            } finally {
                rgbaMat.release()
                orientedMat.release()
                encoder.release()
            }

            framesProcessed > 0 && outputFile.length() > 100
        } catch (e: Exception) {
            Log.e("VIDEO-PROCESS", "Batch frame processing failed: ${e.message}", e)
            false
        } finally {
            retriever.release()
        }
    }

    private suspend fun processVideoWithTimeSeek(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions,
        appContext: Context
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
            val durationUs = (durationMs.takeIf { it > 0L } ?: metadata.durationMs)
                .let { it * 1000L }
                .takeIf { it > 0L }
                ?: (metadata.frameCount.takeIf { it > 0L }?.times(frameDurationUs) ?: frameDurationUs)
            val firstFrame = findFirstReadableFrame(retriever, durationUs) ?: return false
            val rotationDegrees = rotationForDecodedFrame(firstFrame.width, firstFrame.height, metadata)
            val width = displayWidth(firstFrame.width, firstFrame.height, rotationDegrees)
            val height = displayHeight(firstFrame.width, firstFrame.height, rotationDegrees)
            firstFrame.recycle()

            val totalFrames = (durationUs / frameDurationUs).coerceAtLeast(1L)
            val encoder = VideoEncoder(outputFile.absolutePath, width, height, encoderFrameRate(fps))
            showProcessingStage("Preparing ${algorithmLabel(options)}...")
            val opticalFlow = createOpticalFlow(options, appContext)
            val rgbaMat = Mat()
            val orientedMat = Mat()
            var currentTimeUs = 0L
            var framesProcessed = 0L

            try {
                encoder.start()
                while (isProcessingVideo() && currentTimeUs < durationUs) {
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
            Log.e("VIDEO-PROCESS", "Time seek processing failed: ${e.message}", e)
            false
        } finally {
            retriever.release()
        }
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
                // Some decoders throw for sparse or non-keyframe timestamps. Try the next mode.
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
            retriever.getFramesAtIndex(startIndex, frameCount).orEmpty()
        } catch (e: Exception) {
            Log.w("VIDEO-PROCESS", "Frame batch unavailable at index $startIndex: ${e.message}")
            emptyList()
        }
    }

    private fun createOpticalFlow(options: VideoProcessOptions, appContext: Context): OpticalFlow {
        if (options.useAi) {
            val fallback = createConfiguredFarneback(options)
            if (!AIRaftOpticalFlow.isModelAvailable(appContext)) {
                mainViewModel.videoProcessingMessage.postValue("AI model missing; using Farneback...")
                return fallback
            }

            return try {
                val aiFlow = AIRaftOpticalFlow(appContext) { message ->
                    mainViewModel.videoProcessingMessage.postValue(message)
                }.apply {
                    setSensitivity(options.sensitivity)
                    setMovingMode(options.isMoving)
                    setVisualizationMode(currentAiVisualizationMode(options))
                }
                // Prepare model asynchronously to avoid blocking UI thread
                Thread {
                    try { aiFlow.prepare() } catch (e: Exception) {
                        Log.w("AI-RAFT", "prepare() failed: ${e.message}")
                    }
                }.start()
                val failover = FailoverOpticalFlow(aiFlow, fallback, "AI-RAFT") { error ->
                    mainViewModel.videoProcessingMessage.postValue(
                        "AI failed; using Farneback..."
                    )
                    Log.e("AI-RAFT", "Falling back to Farneback: ${error.message}", error)
                }
                FrameStrideOpticalFlow(failover, AI_FRAME_STRIDE, "AI-RAFT")
            } catch (e: Exception) {
                Log.e("AI-RAFT", "Failed to initialize AI optical flow: ${e.message}", e)
                mainViewModel.videoProcessingMessage.postValue("AI unavailable; using Farneback...")
                fallback
            }
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
        return if (options.useFarnebackHeatmap) {
            Farneback.VisualizationMode.HEATMAP
        } else {
            Farneback.VisualizationMode.VECTORS
        }
    }

    private fun currentAiVisualizationMode(options: VideoProcessOptions): AIRaftOpticalFlow.VisualizationMode {
        return if (options.useFarnebackHeatmap) {
            AIRaftOpticalFlow.VisualizationMode.HEATMAP
        } else {
            AIRaftOpticalFlow.VisualizationMode.VECTORS
        }
    }

    private fun algorithmLabel(options: VideoProcessOptions): String {
        return when {
            options.useAi -> "AI RAFT"
            options.useFarneback -> "Farneback"
            else -> "KLT"
        }
    }

    private fun showProcessingStage(message: String) {
        Log.d("VIDEO-PROCESS", message)
        mainViewModel.videoProcessingMessage.postValue(message)
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

        val totalLabel = totalFrames.takeIf { it > 0L }?.toString() ?: "?"
        val action = if (shouldRunAiFrame(frameNumber)) "AI processing" else "AI reusing"
        Log.d("AI-RAFT", "pipeline: $action frame $frameNumber / $totalLabel")
        mainViewModel.videoProcessingMessage.postValue("$action frame $frameNumber / $totalLabel...")
    }

    private fun shouldRunAiFrame(frameNumber: Long): Boolean {
        return frameNumber <= 1L || ((frameNumber - 1L) % AI_FRAME_STRIDE == 0L)
    }

    private fun isProcessingVideo(): Boolean {
        return mainViewModel.videoUploadJob?.isActive == true || VideoProcessingBus.isProcessing
    }

    private fun showProcessingToast() {
        Toast.makeText(
            safeContext(),
            "Đang loading, chờ xử lý xong rồi thử lại",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showUploadLoading(message: String) {
        mainViewModel.videoProcessingMessage.value = message
    }

    private fun dismissUploadLoading() {
        mainViewModel.videoProcessingMessage.value = null
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

            val outFrame = if (activeRoi != null) {
                rgbaMat
            } else {
                output?.ofFrame ?: rgbaMat
            }
            encoder.encodeFrame(outFrame, presentationTimeUs)
            if (outFrame !== rgbaMat && outFrame !== processingFrame) {
                outFrame.release()
            }
        } finally {
            if (processingFrame !== rgbaMat) {
                processingFrame.release()
            }
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
            mask = createRoiMask(
                normalized = normalized,
                frameCols = frameCols,
                frameRows = frameRows,
                rect = rect,
                mapX = { value -> mapX(value) },
                mapY = { value -> mapY(value) }
            )
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

    private fun restoreOutsideRoiMask(
        processingFrame: Mat,
        originalRoiFrame: Mat?,
        mask: Mat?
    ) {
        if (originalRoiFrame == null || mask == null) return

        val inverseMask = Mat()
        try {
            Core.bitwise_not(mask, inverseMask)
            originalRoiFrame.copyTo(processingFrame, inverseMask)
        } finally {
            inverseMask.release()
        }
    }

    private suspend fun updateProgressIfNeeded(framesProcessed: Long, totalFrames: Long) {
        if (totalFrames <= 0L) return
        val shouldUpdate = framesProcessed <= 3L || framesProcessed % progressUpdateIntervalFrames == 0L
        if (!shouldUpdate) return

        val progress = ((framesProcessed.toDouble() / totalFrames.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        withContext(Dispatchers.Main) {
            showProcessingProgress(if (framesProcessed > 0L && progress == 0) 1 else progress)
        }
    }

    private fun showProcessingProgress(progress: Int) {
        showUploadLoading("Processing: ${progress.coerceIn(0, 100)}%")
    }

    private fun validFps(rawFps: Double?, fallback: Double = 30.0): Double {
        return rawFps.takeIf { it != null && it.isFinite() && it >= 1.0 && it <= 120.0 }
            ?: fallback.takeIf { it.isFinite() && it >= 1.0 && it <= 120.0 }
            ?: 30.0
    }

    private fun encoderFrameRate(fps: Double): Int {
        return fps.roundToInt().coerceIn(1, 60)
    }

    private fun frameDurationUs(fps: Double): Long {
        return (1000000.0 / validFps(fps)).roundToLong().coerceAtLeast(1L)
    }

    private fun validDimension(value: Double): Int {
        return value.takeIf { it.isFinite() && it > 0.0 }?.roundToInt() ?: 0
    }

    private fun validFrameCount(value: Double): Long {
        return value.takeIf { it.isFinite() && it > 0.0 }?.roundToLong() ?: 0L
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
                fps = validFps(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toDoubleOrNull()
                ),
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: 0,
                rotationDegrees = normalizeRotationDegrees(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull()
                        ?: 0
                )
            )
        } catch (e: Exception) {
            Log.w("VIDEO-PROCESS", "Failed to read video progress metadata: ${e.message}")
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

    private fun rotationForDecodedFrame(
        frameWidth: Int,
        frameHeight: Int,
        metadata: VideoProgressMetadata
    ): Int {
        val rotationDegrees = normalizeRotationDegrees(metadata.rotationDegrees)
        if (rotationDegrees == 0) return 0

        val swapsAxes = rotationDegrees == 90 || rotationDegrees == 270
        val metadataWidth = metadata.width
        val metadataHeight = metadata.height
        if (swapsAxes &&
            metadataWidth > 0 &&
            metadataHeight > 0 &&
            frameWidth == metadataHeight &&
            frameHeight == metadataWidth
        ) {
            return 0
        }

        return rotationDegrees
    }

    private fun displayWidth(width: Int, height: Int, rotationDegrees: Int): Int {
        return if (normalizeRotationDegrees(rotationDegrees) in setOf(90, 270)) height else width
    }

    private fun displayHeight(width: Int, height: Int, rotationDegrees: Int): Int {
        return if (normalizeRotationDegrees(rotationDegrees) in setOf(90, 270)) width else height
    }

    private fun normalizeRotationDegrees(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return when (normalized) {
            90, 180, 270 -> normalized
            else -> 0
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun initObserver() {
    }

    private companion object {
        const val MIN_ROI_FRAME_SIZE = 32
        const val AI_FRAME_STRIDE = 1
    }

    private data class ActiveRoi(
        val rect: Rect,
        val mask: Mat?
    )
}
