package com.example.opticalflowapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.opticalflowapp.model.OFOutput
import com.example.opticalflowapp.velocity_estimator.classes.BasicFusion
import com.example.opticalflowapp.velocity_estimator.classes.FraneBack
import com.example.opticalflowapp.velocity_estimator.classes.IMUEstimator
import com.example.opticalflowapp.velocity_estimator.classes.KLT
import com.example.opticalflowapp.velocity_estimator.classes.MotionVectorViz
import com.example.opticalflowapp.velocity_estimator.inter.OpticalFlow
import com.example.opticalflowapp.velocity_estimator.inter.SensorFusion
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2, View.OnClickListener {
    private val TAG: String = MainActivity::class.java.simpleName
    private val CAMERA_PERMISSION_REQUEST_CODE = 200

    private var mOpenCvCameraView: CameraBridgeViewBase? = null
    private lateinit var motionVector: ImageView
    private lateinit var resetButton: Button
    private lateinit var updateFeaturesButton: Button
    private lateinit var ofTypeSwitch: Switch
    private lateinit var sensitivityBar: SeekBar
    private var velPredText: TextView? = null
    private var currFrame: Mat? = null
    private var mvMat: Mat? = null
    private var output: OFOutput? = null
    private lateinit var opticalFlow: OpticalFlow
    private lateinit var imuEstimator: IMUEstimator
    private lateinit var fusion: SensorFusion
    private var fuseOutput: FloatArray? = null
    private lateinit var mvViewer: MotionVectorViz

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUi()
        initVars()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            mOpenCvCameraView?.setCameraPermissionGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mOpenCvCameraView?.setCameraPermissionGranted()
            } else {
                Toast.makeText(this, "Camera permission is required for this app", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initVars() {
        // first initialize with KLT optical flow
        opticalFlow = KLT(velPredText)
        output = OFOutput()

        // init fusion algorithm
        fusion = BasicFusion()
        fuseOutput = FloatArray(3)

        // init motion vector viewer
        mvViewer = MotionVectorViz(400, 400)
        mvMat = Mat.zeros(400, 400, CvType.CV_8UC1)
    }

    private fun initUi() {
        // velocity prediction label
        velPredText = findViewById<View>(R.id.vel_pred) as? TextView

        // reset Button
        resetButton = findViewById<View>(R.id.resetMV) as Button
        resetButton.setOnClickListener(this)

        // update features Button
        updateFeaturesButton = findViewById<View>(R.id.update_features_button) as Button
        updateFeaturesButton.setOnClickListener(this)

        // Image view
        motionVector = findViewById<View>(R.id.motion_vector) as ImageView
        motionVector.visibility = View.VISIBLE

        // Java view
        mOpenCvCameraView = findViewById<View>(R.id.camera_view) as? CameraBridgeViewBase
        mOpenCvCameraView?.apply {
            setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK)
            visibility = CameraBridgeViewBase.VISIBLE
            setCvCameraViewListener(this@MainActivity)
        }

        // init IMU_estimator
        imuEstimator = IMUEstimator(this.applicationContext)

        // init switch
        ofTypeSwitch = findViewById<View>(R.id.of_type) as Switch
        ofTypeSwitch.setOnClickListener(this)

        // init seek bar
        sensitivityBar = findViewById<View>(R.id.sensitivity_bar) as SeekBar
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
    }

    override fun onResume() {
        super.onResume()
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            mOpenCvCameraView?.enableView()
        } else {
            Log.e(TAG, "OpenCV library not found!")
        }
        imuEstimator.register()
    }

    override fun onPause() {
        super.onPause()
        mOpenCvCameraView?.disableView()
        imuEstimator.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        mOpenCvCameraView?.disableView()
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

        runOnUiThread {
            velPredText?.text = speedMph.toString()
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

                if (mv != null) {
                    // draw Motion Vector
                    val dst: Bitmap = Bitmap.createBitmap(mv.width(), mv.height(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(mv, dst)
                    runOnUiThread {
                        motionVector.setImageBitmap(dst)
                    }
                }
            }
            return currentOutput.of_frame
        }
        return frame
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.resetMV -> {
                opticalFlow.reset_motion_vector()
                mvViewer.reset_motion_vector()
            }
            R.id.update_features_button -> opticalFlow.UpdateFeatures()
            R.id.of_type -> {
                if (ofTypeSwitch.isChecked) {
                    opticalFlow = FraneBack()
                } else {
                    opticalFlow = KLT(velPredText)
                }
            }
        }
    }

    companion object {
        init {
            if (OpenCVLoader.initDebug()) {
                Log.d("OpenCV", "success")
            } else {
                Log.d("OpenCV", "failed")
            }
        }
    }
}
