package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.graphics.createBitmap
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.optical_flow.classes.BasicFusion
import com.example.gnssandopticalflowapp.optical_flow.classes.FraneBack
import com.example.gnssandopticalflowapp.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.classes.MotionVectorViz
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.inter.SensorFusion
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.sqrt

class CameraOpticalFlowFragment :
    BaseFragment<FragmentOpticalFlowBinding>(FragmentOpticalFlowBinding::inflate),
    CameraBridgeViewBase.CvCameraViewListener2 {

    private var currFrame: Mat? = null
    private var mvMat: Mat? = null
    private var output: OFOutput? = null
    private lateinit var opticalFlow: OpticalFlow
    private lateinit var imuEstimator: IMUEstimator
    private lateinit var fusion: SensorFusion
    private var fuseOutput: FloatArray? = null
    private lateinit var mvViewer: MotionVectorViz

    override fun FragmentOpticalFlowBinding.initView() {
        initVars()

        cameraView.apply {
            setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK)
            visibility = CameraBridgeViewBase.VISIBLE
            setCvCameraViewListener(this@CameraOpticalFlowFragment)
        }

        imuEstimator = IMUEstimator(requireContext().applicationContext)

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
                    Toast.makeText(requireContext(), "Camera permission is required for this app", Toast.LENGTH_LONG).show()
                }

                override fun onNeverAskAgain(permission: String) {
                    Toast.makeText(requireContext(), "Camera permission is required. Please enable it in settings.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    override fun FragmentOpticalFlowBinding.initListener() {
        resetMV.setOnClickListener {
            opticalFlow.reset_motion_vector()
            mvViewer.reset_motion_vector()
        }

        updateFeaturesButton.setOnClickListener {
            opticalFlow.UpdateFeatures()
        }

        ofType.setOnClickListener {
            if (ofType.isChecked) {
                opticalFlow = FraneBack()
            } else {
                opticalFlow = KLT(velPred)
            }
        }

        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("SEEK", progress.toString())
                if (::opticalFlow.isInitialized) {
                    opticalFlow.set_sensitivity(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        ivBack.setSingleClick {
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
        binding.cameraView.disableView()
        if (::imuEstimator.isInitialized) {
            imuEstimator.unregister()
        }
    }

    override fun onDestroyView() {
        binding.cameraView.disableView()
        super.onDestroyView()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        Log.d(TAG, "onCameraViewStarted")
    }

    override fun onCameraViewStopped() {
        Log.d(TAG, "onCameraViewStopped")
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat? {
        // get IMU variables
        val velocity: FloatArray = imuEstimator.getVelocity()
        val imuPosition: FloatArray = imuEstimator.getPosition()

        // Convert the velocity to mph
        val xVelocityMph: Float = velocity[0] * 2.23694f
        val yVelocityMph: Float = velocity[1] * 2.23694f
        val zVelocityMph: Float = velocity[2] * 2.23694f

        Log.d("POS", "${imuPosition[0]}, ${imuPosition[1]}, ${imuPosition[2]}")

        // Get the magnitude of the velocity vector
        val speedMph: Float = sqrt((xVelocityMph * xVelocityMph + yVelocityMph * yVelocityMph + zVelocityMph * zVelocityMph).toDouble()).toFloat()

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
            return currentOutput.of_frame
        }
        return frame
    }
}
