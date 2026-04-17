package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentCameraOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.optical_flow.classes.BasicFusion
import com.example.gnssandopticalflowapp.optical_flow.classes.FraneBack
import com.example.gnssandopticalflowapp.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.classes.MotionVectorViz
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.inter.SensorFusion
import com.example.gnssandopticalflowapp.util.VideoEncoder
import com.example.gnssandopticalflowapp.util.VideoStorageUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.sqrt

class CameraOpticalFlowFragment :
    BaseFragment<FragmentCameraOpticalFlowBinding>(FragmentCameraOpticalFlowBinding::inflate),
    CameraBridgeViewBase.CvCameraViewListener2 {

    private var currFrame: Mat? = null
    private var mvMat: Mat? = null
    private var output: OFOutput? = null
    private lateinit var opticalFlow: OpticalFlow
    private lateinit var imuEstimator: IMUEstimator
    private lateinit var fusion: SensorFusion
    private var fuseOutput: FloatArray? = null
    private lateinit var mvViewer: MotionVectorViz
    private var frameCount: Int = 0
    private val updateInterval: Int = 30 // frames between automatic feature updates

    private var videoEncoder: VideoEncoder? = null
    private var isRecording = false
    private var recordedFilePath = ""

    private var timerJob: Job? = null
    private var timerStartTime: Long = 0L
    private var elapsedBeforePause: Long = 0L

    override fun FragmentCameraOpticalFlowBinding.initView() {
        initVars()

        cameraView.apply {
            setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK)
            visibility = CameraBridgeViewBase.VISIBLE
            setCvCameraViewListener(this@CameraOpticalFlowFragment)
        }

        imuEstimator = IMUEstimator(safeContext().applicationContext)

        checkPermissions()
    }

    private fun initVars() {
        // first initialize with KLT optical flow
        opticalFlow = KLT(binding.velPred)
        output = OFOutput()

        // init fusion algorithm
        fusion = BasicFusion()
        fuseOutput = FloatArray(3)

        // init motion vector viewer
        mvViewer = MotionVectorViz(400, 400)
        mvMat = Mat.zeros(400, 400, CvType.CV_8UC1)
    }

    private fun checkPermissions() {
        doRequestPermission(
            arrayOf(Manifest.permission.CAMERA),
            object : IPermissionListener {
                override fun onAllow() {
                    binding.cameraView.setCameraPermissionGranted()
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

    override fun FragmentCameraOpticalFlowBinding.initListener() {
        resetMV.setOnClickListener {
            opticalFlow.resetMotionVector()
            mvViewer.resetMotionVector()
        }

        updateFeaturesButton.setOnClickListener {
            opticalFlow.updateFeatures()
        }

        ofType.setOnClickListener {
            opticalFlow = if (ofType.isChecked) {
                FraneBack()
            } else {
                KLT(velPred)
            }
        }

        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("SEEK", progress.toString())
                if (::opticalFlow.isInitialized) {
                    opticalFlow.setSensitivity(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        ivVideRecord.setOnClickListener {
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
            onBack()
        }
    }

    override fun initObserver() {}

    override fun onResume() {
        super.onResume()
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            binding.cameraView.enableView()
        } else {
            Log.e(TAG, "OpenCV library not found!")
        }
        if (::imuEstimator.isInitialized) {
            imuEstimator.register()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            stopRecording()
        }
        binding.cameraView.disableView()
        if (::imuEstimator.isInitialized) {
            imuEstimator.unregister()
        }
    }

    override fun onDestroyView() {
        if (isRecording) {
            stopRecording()
        }
        binding.cameraView.disableView()
        super.onDestroyView()
    }

    private fun startRecording() {
        if (currFrame == null) {
            Log.e("CAMERA-RECORD", "No current frame to start recording")
            return
        }
        val cacheDir = safeContext().cacheDir
        val videosDir = java.io.File(cacheDir, "videos")
        if (!videosDir.exists()) videosDir.mkdirs()
        
        val outputFile = java.io.File(videosDir, "recorded_${System.currentTimeMillis()}.mp4")
        recordedFilePath = outputFile.absolutePath
        Log.d("CAMERA-RECORD", "Target path: $recordedFilePath")
        
        videoEncoder = VideoEncoder(recordedFilePath, currFrame!!.cols(), currFrame!!.rows())
        
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
        val file = java.io.File(recordedFilePath)
        
        videoEncoder?.release()
        videoEncoder = null
        
        val size = if (file.exists()) file.length() else 0
        Log.d("CAMERA-RECORD", "Stopping. Final size: $size bytes")

        binding.ivVideRecord.setImageResource(R.drawable.ic_start_record_purple)
        Toast.makeText(safeContext(), "Recording saved", Toast.LENGTH_SHORT).show()
        
        if (recordedFilePath.isNotEmpty() && size > 100) {
            // Scan file to ensure it's ready for general use
            android.media.MediaScannerConnection.scanFile(safeContext(), arrayOf(recordedFilePath), null) { _, _ -> }
            
            VideoStorageUtil.addVideo(safeContext(), recordedFilePath)
            
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

    override fun onCameraViewStarted(width: Int, height: Int) {
        Log.d(TAG, "onCameraViewStarted")
    }

    override fun onCameraViewStopped() {
        Log.d(TAG, "onCameraViewStopped")
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat? {
        // automatic feature update periodically
        frameCount++
        if (frameCount % updateInterval == 0) {
            if (::opticalFlow.isInitialized) opticalFlow.updateFeatures()
        }
        // get IMU variables
        val velocity = imuEstimator.getVelocity()
        val imuPosition = imuEstimator.getPosition()

        // Convert the velocity to mph
        val xVelocity = velocity[0]
        val yVelocity = velocity[1]
        val zVelocity = velocity[2]

        Log.d("POS", "${imuPosition[0]}, ${imuPosition[1]}, ${imuPosition[2]}")

        // Get the magnitude of the velocity vector
        val speedMph = sqrt((xVelocity * xVelocity + yVelocity * yVelocity + zVelocity * zVelocity).toDouble()).toFloat()

        activity?.runOnUiThread {
            binding.velPred.text = speedMph.toString()
        }

        // get OF output
        val frame = inputFrame.rgba()
        currFrame = frame
        val currentOutput = opticalFlow.run(frame)
        output = currentOutput

        if (currentOutput != null && currentOutput.of_frame != null) {
            // fuse the IMU sensor with the Optical Flow
            val pos = currentOutput.position
            if (pos != null) {
                fuseOutput = fusion.getPosition(velocity, imuPosition, pos)
                // get Motion Vector Mat to present
                val mv = mvViewer.getMotionVector(pos)
                mvMat = mv

                // draw Motion Vector
                val dst: Bitmap = createBitmap(mv.width(), mv.height())
                Utils.matToBitmap(mv, dst)
                activity?.runOnUiThread {
                    binding.motionVector.setImageBitmap(dst)
                }
            }
            val outFrame = currentOutput.of_frame ?: frame
            writeToVideoWriter(outFrame)
            return outFrame
        }
        writeToVideoWriter(frame)
        return frame
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
}
