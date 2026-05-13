package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentGnssArBinding
import com.example.gnssandopticalflowapp.gnss.GnssSatelliteTracker
import com.example.gnssandopticalflowapp.gnss.renderer.GNSSARRenderer
import com.example.gnssandopticalflowapp.screen.dialog.ErrorGNSSDialog
import com.example.gnssandopticalflowapp.util.VideoEncoder
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

    @Volatile
    private var isRecording = false
    @Volatile
    private var videoEncoder: VideoEncoder? = null
    private var videoEncoderThread: HandlerThread? = null
    private var videoEncoderHandler: Handler? = null
    private var recordedFilePath = ""
    private var recordingStartNs = 0L
    private var recordingTimerJob: Job? = null
    private var recordingTimerStartMs = 0L
    private var photoCaptureInProgress = false
    private val encodingFramePending = AtomicBoolean(false)

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
        bgLeft.bringToFront()
        bgRight.bringToFront()
        ivCamera.bringToFront()
        ivVideo.bringToFront()
        tvRecordingTimer.bringToFront()
        captureFlashOutline.bringToFront()

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

        ivCamera.setSingleClick {
            capturePhoto()
        }

        ivVideo.setSingleClick {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
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
        if (isRecording) {
            stopRecording()
        }
        stopLocationAndHeading()

        if (glSurfaceResumed) {
            binding.arOverlayView.onPause()
            glSurfaceResumed = false
        }

        runCatching { arSession?.pause() }
        renderer.setSession(null)
    }

    override fun onDestroyView() {
        if (isRecording) {
            stopRecording()
        }
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

    private fun capturePhoto() {
        if (photoCaptureInProgress) return

        photoCaptureInProgress = true
        playCaptureFlash()

        renderer.captureNextFrame { bitmap ->
            if (bitmap == null) {
                photoCaptureInProgress = false
                showToast("Cannot capture AR photo")
                return@captureNextFrame
            }

            val appContext = context?.applicationContext
            if (appContext == null) {
                bitmap.recycle()
                photoCaptureInProgress = false
                return@captureNextFrame
            }

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                var bitmapRecycled = false
                val result = runCatching {
                    val outputFile = MediaStorageUtil.createImageFile(appContext, "gnss_ar")
                    outputFile.outputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, output)) {
                            error("Bitmap compression failed")
                        }
                    }
                    bitmap.recycle()
                    bitmapRecycled = true
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(outputFile.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )
                    outputFile
                }.onFailure {
                    if (!bitmapRecycled) {
                        bitmap.recycle()
                    }
                }

                withContext(Dispatchers.Main) {
                    photoCaptureInProgress = false
                    val file = result.getOrNull()
                    if (file != null && file.exists() && file.length() > 0L) {
                        MediaStorageUtil.addImage(appContext, file.absolutePath)
                        mainViewModel.videoLibraryUpdated.value = System.currentTimeMillis()
                        showToast("Photo saved")
                    } else {
                        showToast("Cannot save photo")
                    }
                }
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return

        val width = binding.arOverlayView.width
        val height = binding.arOverlayView.height
        if (width <= 0 || height <= 0) {
            showToast("AR view is not ready")
            return
        }

        val appContext = safeContext().applicationContext
        val outputFile = MediaStorageUtil.createVideoFile(appContext, "gnss_ar")
        recordedFilePath = outputFile.absolutePath
        encodingFramePending.set(false)

        val thread = HandlerThread("GNSSARVideoEncoder").apply { start() }
        val handler = Handler(thread.looper)
        videoEncoderThread = thread
        videoEncoderHandler = handler

        handler.post {
            try {
                val encoder = VideoEncoder(recordedFilePath, width, height, AR_RECORDING_FPS)
                encoder.start()
                videoEncoder = encoder
                recordingStartNs = System.nanoTime()
                isRecording = true
                renderer.startFrameRecording(::handleRecordedFrame)

                binding.root.post {
                    updateRecordingUi(true)
                    startRecordingTimer()
                    showToast("Recording started")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AR recording", e)
                videoEncoder = null
                videoEncoderHandler = null
                videoEncoderThread = null
                runCatching { outputFile.delete() }
                thread.quitSafely()

                binding.root.post {
                    isRecording = false
                    updateRecordingUi(false)
                    stopRecordingTimer(reset = true)
                    showToast("Failed to start recording")
                }
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording && videoEncoder == null) return

        isRecording = false
        renderer.stopFrameRecording()
        updateRecordingUi(false)
        stopRecordingTimer(reset = true)

        val appContext = context?.applicationContext ?: return
        val pathToSave = recordedFilePath
        val handler = videoEncoderHandler
        val thread = videoEncoderThread
        videoEncoderHandler = null
        videoEncoderThread = null

        if (handler == null) {
            videoEncoder = null
            return
        }

        handler.post {
            val encoder = videoEncoder
            videoEncoder = null
            runCatching {
                encoder?.release()
            }.onFailure { error ->
                Log.e(TAG, "Failed to release AR recording encoder", error)
            }

            val file = File(pathToSave)
            val isSaved = pathToSave.isNotBlank() && file.exists() && file.length() > MIN_VIDEO_FILE_BYTES
            if (isSaved) {
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(pathToSave),
                    arrayOf("video/mp4"),
                    null
                )
                MediaStorageUtil.addVideo(appContext, pathToSave)
            } else {
                runCatching { file.delete() }
            }

            thread?.quitSafely()
            binding.root.post {
                mainViewModel.videoLibraryUpdated.value = System.currentTimeMillis()
                showToast(if (isSaved) "Recording saved" else "Recording failed")
            }
        }
    }

    private fun handleRecordedFrame(width: Int, height: Int, rgbaBytes: ByteArray, timestampNs: Long) {
        if (!isRecording) return
        val handler = videoEncoderHandler ?: return
        if (!encodingFramePending.compareAndSet(false, true)) return

        handler.post {
            var mat: Mat? = null
            try {
                val encoder = videoEncoder ?: return@post
                mat = Mat(height, width, CvType.CV_8UC4)
                mat.put(0, 0, rgbaBytes)
                val presentationTimeUs = ((timestampNs - recordingStartNs).coerceAtLeast(0L)) / 1000L
                encoder.encodeFrame(mat, presentationTimeUs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode AR frame", e)
            } finally {
                mat?.release()
                encodingFramePending.set(false)
            }
        }
    }

    private fun updateRecordingUi(recording: Boolean) = with(binding.ivVideo) {
        imageTintList = if (recording) null else ColorStateList.valueOf(Color.WHITE)
        setImageResource(if (recording) R.drawable.ic_record_dot_red else R.drawable.ic_video_black)
        animate()
            .scaleX(if (recording) 0.72f else 1f)
            .scaleY(if (recording) 0.72f else 1f)
            .setDuration(120L)
            .start()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerStartMs = SystemClock.elapsedRealtime()
        binding.tvRecordingTimer.text = formatRecordingTime(0L)
        binding.tvRecordingTimer.visibility = View.VISIBLE

        recordingTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val elapsedMs = SystemClock.elapsedRealtime() - recordingTimerStartMs
                binding.tvRecordingTimer.text = formatRecordingTime(elapsedMs)
                delay(RECORDING_TIMER_UPDATE_MS)
            }
        }
    }

    private fun stopRecordingTimer(reset: Boolean) {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        if (reset) {
            binding.tvRecordingTimer.text = formatRecordingTime(0L)
            binding.tvRecordingTimer.visibility = View.GONE
        }
    }

    private fun formatRecordingTime(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun playCaptureFlash() = with(binding.captureFlashOutline) {
        animate().cancel()
        alpha = 0f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(45L)
            .withEndAction {
                animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction {
                        visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }

    private fun showToast(message: String) {
        context?.let {
            Toast.makeText(it, message, Toast.LENGTH_SHORT).show()
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
        const val PHOTO_JPEG_QUALITY = 96
        const val AR_RECORDING_FPS = 30
        const val MIN_VIDEO_FILE_BYTES = 100L
        const val RECORDING_TIMER_UPDATE_MS = 250L
        var hasGnssErrorDialogShownThisSession = false
    }
}
