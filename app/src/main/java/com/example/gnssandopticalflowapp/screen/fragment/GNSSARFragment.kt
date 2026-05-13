package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentGnssArBinding
import com.example.gnssandopticalflowapp.gnss.GnssSatelliteTracker
import com.example.gnssandopticalflowapp.gnss.renderer.GNSSARRenderer
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlin.math.atan2
import kotlin.math.hypot

class GNSSARFragment : BaseFragment<FragmentGnssArBinding>(FragmentGnssArBinding::inflate),
    SensorEventListener {

    private lateinit var renderer: GNSSARRenderer
    private var arSession: Session? = null
    private var installRequested = false
    private var permissionsGranted = false
    private var glSurfaceResumed = false

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var headingSensorStarted = false
    private var headingLocked = false

    private var locationManager: LocationManager? = null
    private var locationStarted = false
    private var gnssStatusRegistered = false
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

        arOverlayView.setEGLContextClientVersion(2)
        arOverlayView.setZOrderOnTop(false)
        arOverlayView.setZOrderMediaOverlay(false)
        arOverlayView.preserveEGLContextOnPause = true
        arOverlayView.setRenderer(renderer)
        arOverlayView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        ivBack.bringToFront()

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
                permissionsGranted = true
                startArIfReady()
                startLocationAndHeading()
            }

            override fun onDenied() {
                Toast.makeText(safeContext(), "Permissions required for AR", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun FragmentGnssArBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }
    }

    override fun initObserver() = Unit

    override fun onResume() {
        super.onResume()
        permissionsGranted = hasRequiredPermissions()
        if (permissionsGranted) {
            startArIfReady()
            startLocationAndHeading()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationAndHeading()

        if (glSurfaceResumed) {
            binding.arOverlayView.onPause()
            glSurfaceResumed = false
        }

        runCatching { arSession?.pause() }
        renderer.setSession(null)
    }

    override fun onDestroyView() {
        runCatching { arSession?.close() }
        arSession = null
        super.onDestroyView()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR || headingLocked) return

        val deviceToEnuMatrix = FloatArray(16)
        SensorManager.getRotationMatrixFromVector(deviceToEnuMatrix, event.values)
        val headingDegrees = getBackCameraHeadingDegrees(deviceToEnuMatrix)
        renderer.updateWorldYawDegrees(headingDegrees)
        headingLocked = true
        stopHeadingSensor()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startArIfReady() {
        if (!isResumed || !permissionsGranted) return

        val session = ensureArSession() ?: return
        try {
            renderer.updateDisplayRotation(binding.arOverlayView.display?.rotation ?: Surface.ROTATION_0)
            session.resume()
            renderer.setSession(session)

            if (!glSurfaceResumed) {
                binding.arOverlayView.onResume()
                glSurfaceResumed = true
            }
            binding.ivBack.bringToFront()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "ARCore camera unavailable", e)
            Toast.makeText(safeContext(), "AR camera is not available", Toast.LENGTH_SHORT).show()
            renderer.setSession(null)
            arSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ARCore session", e)
            Toast.makeText(safeContext(), "Cannot start AR on this device", Toast.LENGTH_SHORT).show()
            renderer.setSession(null)
        }
    }

    private fun ensureArSession(): Session? {
        arSession?.let { return it }

        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return null
            }

            val session = Session(requireActivity())
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            session.configure(config)

            arSession = session
            renderer.resetWorld()
            headingLocked = false
            startHeadingSensor()
            return session
        } catch (e: Exception) {
            Log.e(TAG, "ARCore session creation failed", e)
            Toast.makeText(safeContext(), "ARCore is not available on this device", Toast.LENGTH_SHORT).show()
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationAndHeading() {
        if (!permissionsGranted || locationStarted) return

        startHeadingSensor()

        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            gnssStatusRegistered = locationManager?.registerGnssStatusCallback(gnssStatusCallback, null) == true
            locationStarted = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun stopLocationAndHeading() {
        stopHeadingSensor()

        if (!locationStarted) return
        locationManager?.removeUpdates(locationListener)
        if (gnssStatusRegistered) {
            runCatching { locationManager?.unregisterGnssStatusCallback(gnssStatusCallback) }
            gnssStatusRegistered = false
        }
        locationStarted = false
    }

    private fun startHeadingSensor() {
        if (headingLocked || headingSensorStarted) return

        val sensor = rotationSensor ?: run {
            headingLocked = true
            renderer.updateWorldYawDegrees(0f)
            return
        }

        headingSensorStarted = sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        ) == true
    }

    private fun stopHeadingSensor() {
        if (!headingSensorStarted) return
        sensorManager?.unregisterListener(this, rotationSensor)
        headingSensorStarted = false
    }

    private fun hasRequiredPermissions(): Boolean {
        val context = safeContext()
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun getBackCameraHeadingDegrees(deviceToEnuMatrix: FloatArray): Float {
        val forwardEast = -deviceToEnuMatrix[8]
        val forwardNorth = -deviceToEnuMatrix[9]
        val horizontalLength = hypot(forwardEast.toDouble(), forwardNorth.toDouble())
        if (horizontalLength < MIN_HEADING_VECTOR_LENGTH) return 0f

        val headingDegrees = Math.toDegrees(atan2(forwardEast.toDouble(), forwardNorth.toDouble())).toFloat()
        return normalizeDegrees(headingDegrees)
    }

    private fun normalizeDegrees(degrees: Float): Float {
        var normalized = degrees % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    private companion object {
        const val MIN_HEADING_VECTOR_LENGTH = 0.001
    }
}
