package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.SeekBar
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentCameraOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import com.example.gnssandopticalflowapp.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.classes.MotionVectorViz
import com.example.gnssandopticalflowapp.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.util.AnalyticsStorageUtil
import com.example.gnssandopticalflowapp.util.VideoEncoder
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencv.imgproc.Imgproc

class CameraOpticalFlowFragment :
    BaseFragment<FragmentCameraOpticalFlowBinding>(FragmentCameraOpticalFlowBinding::inflate) {

    private var mvMat: Mat? = null
    private var output: OFOutput? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile
    private var currentFrameWidth: Int = 0
    @Volatile
    private var currentFrameHeight: Int = 0
    private lateinit var opticalFlow: OpticalFlow
    private lateinit var kltLabFlow: KLT
    private lateinit var farnebackLabFlow: Farneback
    private lateinit var imuEstimator: IMUEstimator
    private lateinit var mvViewer: MotionVectorViz
    private var frameCount: Int = 0
    private val updateInterval: Int = 30 // frames between automatic feature updates

    private var videoEncoder: VideoEncoder? = null
    private var isRecording = false
    private var recordedFilePath = ""

    private var timerJob: Job? = null
    private var timerStartTime: Long = 0L
    private var elapsedBeforePause: Long = 0L
    private var cameraFrameBitmap: Bitmap? = null
    private var motionVectorBitmap: Bitmap? = null
    private var isMovingMode = false
    private var isMovingModeManualOverride = false
    private var ignoreMovingSwitchChanges = false
    private var isAnalysisActive = false
    private var analysisStartedAtWallMs = 0L
    private var analysisStartedAtElapsedMs = 0L
    private var analysisFrameIndex = 0L
    private var lastAnalysisSampleElapsedMs = -ANALYSIS_SAMPLE_INTERVAL_MS
    private val analysisLock = Any()
    private val analysisSamples = mutableListOf<AnalyticsSample>()
    private var restoreKltSensitivity = 50
    private var restoreFarnebackSensitivity = 50
    // Auto detection source only. testType switch controls manual/auto at runtime.
    // true: phone IMU motion, false: GNSS location speed.
    private val useIndoorPhoneMotionDetection = true
    private var phoneMovingHoldFrames = 0
    private val phoneMovingAccelerationThreshold = 0.25
    private val phoneMovingHoldFrameCount = 12

    override fun FragmentCameraOpticalFlowBinding.initView() {
        initVars()
        kltSensitivityBar.progress = 50
        farnebackSensitivityBar.progress = 50
        testType.isChecked = false
        applyOpticalFlowModeUi(useFarneback = false)
        applyCurrentSensitivity()
        applyMovingMode(isMoving = false, manualOverride = false)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraView.scaleType = PreviewView.ScaleType.FILL_CENTER

        imuEstimator = IMUEstimator(safeContext().applicationContext)

        checkPermissions()
    }

    private fun initVars() {
        // first initialize with KLT optical flow
        opticalFlow = KLT()
        kltLabFlow = KLT()
        farnebackLabFlow = Farneback()
        output = OFOutput()

        // init motion vector viewer
        mvViewer = MotionVectorViz(400, 400)
        mvMat = Mat.zeros(400, 400, CvType.CV_8UC1)
    }

    private fun checkPermissions() {
        doRequestPermission(
            arrayOf(Manifest.permission.CAMERA),
            object : IPermissionListener {
                override fun onAllow() {
                    startCamera()
                }

                override fun onDenied() {
                    Toast.makeText(safeContext(), "Camera permission is required for this app", Toast.LENGTH_LONG).show()
                }

                override fun onNeverAskAgain(permission: String) {
                    Toast.makeText(safeContext(), "Camera permission is required. Please enable it in settings.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            safeContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        if (!::cameraExecutor.isInitialized || !hasCameraPermission()) return

        val context = safeContext()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (!isAdded || view == null) return@addListener

            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val rotation = binding.cameraView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.surfaceProvider = binding.cameraView.surfaceProvider
                }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ::analyzeCameraFrame)
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX use cases: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    override fun FragmentCameraOpticalFlowBinding.initListener() {
//        resetMV.setSingleClick {
//            opticalFlow.resetMotionVector()
//            mvViewer.resetMotionVector()
//        }

        updateFeaturesButton.setSingleClick {
            if (isAnalysisActive) {
                stopAnalysis(saveSession = true, showToast = true)
            } else {
                if (isRecording) {
                    Toast.makeText(safeContext(), "Stop recording before analysis", Toast.LENGTH_SHORT).show()
                    return@setSingleClick
                }
                startAnalysis()
            }
        }

        movingStatus.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreMovingSwitchChanges) return@setOnCheckedChangeListener

            if (binding.testType.isChecked) {
                applyMovingMode(isMoving = isChecked, manualOverride = true)
            } else {
                ignoreMovingSwitchChanges = true
                binding.movingStatus.isChecked = isMovingMode
                ignoreMovingSwitchChanges = false
            }
        }

        testType.setOnCheckedChangeListener { _, isManualMode ->
            phoneMovingHoldFrames = 0

            if (isManualMode) {
                applyMovingMode(isMoving = binding.movingStatus.isChecked, manualOverride = true)
            } else {
                isMovingModeManualOverride = false
                updateAutoMovingModeFromCurrentSource()
            }
        }

        ofType.setSingleClick {
            if (isAnalysisActive) {
                binding.ofType.isChecked = !binding.ofType.isChecked
                Toast.makeText(safeContext(), "Stop analysis before changing mode", Toast.LENGTH_SHORT).show()
                return@setSingleClick
            }

            val selectedOpticalFlow = if (ofType.isChecked) {
                ofAlgorithm.text = "Farneback"
                Farneback()
            } else {
                ofAlgorithm.text = "KLT"
                KLT()
            }
            opticalFlow = selectedOpticalFlow
            applyOpticalFlowModeUi(useFarneback = ofType.isChecked)
            applyCurrentSensitivity()
            opticalFlow.setMovingMode(isMovingMode)
            mvViewer.resetMotionVector()
            motionVectorBitmap = null
            binding.motionVector.setImageBitmap(null)
        }

        kltSensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("SEEK", progress.toString())
                if (::opticalFlow.isInitialized && !ofType.isChecked) {
                    opticalFlow.setSensitivity(progress)
                }
                if (::kltLabFlow.isInitialized) {
                    kltLabFlow.setSensitivity(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        farnebackSensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("SEEK", progress.toString())
                if (::opticalFlow.isInitialized && ofType.isChecked) {
                    opticalFlow.setSensitivity(progress)
                }
                if (::farnebackLabFlow.isInitialized) {
                    farnebackLabFlow.setSensitivity(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        ivVideRecord.setSingleClick {
            if (isAnalysisActive) {
                Toast.makeText(safeContext(), "Recording is locked during analysis", Toast.LENGTH_SHORT).show()
                return@setSingleClick
            }

            if (isRecording) {
                stopRecording()
                stopTimer()
            } else {
                startRecording()
                startTimer()
            }
        }

        ivBack.setSingleClick {
            if (isRecording) stopRecording()
            if (isAnalysisActive) stopAnalysis(saveSession = true, showToast = false)
            onBack()
        }
    }

    private fun applyOpticalFlowModeUi(useFarneback: Boolean) {
        binding.kltSensitivityBar.isEnabled = !useFarneback
        binding.kltSensitivityBar.alpha = if (useFarneback) 0.5f else 1.0f
        binding.farnebackSensitivityBar.isEnabled = useFarneback
        binding.farnebackSensitivityBar.alpha = if (useFarneback) 1.0f else 0.5f
    }

    private fun applyCurrentSensitivity() {
        val kltSensitivity = binding.kltSensitivityBar.progress
        val farnebackSensitivity = binding.farnebackSensitivityBar.progress
        if (::kltLabFlow.isInitialized) kltLabFlow.setSensitivity(kltSensitivity)
        if (::farnebackLabFlow.isInitialized) farnebackLabFlow.setSensitivity(farnebackSensitivity)
        opticalFlow.setSensitivity(if (binding.ofType.isChecked) farnebackSensitivity else kltSensitivity)
    }

    private fun applyMovingMode(isMoving: Boolean, manualOverride: Boolean) {
        isMovingMode = isMoving
        isMovingModeManualOverride = manualOverride
        opticalFlow.setMovingMode(isMoving)
        if (::kltLabFlow.isInitialized) kltLabFlow.setMovingMode(isMoving)
        if (::farnebackLabFlow.isInitialized) farnebackLabFlow.setMovingMode(isMoving)

        ignoreMovingSwitchChanges = true
        binding.movingStatus.isChecked = isMoving
        ignoreMovingSwitchChanges = false
        binding.movingType.text = if (isMoving) "Moving" else "Stand Still"
    }

    private fun updateAutoMovingModeFromCurrentSource() {
        if (isMovingModeManualOverride) return

        if (useIndoorPhoneMotionDetection) {
            updateMovingModeFromPhoneMotion()
        } else {
            val isMovingFromLocation = (mainViewModel.currentLocation.value?.speed ?: 0f) > 0f
            applyMovingMode(isMoving = isMovingFromLocation, manualOverride = false)
        }
    }

    override fun initObserver() {
        mainViewModel.currentLocation.observe(viewLifecycleOwner) { location ->
            if (isMovingModeManualOverride || useIndoorPhoneMotionDetection) return@observe

            val isMovingFromLocation = (location?.speed ?: 0f) > 0f
            applyMovingMode(isMoving = isMovingFromLocation, manualOverride = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            startCamera()
        }
        if (::imuEstimator.isInitialized) {
            imuEstimator.register()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isAnalysisActive) {
            stopAnalysis(saveSession = true, showToast = false)
        }
        if (isRecording) {
            stopRecording()
        }
        stopCamera()
        if (::imuEstimator.isInitialized) {
            imuEstimator.unregister()
        }
    }

    override fun onDestroyView() {
        if (isAnalysisActive) {
            stopAnalysis(saveSession = true, showToast = false)
        }
        if (isRecording) {
            stopRecording()
        }
        stopCamera()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroyView()
    }

    private fun startRecording() {
        val frameWidth = currentFrameWidth
        val frameHeight = currentFrameHeight
        if (frameWidth <= 0 || frameHeight <= 0) {
            Log.e("CAMERA-RECORD", "No current frame to start recording")
            return
        }
        val cacheDir = safeContext().cacheDir
        val videosDir = File(cacheDir, "videos")
        if (!videosDir.exists()) videosDir.mkdirs()
        
        val outputFile = File(videosDir, "recorded_${System.currentTimeMillis()}.mp4")
        recordedFilePath = outputFile.absolutePath
        Log.d("CAMERA-RECORD", "Target path: $recordedFilePath")
        
        videoEncoder = VideoEncoder(recordedFilePath, frameWidth, frameHeight)
        
        try {
            videoEncoder?.start()
            isRecording = true
            binding.ivVideRecord.setImageResource(R.drawable.ic_stop_record_purple)
            Toast.makeText(safeContext(), "Recording started", Toast.LENGTH_SHORT).show()
            Log.d("CAMERA-RECORD", "Encoder started")
        } catch (e: Exception) {
            Log.e("CAMERA-RECORD", "Failed to start encoder: ${e.message}")
            Toast.makeText(safeContext(), "Failed to start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        isRecording = false
        val file = File(recordedFilePath)
        
        videoEncoder?.release()
        videoEncoder = null
        
        val size = if (file.exists()) file.length() else 0
        Log.d("CAMERA-RECORD", "Stopping. Final size: $size bytes")

        binding.ivVideRecord.setImageResource(R.drawable.ic_start_record_purple)
        Toast.makeText(safeContext(), "Recording saved", Toast.LENGTH_SHORT).show()
        
        if (recordedFilePath.isNotEmpty() && size > 100) {
            // Scan file to ensure it's ready for general use
            MediaScannerConnection.scanFile(safeContext(), arrayOf(recordedFilePath), null) { _, _ -> }
            
            MediaStorageUtil.addVideo(safeContext(), recordedFilePath)
            mainViewModel.videoLibraryUpdated.value = System.currentTimeMillis()
            
            // Brief delay to ensure file lock is released
            binding.root.postDelayed({
                mainViewModel.selectedVideoPath.value = recordedFilePath
                navigateTo(R.id.videoOpticalFlowFragment)
            }, 500)
        } else {
            Log.e("CAMERA-RECORD", "Record resulted in empty or invalid file.")
        }
    }

    private fun startTimer() {
        if (timerJob != null) return

        timerStartTime = SystemClock.elapsedRealtime()

        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsedMillis = elapsedBeforePause + (SystemClock.elapsedRealtime() - timerStartTime)
                binding.tvTimer.text = formatElapsedTime(elapsedMillis)
                delay(1000L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null

        elapsedBeforePause += SystemClock.elapsedRealtime() - timerStartTime
        binding.tvTimer.text = formatElapsedTime(elapsedBeforePause)
    }
    private fun formatElapsedTime(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun startAnalysis() {
        synchronized(analysisLock) {
            analysisSamples.clear()
            analysisFrameIndex = 0L
            lastAnalysisSampleElapsedMs = -ANALYSIS_SAMPLE_INTERVAL_MS
        }
        analysisStartedAtWallMs = System.currentTimeMillis()
        analysisStartedAtElapsedMs = SystemClock.elapsedRealtime()
        restoreKltSensitivity = binding.kltSensitivityBar.progress
        restoreFarnebackSensitivity = binding.farnebackSensitivityBar.progress
        applyAnalysisSensitivityLock(locked = true)

        kltLabFlow = KLT().apply {
            setSensitivity(binding.kltSensitivityBar.progress)
            setMovingMode(isMovingMode)
        }
        farnebackLabFlow = Farneback().apply {
            setSensitivity(binding.farnebackSensitivityBar.progress)
            setMovingMode(isMovingMode)
        }

        isAnalysisActive = true
        binding.updateFeaturesButton.text = "Stop Analysis"
        binding.updateFeaturesButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        binding.ofAlgorithm.text = "Lab"
        binding.tvTitle.text = "KLT vs Farneback"
        binding.ofType.isEnabled = false
        setRecordLocked(locked = true)
        if (!isRecording) {
            resetTimer()
            startTimer()
        }
        Toast.makeText(safeContext(), "Analysis started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAnalysis(saveSession: Boolean, showToast: Boolean) {
        if (!isAnalysisActive) return

        val endedAtMs = System.currentTimeMillis()
        val durationMs = (SystemClock.elapsedRealtime() - analysisStartedAtElapsedMs).coerceAtLeast(0L)
        val samples = synchronized(analysisLock) { analysisSamples.toList() }
        isAnalysisActive = false

        binding.updateFeaturesButton.text = "Start Analysis"
        binding.updateFeaturesButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        binding.ofAlgorithm.text = if (binding.ofType.isChecked) "Farneback" else "KLT"
        binding.tvTitle.text = "Optical Flow"
        binding.ofType.isEnabled = true
        setRecordLocked(locked = false)
        applyAnalysisSensitivityLock(locked = false)
        if (!isRecording) {
            stopTimer()
        }

        if (!saveSession || samples.isEmpty() || !isAdded) {
            if (showToast && isAdded) Toast.makeText(safeContext(), "No analysis samples saved", Toast.LENGTH_SHORT).show()
            return
        }

        val session = AnalyticsSession(
            id = AnalyticsStorageUtil.createSessionId(analysisStartedAtWallMs),
            startedAtMs = analysisStartedAtWallMs,
            endedAtMs = endedAtMs,
            durationMs = durationMs,
            kltSensitivity = ANALYSIS_SENSITIVITY,
            farnebackSensitivity = ANALYSIS_SENSITIVITY,
            movingMode = isMovingMode,
            samples = samples
        )
        val file = AnalyticsStorageUtil.saveSession(safeContext(), session)
        MediaScannerConnection.scanFile(safeContext(), arrayOf(file.absolutePath), null) { _, _ -> }
        mainViewModel.analyticsLibraryUpdated.value = System.currentTimeMillis()

        if (showToast) {
            Toast.makeText(safeContext(), "Saved ${samples.size} analysis samples", Toast.LENGTH_SHORT).show()
        }
    }

    private fun imageProxyToRgbaMat(imageProxy: ImageProxy): Mat {
        val plane = imageProxy.planes.first()
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val bytesPerPixel = 4
        val rowSize = width * bytesPerPixel
        val rgbaBytes = ByteArray(rowSize * height)

        if (plane.rowStride == rowSize && plane.pixelStride == bytesPerPixel) {
            buffer.rewind()
            buffer.get(rgbaBytes, 0, rgbaBytes.size.coerceAtMost(buffer.remaining()))
        } else {
            val rowBuffer = ByteArray(plane.rowStride)
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                val bytesToRead = minOf(plane.rowStride, buffer.remaining())
                buffer.get(rowBuffer, 0, bytesToRead)
                System.arraycopy(rowBuffer, 0, rgbaBytes, row * rowSize, minOf(rowSize, bytesToRead))
            }
        }

        return Mat(height, width, CvType.CV_8UC4).apply {
            put(0, 0, rgbaBytes)
        }
    }

    private fun rotateFrameForDisplay(frame: Mat, rotationDegrees: Int): Mat {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return frame

        val rotatedFrame = Mat()
        when (normalizedRotation) {
            90 -> Core.rotate(frame, rotatedFrame, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(frame, rotatedFrame, Core.ROTATE_180)
            270 -> Core.rotate(frame, rotatedFrame, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> frame.copyTo(rotatedFrame)
        }
        return rotatedFrame
    }

    private fun analyzeCameraFrame(imageProxy: ImageProxy) {
        var rgbaFrame: Mat? = null
        var frameForProcessing: Mat? = null
        try {
            rgbaFrame = imageProxyToRgbaMat(imageProxy)
            val processingFrame = rotateFrameForDisplay(rgbaFrame, imageProxy.imageInfo.rotationDegrees)
            frameForProcessing = processingFrame
            processCameraFrame(processingFrame)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze CameraX frame: ${e.message}", e)
        } finally {
            if (frameForProcessing !== rgbaFrame) {
                frameForProcessing?.release()
            }
            rgbaFrame?.release()
            imageProxy.close()
        }
    }

    private fun processCameraFrame(frame: Mat) {
        // automatic feature update periodically
        frameCount++
        if (frameCount % updateInterval == 0) {
            if (isAnalysisActive) {
                if (::kltLabFlow.isInitialized) kltLabFlow.updateFeatures()
            } else if (::opticalFlow.isInitialized) {
                opticalFlow.updateFeatures()
            }
        }
        // get IMU variables
        val velocity = imuEstimator.getVelocity()
        if (!isMovingModeManualOverride && useIndoorPhoneMotionDetection) {
            updateMovingModeFromPhoneMotion()
        }

        // Convert the velocity to mph
        val xVelocity = velocity[0]
        val yVelocity = velocity[1]
        val zVelocity = velocity[2]

        // Get the magnitude of the velocity vector
        val motionMagnitude = sqrt((xVelocity * xVelocity + yVelocity * yVelocity + zVelocity * zVelocity).toDouble()).toFloat()

        activity?.runOnUiThread {
            binding.velPred.text = formatThreeDigitValue(motionMagnitude)
        }

        if (isAnalysisActive) {
            processAnalysisFrame(frame)
            return
        }

        // get OF output
        val currentOutput = opticalFlow.run(frame)
        output = currentOutput

        if (currentOutput != null && currentOutput.ofFrame != null) {
            val pos = currentOutput.position
            if (pos != null) {
                val mv = mvViewer.getMotionVector(pos)
                mvMat = mv

                // draw Motion Vector
                val dst = getOrCreateMotionVectorBitmap(mv)
                Utils.matToBitmap(mv, dst)
                activity?.runOnUiThread {
                    binding.motionVector.setImageBitmap(dst)
                }
            }
            val outFrame = currentOutput.ofFrame ?: frame
            currentFrameWidth = outFrame.cols()
            currentFrameHeight = outFrame.rows()
            renderOpticalFlowFrame(outFrame)
            writeToVideoWriter(outFrame)
            return
        }
        currentFrameWidth = frame.cols()
        currentFrameHeight = frame.rows()
        renderOpticalFlowFrame(frame)
        writeToVideoWriter(frame)
    }

    private fun processAnalysisFrame(frame: Mat) {
        val kltFrame = frame.clone()
        val farnebackFrame = frame.clone()
        var compositeFrame: Mat? = null

        try {
            val kltOutput = kltLabFlow.run(kltFrame)
            val farnebackOutput = farnebackLabFlow.run(farnebackFrame)
            val kltMetrics = kltOutput.metrics
            val farnebackMetrics = farnebackOutput.metrics

            (kltOutput.position ?: farnebackOutput.position)?.let { renderMotionVector(it) }
            recordAnalysisSample(kltMetrics, farnebackMetrics)

            val leftFrame = kltOutput.ofFrame ?: kltFrame
            val rightFrame = farnebackOutput.ofFrame ?: farnebackFrame
            compositeFrame = composeAnalysisFrame(leftFrame, rightFrame, kltMetrics, farnebackMetrics)

            currentFrameWidth = compositeFrame.cols()
            currentFrameHeight = compositeFrame.rows()
            renderOpticalFlowFrame(compositeFrame)
            writeToVideoWriter(compositeFrame)
        } finally {
            compositeFrame?.release()
            kltFrame.release()
            farnebackFrame.release()
        }
    }

    private fun composeAnalysisFrame(
        kltFrame: Mat,
        farnebackFrame: Mat,
        kltMetrics: OpticalFlowMetrics?,
        farnebackMetrics: OpticalFlowMetrics?
    ): Mat {
        val rows = kltFrame.rows().coerceAtLeast(1)
        val cols = kltFrame.cols().coerceAtLeast(2)
        val halfWidth = (cols / 2).coerceAtLeast(1)
        val rightWidth = (cols - halfWidth).coerceAtLeast(1)
        val output = Mat.zeros(rows, cols, kltFrame.type())
        val leftScaled = Mat()
        val rightScaled = Mat()
        var leftRoi: Mat? = null
        var rightRoi: Mat? = null

        try {
            Imgproc.resize(kltFrame, leftScaled, Size(halfWidth.toDouble(), rows.toDouble()))
            Imgproc.resize(farnebackFrame, rightScaled, Size(rightWidth.toDouble(), rows.toDouble()))
            leftRoi = output.submat(Rect(0, 0, halfWidth, rows))
            rightRoi = output.submat(Rect(halfWidth, 0, rightWidth, rows))
            leftScaled.copyTo(leftRoi)
            rightScaled.copyTo(rightRoi)
        } finally {
            leftRoi?.release()
            rightRoi?.release()
            leftScaled.release()
            rightScaled.release()
        }

        Imgproc.line(
            output,
            Point(halfWidth.toDouble(), 0.0),
            Point(halfWidth.toDouble(), rows.toDouble()),
            Scalar(255.0, 255.0, 255.0, 255.0),
            2
        )
        drawAnalysisOverlay(
            frame = output,
            x = 12,
            title = "KLT",
            color = Scalar(240.0, 230.0, 140.0, 255.0),
            metrics = kltMetrics
        )
        drawAnalysisOverlay(
            frame = output,
            x = halfWidth + 12,
            title = "Farneback",
            color = Scalar(0.0, 255.0, 102.0, 255.0),
            metrics = farnebackMetrics
        )
        return output
    }

    private fun drawAnalysisOverlay(
        frame: Mat,
        x: Int,
        title: String,
        color: Scalar,
        metrics: OpticalFlowMetrics?
    ) {
        val font = Imgproc.FONT_HERSHEY_SIMPLEX
        val titleScale = 0.72
        val statScale = 0.44
        val titleY = 30.0
        val statY = 55.0
        val statY2 = 76.0
        val statY3 = 97.0
        val boxWidth = ((frame.cols() / 2) - 18).coerceAtLeast(120)

        Imgproc.rectangle(
            frame,
            Point((x - 8).toDouble(), 8.0),
            Point((x - 8 + boxWidth).toDouble(), 108.0),
            Scalar(0.0, 0.0, 0.0, 170.0),
            -1
        )
        Imgproc.putText(frame, title, Point(x.toDouble(), titleY), font, titleScale, color, 2, Imgproc.LINE_AA)

        val fps = metrics?.instantFps ?: 0.0
        val count = metrics?.featureCount ?: 0
        val active = metrics?.activeVectorCount ?: 0
        val confidence = metrics?.confidence ?: 0.0
        val magnitude = metrics?.avgMagnitude ?: 0.0
        val threshold = metrics?.threshold ?: 0.0
        Imgproc.putText(
            frame,
            "FPS ${formatMetric(fps, 1)}  Pts $count",
            Point(x.toDouble(), statY),
            font,
            statScale,
            Scalar(245.0, 245.0, 245.0, 255.0),
            1,
            Imgproc.LINE_AA
        )
        Imgproc.putText(
            frame,
            "Active $active  Conf ${formatMetric(confidence, 0)}%",
            Point(x.toDouble(), statY2),
            font,
            statScale,
            Scalar(245.0, 245.0, 245.0, 255.0),
            1,
            Imgproc.LINE_AA
        )
        Imgproc.putText(
            frame,
            "Avg ${formatMetric(magnitude, 2)}  Thr ${formatMetric(threshold, 2)}",
            Point(x.toDouble(), statY3),
            font,
            statScale,
            Scalar(245.0, 245.0, 245.0, 255.0),
            1,
            Imgproc.LINE_AA
        )
    }

    private fun recordAnalysisSample(
        kltMetrics: OpticalFlowMetrics?,
        farnebackMetrics: OpticalFlowMetrics?
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - analysisStartedAtElapsedMs
        synchronized(analysisLock) {
            if (elapsedMs - lastAnalysisSampleElapsedMs < ANALYSIS_SAMPLE_INTERVAL_MS && analysisSamples.isNotEmpty()) {
                return
            }
            lastAnalysisSampleElapsedMs = elapsedMs
            analysisSamples.add(
                AnalyticsSample(
                    elapsedMs = elapsedMs,
                    frameIndex = analysisFrameIndex++,
                    kltFps = kltMetrics?.instantFps ?: 0.0,
                    farnebackFps = farnebackMetrics?.instantFps ?: 0.0,
                    kltProcessMs = kltMetrics?.processTimeMs ?: 0.0,
                    farnebackProcessMs = farnebackMetrics?.processTimeMs ?: 0.0,
                    kltFeatureCount = kltMetrics?.featureCount ?: 0,
                    farnebackSampleCount = farnebackMetrics?.featureCount ?: 0,
                    kltActiveVectorCount = kltMetrics?.activeVectorCount ?: 0,
                    farnebackActiveVectorCount = farnebackMetrics?.activeVectorCount ?: 0,
                    kltAvgDx = kltMetrics?.avgDx ?: 0.0,
                    kltAvgDy = kltMetrics?.avgDy ?: 0.0,
                    farnebackAvgDx = farnebackMetrics?.avgDx ?: 0.0,
                    farnebackAvgDy = farnebackMetrics?.avgDy ?: 0.0,
                    kltAvgMagnitude = kltMetrics?.avgMagnitude ?: 0.0,
                    farnebackAvgMagnitude = farnebackMetrics?.avgMagnitude ?: 0.0,
                    kltConfidence = kltMetrics?.confidence ?: 0.0,
                    farnebackConfidence = farnebackMetrics?.confidence ?: 0.0,
                    kltThreshold = kltMetrics?.threshold ?: 0.0,
                    farnebackThreshold = farnebackMetrics?.threshold ?: 0.0
                )
            )
        }
    }

    private fun applyAnalysisSensitivityLock(locked: Boolean) {
        if (locked) {
            binding.kltSensitivityBar.progress = ANALYSIS_SENSITIVITY
            binding.farnebackSensitivityBar.progress = ANALYSIS_SENSITIVITY
            binding.kltSensitivityBar.isEnabled = false
            binding.farnebackSensitivityBar.isEnabled = false
            binding.kltSensitivityBar.alpha = 0.45f
            binding.farnebackSensitivityBar.alpha = 0.45f
            kltLabFlow.setSensitivity(ANALYSIS_SENSITIVITY)
            farnebackLabFlow.setSensitivity(ANALYSIS_SENSITIVITY)
            opticalFlow.setSensitivity(ANALYSIS_SENSITIVITY)
        } else {
            binding.kltSensitivityBar.progress = restoreKltSensitivity
            binding.farnebackSensitivityBar.progress = restoreFarnebackSensitivity
            applyOpticalFlowModeUi(useFarneback = binding.ofType.isChecked)
            applyCurrentSensitivity()
        }
    }

    private fun resetTimer() {
        timerJob?.cancel()
        timerJob = null
        timerStartTime = 0L
        elapsedBeforePause = 0L
        binding.tvTimer.text = formatElapsedTime(0L)
    }

    private fun setRecordLocked(locked: Boolean) {
        binding.ivVideRecord.isEnabled = !locked
        binding.ivVideRecord.alpha = if (locked) 0.35f else 1f
        binding.recordPanel.alpha = if (locked) 0.55f else 1f
    }

    private fun renderMotionVector(pos: Point) {
        val mv = mvViewer.getMotionVector(pos)
        mvMat = mv
        val dst = getOrCreateMotionVectorBitmap(mv)
        Utils.matToBitmap(mv, dst)
        activity?.runOnUiThread {
            binding.motionVector.setImageBitmap(dst)
        }
    }

    private fun renderOpticalFlowFrame(frame: Mat) {
        if (frame.empty()) return

        makeFrameOpaqueForOverlay(frame)
        val bitmap = getOrCreateCameraFrameBitmap(frame)
        Utils.matToBitmap(frame, bitmap)

        activity?.runOnUiThread {
            binding.cameraOutputOverlay.setImageBitmap(bitmap)
            binding.cameraOutputOverlay.invalidate()
        }
    }

    private fun writeToVideoWriter(matFrame: Mat) {
        if (isRecording && videoEncoder != null) {
            // Log mean occasionally
            if (System.currentTimeMillis() % 2000 < 100) {
                val mean = org.opencv.core.Core.mean(matFrame)
                Log.d("CAMERA-RECORD", "Recording frame mean: $mean")
            }

            videoEncoder?.encodeFrame(matFrame)
        }
    }

    private fun getOrCreateMotionVectorBitmap(mat: Mat): Bitmap {
        val currentBitmap = motionVectorBitmap
        if (currentBitmap != null && currentBitmap.width == mat.cols() && currentBitmap.height == mat.rows()) {
            return currentBitmap
        }

        return createBitmap(mat.cols(), mat.rows()).also {
            motionVectorBitmap = it
        }
    }

    private fun getOrCreateCameraFrameBitmap(mat: Mat): Bitmap {
        val currentBitmap = cameraFrameBitmap
        if (currentBitmap != null && currentBitmap.width == mat.cols() && currentBitmap.height == mat.rows()) {
            return currentBitmap
        }

        return createBitmap(mat.cols(), mat.rows()).also {
            cameraFrameBitmap = it
        }
    }

    private fun makeFrameOpaqueForOverlay(frame: Mat) {
        if (frame.channels() < 4) return

        val channels = mutableListOf<Mat>()
        Core.split(frame, channels)
        if (channels.size >= 4) {
            channels[3].setTo(Scalar(255.0))
            Core.merge(channels, frame)
        }
        channels.forEach { it.release() }
    }

    private fun updateMovingModeFromPhoneMotion() {
        if (isMovingModeManualOverride) return

        val acceleration = imuEstimator.getLinearAcceleration()
        val ax = acceleration.getOrElse(0) { 0f }.toDouble()
        val ay = acceleration.getOrElse(1) { 0f }.toDouble()
        val az = acceleration.getOrElse(2) { 0f }.toDouble()
        val accelerationMagnitude = sqrt((ax * ax) + (ay * ay) + (az * az))

        if (accelerationMagnitude > phoneMovingAccelerationThreshold) {
            phoneMovingHoldFrames = phoneMovingHoldFrameCount
        } else if (phoneMovingHoldFrames > 0) {
            phoneMovingHoldFrames--
        }

        val detectedMoving = phoneMovingHoldFrames > 0
        if (detectedMoving != isMovingMode) {
            activity?.runOnUiThread {
                if (!isMovingModeManualOverride) {
                    applyMovingMode(isMoving = detectedMoving, manualOverride = false)
                }
            }
        }
    }

    private fun formatThreeDigitValue(value: Float): String {
        val safeValue = if (value.isFinite()) value.coerceIn(0f, 999f) else 0f
        return when {
            safeValue < 10f -> String.format(Locale.US, "%.2f", safeValue)
            safeValue < 100f -> String.format(Locale.US, "%.1f", safeValue)
            else -> String.format(Locale.US, "%.0f", safeValue)
        }
    }

    private fun formatMetric(value: Double, decimals: Int): String {
        val safeValue = if (value.isFinite()) value else 0.0
        return when (decimals) {
            0 -> String.format(Locale.US, "%.0f", safeValue)
            1 -> String.format(Locale.US, "%.1f", safeValue)
            else -> String.format(Locale.US, "%.2f", safeValue)
        }
    }

    companion object {
        private const val ANALYSIS_SAMPLE_INTERVAL_MS = 250L
        private const val ANALYSIS_SENSITIVITY = 100
    }
}
