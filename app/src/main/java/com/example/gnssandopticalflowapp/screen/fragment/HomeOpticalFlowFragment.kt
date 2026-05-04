package com.example.gnssandopticalflowapp.screen.fragment

import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class HomeOpticalFlowFragment : BaseFragment<FragmentHomeOpticalFlowBinding>(FragmentHomeOpticalFlowBinding::inflate) {
    private var copyJob: Job? = null
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
        showLoadingDialog("Copying video...") {
            copyJob?.cancel()
        }
        
        copyJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = safeContext().cacheDir
                val videosDir = File(cacheDir, "videos")
                if (!videosDir.exists()) videosDir.mkdirs()
                
                val sourceFile = File(videosDir, "temp_source_${System.currentTimeMillis()}.mp4")
                safeContext().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                processVideoOffline(sourceFile, options)
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismissLoadingDialog()
                }
            }
        }
    }

    private suspend fun processVideoOffline(sourceFile: File, options: VideoProcessOptions) {
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

        if (processed && copyJob?.isActive == true) {
            MediaScannerConnection.scanFile(safeContext(), arrayOf(outputFile.absolutePath), null) { _, _ -> }

            withContext(Dispatchers.Main) {
                showProcessingProgress(100)
                dismissLoadingDialog()
                VideoStorageUtil.addVideo(safeContext(), outputFile.absolutePath)
                kotlinx.coroutines.delay(500)
                mainViewModel.selectedVideoPath.value = outputFile.absolutePath
                navigateTo(R.id.videoOpticalFlowFragment)
            }
        } else {
            Log.d("VIDEO-PROCESS", "Processing cancelled.")
            outputFile.delete()
            withContext(Dispatchers.Main) {
                dismissLoadingDialog()
            }
        }
    }

    private suspend fun retryWithCleanOutput(
        outputFile: File,
        block: suspend () -> Boolean
    ): Boolean {
        if (copyJob?.isActive != true) return false
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
        val encoder = VideoEncoder(outputFile.absolutePath, width, height, encoderFrameRate(fps))
        val opticalFlow = createOpticalFlow(options)
        val sourceMat = Mat()
        val rgbaMat = Mat()
        var framesProcessed = 0L

        try {
            encoder.start()
            while (copyJob?.isActive == true && capture.read(sourceMat)) {
                if (sourceMat.empty()) break

                convertCaptureFrameToRgba(sourceMat, rgbaMat)
                encodeProcessedFrame(
                    opticalFlow = opticalFlow,
                    rgbaMat = rgbaMat,
                    encoder = encoder,
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
            capture.release()
            encoder.release()
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
            val frameCount = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return false

            val firstFrame = retriever.getFrameAtIndex(0) ?: return false
            val width = firstFrame.width
            val height = firstFrame.height
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
            var framesProcessed = 0L

            try {
                encoder.start()
                while (copyJob?.isActive == true && framesProcessed < frameCount) {
                    val requestCount = minOf(batchFrameSize.toLong(), frameCount - framesProcessed).toInt()
                    val bitmaps = retriever.getFramesAtIndex(framesProcessed.toInt(), requestCount)
                    if (bitmaps.isNullOrEmpty()) break

                    for (bitmap in bitmaps) {
                        try {
                            Utils.bitmapToMat(bitmap, rgbaMat)
                            encodeProcessedFrame(
                                opticalFlow = opticalFlow,
                                rgbaMat = rgbaMat,
                                encoder = encoder,
                                presentationTimeUs = framesProcessed * frameDurationUs
                            )
                            framesProcessed++
                            updateProgressIfNeeded(framesProcessed, frameCount)
                        } finally {
                            bitmap.recycle()
                        }

                        if (copyJob?.isActive != true) break
                    }
                }
            } finally {
                rgbaMat.release()
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
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val firstFrame = retriever.getFrameAtTime(0) ?: return false
            val width = firstFrame.width
            val height = firstFrame.height
            firstFrame.recycle()

            val fps = 30.0
            val frameDurationUs = frameDurationUs(fps)
            val totalFrames = ((durationMs * 1000L) / frameDurationUs).coerceAtLeast(1L)
            val encoder = VideoEncoder(outputFile.absolutePath, width, height, encoderFrameRate(fps))
            val opticalFlow = createOpticalFlow(options)
            val rgbaMat = Mat()
            var currentTimeUs = 0L
            var framesProcessed = 0L

            try {
                encoder.start()
                while (copyJob?.isActive == true && currentTimeUs < durationMs * 1000L) {
                    val bitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        try {
                            Utils.bitmapToMat(bitmap, rgbaMat)
                            encodeProcessedFrame(
                                opticalFlow = opticalFlow,
                                rgbaMat = rgbaMat,
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
            KLT(null)
        }

        return opticalFlow.apply {
            setSensitivity(50)
            setMovingMode(options.isMoving)
        }
    }

    private fun convertCaptureFrameToRgba(sourceMat: Mat, rgbaMat: Mat) {
        when (sourceMat.channels()) {
            4 -> Imgproc.cvtColor(sourceMat, rgbaMat, Imgproc.COLOR_BGRA2RGBA)
            3 -> Imgproc.cvtColor(sourceMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            1 -> Imgproc.cvtColor(sourceMat, rgbaMat, Imgproc.COLOR_GRAY2RGBA)
            else -> sourceMat.copyTo(rgbaMat)
        }
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
        showLoadingDialog("Processing: ${progress.coerceIn(0, 100)}%")
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

    override fun onDestroyView() {
        super.onDestroyView()
        copyJob?.cancel()
    }

    override fun initObserver() {
    }
}
