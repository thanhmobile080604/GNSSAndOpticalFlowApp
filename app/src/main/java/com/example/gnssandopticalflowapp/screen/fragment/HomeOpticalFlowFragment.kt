package com.example.gnssandopticalflowapp.screen.fragment

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.model.VideoProgressMetadata
import com.example.gnssandopticalflowapp.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import com.example.gnssandopticalflowapp.screen.dialog.VideoProcessOptionsDialog
import com.example.gnssandopticalflowapp.util.VideoEncoder
import com.example.gnssandopticalflowapp.util.VideoStorageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import java.io.File
import java.io.FileOutputStream
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
    }

    private fun showVideoProcessOptions(uri: Uri) {
        VideoProcessOptionsDialog.show(childFragmentManager) { options ->
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
                
                processVideoOffline(sourceFile, options, appContext)
                
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
            showProcessingProgress(0)
        }

        val outputFile = File(sourceFile.parentFile, "processed_${System.currentTimeMillis()}.mp4")

        val processed = try {
            processVideoWithOpenCv(sourceFile, outputFile, options)
                || retryWithCleanOutput(outputFile) {
                    processVideoWithFrameBatch(sourceFile, outputFile, options)
                }
                || retryWithCleanOutput(outputFile) {
                    processVideoWithTimeSeek(sourceFile, outputFile, options)
                }
        } finally {
            sourceFile.delete()
        }

        if (processed && isProcessingVideo()) {
            MediaScannerConnection.scanFile(appContext, arrayOf(outputFile.absolutePath), null) { _, _ -> }

            withContext(Dispatchers.Main) {
                showProcessingProgress(100)
                VideoStorageUtil.addVideo(appContext, outputFile.absolutePath)
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
        options: VideoProcessOptions
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
        val opticalFlow = createOpticalFlow(options)
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
                encodeProcessedFrame(
                    opticalFlow = opticalFlow,
                    rgbaMat = frameForProcessing,
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

            val firstFrame = retriever.getFrameAtIndex(0) ?: return false
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
                while (isProcessingVideo() && framesProcessed < frameCount) {
                    val requestCount = minOf(batchFrameSize.toLong(), frameCount - framesProcessed).toInt()
                    val bitmaps = retriever.getFramesAtIndex(framesProcessed.toInt(), requestCount)
                    if (bitmaps.isNullOrEmpty()) break

                    for (bitmap in bitmaps) {
                        try {
                            Utils.bitmapToMat(bitmap, rgbaMat)
                            val frameForProcessing = rotateFrameForDisplay(rgbaMat, orientedMat, rotationDegrees)
                            encodeProcessedFrame(
                                opticalFlow = opticalFlow,
                                rgbaMat = frameForProcessing,
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
        options: VideoProcessOptions
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourceFile.absolutePath)
            val metadata = readVideoProgressMetadata(sourceFile)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val firstFrame = retriever.getFrameAtTime(0) ?: return false
            val rotationDegrees = rotationForDecodedFrame(firstFrame.width, firstFrame.height, metadata)
            val width = displayWidth(firstFrame.width, firstFrame.height, rotationDegrees)
            val height = displayHeight(firstFrame.width, firstFrame.height, rotationDegrees)
            firstFrame.recycle()

            val fps = 30.0
            val frameDurationUs = frameDurationUs(fps)
            val totalFrames = ((durationMs * 1000L) / frameDurationUs).coerceAtLeast(1L)
            val encoder = VideoEncoder(outputFile.absolutePath, width, height, encoderFrameRate(fps))
            val opticalFlow = createOpticalFlow(options)
            val rgbaMat = Mat()
            val orientedMat = Mat()
            var currentTimeUs = 0L
            var framesProcessed = 0L

            try {
                encoder.start()
                while (isProcessingVideo() && currentTimeUs < durationMs * 1000L) {
                    val bitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        try {
                            Utils.bitmapToMat(bitmap, rgbaMat)
                            val frameForProcessing = rotateFrameForDisplay(rgbaMat, orientedMat, rotationDegrees)
                            encodeProcessedFrame(
                                opticalFlow = opticalFlow,
                                rgbaMat = frameForProcessing,
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

    private fun createOpticalFlow(options: VideoProcessOptions): OpticalFlow {
        val opticalFlow: OpticalFlow = if (options.useFarneback) {
            Farneback()
        } else {
            KLT()
        }

        return opticalFlow.apply {
            setSensitivity(options.sensitivity)
            setMovingMode(options.isMoving)
        }
    }

    private fun isProcessingVideo(): Boolean {
        return mainViewModel.videoUploadJob?.isActive == true
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
        encoder: VideoEncoder,
        presentationTimeUs: Long
    ) {
        if (rgbaMat.empty()) return

        val output = opticalFlow.run(rgbaMat)
        val outFrame = output?.ofFrame ?: rgbaMat
        encoder.encodeFrame(outFrame, presentationTimeUs)
        if (outFrame !== rgbaMat) {
            outFrame.release()
        }
    }

    private suspend fun updateProgressIfNeeded(framesProcessed: Long, totalFrames: Long) {
        if (totalFrames <= 0L || framesProcessed % progressUpdateIntervalFrames != 0L) return

        val progress = ((framesProcessed.toDouble() / totalFrames.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        withContext(Dispatchers.Main) {
            showProcessingProgress(progress)
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
}
