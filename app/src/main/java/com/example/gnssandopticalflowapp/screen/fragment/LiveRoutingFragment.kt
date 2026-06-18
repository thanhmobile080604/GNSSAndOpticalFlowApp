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
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import com.example.gnssandopticalflowapp.common.AndroidConnectivityObserver
import com.example.gnssandopticalflowapp.common.Constants
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentLiveRoutingBinding
import com.example.gnssandopticalflowapp.function.gnss.MapRepository
import com.example.gnssandopticalflowapp.function.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.function.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.function.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.model.RouteInfo
import com.example.gnssandopticalflowapp.util.RouteDebugLogger
import com.example.gnssandopticalflowapp.util.RouteStorageUtil
import com.example.gnssandopticalflowapp.screen.viewmodel.LiveRoutingViewModel
import com.example.gnssandopticalflowapp.screen.viewmodel.LiveRoutingViewModel.OpticalMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

class LiveRoutingFragment :
    BaseFragment<FragmentLiveRoutingBinding>(FragmentLiveRoutingBinding::inflate) {

    private val liveRoutingViewModel: LiveRoutingViewModel by activityViewModels()
    private val mapRepository by lazy {
        MapRepository(requireContext().applicationContext)
    }
    private val connectivityObserver by lazy {
        AndroidConnectivityObserver(requireContext().applicationContext)
    }

    private lateinit var locationManager: LocationManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imuEstimator: IMUEstimator
    private val routeDebugLogger = RouteDebugLogger()

    private var navigationMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val gnssTravelLines = ArrayList<Polyline>()
    private val opticalAssistLines = ArrayList<Polyline>()
    private val weakMarkers = ArrayList<Marker>()
    private val strongMarkers = ArrayList<Marker>()

    private var tickerJob: Job? = null
    private var testDropoutJob: Job? = null
    private var routeRefreshJob: Job? = null
    private var connectivityJob: Job? = null
    private var lastTickMs = 0L
    private var lastOfflineRouteToastMs = 0L
    private var hasInternetConnection = true
    private var isFollowingNavigation = true
    private var lastNavPoint: GeoPoint? = null
    private var lastNavHeadingDeg = 0.0
    private var lastNavSpeedMps = 0.0
    // Render-side heading smoothing state (see updateNavigationMarker / advanceDisplayHeading).
    private var displayHeadingDeg = 0.0
    private var hasDisplayHeading = false
    private var lastDisplayTickMs = 0L
    private var lastSatellitesInFix = 0

    private var opticalFlow: OpticalFlow? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var overlayBitmap: Bitmap? = null
    private var cameraPermissionRequestInFlight = false
    private var locationUpdatesActive = false
    private var gnssStatusRegistered = false

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
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            lastSatellitesInFix = used
            applyAssistDecision(liveRoutingViewModel.onGnssStatusChanged(status.satelliteCount))
            updateGnssStrengthText()
        }
    }

    override fun FragmentLiveRoutingBinding.initView() {
        val state = mainViewModel.liveRouteState
        if (state == null) {
            showToast(MESSAGE_NO_ACTIVE_ROUTE)
            root.post { onBack() }
            return
        }

        unlockOrientationForLiveRouting()
        lastOrientation = resources.configuration.orientation

        if (Constants.DEBUG_ROUTE_LOG) {
            routeDebugLogger.start(safeContext())
            showToast("Debug: logging route to Android/data/${safeContext().packageName}/files", Toast.LENGTH_LONG)
        }

        initializeRuntimeDependencies()
        setupMap()
        bindInitialRoute(liveRoutingViewModel.restoreOrInitialize(state))
        rebuildActiveOpticalFlow()
        renderTestModeButton()
        updateGnssStrengthText()
        prepareCameraPanelFromState()
        ensureLocationPermission()
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
        btnTestMode.setSingleClick { onTestModeTapped() }
        ivRecenter.setSingleClick { setFollowing(true) }
    }

    override fun initObserver() = Unit

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (::imuEstimator.isInitialized) imuEstimator.register()
        startLiveRuntime()
        // Camera runs the whole time in live routing so the speed scale (vs GNSS) and yaw scale
        // (vs gyro) keep calibrating even while GNSS is healthy — ready the moment GNSS drops.
        ensureAssistCameraStarted()
    }

    override fun onPause() {
        super.onPause()
        stopLiveRuntime()
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
        saveSessionIfAny()
        liveRoutingViewModel.clearRoute()
        mainViewModel.liveRouteState = null
        mainViewModel.resetGnssViewerRouteOnResume = true
        restorePortrait()
        super.onBack()
    }

    /** Persist the just-finished session (red/black paths + pins) so it can be replayed later. */
    private fun saveSessionIfAny() {
        val session = liveRoutingViewModel.exportSession() ?: return
        runCatching {
            RouteStorageUtil.saveSession(safeContext().applicationContext, session)
            mainViewModel.routeLibraryUpdated.postValue(System.currentTimeMillis())
        }.onFailure { Log.e(TAG, "Save route session failed: ${it.message}", it) }
    }

    override fun onDestroyView() {
        if (!recreateForOrientationChange) {
            restorePortrait()
        }
        stopLiveRuntime()
        if (Constants.DEBUG_ROUTE_LOG) routeDebugLogger.stop()
        if (::imuEstimator.isInitialized) imuEstimator.unregister()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroyView()
    }

    private fun initializeRuntimeDependencies() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        imuEstimator = IMUEstimator(safeContext().applicationContext)
        binding.cameraView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private fun startLiveRuntime() {
        startConnectivityObserver()
        if (hasLocationPermission()) startLocationUpdates()
        startNavigationTicker()
        startTestDropoutTicker()
    }

    private fun stopLiveRuntime() {
        tickerJob = tickerJob.cancelAndClear()
        testDropoutJob = testDropoutJob.cancelAndClear()
        routeRefreshJob = routeRefreshJob.cancelAndClear()
        stopConnectivityObserver()
        stopLocationUpdates()
        stopCamera()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        val ctx = safeContext()
        OsmConfiguration.getInstance().userAgentValue = ctx.packageName
        OsmConfiguration.getInstance().load(
            requireActivity().applicationContext,
            ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        // Two-finger rotation, plus a touch hook that drops auto-follow the moment the user drags or
        // rotates the map (programmatic recenters don't fire touch events, so following stays intact).
        binding.mapView.overlays.add(RotationGestureOverlay(binding.mapView).apply { isEnabled = true })
        binding.mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) setFollowing(false)
            false
        }
        binding.mapView.controller.setZoom(18.0)
    }

    private fun setFollowing(follow: Boolean) {
        if (isFollowingNavigation == follow) return
        isFollowingNavigation = follow
        binding.ivRecenter.visibility = if (follow) View.GONE else View.VISIBLE
        if (follow) {
            lastNavPoint?.let { point ->
                // Snap follow to the currently rendered (smoothed) heading, not the raw target, so the
                // recenter does not jump.
                binding.mapView.mapOrientation = (-displayHeadingDeg).toFloat()
                navigationMarker?.rotation = displayHeadingDeg.toFloat()
                binding.mapView.controller.animateTo(point)
                binding.mapView.invalidate()
            }
        }
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
            alpha = 255,
            strokeWidth = 8f
        )
    }

    private fun drawWeakMarkers(points: List<GeoPoint>) {
        drawMarkers(points, weakMarkers, R.drawable.ic_weak, "GNSS Lost")
    }

    private fun drawStrongMarkers(points: List<GeoPoint>) {
        drawMarkers(points, strongMarkers, R.drawable.ic_strong, "GNSS Restored")
    }

    private fun drawMarkers(
        points: List<GeoPoint>,
        markers: ArrayList<Marker>,
        iconRes: Int,
        titleText: String
    ) {
        while (markers.size > points.size) {
            binding.mapView.overlays.remove(markers.removeAt(markers.lastIndex))
        }
        points.forEachIndexed { index, point ->
            val marker = markers.getOrNull(index) ?: Marker(binding.mapView).apply {
                setAnchor(0.2f, 0.85f)
                icon = buildMarkerIcon(iconRes, 24)
                title = titleText
                markers.add(this)
                binding.mapView.overlays.add(this)
            }
            marker.position = point
        }
        binding.mapView.invalidate()
    }

    private fun drawPathSegments(
        segments: List<List<GeoPoint>>,
        lines: ArrayList<Polyline>,
        color: Int,
        alpha: Int = 255,
        strokeWidth: Float
    ) {
        val visibleSegments = segments.filter { it.size >= 2 }
        while (lines.size > visibleSegments.size) {
            binding.mapView.overlays.remove(lines.removeAt(lines.lastIndex))
        }
        visibleSegments.forEachIndexed { index, segment ->
            val line = lines.getOrNull(index) ?: Polyline(binding.mapView).apply {
                outlinePaint.color = color
                outlinePaint.alpha = alpha
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
        snapshot.weakGnssPoints?.let(::drawWeakMarkers)
        snapshot.strongGnssPoints?.let(::drawStrongMarkers)
        snapshot.opticalAssistSegments?.let(::drawOpticalAssistLines)
        lastNavSpeedMps = snapshot.speedMps
        updateNavigationMarker(snapshot.point, snapshot.headingDeg)
        updateSpeedText(snapshot.speedMps)
    }

    private fun updateNavigationMarker(point: GeoPoint, headingDeg: Double) {
        if (navigationMarker == null) {
            navigationMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = buildMarkerIcon(R.drawable.ic_arrow_navigation, 46)
                title = "Navigation"
                isFlat = true
            }
            binding.mapView.overlays.add(navigationMarker)
        }

        val pointChanged = lastNavPoint?.let {
            it.latitude != point.latitude || it.longitude != point.longitude
        } ?: true
        lastNavPoint = point
        lastNavHeadingDeg = headingDeg
        navigationMarker?.position = point

        val rotationChanged = advanceDisplayHeading(headingDeg)
        if (rotationChanged || pointChanged) {
            navigationMarker?.rotation = displayHeadingDeg.toFloat()
            if (isFollowingNavigation) {
                binding.mapView.mapOrientation = (-displayHeadingDeg).toFloat()
                binding.mapView.controller.setCenter(point)
            }
            binding.mapView.invalidate()
        }
    }

    private fun advanceDisplayHeading(targetDeg: Double): Boolean {
        if (!hasDisplayHeading) {
            displayHeadingDeg = normalizeHeading(targetDeg)
            hasDisplayHeading = true
            lastDisplayTickMs = System.currentTimeMillis()
            return true
        }
        val now = System.currentTimeMillis()
        val dtSec = ((now - lastDisplayTickMs).coerceIn(1L, 500L)) / 1000.0
        lastDisplayTickMs = now

        var delta = shortestHeadingDelta(displayHeadingDeg, targetDeg)
        val maxStep = maxYawRateForSpeed(lastNavSpeedMps) * dtSec
        delta = delta.coerceIn(-maxStep, maxStep)
        delta *= (1.0 - exp(-DISPLAY_SMOOTH_SPEED * dtSec))
        if (abs(delta) < DISPLAY_DEADBAND_DEG) return false

        displayHeadingDeg = normalizeHeading(displayHeadingDeg + delta)
        return true
    }

    /** Physically-plausible max heading-change rate (deg/s): yaw rate r = a_lat / v, clamped. */
    private fun maxYawRateForSpeed(speedMps: Double): Double {
        return (DEG_PER_RAD * HEADING_SLEW_A_LAT_MPS2 / max(speedMps, HEADING_SLEW_MIN_SPEED_MPS))
            .coerceIn(HEADING_SLEW_MIN_DEG_SEC, HEADING_SLEW_MAX_DEG_SEC)
    }

    private fun shortestHeadingDelta(fromDeg: Double, toDeg: Double): Double {
        var delta = (toDeg - fromDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private fun normalizeHeading(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
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

    private fun ensureLocationPermission() {
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
                    showToast(MESSAGE_LOCATION_PERMISSION_REQUIRED, Toast.LENGTH_LONG)
                }
                override fun onNeverAskAgain(permission: String) {
                    showToast(MESSAGE_LOCATION_PERMISSION_SETTINGS, Toast.LENGTH_LONG)
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
        if (locationUpdatesActive) return

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
        }.onFailure {
            Log.e(TAG, "Location setup failed: ${it.message}", it)
            return
        }
        locationUpdatesActive = true

        gnssStatusRegistered = runCatching {
            locationManager.registerGnssStatusCallback(safeContext().mainExecutor, gnssStatusCallback)
        }.getOrElse {
            Log.e(TAG, "GNSS status setup failed: ${it.message}", it)
            false
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
            if (locationUpdatesActive) {
                locationManager.removeUpdates(locationListener)
            }
            if (gnssStatusRegistered) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            }
        }.onFailure {
            Log.e(TAG, "Location cleanup failed: ${it.message}", it)
        }.also {
            locationUpdatesActive = false
            gnssStatusRegistered = false
        }
    }

    private fun handleLocationUpdate(location: Location) {
        if (Constants.DEBUG_ROUTE_LOG) {
            // Log the raw true fix BEFORE the view-model can reject it (e.g. during the dropout test),
            // so the ground-truth track stays continuous.
            routeDebugLogger.logGnss(
                timeMs = System.currentTimeMillis(),
                lat = location.latitude,
                lon = location.longitude,
                headingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else Double.NaN
            )
        }
        val result = liveRoutingViewModel.onLocationUpdate(location)
        if (result.accepted) {
            mainViewModel.postCurrentLocation(location)
            mainViewModel.postCurrentTime(location.time)
        }
        result.navigation?.let(::applyNavigationSnapshot)
        applyAssistDecision(result.assistDecision)
        if (result.accepted) {
            refreshRouteAfterLocationChange()
        }
    }

    private fun refreshRouteAfterLocationChange() {
        if (routeRefreshJob?.isActive == true) return
        if (!liveRoutingViewModel.updateRouteDeviation()) return

        val origin = liveRoutingViewModel.currentRouteOrigin ?: return
        val destination = liveRoutingViewModel.destinationPoint ?: return
        if (!hasInternetConnection) {
            showOfflineRouteToast()
            return
        }

        liveRoutingViewModel.markRouteRefreshStarted()
        routeRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val route = fetchRoute(origin, destination)

            if (!isAdded || view == null) return@launch
            applyRouteRefresh(route)
        }
    }

    private suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint): RouteInfo? {
        return withContext(Dispatchers.IO) {
            runCatching {
                mapRepository.fetchRoute(origin, destination)
            }.getOrNull()
        }
    }

    private fun applyRouteRefresh(route: RouteInfo?) {
        if (route == null || route.points.size < 2) {
            showToast(MESSAGE_ROUTE_REFRESH_FAILED)
            return
        }

        val navigation = liveRoutingViewModel.applyRoute(route) ?: return
        mainViewModel.liveRouteState = liveRoutingViewModel.routeState
        applyNavigationSnapshot(navigation)
        showToast(MESSAGE_ROUTE_UPDATED)
    }

    private fun showOfflineRouteToast() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastOfflineRouteToastMs < OFFLINE_ROUTE_TOAST_COOLDOWN_MS) return

        lastOfflineRouteToastMs = nowMs
        showToast(MESSAGE_ROUTE_REFRESH_OFFLINE)
    }

    private fun startConnectivityObserver() {
        if (connectivityJob?.isActive == true) return

        connectivityJob = viewLifecycleOwner.lifecycleScope.launch {
            connectivityObserver.isConnected.collect { isConnected ->
                hasInternetConnection = isConnected
            }
        }
    }

    private fun stopConnectivityObserver() {
        connectivityJob?.cancel()
        connectivityJob = null
    }

    private fun startNavigationTicker() {
        if (tickerJob?.isActive == true) return

        tickerJob = viewLifecycleOwner.lifecycleScope.launch {
            lastTickMs = System.currentTimeMillis()
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                val dtSec = ((nowMs - lastTickMs).coerceIn(1L, 500L)) / 1000.0
                lastTickMs = nowMs

                // Re-learn the gyro zero-rate bias when confidently stopped OR cruising straight (true
                // yaw ~ 0), so getYawRate() stays drift-free going into the next GNSS outage — including
                // on a drive that starts already moving and never sits still.
                if (liveRoutingViewModel.isStationaryForBias(nowMs) ||
                    liveRoutingViewModel.isStraightLineForBias(nowMs)
                ) {
                    imuEstimator.learnGyroBias()
                }

                val result = liveRoutingViewModel.onTick(
                    nowMs = nowMs,
                    dtSec = dtSec,
                    yawRateDegPerSec = imuEstimator.getYawRate().toDouble(),
                    horizontalAccelDevice = imuEstimator.getHorizontalLinearAcceleration()
                )
                applyAssistDecision(result.assistDecision)
                result.navigation?.let { navigation ->
                    applyNavigationSnapshot(navigation)
                    // The tick now emits a snapshot every cycle (to rotate the chevron smoothly), but
                    // under healthy GNSS the route refresh is already driven by handleLocationUpdate at
                    // the fix rate — only the dead-reckoning (outage) path needs to drive it here.
                    if (liveRoutingViewModel.gnssAssistActive) {
                        if (Constants.DEBUG_ROUTE_LOG) {
                            routeDebugLogger.logDeadReckon(
                                timeMs = nowMs,
                                lat = navigation.point.latitude,
                                lon = navigation.point.longitude,
                                headingDeg = navigation.headingDeg,
                                speedMps = navigation.speedMps,
                                routeLocked = liveRoutingViewModel.isRouteLocked
                            )
                        }
                        refreshRouteAfterLocationChange()
                    }
                } ?: updateSpeedText(result.speedMps)
                delay(LiveRoutingViewModel.TICK_MS.milliseconds)
            }
        }
    }

    private fun startTestDropoutTicker() {
        if (liveRoutingViewModel.testMode != LiveRoutingViewModel.TestMode.GNSS_DROPOUT) return
        if (testDropoutJob?.isActive == true) return

        testDropoutJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                applyAssistDecision(liveRoutingViewModel.setTestGnssSuppressed(true))
                delay(LiveRoutingViewModel.TEST_GNSS_ABSENT_MS.milliseconds)

                applyAssistDecision(liveRoutingViewModel.setTestGnssSuppressed(false))
                delay(LiveRoutingViewModel.TEST_GNSS_PRESENT_MS.milliseconds)
            }
        }
    }

    private fun applyAssistDecision(decision: LiveRoutingViewModel.AssistDecision) {
        if (!decision.changed) return
        ensureAssistCameraStarted()
        updateGnssStrengthText()
    }

    private fun updateGnssStrengthText() {
        if (!isAdded || view == null) return
        val label = when {
            liveRoutingViewModel.gnssAssistActive -> "weak"
            lastSatellitesInFix >= STRONG_FIX_SATELLITES -> "strong"
            else -> "medium"
        }
        binding.tvGnssStatus.text = "GNSS: $label"
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
        ensureAssistCameraStarted()
        binding.cameraViewGroup.visibility = View.VISIBLE
        binding.cameraViewGroup.animate().cancel()
        binding.ivShowCameraView.animate().cancel()
        binding.ivRecenter.animate().cancel()
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
            binding.ivRecenter.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(CAMERA_PANEL_ANIM_MS)
                .start()
        } else {
            binding.cameraViewGroup.translationX = 0f
            binding.cameraViewGroup.translationY = 0f
            binding.ivShowCameraView.translationX = 0f
            binding.ivShowCameraView.translationY = 0f
            binding.ivShowCameraView.visibility = View.GONE
            binding.ivRecenter.translationX = 0f
            binding.ivRecenter.translationY = 0f
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
        binding.ivRecenter.animate().cancel()
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
            binding.ivRecenter.animate()
                .translationX(hiddenX)
                .translationY(hiddenY)
                .setDuration(CAMERA_PANEL_ANIM_MS)
                .start()
        } else {
            binding.cameraViewGroup.translationX = hiddenX
            binding.cameraViewGroup.translationY = hiddenY
            binding.ivShowCameraView.translationX = hiddenX
            binding.ivShowCameraView.translationY = hiddenY
            binding.ivRecenter.translationX = hiddenX
            binding.ivRecenter.translationY = hiddenY
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
        }
        flow.setMovingMode(true)
        flow.setSensitivity(LiveRoutingViewModel.FLOW_SENSITIVITY)
        return flow
    }

    private fun applyOpticalModeUi() = with(binding) {
        setModeButtonSelected(btnKLT, liveRoutingViewModel.activeOpticalMode == OpticalMode.KLT)
        setModeButtonSelected(btnFBVector, liveRoutingViewModel.activeOpticalMode == OpticalMode.FARNEBACK_VECTOR)
    }

    private fun setModeButtonSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_gradient_update_button_12 else R.drawable.bg_glass_chip
        )
        view.alpha = if (selected) 1f else 0.82f
    }

    private fun ensureAssistCameraStarted() {
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
                    showToast(MESSAGE_CAMERA_PERMISSION_REQUIRED, Toast.LENGTH_LONG)
                }

                override fun onNeverAskAgain(permission: String) {
                    cameraPermissionRequestInFlight = false
                    showToast(MESSAGE_CAMERA_PERMISSION_SETTINGS, Toast.LENGTH_LONG)
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

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)

            applyStabilization(analysisBuilder)
            val analysis = analysisBuilder
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

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun applyStabilization(builder: ImageAnalysis.Builder) {
        runCatching {
            val extender = Camera2Interop.Extender(builder)
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )

            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }.onFailure {
            Log.e(TAG, "Configure camera stabilization failed: ${it.message}", it)
        }
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

    private fun onTestModeTapped() {
        val mode = liveRoutingViewModel.cycleTestMode()
        renderTestModeButton(mode)
        restartTestDropout()
    }

    private fun renderTestModeButton(mode: LiveRoutingViewModel.TestMode = liveRoutingViewModel.testMode) {
        binding.btnTestMode.text = when (mode) {
            LiveRoutingViewModel.TestMode.REAL_LIFE -> "REAL"
            LiveRoutingViewModel.TestMode.GNSS_DROPOUT -> "DROP"
        }
    }

    private fun restartTestDropout() {
        testDropoutJob = testDropoutJob.cancelAndClear()
        startTestDropoutTicker()
    }

    private fun shortPlaceName(name: String): String {
        return name.substringBefore(",").trim().ifBlank { name }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(safeContext(), message, duration).show()
    }

    private fun Job?.cancelAndClear(): Job? {
        this?.cancel()
        return null
    }

    private companion object {
        const val CAMERA_PANEL_ANIM_MS = 260L
        const val STRONG_FIX_SATELLITES = 7
        const val OFFLINE_ROUTE_TOAST_COOLDOWN_MS = 15_000L
        const val HEADING_SLEW_A_LAT_MPS2 = 7.0 // ~0.71g (motorbike cornering)
        const val HEADING_SLEW_MIN_SPEED_MPS = 1.0
        const val HEADING_SLEW_MIN_DEG_SEC = 6.0
        const val HEADING_SLEW_MAX_DEG_SEC = 120.0
        const val DISPLAY_SMOOTH_SPEED = 8.0 // tau ~0.125 s
        const val DISPLAY_DEADBAND_DEG = 1.0
        const val DEG_PER_RAD = 57.29577951308232
        const val MESSAGE_NO_ACTIVE_ROUTE = "No active route"
        const val MESSAGE_LOCATION_PERMISSION_REQUIRED = "Location permission is required"
        const val MESSAGE_LOCATION_PERMISSION_SETTINGS = "Enable location permission in settings"
        const val MESSAGE_ROUTE_REFRESH_FAILED = "Cannot update route. Keeping current route."
        const val MESSAGE_ROUTE_REFRESH_OFFLINE = "No internet. Keeping current route."
        const val MESSAGE_ROUTE_UPDATED = "Route updated"
        const val MESSAGE_CAMERA_PERMISSION_REQUIRED = "Camera permission is required"
        const val MESSAGE_CAMERA_PERMISSION_SETTINGS = "Enable camera permission in settings"
    }
}
