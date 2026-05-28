package com.example.gnssandopticalflowapp.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.gnssandopticalflowapp.MainActivity
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.common.AndroidConnectivityObserver
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.model.VideoProgressMetadata
import com.example.gnssandopticalflowapp.optical_flow.classes.AIRaftOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.classes.FailoverOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.optical_flow.classes.FrameStrideOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.interfaces.FrameProgressAwareOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import com.example.gnssandopticalflowapp.util.VideoEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class VideoProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private var currentProgressPercent = VideoProcessingProgressText.DEFAULT_PERCENT
    private var currentProcessingMode: VideoProcessOptions.ProcessingMode? = null
    private val connectivityObserver = AndroidConnectivityObserver(appContext.applicationContext)
    private val localJobId: String = workerParams.inputData.getString(EXTRA_JOB_ID)
        ?.takeIf { it.isNotBlank() }
        ?: workerParams.id.toString().replace("-", "").take(8).uppercase(Locale.US)

    @Volatile
    private var currentServerJobId: String? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return createForegroundInfo(currentProgressMessage(), ongoing = true)
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()

        val sourcePath = inputData.getString(EXTRA_SOURCE_PATH)
        val optionsPath = inputData.getString(EXTRA_OPTIONS_PATH)
        val optionsFile = optionsPath?.takeIf { it.isNotBlank() }?.let(::File)
        val options = optionsFile
            ?.takeIf { it.isFile }
            ?.readText()
            ?.toVideoProcessOptions()
        if (sourcePath.isNullOrBlank() || options == null) {
            Log.e(TAG, "Missing source path or process options")
            sourcePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            optionsFile?.delete()
            VideoProcessingBus.postFailed(localJobId, "Processing failed")
            return Result.failure()
        }
        currentProcessingMode = options.processingMode

        currentProgressPercent = VideoProcessingProgressText.DEFAULT_PERCENT
        setForeground(createForegroundInfo(currentProgressMessage(), ongoing = true))
        VideoProcessingBus.postProcessing(localJobId, options.processingMode, currentProgressMessage())

        val sourceFile = File(sourcePath)
        return try {
            val outputFile = processVideo(sourceFile, options)
            MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(outputFile.absolutePath),
                null
            ) { _, _ -> }
            MediaStorageUtil.addVideo(applicationContext, outputFile.absolutePath)
            VideoProcessingBus.postFinished(localJobId, outputFile.absolutePath)
            showCompletedNotification("Processing done", outputFile.absolutePath)
            Result.success(workDataOf(OUTPUT_VIDEO_PATH to outputFile.absolutePath))
        } catch (e: CancellationException) {
            Log.d(TAG, "Processing cancelled")
            VideoProcessingBus.postCancelled(localJobId)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed: ${e.message}", e)
            VideoProcessingBus.postFailed(localJobId, "Processing failed")
            showCompletedNotification("Processing failed", null)
            Result.failure()
        } finally {
            sourceFile.delete()
            optionsFile.delete()
        }
    }

    private suspend fun processVideo(
        sourceFile: File,
        options: VideoProcessOptions
    ): File {
        postProgressPercent(VideoProcessingProgressText.DEFAULT_PERCENT)
        val outputFile = File(sourceFile.parentFile, "processed_${System.currentTimeMillis()}.mp4")

        val processed = try {
            when (options.processingMode) {
                VideoProcessOptions.ProcessingMode.OFFLINE -> processVideoLocally(
                    sourceFile,
                    outputFile,
                    options
                )
                VideoProcessOptions.ProcessingMode.ONLINE -> processVideoOnServer(
                    sourceFile,
                    outputFile,
                    options
                )
            }
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        }

        if (!isProcessingActive()) {
            outputFile.delete()
            throw CancellationException("Video processing did not complete")
        }
        if (!processed) {
            outputFile.delete()
            throw IllegalStateException("Video processing did not complete")
        }

        postProgressPercent(VideoProcessingProgressText.COMPLETE_PERCENT)
        return outputFile
    }

    private suspend fun processVideoLocally(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean {
        val aiOptions = options.copy(useFarneback = false, useAi = true)
        postCurrentProgress("Processing on-device AI model...")
        return processVideoWithOpenCv(sourceFile, outputFile, aiOptions) ||
            retryWithCleanOutput(outputFile) {
                processVideoWithFrameBatch(sourceFile, outputFile, aiOptions)
            } ||
            retryWithCleanOutput(outputFile) {
                processVideoWithTimeSeek(sourceFile, outputFile, aiOptions)
            }
    }

    private suspend fun retryWithCleanOutput(
        outputFile: File,
        block: suspend () -> Boolean
    ): Boolean {
        if (!isProcessingActive()) return false
        outputFile.delete()
        return block()
    }

    private suspend fun processVideoOnServer(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            postCurrentProgress("Uploading to server...")
            val jobId = createServerVideoJob(sourceFile, options) ?: return@withContext false
            currentServerJobId = jobId
            postCurrentProgress("Processing on server...")
            return@withContext waitForServerVideoJob(jobId, outputFile)
        } catch (e: CancellationException) {
            cancelCurrentServerJob()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Server connection failed: ${e.message}", e)
            return@withContext false
        } finally {
            currentServerJobId = null
        }
    }

    private suspend fun createServerVideoJob(
        sourceFile: File,
        options: VideoProcessOptions
    ): String? {
        while (isProcessingActive()) {
            Log.d(
                TAG,
                "Creating server video job mode=${if (options.useFarnebackHeatmap) "HEATMAP" else "VECTORS"} " +
                    "algorithm=${serverAlgorithmName(options)} isMoving=${options.isMoving}"
            )
            val response = runServerNetworkRequest("Server job upload") {
                val videoBody = sourceFile.asRequestBody("video/mp4".toMediaType())
                val videoPart = MultipartBody.Part.createFormData("file", sourceFile.name, videoBody)
                OpticalFlowServerClient.api.createProcessVideoJob(
                    file = videoPart,
                    fields = serverMultipartFields(options)
                )
            }

            if (response.isSuccessful) {
                return response.body()?.jobId?.takeIf { it.isNotBlank() }?.also { jobId ->
                    currentServerJobId = jobId
                    VideoProcessingBus.postServerJobId(localJobId, jobId)
                }
            }

            if (response.code() == HTTP_TOO_MANY_REQUESTS) {
                Log.w(TAG, "Server queue is full; waiting before retrying job upload")
                postCurrentProgress("Server queue is full; waiting...")
                delay(SERVER_QUEUE_RETRY_DELAY_MS)
                continue
            }

            Log.e(TAG, "Server job create error: ${response.code()} ${response.errorBodyText()}")
            return null
        }
        throw CancellationException("Video processing stopped")
    }

    private suspend fun waitForServerVideoJob(jobId: String, outputFile: File): Boolean {
        while (isProcessingActive()) {
            val statusPayload = fetchServerVideoJob(jobId) ?: return false
            statusPayload.progress?.let { postProgressPercent(it) }
            when (val status = statusPayload.status) {
                "queued" -> {
                    postCurrentProgress("Queued on server...")
                    delay(SERVER_POLL_INTERVAL_MS)
                }
                "processing" -> {
                    delay(SERVER_POLL_INTERVAL_MS)
                }
                "cancelling" -> {
                    postCurrentProgress("Cancelling server job...")
                    delay(SERVER_POLL_INTERVAL_MS)
                }
                "cancelled" -> {
                    Log.d(TAG, "Server job cancelled: $jobId")
                    return false
                }
                "completed" -> {
                    postCurrentProgress("Downloading processed video...")
                    return downloadServerVideoJobResult(jobId, outputFile)
                }
                "failed" -> {
                    Log.e(TAG, "Server job failed: ${statusPayload.error.orEmpty()}")
                    return false
                }
                else -> {
                    Log.e(TAG, "Unknown server job status: $status")
                    return false
                }
            }
        }
        return false
    }

    private suspend fun fetchServerVideoJob(jobId: String): ServerVideoJobResponse? {
        return runServerNetworkRequest("Server job status") {
            val response = OpticalFlowServerClient.api.getProcessVideoJob(jobId)
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.e(TAG, "Server job status error: ${response.code()} ${response.errorBodyText()}")
                null
            }
        }
    }

    private suspend fun cancelCurrentServerJob() {
        val jobId = currentServerJobId?.takeIf { it.isNotBlank() } ?: return
        kotlinx.coroutines.withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                withTimeout(SERVER_CANCEL_TIMEOUT_MS) {
                    val response = OpticalFlowServerClient.api.cancelProcessVideoJob(jobId)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Server job cancel requested: $jobId")
                    } else {
                        Log.w(TAG, "Server job cancel failed: $jobId ${response.code()} ${response.errorBodyText()}")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Server job cancel request failed: $jobId ${error.message}")
            }
        }
    }

    private suspend fun downloadServerVideoJobResult(jobId: String, outputFile: File): Boolean {
        return runServerNetworkRequest("Server result download") {
            outputFile.delete()
            val response = OpticalFlowServerClient.api.downloadProcessVideoJobResult(jobId)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                java.io.FileOutputStream(outputFile).use { fos ->
                    body.byteStream().use { it.copyTo(fos) }
                }
                outputFile.length() > 100
            } else {
                Log.e(TAG, "Server result download error: ${response.code()} ${response.errorBodyText()}")
                false
            }
        }
    }

    private suspend fun <T> runServerNetworkRequest(
        operationName: String,
        block: suspend () -> T
    ): T {
        var retryDelayMs = NETWORK_RETRY_DELAY_MS
        while (isProcessingActive()) {
            waitForInternetConnection()
            try {
                return block()
            } catch (e: IOException) {
                if (!isProcessingActive()) {
                    throw CancellationException("Video processing stopped")
                }

                val internetAvailable = isInternetConnected()
                Log.w(
                    TAG,
                    "$operationName interrupted by network error: ${e.message}; " +
                        "internetAvailable=$internetAvailable"
                )

                if (internetAvailable) {
                    postCurrentProgress("$operationName interrupted; retrying...")
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_NETWORK_RETRY_DELAY_MS)
                } else {
                    retryDelayMs = NETWORK_RETRY_DELAY_MS
                    waitForInternetConnection()
                }
            }
        }
        throw CancellationException("Video processing stopped")
    }

    private suspend fun waitForInternetConnection() {
        if (isInternetConnected()) return
        postCurrentProgress("Waiting for internet connection...")
        connectivityObserver.isConnected.first { isConnected ->
            !isProcessingActive() || isConnected
        }
        if (!isProcessingActive()) {
            throw CancellationException("Video processing stopped")
        }
        postCurrentProgress("Network restored. Resuming...")
    }

    private suspend fun isInternetConnected(): Boolean {
        return connectivityObserver.isConnected.first()
    }

    private fun serverMultipartFields(options: VideoProcessOptions): Map<String, RequestBody> {
        val fields = mutableMapOf<String, RequestBody>()
        fields["mode"] = textPart(if (options.useFarnebackHeatmap) "HEATMAP" else "VECTORS")
        fields["processing_mode"] = textPart(options.processingMode.name)
        fields["algorithm"] = textPart(serverAlgorithmName(options))
        fields["sensitivity"] = textPart(options.sensitivity.toString())
        fields["is_moving"] = textPart(options.isMoving.toString())
        fields["isMoving"] = textPart(options.isMoving.toString())
        fields["roi_enabled"] = textPart((options.roi != null).toString())

        options.roi?.let { roi ->
            fields["roi_left"] = textPart(roi.left.toString())
            fields["roi_top"] = textPart(roi.top.toString())
            fields["roi_right"] = textPart(roi.right.toString())
            fields["roi_bottom"] = textPart(roi.bottom.toString())
            fields["roi_view_aspect_ratio"] = textPart(roi.viewAspectRatio.toString())
            fields["roi_path_points"] = textPart(
                roi.pathPoints.joinToString(separator = ";") { point ->
                    "${point.x},${point.y}"
                }
            )
        }
        return fields
    }

    private fun textPart(value: String): RequestBody {
        return value.toRequestBody("text/plain".toMediaType())
    }

    private fun retrofit2.Response<*>.errorBodyText(): String {
        return try {
            errorBody()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun serverAlgorithmName(options: VideoProcessOptions): String {
        return when {
            options.useAi -> "AI"
            options.useFarneback -> "FARNEBACK"
            else -> "KLT"
        }
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
            while (isProcessingActive() && capture.read(sourceMat)) {
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
                while (isProcessingActive() && framesProcessed < frameCount) {
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
                        if (!isProcessingActive()) break
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
                while (isProcessingActive() && currentTimeUs < durationUs) {
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
            if (!AIRaftOpticalFlow.isModelAvailable(applicationContext)) {
                postCurrentProgress(
                    if (requireAiModel) "AI model missing" else "AI model missing; using Farneback"
                )
                if (requireAiModel) throw AIRaftOpticalFlow.ModelMissingException()
                return fallback
            }

            val aiFlow = AIRaftOpticalFlow(applicationContext, ::postCurrentProgress).apply {
                setSensitivity(options.sensitivity)
                setMovingMode(options.isMoving)
                setVisualizationMode(currentAiVisualizationMode(options))
            }
            // Load model in background thread to avoid blocking service startup
            Thread {
                try { aiFlow.prepare() } catch (e: Exception) {
                    Log.w("AI-RAFT", "prepare() failed: ${e.message}")
                }
            }.start()
            if (requireAiModel) {
                return FrameStrideOpticalFlow(aiFlow, AI_FRAME_STRIDE, "AI-RAFT")
            }

            val failover = FailoverOpticalFlow(aiFlow, fallback, "AI-RAFT") { error ->
                postCurrentProgress("AI failed; using Farneback")
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

        postProgressPercent(progressPercent(frameNumber, totalFrames))
    }

    private fun updateProgressIfNeeded(framesProcessed: Long, totalFrames: Long) {
        if (totalFrames <= 0L) return
        val shouldUpdate = framesProcessed <= 3L || framesProcessed % PROGRESS_UPDATE_INTERVAL_FRAMES == 0L
        if (!shouldUpdate) return

        postProgressPercent(progressPercent(framesProcessed, totalFrames))
    }

    private fun postCurrentProgress(status: String) {
        Log.d(TAG, status)
        postProgress(VideoProcessingProgressText.normalize(status, currentProgressPercent))
    }

    private fun postProgressPercent(percent: Int) {
        currentProgressPercent = percent.coerceIn(
            VideoProcessingProgressText.DEFAULT_PERCENT,
            VideoProcessingProgressText.COMPLETE_PERCENT
        )
        postProgress(currentProgressMessage())
    }

    private fun postProgress(message: String) {
        Log.d(TAG, message)
        VideoProcessingBus.postProcessing(localJobId, currentProcessingMode, message)
        updateNotification(message, ongoing = true)
    }

    private fun currentProgressMessage(): String {
        return VideoProcessingProgressText.format(currentProgressPercent)
    }

    private fun progressPercent(framesProcessed: Long, totalFrames: Long): Int {
        if (totalFrames <= 0L) return currentProgressPercent

        val progress = ((framesProcessed.toDouble() / totalFrames.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(
                VideoProcessingProgressText.DEFAULT_PERCENT,
                VideoProcessingProgressText.COMPLETE_PERCENT
            )
        return if (framesProcessed > 0L && progress == 0) 1 else progress
    }

    private fun updateNotification(message: String, ongoing: Boolean) {
        setProgressAsync(workDataOf(PROGRESS_PERCENT to currentProgressPercent))
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId(localJobId), buildNotification(message, ongoing))
    }

    private fun createForegroundInfo(message: String, ongoing: Boolean): ForegroundInfo {
        val notification = buildNotification(message, ongoing)
        val notificationId = notificationId(localJobId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(message: String, ongoing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId(localJobId),
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_process)
            .setContentTitle("Video processing #$localJobId")
            .setContentText(message)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_close, "Cancel", cancelIntent)
            .build()
    }

    private fun showCompletedNotification(message: String, videoPath: String?) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (videoPath != null) {
                putExtra("processed_video_path", videoPath)
            }
        }
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            completedNotificationId(localJobId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, COMPLETED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_process)
            .setContentTitle("Video processing #$localJobId")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        manager.notify(completedNotificationId(localJobId), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video processing",
            NotificationManager.IMPORTANCE_LOW
        )
        val completedChannel = NotificationChannel(
            COMPLETED_CHANNEL_ID,
            "Video processing completed",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(completedChannel)
    }

    private fun isProcessingActive(): Boolean {
        return !isStopped
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

    private data class ActiveRoi(val rect: Rect, val mask: Mat?)

    companion object {
        private const val TAG = "VIDEO-WORKER"
        private const val CHANNEL_ID = "video_processing"
        private const val COMPLETED_CHANNEL_ID = "video_processing_completed"
        private const val NOTIFICATION_ID_BASE = 3001
        private const val COMPLETED_NOTIFICATION_ID_BASE = 13001
        private const val WORK_TAG = "video_processing_work"
        private const val JOB_TAG_PREFIX = "video_processing_job_"
        private const val NOTIFICATION_ID_RANGE = 9000
        private const val EXTRA_SOURCE_PATH = "source_path"
        private const val EXTRA_OPTIONS_PATH = "options_path"
        private const val EXTRA_JOB_ID = "job_id"
        private const val OUTPUT_VIDEO_PATH = "output_video_path"
        private const val PROGRESS_PERCENT = "progress_percent"
        private const val BATCH_FRAME_SIZE = 8
        private const val PROGRESS_UPDATE_INTERVAL_FRAMES = 30L
        private const val MIN_ROI_FRAME_SIZE = 32
        private const val AI_FRAME_STRIDE = 1
        private const val SERVER_POLL_INTERVAL_MS = 2_000L
        private const val SERVER_CANCEL_TIMEOUT_MS = 5_000L
        private const val SERVER_QUEUE_RETRY_DELAY_MS = 5_000L
        private const val NETWORK_RETRY_DELAY_MS = 3_000L
        private const val MAX_NETWORK_RETRY_DELAY_MS = 30_000L
        private const val HTTP_TOO_MANY_REQUESTS = 429

        fun newJobId(): String = VideoProcessingBus.createJobId()

        fun enqueue(
            context: Context,
            sourcePath: String,
            options: VideoProcessOptions,
            jobId: String = newJobId()
        ): String {
            val sourceFile = File(sourcePath)
            val optionsFile = File(
                sourceFile.parentFile ?: context.applicationContext.cacheDir,
                "${sourceFile.nameWithoutExtension}_${jobId}_options.json"
            )
            optionsFile.writeText(options.toJsonString())

            val request = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                .setInputData(
                    workDataOf(
                        EXTRA_JOB_ID to jobId,
                        EXTRA_SOURCE_PATH to sourcePath,
                        EXTRA_OPTIONS_PATH to optionsFile.absolutePath
                    )
                )
                .addTag(WORK_TAG)
                .addTag(jobTag(jobId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return jobId
        }

        fun cancel(context: Context, jobId: String? = null) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            if (jobId.isNullOrBlank()) {
                workManager.cancelAllWorkByTag(WORK_TAG)
                VideoProcessingBus.jobsSnapshot()
                    .filter { it.isActive }
                    .forEach { VideoProcessingBus.postCancelled(it.jobId) }
                return
            }

            workManager.cancelAllWorkByTag(jobTag(jobId))
            VideoProcessingBus.postCancelled(jobId)
        }

        private fun jobTag(jobId: String): String {
            return JOB_TAG_PREFIX + jobId
        }

        private fun notificationId(jobId: String): Int {
            return NOTIFICATION_ID_BASE + ((jobId.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_RANGE)
        }

        private fun completedNotificationId(jobId: String): Int {
            return COMPLETED_NOTIFICATION_ID_BASE + ((jobId.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_RANGE)
        }

        private fun VideoProcessOptions.toJsonString(): String {
            val root = JSONObject()
                .put("isMoving", isMoving)
                .put("useFarneback", useFarneback)
                .put("sensitivity", sensitivity)
                .put("useFarnebackHeatmap", useFarnebackHeatmap)
                .put("useAi", useAi)
                .put("processingMode", processingMode.name)

            roi?.let { normalizedRoi ->
                val pathPoints = JSONArray()
                normalizedRoi.pathPoints.forEach { point ->
                    pathPoints.put(
                        JSONObject()
                            .put("x", point.x.toDouble())
                            .put("y", point.y.toDouble())
                    )
                }

                root.put(
                    "roi",
                    JSONObject()
                        .put("left", normalizedRoi.left.toDouble())
                        .put("top", normalizedRoi.top.toDouble())
                        .put("right", normalizedRoi.right.toDouble())
                        .put("bottom", normalizedRoi.bottom.toDouble())
                        .put("viewAspectRatio", normalizedRoi.viewAspectRatio.toDouble())
                        .put("pathPoints", pathPoints)
                )
            }

            return root.toString()
        }

        private fun String.toVideoProcessOptions(): VideoProcessOptions? {
            return runCatching {
                val root = JSONObject(this)
                val roi = root.optJSONObject("roi")?.let { roiJson ->
                    val pathPointsJson = roiJson.optJSONArray("pathPoints")
                    val pathPoints = if (pathPointsJson == null) {
                        emptyList()
                    } else {
                        List(pathPointsJson.length()) { index ->
                            val pointJson = pathPointsJson.getJSONObject(index)
                            VideoProcessOptions.NormalizedPoint(
                                x = pointJson.getDouble("x").toFloat(),
                                y = pointJson.getDouble("y").toFloat()
                            )
                        }
                    }

                    VideoProcessOptions.NormalizedRoi(
                        left = roiJson.getDouble("left").toFloat(),
                        top = roiJson.getDouble("top").toFloat(),
                        right = roiJson.getDouble("right").toFloat(),
                        bottom = roiJson.getDouble("bottom").toFloat(),
                        viewAspectRatio = roiJson.getDouble("viewAspectRatio").toFloat(),
                        pathPoints = pathPoints
                    )
                }

                val processingMode = runCatching {
                    VideoProcessOptions.ProcessingMode.valueOf(
                        root.optString(
                            "processingMode",
                            VideoProcessOptions.ProcessingMode.OFFLINE.name
                        )
                    )
                }.getOrDefault(VideoProcessOptions.ProcessingMode.OFFLINE)

                VideoProcessOptions(
                    isMoving = root.getBoolean("isMoving"),
                    useFarneback = root.getBoolean("useFarneback"),
                    sensitivity = root.getInt("sensitivity"),
                    useFarnebackHeatmap = root.optBoolean("useFarnebackHeatmap", false),
                    useAi = root.optBoolean("useAi", false),
                    roi = roi,
                    processingMode = processingMode
                )
            }.getOrNull()
        }
    }
}
