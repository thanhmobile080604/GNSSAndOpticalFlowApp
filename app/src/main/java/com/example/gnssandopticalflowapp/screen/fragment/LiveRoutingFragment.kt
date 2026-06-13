package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.Constants
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentLiveRoutingBinding
import com.example.gnssandopticalflowapp.function.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.function.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.function.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.screen.viewmodel.LiveRoutingViewModel
import com.example.gnssandopticalflowapp.screen.viewmodel.LiveRoutingViewModel.OpticalMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveRoutingFragment :
    BaseFragment<FragmentLiveRoutingBinding>(FragmentLiveRoutingBinding::inflate) {

    private val liveRoutingViewModel: LiveRoutingViewModel by activityViewModels()

    private lateinit var locationManager: LocationManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imuEstimator: IMUEstimator

    private var navigationMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val gnssTravelLines = ArrayList<Polyline>()
    private val testGnssLines = ArrayList<Polyline>()
    private val opticalAssistLines = ArrayList<Polyline>()

    private var tickerJob: Job? = null
    private var testDropoutJob: Job? = null
    private var lastTickMs = 0L

    private var opticalFlow: OpticalFlow? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var overlayBitmap: Bitmap? = null
    private var cameraPermissionRequestInFlight = false

    private var orientationUnlocked = false
    private var recreateForOrientationChange = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val effectiveLocation = mainViewModel.getEffectiveLocation(location) ?: return
            handleLocationUpdate(effectiveLocation)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            applyAssistDecision(liveRoutingViewModel.onGnssProviderDisabled())
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            applyAssistDecision(liveRoutingViewModel.onGnssStatusChanged(status.satelliteCount))
        }
    }

    override fun FragmentLiveRoutingBinding.initView() {
        val state = mainViewModel.liveRouteState
        if (state == null) {
            Toast.makeText(safeContext(), "No active route", Toast.LENGTH_SHORT).show()
            root.post { onBack() }
            return
        }

        unlockOrientationForLiveRouting()
        lastOrientation = resources.configuration.orientation

        cameraExecutor = Executors.newSingleThreadExecutor()
        imuEstimator = IMUEstimator(safeContext().applicationContext)
        cameraView.scaleType = PreviewView.ScaleType.FILL_CENTER

        setupMap()
        bindInitialRoute(liveRoutingViewModel.restoreOrInitialize(state))
        rebuildActiveOpticalFlow()
        prepareCameraPanelFromState()
        requestLocationPermissionIfNeeded()
    }

    override fun FragmentLiveRoutingBinding.initListener() {
        ivBack.setSingleClick { onBack() }
        ivCameraViewClose.setSingleClick {
            liveRoutingViewModel.dismissCameraPanel()
            hideCameraPanel(animated = true, showHandle = true)
        }
        ivShowCameraView.setSingleClick {
            liveRoutingViewModel.requestCameraPanel()
            showCameraPanel(animated = true)
        }
        btnKLT.setSingleClick {
            setActiveOpticalMode(OpticalMode.KLT)
        }
        btnFBVector.setSingleClick {
            setActiveOpticalMode(OpticalMode.FARNEBACK_VECTOR)
        }
        btnFBHeatmap.setSingleClick {
            setActiveOpticalMode(OpticalMode.FARNEBACK_HEATMAP)
        }
    }

    override fun initObserver() = Unit

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (::imuEstimator.isInitialized) imuEstimator.register()
        if (hasLocationPermission()) startLocationUpdates()
        startTicker()
        startTestDropoutTicker()
        if (liveRoutingViewModel.cameraPanelVisible || liveRoutingViewModel.gnssAssistActive) {
            startAssistCameraIfReady()
        }
    }

    override fun onPause() {
        super.onPause()
        tickerJob?.cancel()
        tickerJob = null
        testDropoutJob?.cancel()
        testDropoutJob = null
        stopLocationUpdates()
        stopCamera()
        if (::imuEstimator.isInitialized) imuEstimator.unregister()
        binding.mapView.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation || recreateForOrientationChange) return

        lastOrientation = newConfig.orientation
        recreateForOrientationChange = true
        binding.root.post {
            if (isAdded) requireActivity().recreate()
        }
    }

    override fun onBack() {
        liveRoutingViewModel.clearRoute()
        mainViewModel.liveRouteState = null
        mainViewModel.resetGnssViewerRouteOnResume = true
        restorePortrait()
        super.onBack()
    }

    override fun onDestroyView() {
        if (!recreateForOrientationChange) {
            restorePortrait()
        }
        stopCamera()
        stopLocationUpdates()
        tickerJob?.cancel()
        testDropoutJob?.cancel()
        if (::imuEstimator.isInitialized) imuEstimator.unregister()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroyView()
    }

    private fun unlockOrientationForLiveRouting() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        orientationUnlocked = true
    }

    private fun restorePortrait() {
        if (!orientationUnlocked) return
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        orientationUnlocked = false
    }

    private fun setupMap() {
        val ctx = safeContext()
        OsmConfiguration.getInstance().userAgentValue = ctx.packageName
        OsmConfiguration.getInstance().load(
            requireActivity().applicationContext,
            ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.setBuiltInZoomControls(false)
        binding.mapView.controller.setZoom(18.0)
    }

    private fun bindInitialRoute(initialRoute: LiveRoutingViewModel.InitialRouteUi) {
        drawRouteLine(initialRoute.routePoints)
        updateTargetMarker(initialRoute.destinationPoint)
        applyNavigationSnapshot(initialRoute.navigation)

        binding.mapView.post {
            binding.mapView.controller.setZoom(18.5)
            binding.mapView.controller.animateTo(initialRoute.navigation.point)
        }
    }

    private fun drawRouteLine(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (routeLine == null) {
            routeLine = Polyline(binding.mapView).apply {
                outlinePaint.color = Color.rgb(123, 92, 255)
                outlinePaint.strokeWidth = 9f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            binding.mapView.overlays.add(0, routeLine)
        }
        routeLine?.setPoints(points)
        binding.mapView.invalidate()
    }

    private fun drawOpticalAssistLines(segments: List<List<GeoPoint>>) {
        drawPathSegments(
            segments = segments,
            lines = opticalAssistLines,
            color = Color.rgb(255, 40, 60),
            strokeWidth = 8f
        )
    }

    private fun drawGnssTravelLines(segments: List<List<GeoPoint>>) {
        drawPathSegments(
            segments = segments,
            lines = gnssTravelLines,
            color = Color.BLACK,
            strokeWidth = 8f
        )
    }

    private fun drawTestGnssLines(segments: List<List<GeoPoint>>) {
        drawPathSegments(
            segments = segments,
            lines = testGnssLines,
            color = Color.rgb(0, 122, 255),
            strokeWidth = 8f
        )
    }

    private fun drawPathSegments(
        segments: List<List<GeoPoint>>,
        lines: ArrayList<Polyline>,
        color: Int,
        strokeWidth: Float
    ) {
        val visibleSegments = segments.filter { it.size >= 2 }
        while (lines.size > visibleSegments.size) {
            binding.mapView.overlays.remove(lines.removeAt(lines.lastIndex))
        }
        visibleSegments.forEachIndexed { index, segment ->
            val line = lines.getOrNull(index) ?: Polyline(binding.mapView).apply {
                outlinePaint.color = color
                outlinePaint.strokeWidth = strokeWidth
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                lines.add(this)
                binding.mapView.overlays.add(this)
            }
            line.setPoints(segment)
        }
        binding.mapView.invalidate()
    }

    private fun updateTargetMarker(point: GeoPoint) {
        if (targetMarker == null) {
            targetMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = buildMarkerIcon(R.drawable.ic_target_location, 46)
                title = shortPlaceName(
                    liveRoutingViewModel.routeState?.destination?.name ?: "Destination"
                )
            }
            binding.mapView.overlays.add(targetMarker)
        }
        targetMarker?.position = point
    }

    private fun applyNavigationSnapshot(snapshot: LiveRoutingViewModel.NavigationSnapshot) {
        snapshot.remainingRoutePoints?.let(::drawRouteLine)
        snapshot.gnssTravelPathSegments?.let(::drawGnssTravelLines)
        snapshot.testGnssPathSegments?.let(::drawTestGnssLines)
        snapshot.opticalAssistSegments?.let(::drawOpticalAssistLines)
        updateNavigationMarker(snapshot.point, snapshot.headingDeg)
        updateSpeedText(snapshot.speedMps)
    }

    private fun updateNavigationMarker(point: GeoPoint, headingDeg: Double) {
        if (navigationMarker == null) {
            navigationMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = buildMarkerIcon(R.drawable.ic_arrow_navigation, 46)
                title = "Navigation"
            }
            binding.mapView.overlays.add(navigationMarker)
        }

        navigationMarker?.position = point
        navigationMarker?.rotation = 0f
        binding.mapView.mapOrientation = (-headingDeg).toFloat()
        binding.mapView.controller.setCenter(point)
        binding.mapView.invalidate()
    }

    private fun buildMarkerIcon(drawableRes: Int, sizeDp: Int) = context?.let { ctx ->
        getDrawable(ctx, drawableRes)?.let { drawable ->
            val sizePx = sizeDp.dp
            val bitmap = createBitmap(sizePx, sizePx)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.toDrawable(ctx.resources)
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (hasLocationPermission()) {
            startLocationUpdates()
            return
        }

        doRequestPermission(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            object : IPermissionListener {
                override fun onAllow() = startLocationUpdates()
                override fun onDenied() {
                    Toast.makeText(safeContext(), "Location permission is required", Toast.LENGTH_LONG).show()
                }
                override fun onNeverAskAgain(permission: String) {
                    Toast.makeText(safeContext(), "Enable location permission in settings", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun hasLocationPermission(): Boolean {
        if (Constants.USE_FAKE_LOCATION) return true
        return ActivityCompat.checkSelfPermission(
            safeContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                safeContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (!::locationManager.isInitialized) {
            locationManager = safeContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }

        if (Constants.USE_FAKE_LOCATION) {
            mainViewModel.getEffectiveLocation(null)?.let(::handleLocationUpdate)
            return
        }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LiveRoutingViewModel.LOCATION_UPDATE_MS,
                0.5f,
                locationListener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LiveRoutingViewModel.LOCATION_UPDATE_MS,
                0.5f,
                locationListener
            )
            locationManager.registerGnssStatusCallback(safeContext().mainExecutor, gnssStatusCallback)
        }.onFailure {
            Log.e(TAG, "Location setup failed: ${it.message}", it)
        }

        val lastKnown = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        lastKnown?.let { handleLocationUpdate(mainViewModel.getEffectiveLocation(it) ?: it) }
    }

    private fun stopLocationUpdates() {
        if (!::locationManager.isInitialized) return
        runCatching {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val result = liveRoutingViewModel.onLocationUpdate(location)
        if (result.accepted) {
            mainViewModel.postCurrentLocation(location)
            mainViewModel.postCurrentTime(location.time)
        }
        result.testGnssPathSegments?.let(::drawTestGnssLines)
        result.navigation?.let(::applyNavigationSnapshot)
        applyAssistDecision(result.assistDecision)
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return

        tickerJob = viewLifecycleOwner.lifecycleScope.launch {
            lastTickMs = System.currentTimeMillis()
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                val dtSec = ((nowMs - lastTickMs).coerceIn(1L, 500L)) / 1000.0
                lastTickMs = nowMs

                val result = liveRoutingViewModel.onTick(
                    nowMs = nowMs,
                    dtSec = dtSec,
                    yawRateDegPerSec = imuEstimator.getYawRate().toDouble()
                )
                applyAssistDecision(result.assistDecision)
                result.navigation?.let(::applyNavigationSnapshot) ?: updateSpeedText(result.speedMps)
                delay(LiveRoutingViewModel.TICK_MS)
            }
        }
    }

    private fun startTestDropoutTicker() {
        if (!LiveRoutingViewModel.TEST_GNSS_DROPOUT || testDropoutJob?.isActive == true) return

        testDropoutJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(LiveRoutingViewModel.TEST_GNSS_DROPOUT_INTERVAL_MS)
                applyAssistDecision(liveRoutingViewModel.toggleTestGnssDropout())
            }
        }
    }

    private fun applyAssistDecision(decision: LiveRoutingViewModel.AssistDecision) {
        if (!decision.changed) return

        if (decision.active) {
            startAssistCameraIfReady()
            renderCameraPanelState(animated = true)
        } else {
            renderCameraPanelState(animated = true)
            stopCamera()
        }
    }

    private fun prepareCameraPanelFromState() {
        binding.cameraViewGroup.post {
            renderCameraPanelState(animated = false)
        }
    }

    private fun renderCameraPanelState(animated: Boolean) {
        val panelState = liveRoutingViewModel.cameraPanelState()
        when {
            panelState.visible -> showCameraPanel(animated = animated)
            else -> hideCameraPanel(animated = animated, showHandle = panelState.showHandle)
        }
    }

    private fun showCameraPanel(animated: Boolean) {
        startAssistCameraIfReady()
        binding.cameraViewGroup.visibility = View.VISIBLE
        binding.cameraViewGroup.animate().cancel()
        binding.ivShowCameraView.animate().cancel()
        if (animated) {
            binding.cameraViewGroup.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(CAMERA_PANEL_ANIM_MS)
                .start()
            binding.ivShowCameraView.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(CAMERA_PANEL_ANIM_MS)
                .withEndAction {
                    binding.ivShowCameraView.visibility = View.GONE
                }
                .start()
        } else {
            binding.cameraViewGroup.translationX = 0f
            binding.cameraViewGroup.translationY = 0f
            binding.ivShowCameraView.translationX = 0f
            binding.ivShowCameraView.translationY = 0f
            binding.ivShowCameraView.visibility = View.GONE
        }
    }

    private fun hideCameraPanel(animated: Boolean, showHandle: Boolean) {
        val hiddenX = if (isLandscape()) binding.cameraViewGroup.width.toFloat() else 0f
        val hiddenY = if (isLandscape()) 0f else binding.cameraViewGroup.height.toFloat()
        if (hiddenX <= 0f && hiddenY <= 0f) {
            binding.cameraViewGroup.post { hideCameraPanel(animated = false, showHandle = showHandle) }
            return
        }

        binding.ivShowCameraView.visibility = if (showHandle) View.VISIBLE else View.GONE
        binding.cameraViewGroup.animate().cancel()
        binding.ivShowCameraView.animate().cancel()
        if (animated) {
            binding.cameraViewGroup.animate()
                .translationX(hiddenX)
                .translationY(hiddenY)
                .setDuration(CAMERA_PANEL_ANIM_MS)
                .start()
            if (showHandle) {
                binding.ivShowCameraView.animate()
                    .translationX(hiddenX)
                    .translationY(hiddenY)
                    .setDuration(CAMERA_PANEL_ANIM_MS)
                    .start()
            } else {
                binding.ivShowCameraView.translationX = hiddenX
                binding.ivShowCameraView.translationY = hiddenY
            }
        } else {
            binding.cameraViewGroup.translationX = hiddenX
            binding.cameraViewGroup.translationY = hiddenY
            binding.ivShowCameraView.translationX = hiddenX
            binding.ivShowCameraView.translationY = hiddenY
        }
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun setActiveOpticalMode(mode: OpticalMode) {
        liveRoutingViewModel.setActiveOpticalMode(mode)
        opticalFlow = createOpticalFlow(mode)
        applyOpticalModeUi()
    }

    private fun rebuildActiveOpticalFlow() {
        opticalFlow = createOpticalFlow(liveRoutingViewModel.activeOpticalMode)
        applyOpticalModeUi()
    }

    private fun createOpticalFlow(mode: OpticalMode): OpticalFlow {
        val flow: OpticalFlow = when (mode) {
            OpticalMode.KLT -> KLT()
            OpticalMode.FARNEBACK_VECTOR -> Farneback().apply {
                setVisualizationMode(Farneback.VisualizationMode.VECTORS)
            }
            OpticalMode.FARNEBACK_HEATMAP -> Farneback().apply {
                setVisualizationMode(Farneback.VisualizationMode.HEATMAP)
            }
        }
        flow.setMovingMode(true)
        flow.setSensitivity(LiveRoutingViewModel.FLOW_SENSITIVITY)
        return flow
    }

    private fun applyOpticalModeUi() = with(binding) {
        setModeButtonSelected(btnKLT, liveRoutingViewModel.activeOpticalMode == OpticalMode.KLT)
        setModeButtonSelected(btnFBVector, liveRoutingViewModel.activeOpticalMode == OpticalMode.FARNEBACK_VECTOR)
        setModeButtonSelected(btnFBHeatmap, liveRoutingViewModel.activeOpticalMode == OpticalMode.FARNEBACK_HEATMAP)
    }

    private fun setModeButtonSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_gradient_update_button_12 else R.drawable.bg_glass_chip
        )
        view.alpha = if (selected) 1f else 0.82f
    }

    private fun startAssistCameraIfReady() {
        if (cameraProvider != null) return
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        startCamera()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            safeContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        if (cameraPermissionRequestInFlight) return
        cameraPermissionRequestInFlight = true
        doRequestPermission(
            arrayOf(Manifest.permission.CAMERA),
            object : IPermissionListener {
                override fun onAllow() {
                    cameraPermissionRequestInFlight = false
                    startCamera()
                }

                override fun onDenied() {
                    cameraPermissionRequestInFlight = false
                    Toast.makeText(safeContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
                }

                override fun onNeverAskAgain(permission: String) {
                    cameraPermissionRequestInFlight = false
                    Toast.makeText(safeContext(), "Enable camera permission in settings", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startCamera() {
        if (!::cameraExecutor.isInitialized || !hasCameraPermission()) return

        val context = safeContext()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isAdded || view == null) return@addListener
            val provider = future.get()
            cameraProvider = provider

            if (opticalFlow == null) {
                opticalFlow = createOpticalFlow(liveRoutingViewModel.activeOpticalMode)
            }

            val rotation = binding.cameraView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = binding.cameraView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFlowFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Bind camera failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        overlayBitmap = null
        if (isAdded && view != null) {
            binding.cameraOutputOverlay.setImageDrawable(null)
        }
    }

    private fun analyzeFlowFrame(imageProxy: ImageProxy) {
        var rgba: Mat? = null
        var processing: Mat? = null
        try {
            rgba = imageProxyToRgbaMat(imageProxy)
            processing = rotateFrameForDisplay(rgba, imageProxy.imageInfo.rotationDegrees)
            processFlowFrame(processing)
        } catch (e: Exception) {
            Log.e(TAG, "Flow frame failed: ${e.message}", e)
        } finally {
            if (processing !== rgba) processing?.release()
            rgba?.release()
            imageProxy.close()
        }
    }

    private fun processFlowFrame(frame: Mat) {
        val flow = opticalFlow ?: return
        val nowMs = System.currentTimeMillis()

        if (liveRoutingViewModel.onFlowFrameStarted()) {
            flow.updateFeatures()
        }

        val output = flow.run(frame) ?: return
        output.metrics?.let { metrics ->
            liveRoutingViewModel.onOpticalMetrics(metrics, nowMs)
        }

        output.ofFrame?.let { outFrame ->
            if (outFrame.empty()) return@let
            makeFrameOpaque(outFrame)
            val bitmap = getOrCreateOverlayBitmap(outFrame)
            Utils.matToBitmap(outFrame, bitmap)
            activity?.runOnUiThread {
                if (isAdded && view != null) {
                    binding.cameraOutputOverlay.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun getOrCreateOverlayBitmap(mat: Mat): Bitmap {
        val current = overlayBitmap
        if (current != null && current.width == mat.cols() && current.height == mat.rows()) {
            return current
        }
        return createBitmap(mat.cols(), mat.rows()).also { overlayBitmap = it }
    }

    private fun makeFrameOpaque(frame: Mat) {
        if (frame.channels() < 4) return
        val channels = mutableListOf<Mat>()
        Core.split(frame, channels)
        if (channels.size >= 4) {
            channels[3].setTo(Scalar(255.0))
            Core.merge(channels, frame)
        }
        channels.forEach { it.release() }
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

        return Mat(height, width, CvType.CV_8UC4).apply { put(0, 0, rgbaBytes) }
    }

    private fun rotateFrameForDisplay(frame: Mat, rotationDegrees: Int): Mat {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return frame
        val rotated = Mat()
        when (normalized) {
            90 -> Core.rotate(frame, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(frame, rotated, Core.ROTATE_180)
            270 -> Core.rotate(frame, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> frame.copyTo(rotated)
        }
        return rotated
    }

    private fun updateSpeedText(speedMps: Double) {
        binding.tvSpeed.text = String.format(Locale.US, "%.1fkm/h", speedMps.coerceAtLeast(0.0) * 3.6)
    }

    private fun shortPlaceName(name: String): String {
        return name.substringBefore(",").trim().ifBlank { name }
    }

    private companion object {
        const val CAMERA_PANEL_ANIM_MS = 260L
    }
}
