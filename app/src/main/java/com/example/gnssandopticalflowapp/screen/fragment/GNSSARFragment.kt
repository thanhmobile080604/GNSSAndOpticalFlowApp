package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentGnssArBinding
import com.example.gnssandopticalflowapp.gnss.GnssSatelliteTracker
import com.example.gnssandopticalflowapp.gnss.renderer.GNSSARRenderer
import com.example.gnssandopticalflowapp.screen.dialog.ErrorGNSSDialog
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var gnssMeasurementsRegistered = false
    private var externalOrbitRefreshJob: Job? = null
    private var gnssErrorDialogJob: Job? = null
    private var lastGnssStatusSatelliteCount = 0
    private var renderSatellitesDisabledByGnssError = false

    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            satelliteTracker.updateMeasurements(eventArgs)
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            lastGnssStatusSatelliteCount = status.satelliteCount
            if (status.satelliteCount > 0) {
                cancelGnssErrorDialogCheck()
            } else {
                renderer.updateSatellites(emptyList())
                scheduleGnssErrorDialogCheck()
            }

            if (renderSatellitesDisabledByGnssError) {
                renderer.updateSatellites(emptyList())
                return
            }

            val satList = satelliteTracker.buildSatelliteInfo(status, currentLocation)
            renderer.updateSatellites(satList)
        }
    }

    private val locationListener = LocationListener { location ->
        currentLocation = location
        renderer.updateUserLocation(location)
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
        lastGnssStatusSatelliteCount = 0
        renderSatellitesDisabledByGnssError = false
        refreshExternalOrbitDataIfNeeded()

        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            runCatching {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
            }.onFailure { error ->
                Log.d(TAG, "Network location updates unavailable: ${error.message}")
            }
            updateLastKnownLocation()
            gnssStatusRegistered = locationManager?.registerGnssStatusCallback(gnssStatusCallback, null) == true
            registerGnssMeasurements()
            scheduleGnssErrorDialogCheck()
            locationStarted = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun stopLocationAndHeading() {
        stopHeadingSensor()
        cancelGnssErrorDialogCheck()

        if (!locationStarted) {
            externalOrbitRefreshJob?.cancel()
            externalOrbitRefreshJob = null
            return
        }
        locationManager?.removeUpdates(locationListener)
        if (gnssStatusRegistered) {
            runCatching { locationManager?.unregisterGnssStatusCallback(gnssStatusCallback) }
            gnssStatusRegistered = false
        }
        if (gnssMeasurementsRegistered) {
            runCatching { locationManager?.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback) }
            gnssMeasurementsRegistered = false
        }
        externalOrbitRefreshJob?.cancel()
        externalOrbitRefreshJob = null
        lastGnssStatusSatelliteCount = 0
        satelliteTracker.clear()
        currentLocation = null
        renderer.updateUserLocation(null)
        renderer.updateSatellites(emptyList())
        locationStarted = false
    }

    @SuppressLint("MissingPermission")
    private fun updateLastKnownLocation() {
        val lastKnownLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return
        val locationAge = System.currentTimeMillis() - lastKnownLocation.time
        if (locationAge > LAST_KNOWN_LOCATION_MAX_AGE_MS) return

        currentLocation = lastKnownLocation
        renderer.updateUserLocation(lastKnownLocation)
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssMeasurements() {
        if (gnssMeasurementsRegistered) return

        gnssMeasurementsRegistered = runCatching {
            locationManager?.registerGnssMeasurementsCallback(gnssMeasurementsCallback) == true
        }.onFailure { error ->
            Log.d(TAG, "GNSS measurements callback unavailable: ${error.message}")
        }.getOrDefault(false)
    }

    private fun refreshExternalOrbitDataIfNeeded(forceRefresh: Boolean = false) {
        if (!forceRefresh && externalOrbitRefreshJob?.isActive == true) return

        externalOrbitRefreshJob?.cancel()
        externalOrbitRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(EXTERNAL_ORBIT_REFRESH_ATTEMPTS) { attempt ->
                val forceAttemptRefresh = forceRefresh || attempt > 0
                val igsLoaded = satelliteTracker.refreshIgsBroadcastDataIfNeeded(forceAttemptRefresh)
                val celesTrakLoaded = if (igsLoaded) {
                    false
                } else {
                    satelliteTracker.refreshCelesTrakDataIfNeeded(forceAttemptRefresh)
                }
                if (igsLoaded || celesTrakLoaded) return@launch

                if (attempt < EXTERNAL_ORBIT_REFRESH_ATTEMPTS - 1) {
                    delay(EXTERNAL_ORBIT_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun scheduleGnssErrorDialogCheck() {
        if (!permissionsGranted || hasGnssErrorDialogShownThisSession) return
        if (gnssErrorDialogJob?.isActive == true) return

        gnssErrorDialogJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(GNSS_ERROR_DIALOG_DELAY_MS)
            if (!isResumed || hasGnssErrorDialogShownThisSession) return@launch
            if (hasUsableGnssForAr()) return@launch

            showGnssErrorDialogOnceAndDisableSatellites()
        }
    }

    private fun cancelGnssErrorDialogCheck() {
        gnssErrorDialogJob?.cancel()
        gnssErrorDialogJob = null
    }

    private fun hasUsableGnssForAr(): Boolean {
        return gnssStatusRegistered && lastGnssStatusSatelliteCount > 0
    }

    private fun showGnssErrorDialogOnceAndDisableSatellites() {
        renderSatellitesDisabledByGnssError = true
        renderer.updateSatellites(emptyList())

        if (hasGnssErrorDialogShownThisSession) return
        checkIfFragmentAttached {
            if (this@GNSSARFragment.parentFragmentManager.isStateSaved) return@checkIfFragmentAttached
            hasGnssErrorDialogShownThisSession = true
            ErrorGNSSDialog.show(this@GNSSARFragment.parentFragmentManager)
        }
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
        const val LAST_KNOWN_LOCATION_MAX_AGE_MS = 120_000L
        const val EXTERNAL_ORBIT_RETRY_DELAY_MS = 30_000L
        const val EXTERNAL_ORBIT_REFRESH_ATTEMPTS = 3
        const val GNSS_ERROR_DIALOG_DELAY_MS = 10_000L
        var hasGnssErrorDialogShownThisSession = false
    }
}
