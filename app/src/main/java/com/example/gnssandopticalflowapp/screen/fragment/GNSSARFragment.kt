package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentGnssArBinding
import com.example.gnssandopticalflowapp.gnss.GnssSatelliteTracker
import com.example.gnssandopticalflowapp.gnss.renderer.GNSSARRenderer

class GNSSARFragment : BaseFragment<FragmentGnssArBinding>(FragmentGnssArBinding::inflate), SensorEventListener {

    private lateinit var renderer: GNSSARRenderer
    private var cameraProvider: ProcessCameraProvider? = null
    
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    
    private var locationManager: LocationManager? = null
    private val satelliteTracker = GnssSatelliteTracker()
    private var currentLocation: Location? = null

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val satList = satelliteTracker.buildSatelliteInfo(status, currentLocation)
            renderer.updateSatellites(satList)
        }
    }

    private val locationListener = LocationListener { location ->
        currentLocation = location
    }

    override fun FragmentGnssArBinding.initView() {
        renderer = GNSSARRenderer()
        
        // Setup GLSurfaceView
        arOverlayView.setEGLContextClientVersion(2)
        arOverlayView.setZOrderOnTop(true)
        arOverlayView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        arOverlayView.holder.setFormat(PixelFormat.TRANSLUCENT)
        arOverlayView.setRenderer(renderer)
        
        sensorManager = safeContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        locationManager = safeContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val perms = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        doRequestPermission(perms, object : IPermissionListener {
            override fun onAllow() {
                startCamera()
                startLocationAndSensors()
            }
            override fun onDenied() {
                Toast.makeText(safeContext(), "Permissions required for AR", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun startCamera() {
        val context = safeContext()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder().setTargetRotation(rotation).build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationAndSensors() {
        sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            locationManager?.registerGnssStatusCallback(gnssStatusCallback, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing")
        }
    }

    override fun FragmentGnssArBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }
    }

    override fun initObserver() = Unit

    override fun onResume() {
        super.onResume()
        binding.arOverlayView.onResume()
        if (ContextCompat.checkSelfPermission(safeContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationAndSensors()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.arOverlayView.onPause()
        sensorManager?.unregisterListener(this)
        locationManager?.removeUpdates(locationListener)
        locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rDeviceToSensorWorld = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(rDeviceToSensorWorld, event.values)
            
            val rSensorWorldToDevice = FloatArray(16)
            Matrix.transposeM(rSensorWorldToDevice, 0, rDeviceToSensorWorld, 0)
            
            // M_world_to_sensor maps OpenGL world (X=East, Y=Up, Z=South) to Sensor world (X=East, Y=North, Z=Up)
            // Column-major format for OpenGL
            val mWorldToSensor = FloatArray(16).apply {
                this[0] = 1f;  this[4] = 0f;  this[8] = 0f;   this[12] = 0f
                this[1] = 0f;  this[5] = 0f;  this[9] = -1f;  this[13] = 0f
                this[2] = 0f;  this[6] = 1f;  this[10] = 0f;  this[14] = 0f
                this[3] = 0f;  this[7] = 0f;  this[11] = 0f;  this[15] = 1f
            }
            
            val viewMatrix = FloatArray(16)
            Matrix.multiplyMM(viewMatrix, 0, rSensorWorldToDevice, 0, mWorldToSensor, 0)
            
            renderer.updateViewMatrix(viewMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
