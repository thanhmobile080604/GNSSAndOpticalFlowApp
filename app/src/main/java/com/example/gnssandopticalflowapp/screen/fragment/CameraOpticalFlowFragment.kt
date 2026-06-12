package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.content.pm.PackageManager
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentCameraOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import com.example.gnssandopticalflowapp.function.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.function.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.function.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.function.optical_flow.classes.MotionVectorViz
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.screen.viewmodel.CameraOpticalFlowViewModel.MotionControlMode
import com.example.gnssandopticalflowapp.screen.viewmodel.CameraOpticalFlowViewModel
import com.example.gnssandopticalflowapp.util.AnalyticsStorageUtil
import com.example.gnssandopticalflowapp.util.VideoEncoder
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import com.example.gnssandopticalflowapp.view.RoiOverlayView
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
import org.opencv.core.Rect2d
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.tracking.legacy_Tracker
import org.opencv.tracking.legacy_TrackerCSRT
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import com.example.gnssandopticalflowapp.common.show
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencv.imgproc.Imgproc
import kotlin.time.Duration.Companion.milliseconds

class CameraOpticalFlowFragment :
    BaseFragment<FragmentCameraOpticalFlowBinding>(FragmentCameraOpticalFlowBinding::inflate) {

    private var mvMat: Mat? = null
    private var output: OFOutput? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraViewModel: CameraOpticalFlowViewModel by viewModels()
    private var currentFrameWidth: Int
        get() = cameraViewModel.currentFrameWidth
        set(value) {
            cameraViewModel.currentFrameWidth = value
        }
    private var currentFrameHeight: Int
        get() = cameraViewModel.currentFrameHeight
        set(value) {
            cameraViewModel.currentFrameHeight = value
        }
    private lateinit var opticalFlow: OpticalFlow
    private lateinit var kltLabFlow: KLT
    private lateinit var farnebackLabFlow: Farneback
    private lateinit var imuEstimator: IMUEstimator
    private lateinit var mvViewer: MotionVectorViz
    private var objectTracker: legacy_Tracker? = null
    private var lastTrackedFrameRect: Rect? = null
    private var objectTemplateGray: Mat? = null
    private var trackerFrameWidth = 0
    private var trackerFrameHeight = 0
    private var trackerInitializedFromSelection = false
    private var lostTrackingFrameCount = 0
    private var reacquireFrameCounter = 0
    @Volatile
    private var roiSelectionVersion = 0
    private var frameCount: Int
        get() = cameraViewModel.frameCount
        set(value) {
            cameraViewModel.frameCount = value
        }
    private val updateInterval: Int = 30 // frames between automatic feature updates

    private var videoEncoder: VideoEncoder? = null
    private var isRecording: Boolean
        get() = cameraViewModel.isRecording
        set(value) {
            cameraViewModel.isRecording = value
        }
    private var recordedFilePath: String
        get() = cameraViewModel.recordedFilePath
        set(value) {
            cameraViewModel.recordedFilePath = value
        }

    private var timerJob: Job? = null
    private var cameraFrameBitmap: Bitmap? = null
    private var motionVectorBitmap: Bitmap? = null
    private var isMovingMode: Boolean
        get() = cameraViewModel.isMovingMode
        set(value) {
            cameraViewModel.isMovingMode = value
        }
    private var isMovingModeManualOverride: Boolean
        get() = cameraViewModel.isMovingModeManualOverride
        set(value) {
            cameraViewModel.isMovingModeManualOverride = value
        }
    private val isAnalysisActive: Boolean
        get() = cameraViewModel.isAnalysisActive
    private var restoreKltSensitivity: Int
        get() = cameraViewModel.restoreKltSensitivity
        set(value) {
            cameraViewModel.restoreKltSensitivity = value
        }
    private var restoreFarnebackSensitivity: Int
        get() = cameraViewModel.restoreFarnebackSensitivity
        set(value) {
            cameraViewModel.restoreFarnebackSensitivity = value
        }
    // Auto detection source only. Motion buttons control manual/auto at runtime.
    // true: phone IMU motion, false: GNSS location speed.
    private val useIndoorPhoneMotionDetection = true

    override fun FragmentCameraOpticalFlowBinding.initView() {
        initVars()
        kltSensitivityBar.progress = 50
        farnebackSensitivityBar.progress = 50
        cameraViewModel.useFarneback = false
        cameraViewModel.useFarnebackHeatmap = false
        cameraViewModel.motionControlMode = MotionControlMode.AUTO
        cameraViewModel.roiEnabled = false
        cameraViewModel.normalizedRoi = null
        applyOpticalFlowModeUi(useFarneback = false)
        applyCurrentSensitivity()
        applyMovingMode(isMoving = false, manualOverride = false)
        updateAlgorithmModeUi()
        updateFarnebackDisplayUi()
        updateMotionControlUi()
        updateRoiUi()

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraView.scaleType = PreviewView.ScaleType.FILL_CENTER
        roiOverlay.selectionShape = RoiOverlayView.SelectionShape.RECTANGLE
        roiOverlay.onRoiChanged = {
            cameraViewModel.normalizedRoi = roiOverlay.normalizedRoi?.let { roi ->
                CameraOpticalFlowViewModel.NormalizedRoi(
                    left = roi.left,
                    top = roi.top,
                    right = roi.right,
                    bottom = roi.bottom,
                    viewAspectRatio = if (roiOverlay.height > 0) {
                        roiOverlay.width.toFloat() / roiOverlay.height.toFloat()
                    } else {
                        1f
                    },
                    pathPoints = roiOverlay.normalizedPath.map { point ->
                        CameraOpticalFlowViewModel.NormalizedPoint(point.x, point.y)
                    }
                )
            }
            cameraViewModel.roiEnabled = roiOverlay.selectionEnabled || cameraViewModel.normalizedRoi != null
            invalidateRoiTrackingState()
            updateRoiUi()
        }
        roiOverlay.onInvalidSelection = {
            Toast.makeText(safeContext(), "Drag a larger rectangle", Toast.LENGTH_SHORT).show()
        }

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

        btnAlgorithmKlt.setSingleClick { setAlgorithmMode(useFarneback = false) }
        btnAlgorithmFarneback.setSingleClick { setAlgorithmMode(useFarneback = true) }

        btnFarnebackVectors.setSingleClick { setFarnebackDisplayMode(heatmap = false) }
        btnFarnebackHeatmap.setSingleClick { setFarnebackDisplayMode(heatmap = true) }

        btnMotionAuto.setSingleClick {
            cameraViewModel.motionControlMode = MotionControlMode.AUTO
            cameraViewModel.resetPhoneMotionHold()
            isMovingModeManualOverride = false
            updateAutoMovingModeFromCurrentSource()
            updateMotionControlUi()
        }
        btnMotionStill.setSingleClick {
            cameraViewModel.motionControlMode = MotionControlMode.STILL
            applyMovingMode(isMoving = false, manualOverride = true)
        }
        btnMotionMoving.setSingleClick {
            cameraViewModel.motionControlMode = MotionControlMode.MOVING
            applyMovingMode(isMoving = true, manualOverride = true)
        }

        btnRoiSelect.setSingleClick {
            cameraViewModel.roiEnabled = true
            cameraViewModel.normalizedRoi = null
            invalidateRoiTrackingState()
            roiOverlay.setSelectionEnabled(true)
            roiOverlay.clearSelection()
            updateRoiUi()
        }
        btnRoiFull.setSingleClick {
            cameraViewModel.roiEnabled = false
            cameraViewModel.normalizedRoi = null
            invalidateRoiTrackingState()
            roiOverlay.setSelectionEnabled(false)
            roiOverlay.clearSelection()
            updateRoiUi()
        }

        kltSensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("SEEK", progress.toString())
                if (::opticalFlow.isInitialized && !cameraViewModel.useFarneback) {
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
                if (::opticalFlow.isInitialized && cameraViewModel.useFarneback) {
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

    private fun setAlgorithmMode(useFarneback: Boolean) {
        if (isAnalysisActive) {
            Toast.makeText(safeContext(), "Stop analysis before changing mode", Toast.LENGTH_SHORT).show()
            return
        }

        if (cameraViewModel.useFarneback == useFarneback && ::opticalFlow.isInitialized) return
        cameraViewModel.useFarneback = useFarneback
        resetActiveOpticalFlow()
        applyOpticalFlowModeUi(useFarneback)
    }

    private fun setFarnebackDisplayMode(heatmap: Boolean) {
        if (isAnalysisActive) {
            Toast.makeText(safeContext(), "Stop analysis before changing display", Toast.LENGTH_SHORT).show()
            return
        }
        cameraViewModel.useFarnebackHeatmap = heatmap
        (opticalFlow as? Farneback)?.setVisualizationMode(currentFarnebackVisualizationMode())
        updateFarnebackDisplayUi()
    }

    private fun resetActiveOpticalFlow() {
        opticalFlow = createSelectedOpticalFlow()
        mvViewer.resetMotionVector()
        motionVectorBitmap = null
        binding.motionVector.setImageBitmap(null)
    }

    private fun resetObjectTracker(clearTemplate: Boolean = false) {
        objectTracker = null
        lastTrackedFrameRect = null
        trackerFrameWidth = 0
        trackerFrameHeight = 0
        lostTrackingFrameCount = 0
        reacquireFrameCounter = 0
        if (clearTemplate) {
            objectTemplateGray?.release()
            objectTemplateGray = null
            trackerInitializedFromSelection = false
        }
    }

    private fun invalidateRoiTrackingState() {
        roiSelectionVersion++
        resetObjectTracker(clearTemplate = true)
    }

    private fun createSelectedOpticalFlow(): OpticalFlow {
        val flow: OpticalFlow = if (cameraViewModel.useFarneback) Farneback() else KLT()
        flow.setMovingMode(isMovingMode)
        flow.setSensitivity(
            if (cameraViewModel.useFarneback) {
                binding.farnebackSensitivityBar.progress
            } else {
                binding.kltSensitivityBar.progress
            }
        )
        (flow as? Farneback)?.setVisualizationMode(currentFarnebackVisualizationMode())
        return flow
    }

    private fun currentFarnebackVisualizationMode(): Farneback.VisualizationMode {
        return if (cameraViewModel.useFarnebackHeatmap) {
            Farneback.VisualizationMode.HEATMAP
        } else {
            Farneback.VisualizationMode.VECTORS
        }
    }

    private fun updateAlgorithmModeUi() = with(binding) {
        setSegmentSelected(btnAlgorithmKlt, !cameraViewModel.useFarneback)
        setSegmentSelected(btnAlgorithmFarneback, cameraViewModel.useFarneback)
        ofAlgorithm.text = if (cameraViewModel.useFarneback) "Farneback" else "KLT"
    }

    private fun updateFarnebackDisplayUi() = with(binding) {
        setSegmentSelected(btnFarnebackVectors, !cameraViewModel.useFarnebackHeatmap)
        setSegmentSelected(btnFarnebackHeatmap, cameraViewModel.useFarnebackHeatmap)
        btnFarnebackVectors.isEnabled = cameraViewModel.useFarneback && !isAnalysisActive
        btnFarnebackHeatmap.isEnabled = cameraViewModel.useFarneback && !isAnalysisActive
        val enabledAlpha = if (cameraViewModel.useFarneback && !isAnalysisActive) 1f else 0.45f
        btnFarnebackVectors.alpha = enabledAlpha
        btnFarnebackHeatmap.alpha = enabledAlpha
    }

    private fun updateMotionControlUi() = with(binding) {
        setSegmentSelected(btnMotionAuto, cameraViewModel.motionControlMode == MotionControlMode.AUTO)
        setSegmentSelected(btnMotionStill, cameraViewModel.motionControlMode == MotionControlMode.STILL)
        setSegmentSelected(btnMotionMoving, cameraViewModel.motionControlMode == MotionControlMode.MOVING)
    }

    private fun updateRoiUi() = with(binding) {
        val hasRoi = cameraViewModel.normalizedRoi != null
        setSegmentSelected(btnRoiSelect, cameraViewModel.roiEnabled || hasRoi)
        setSegmentSelected(btnRoiFull, !cameraViewModel.roiEnabled && !hasRoi)
        tvRoiLabel.text = if (hasRoi) "ROI On" else "ROI"
    }

    private fun setAlgorithmControlsEnabled(enabled: Boolean) = with(binding) {
        listOf(
            btnAlgorithmKlt,
            btnAlgorithmFarneback,
            btnFarnebackVectors,
            btnFarnebackHeatmap,
            btnRoiSelect,
            btnRoiFull
        ).forEach { control ->
            control.isEnabled = enabled
            control.alpha = if (enabled) 1f else 0.45f
        }
        updateFarnebackDisplayUi()
    }

    private fun setSegmentSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_gradient_update_button_12 else R.drawable.bg_glass_chip
        )
    }

    private fun applyOpticalFlowModeUi(useFarneback: Boolean) {
        binding.kltSensitivityBar.isEnabled = !useFarneback
        binding.kltSensitivityBar.alpha = if (useFarneback) 0.5f else 1.0f
        binding.farnebackSensitivityBar.isEnabled = useFarneback
        binding.farnebackSensitivityBar.alpha = if (useFarneback) 1.0f else 0.5f
        binding.farnebackViewCard.alpha = if (useFarneback) 1.0f else 0.45f
        binding.ofAlgorithm.text = if (useFarneback) "Farneback" else "KLT"
        updateAlgorithmModeUi()
        updateFarnebackDisplayUi()
    }

    private fun applyCurrentSensitivity() {
        val kltSensitivity = binding.kltSensitivityBar.progress
        val farnebackSensitivity = binding.farnebackSensitivityBar.progress
        if (::kltLabFlow.isInitialized) kltLabFlow.setSensitivity(kltSensitivity)
        if (::farnebackLabFlow.isInitialized) farnebackLabFlow.setSensitivity(farnebackSensitivity)
        opticalFlow.setSensitivity(if (cameraViewModel.useFarneback) farnebackSensitivity else kltSensitivity)
        (opticalFlow as? Farneback)?.setVisualizationMode(currentFarnebackVisualizationMode())
    }

    private fun applyMovingMode(isMoving: Boolean, manualOverride: Boolean) {
        cameraViewModel.setMovingMode(isMoving, manualOverride)
        opticalFlow.setMovingMode(isMoving)
        if (::kltLabFlow.isInitialized) kltLabFlow.setMovingMode(isMoving)
        if (::farnebackLabFlow.isInitialized) farnebackLabFlow.setMovingMode(isMoving)

        binding.movingType.text = if (isMoving) "Moving" else "Stand Still"
        updateMotionControlUi()
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
        resetObjectTracker(clearTemplate = true)
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

        cameraViewModel.startTimer()

        timerJob = lifecycleScope.launch {
            while (isActive) {
                binding.tvTimer.show()
                val elapsedMillis = cameraViewModel.currentTimerElapsed()
                binding.tvTimer.text = formatElapsedTime(elapsedMillis)
                delay(1000L.milliseconds)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null

        binding.tvTimer.text = formatElapsedTime(cameraViewModel.stopTimer())
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
        resetObjectTracker()
        cameraViewModel.startAnalysis(
            kltSensitivity = binding.kltSensitivityBar.progress,
            farnebackSensitivity = binding.farnebackSensitivityBar.progress
        )
        applyAnalysisSensitivityLock(locked = true)

        kltLabFlow = KLT().apply {
            setSensitivity(binding.kltSensitivityBar.progress)
            setMovingMode(isMovingMode)
        }
        farnebackLabFlow = Farneback().apply {
            setSensitivity(binding.farnebackSensitivityBar.progress)
            setMovingMode(isMovingMode)
        }

        binding.updateFeaturesButton.text = "Stop Analysis"
        binding.updateFeaturesButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        binding.ofAlgorithm.text = "Lab"
        binding.tvTitle.text = "KLT vs Farneback"
        setAlgorithmControlsEnabled(false)
        binding.roiOverlay.setSelectionEnabled(false)
        binding.roiOverlay.visibility = View.GONE
        setRecordLocked(locked = true)
        if (!isRecording) {
            resetTimer()
            startTimer()
        }
        Toast.makeText(safeContext(), "Analysis started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAnalysis(saveSession: Boolean, showToast: Boolean) {
        if (!isAnalysisActive) return

        val completedSession = cameraViewModel.finishAnalysis(saveSession)

        binding.updateFeaturesButton.text = "Start Analysis"
        binding.updateFeaturesButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        binding.ofAlgorithm.text = if (cameraViewModel.useFarneback) "Farneback" else "KLT"
        binding.tvTitle.text = "Optical Flow"
        setAlgorithmControlsEnabled(true)
        setRecordLocked(locked = false)
        applyAnalysisSensitivityLock(locked = false)
        binding.roiOverlay.setSelectionEnabled(cameraViewModel.roiEnabled)
        updateRoiUi()
        if (!isRecording) {
            stopTimer()
        }

        if (completedSession == null || !isAdded) {
            if (showToast && isAdded) Toast.makeText(safeContext(), "No analysis samples saved", Toast.LENGTH_SHORT).show()
            return
        }

        val session = AnalyticsSession(
            id = AnalyticsStorageUtil.createSessionId(completedSession.startedAtMs),
            startedAtMs = completedSession.startedAtMs,
            endedAtMs = completedSession.endedAtMs,
            durationMs = completedSession.durationMs,
            kltSensitivity = ANALYSIS_SENSITIVITY,
            farnebackSensitivity = ANALYSIS_SENSITIVITY,
            movingMode = completedSession.movingMode,
            samples = completedSession.samples
        )
        val file = AnalyticsStorageUtil.saveSession(safeContext(), session)
        MediaScannerConnection.scanFile(safeContext(), arrayOf(file.absolutePath), null) { _, _ -> }
        mainViewModel.analyticsLibraryUpdated.value = System.currentTimeMillis()

        if (showToast) {
            Toast.makeText(safeContext(), "Saved ${completedSession.samples.size} analysis samples", Toast.LENGTH_SHORT).show()
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

        val roiModeActive = cameraViewModel.roiEnabled
        val activeRoi = activeTrackedRoi(frame)
        val originalFrame = if (roiModeActive) frame.clone() else null
        try {
            val currentOutput = opticalFlow.run(frame)
            output = currentOutput

            if (currentOutput != null && currentOutput.ofFrame != null) {
                val outFrame = currentOutput.ofFrame ?: frame
                if (activeRoi != null) {
                    val roi = activeRoi
                    keepOverlayInsideTrackedRect(outFrame, originalFrame, roi.rect)
                } else if (roiModeActive) {
                    originalFrame?.copyTo(outFrame)
                }
                currentOutput.position?.let { pos ->
                    val mv = mvViewer.getMotionVector(pos)
                    mvMat = mv

                    val dst = getOrCreateMotionVectorBitmap(mv)
                    Utils.matToBitmap(mv, dst)
                    activity?.runOnUiThread {
                        binding.motionVector.setImageBitmap(dst)
                    }
                }
                currentFrameWidth = outFrame.cols()
                currentFrameHeight = outFrame.rows()
                renderOpticalFlowFrame(outFrame)
                writeToVideoWriter(outFrame)
                return
            }
        } finally {
            originalFrame?.release()
        }
        currentFrameWidth = frame.cols()
        currentFrameHeight = frame.rows()
        renderOpticalFlowFrame(frame)
        writeToVideoWriter(frame)
    }

    private fun activeTrackedRoi(frame: Mat): ActiveRoi? {
        val normalized = cameraViewModel.normalizedRoi ?: return null
        if (!cameraViewModel.roiEnabled) return null
        val selectionVersion = roiSelectionVersion

        val mapper = RoiFrameMapper(
            frameCols = frame.cols().coerceAtLeast(1),
            frameRows = frame.rows().coerceAtLeast(1),
            viewAspectRatio = normalized.viewAspectRatio
        )
        val frameSizeChanged = trackerFrameWidth != 0 &&
            (trackerFrameWidth != mapper.frameCols || trackerFrameHeight != mapper.frameRows)
        if (frameSizeChanged) {
            resetObjectTracker(clearTemplate = true)
        }

        val trackerInputFrame = createTrackerInputFrame(frame)
        val trackedRect = try {
            if (objectTracker == null) {
                if (trackerInitializedFromSelection && objectTemplateGray != null) {
                    reacquireObjectTracker(trackerInputFrame, mapper)
                } else {
                    initializeObjectTracker(trackerInputFrame, normalized, mapper)
                }
            } else {
                updateObjectTracker(trackerInputFrame, mapper)
            }?.let { rect ->
                sanitizeFrameRect(rect, mapper.frameCols, mapper.frameRows)
            }
        } finally {
            trackerInputFrame.release()
        }

        if (trackedRect == null) {
            if (trackerInitializedFromSelection) {
                clearTrackingOverlayRect(selectionVersion)
            }
            return null
        }

        lastTrackedFrameRect = copyRect(trackedRect)
        syncTrackingOverlay(trackedRect, mapper, selectionVersion)
        return ActiveRoi(rect = trackedRect)
    }

    private fun clearTrackingOverlayRect(selectionVersion: Int) {
        activity?.runOnUiThread {
            if (isAdded && selectionVersion == roiSelectionVersion && cameraViewModel.roiEnabled) {
                binding.roiOverlay.setNormalizedRoi(null, notify = false)
            }
        }
    }

    private fun keepOverlayInsideTrackedRect(frameWithOverlay: Mat, originalFrame: Mat?, rect: Rect) {
        if (originalFrame == null || frameWithOverlay.empty()) return

        val clippedRect = sanitizeFrameRect(
            rect = rect,
            frameCols = frameWithOverlay.cols(),
            frameRows = frameWithOverlay.rows()
        ) ?: return

        var processedRect: Mat? = null
        var processedRectCopy: Mat? = null
        var outputRect: Mat? = null
        try {
            processedRect = frameWithOverlay.submat(clippedRect)
            processedRectCopy = processedRect.clone()
            originalFrame.copyTo(frameWithOverlay)
            outputRect = frameWithOverlay.submat(clippedRect)
            processedRectCopy.copyTo(outputRect)
        } finally {
            processedRect?.release()
            processedRectCopy?.release()
            outputRect?.release()
        }
    }

    private fun createTrackerInputFrame(frame: Mat): Mat {
        val trackerFrame = Mat()
        when (frame.channels()) {
            4 -> Imgproc.cvtColor(frame, trackerFrame, Imgproc.COLOR_RGBA2BGR)
            1 -> Imgproc.cvtColor(frame, trackerFrame, Imgproc.COLOR_GRAY2BGR)
            else -> frame.copyTo(trackerFrame)
        }
        return trackerFrame
    }

    private fun initializeObjectTracker(
        frame: Mat,
        normalized: CameraOpticalFlowViewModel.NormalizedRoi,
        mapper: RoiFrameMapper
    ): Rect? {
        val initialRect = mapper.mapNormalizedRoiToFrame(normalized) ?: return null
        storeObjectTemplate(frame, initialRect)
        return initializeTrackerAtRect(frame, initialRect, mapper)
    }

    private fun initializeTrackerAtRect(frame: Mat, rect: Rect, mapper: RoiFrameMapper): Rect? {
        val safeRect = sanitizeFrameRect(rect, mapper.frameCols, mapper.frameRows) ?: return null
        return try {
            val tracker = legacy_TrackerCSRT.create()
            val initialized = tracker.init(frame, safeRect.toRect2d())
            if (!initialized) {
                objectTracker = null
                return null
            }
            objectTracker = tracker
            trackerFrameWidth = mapper.frameCols
            trackerFrameHeight = mapper.frameRows
            trackerInitializedFromSelection = true
            lostTrackingFrameCount = 0
            reacquireFrameCounter = 0
            safeRect
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize object tracker: ${e.message}", e)
            objectTracker = null
            safeRect
        }
    }

    private fun updateObjectTracker(frame: Mat, mapper: RoiFrameMapper): Rect? {
        val updatedRect = Rect2d()
        val success = try {
            objectTracker?.update(frame, updatedRect) == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update object tracker: ${e.message}", e)
            objectTracker = null
            false
        }

        val safeRect = if (success && isTrackerRectVisible(updatedRect, mapper.frameCols, mapper.frameRows)) {
            sanitizeFrameRect(updatedRect.toRect(), mapper.frameCols, mapper.frameRows)
        } else {
            null
        }

        return if (safeRect != null) {
            lostTrackingFrameCount = 0
            reacquireFrameCounter = 0
            safeRect
        } else {
            lostTrackingFrameCount++
            objectTracker = null
            reacquireObjectTracker(frame, mapper)
        }
    }

    private fun storeObjectTemplate(frame: Mat, rect: Rect) {
        val safeRect = sanitizeFrameRect(rect, frame.cols(), frame.rows()) ?: return
        var roi: Mat? = null
        val templateGray = Mat()
        try {
            roi = frame.submat(safeRect)
            Imgproc.cvtColor(roi, templateGray, Imgproc.COLOR_BGR2GRAY)
            objectTemplateGray?.release()
            objectTemplateGray = templateGray.clone()
        } finally {
            roi?.release()
            templateGray.release()
        }
    }

    private fun reacquireObjectTracker(frame: Mat, mapper: RoiFrameMapper): Rect? {
        val template = objectTemplateGray ?: return null
        reacquireFrameCounter++
        if (reacquireFrameCounter != 1 && reacquireFrameCounter % REACQUIRE_FRAME_INTERVAL != 0) {
            return null
        }

        val frameGray = Mat()
        val scaledFrame = Mat()
        val scaledTemplate = Mat()
        val matchResult = Mat()
        try {
            Imgproc.cvtColor(frame, frameGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.resize(
                frameGray,
                scaledFrame,
                Size(),
                REACQUIRE_SEARCH_SCALE,
                REACQUIRE_SEARCH_SCALE,
                Imgproc.INTER_AREA
            )

            var bestScore = Double.NEGATIVE_INFINITY
            var bestRect: Rect? = null
            for (templateScale in REACQUIRE_TEMPLATE_SCALES) {
                val width = (template.cols() * REACQUIRE_SEARCH_SCALE * templateScale).roundToInt()
                val height = (template.rows() * REACQUIRE_SEARCH_SCALE * templateScale).roundToInt()
                if (width < MIN_REACQUIRE_TEMPLATE_SIZE || height < MIN_REACQUIRE_TEMPLATE_SIZE) continue
                if (width >= scaledFrame.cols() || height >= scaledFrame.rows()) continue

                Imgproc.resize(
                    template,
                    scaledTemplate,
                    Size(width.toDouble(), height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
                Imgproc.matchTemplate(
                    scaledFrame,
                    scaledTemplate,
                    matchResult,
                    Imgproc.TM_CCOEFF_NORMED
                )
                val result = Core.minMaxLoc(matchResult)
                if (result.maxVal > bestScore) {
                    bestScore = result.maxVal
                    bestRect = Rect(
                        (result.maxLoc.x / REACQUIRE_SEARCH_SCALE).roundToInt(),
                        (result.maxLoc.y / REACQUIRE_SEARCH_SCALE).roundToInt(),
                        (width / REACQUIRE_SEARCH_SCALE).roundToInt(),
                        (height / REACQUIRE_SEARCH_SCALE).roundToInt()
                    )
                }
            }

            val reacquiredRect = bestRect
                ?.takeIf { bestScore >= REACQUIRE_MATCH_THRESHOLD }
                ?.let { sanitizeFrameRect(it, mapper.frameCols, mapper.frameRows) }
                ?: return null

            return initializeTrackerAtRect(frame, reacquiredRect, mapper)
        } finally {
            frameGray.release()
            scaledFrame.release()
            scaledTemplate.release()
            matchResult.release()
        }
    }

    private fun syncTrackingOverlay(frameRect: Rect, mapper: RoiFrameMapper, selectionVersion: Int) {
        if (selectionVersion != roiSelectionVersion || !cameraViewModel.roiEnabled) return

        val normalizedRect = mapper.mapFrameRectToNormalized(frameRect)
        cameraViewModel.normalizedRoi = CameraOpticalFlowViewModel.NormalizedRoi(
            left = normalizedRect.left,
            top = normalizedRect.top,
            right = normalizedRect.right,
            bottom = normalizedRect.bottom,
            viewAspectRatio = mapper.viewAspectRatio,
            pathPoints = emptyList()
        )

        activity?.runOnUiThread {
            if (isAdded && selectionVersion == roiSelectionVersion && cameraViewModel.roiEnabled) {
                binding.roiOverlay.setNormalizedRoi(normalizedRect, notify = false)
            }
        }
    }

    private fun sanitizeFrameRect(rect: Rect, frameCols: Int, frameRows: Int): Rect? {
        if (frameCols <= 1 || frameRows <= 1) return null

        val rawLeft = minOf(rect.x, rect.x + rect.width)
        val rawRight = maxOf(rect.x, rect.x + rect.width)
        val rawTop = minOf(rect.y, rect.y + rect.height)
        val rawBottom = maxOf(rect.y, rect.y + rect.height)
        val left = rawLeft.coerceIn(0, frameCols - 1)
        val top = rawTop.coerceIn(0, frameRows - 1)
        val right = rawRight.coerceIn(left + 1, frameCols)
        val bottom = rawBottom.coerceIn(top + 1, frameRows)
        val width = right - left
        val height = bottom - top
        if (width < MIN_ROI_FRAME_SIZE || height < MIN_ROI_FRAME_SIZE) return null
        return Rect(left, top, width, height)
    }

    private fun isTrackerRectVisible(rect: Rect2d, frameCols: Int, frameRows: Int): Boolean {
        if (frameCols <= 1 || frameRows <= 1 || rect.width <= 0.0 || rect.height <= 0.0) return false

        val rawLeft = rect.x
        val rawTop = rect.y
        val rawRight = rect.x + rect.width
        val rawBottom = rect.y + rect.height
        if (rawRight <= 0.0 ||
            rawBottom <= 0.0 ||
            rawLeft >= frameCols.toDouble() ||
            rawTop >= frameRows.toDouble()
        ) {
            return false
        }

        val visibleLeft = rawLeft.coerceIn(0.0, frameCols.toDouble())
        val visibleTop = rawTop.coerceIn(0.0, frameRows.toDouble())
        val visibleRight = rawRight.coerceIn(0.0, frameCols.toDouble())
        val visibleBottom = rawBottom.coerceIn(0.0, frameRows.toDouble())
        val visibleArea = (visibleRight - visibleLeft).coerceAtLeast(0.0) *
            (visibleBottom - visibleTop).coerceAtLeast(0.0)
        val rectArea = rect.width * rect.height
        if (rectArea <= 0.0) return false

        return visibleArea / rectArea >= MIN_TRACKER_VISIBLE_RATIO
    }

    private fun copyRect(rect: Rect): Rect {
        return Rect(rect.x, rect.y, rect.width, rect.height)
    }

    private fun Rect.toRect2d(): Rect2d {
        return Rect2d(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }

    private fun Rect2d.toRect(): Rect {
        return Rect(
            x.roundToInt(),
            y.roundToInt(),
            width.roundToInt(),
            height.roundToInt()
        )
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
        cameraViewModel.recordAnalysisSample(kltMetrics, farnebackMetrics)
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
            applyOpticalFlowModeUi(useFarneback = cameraViewModel.useFarneback)
            applyCurrentSensitivity()
        }
    }

    private fun resetTimer() {
        timerJob?.cancel()
        timerJob = null
        cameraViewModel.resetTimer()
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
                val mean = Core.mean(matFrame)
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
        val detectedMoving = cameraViewModel.detectPhoneMoving(accelerationMagnitude) ?: return

        activity?.runOnUiThread {
            if (!isMovingModeManualOverride) {
                applyMovingMode(isMoving = detectedMoving, manualOverride = false)
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
        private const val ANALYSIS_SENSITIVITY = 100
        private const val MIN_ROI_FRAME_SIZE = 32
        private const val MIN_TRACKER_VISIBLE_RATIO = 0.70
        private const val REACQUIRE_SEARCH_SCALE = 0.5
        private const val REACQUIRE_MATCH_THRESHOLD = 0.68
        private const val REACQUIRE_FRAME_INTERVAL = 5
        private const val MIN_REACQUIRE_TEMPLATE_SIZE = 12
        private val REACQUIRE_TEMPLATE_SCALES = doubleArrayOf(0.85, 1.0, 1.15)
    }

    private data class RoiFrameMapper(
        val frameCols: Int,
        val frameRows: Int,
        val viewAspectRatio: Float
    ) {
        private val viewHeight = 1.0
        private val viewWidth = viewAspectRatio.toDouble().coerceAtLeast(0.01)
        private val scale = max(viewWidth / frameCols.toDouble(), viewHeight / frameRows.toDouble())
        private val offsetX = ((frameCols * scale) - viewWidth) / 2.0
        private val offsetY = ((frameRows * scale) - viewHeight) / 2.0

        fun mapNormalizedRoiToFrame(normalized: CameraOpticalFlowViewModel.NormalizedRoi): Rect? {
            if (frameCols <= 1 || frameRows <= 1) return null

            val left = mapX(normalized.left).coerceIn(0, frameCols - 1)
            val top = mapY(normalized.top).coerceIn(0, frameRows - 1)
            val right = mapX(normalized.right).coerceIn(left + 1, frameCols)
            val bottom = mapY(normalized.bottom).coerceIn(top + 1, frameRows)
            return Rect(left, top, right - left, bottom - top)
        }

        fun mapFrameRectToNormalized(frameRect: Rect): RectF {
            val left = normalizeX(frameRect.x.toDouble()).coerceIn(0f, 1f)
            val top = normalizeY(frameRect.y.toDouble()).coerceIn(0f, 1f)
            val right = normalizeX((frameRect.x + frameRect.width).toDouble()).coerceIn(left, 1f)
            val bottom = normalizeY((frameRect.y + frameRect.height).toDouble()).coerceIn(top, 1f)
            return RectF(left, top, right, bottom)
        }

        private fun mapX(normalizedX: Float): Int {
            return (((normalizedX * viewWidth) + offsetX) / scale).roundToInt()
        }

        private fun mapY(normalizedY: Float): Int {
            return (((normalizedY * viewHeight) + offsetY) / scale).roundToInt()
        }

        private fun normalizeX(frameX: Double): Float {
            return ((frameX * scale - offsetX) / viewWidth).toFloat()
        }

        private fun normalizeY(frameY: Double): Float {
            return ((frameY * scale - offsetY) / viewHeight).toFloat()
        }
    }

    private data class ActiveRoi(
        val rect: Rect
    )
}
