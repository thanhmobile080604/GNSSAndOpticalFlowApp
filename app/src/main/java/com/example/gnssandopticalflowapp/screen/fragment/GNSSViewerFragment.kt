package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentGnssViewerBinding
import com.example.gnssandopticalflowapp.gnss.EarthRenderer
import com.example.gnssandopticalflowapp.gnss.GnssSatellitePvtResolver
import com.example.gnssandopticalflowapp.gnss.SatelliteCalculator.calculateSatellitePositionFromEcef
import com.example.gnssandopticalflowapp.gnss.SatelliteCalculator.calculateSatellitePosition
import com.example.gnssandopticalflowapp.gnss.SatelliteCalculator.calculateSpeedFromEcefVelocity
import com.example.gnssandopticalflowapp.gnss.SatelliteCalculator.getOrbitRadiusAndSpeed
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.screen.dialog.Map2DInformationDialog
import com.example.gnssandopticalflowapp.screen.dialog.Map3DInformationDialog
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.R)
class GNSSViewerFragment :
    BaseFragment<FragmentGnssViewerBinding>(FragmentGnssViewerBinding::inflate) {
    private data class SatelliteKey(val constellationType: Int, val svid: Int)

    private var rendererSet = false
    private lateinit var earthRenderer: EarthRenderer
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var is3DMode = false
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var userMarker: Marker? = null
    // Set to true to force a test location (New York) for EarthRenderer testing
    private var useTestLocation: Boolean = false
    private val testLatitude = 40.712776
    private val testLongitude = -74.005974
    private val latestSatellitePvt =
        mutableMapOf<SatelliteKey, GnssSatellitePvtResolver.SatellitePvtSnapshot>()
    private var gnssMeasurementsRegistered = false
    private var chipsetSupportsSatellitePvt = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            updateMapLocation()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun hasLocationPermission(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return permissions.all {
            ActivityCompat.checkSelfPermission(
                safeContext(),
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            val now = SystemClock.elapsedRealtimeNanos()
            for (measurement in eventArgs.measurements) {
                val snapshot = GnssSatellitePvtResolver.extractPvt(measurement) ?: continue
                latestSatellitePvt[SatelliteKey(measurement.constellationType, measurement.svid)] =
                    snapshot.copy(capturedAtElapsedRealtimeNanos = now)
            }

            val staleThresholdNanos = 10_000_000_000L
            latestSatellitePvt.entries.removeAll { (_, snapshot) ->
                now - snapshot.capturedAtElapsedRealtimeNanos > staleThresholdNanos
            }
        }
    }

    @SuppressLint("NewApi")
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            super.onStarted()
            Log.d("GNSS_STATUS", "GNSS system started, searching for satellites...")
        }

        override fun onStopped() {
            super.onStopped()
            Log.d("GNSS_STATUS", "GNSS system stopped.")
        }

        override fun onFirstFix(ttffMillis: Int) {
            super.onFirstFix(ttffMillis)
            Log.d("GNSS_STATUS", "First GNSS fix acquired in ${ttffMillis}ms")
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val satelliteCount = status.satelliteCount
            Log.d("GNSS_STATUS", "Satellite status changed. Found $satelliteCount satellites.")

            if (rendererSet) {
                val satellites = mutableListOf<SatelliteInfo>()
                for (i in 0 until satelliteCount) {
                    val freq =
                        if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f
                    val svid = status.getSvid(i)
                    val constellation = status.getConstellationType(i)
                    val satelliteKey = SatelliteKey(constellation, svid)

                    Log.v(
                        "GNSS_SAT",
                        "Sat index $i: SVID=$svid, Constellation=$constellation, CN0=${
                            status.getCn0DbHz(i)
                        }, UsedInFix=${status.usedInFix(i)}"
                    )

                    val elevationDegrees = status.getElevationDegrees(i)
                    val azimuthDegrees = status.getAzimuthDegrees(i)

                    var lat = 0.0
                    var lon = 0.0
                    var alt = 0.0
                    var spd = 0.0
                    var positionSource = "Approximate"
                    var ephemerisSource: String? = null

                    val pvtSnapshot = latestSatellitePvt[satelliteKey]?.takeIf { snapshot ->
                        SystemClock.elapsedRealtimeNanos() - snapshot.capturedAtElapsedRealtimeNanos <= 10_000_000_000L
                    }

                    if (pvtSnapshot != null) {
                        val pos = calculateSatellitePositionFromEcef(
                            ecefX = pvtSnapshot.ecefX,
                            ecefY = pvtSnapshot.ecefY,
                            ecefZ = pvtSnapshot.ecefZ
                        )
                        lat = pos.latitude
                        lon = pos.longitude
                        alt = pos.altitude
                        spd = calculateSpeedFromEcefVelocity(
                            pvtSnapshot.velocityXMetersPerSecond,
                            pvtSnapshot.velocityYMetersPerSecond,
                            pvtSnapshot.velocityZMetersPerSecond
                        ) ?: 0.0
                        positionSource = "Real GNSS PVT"
                        ephemerisSource =
                            GnssSatellitePvtResolver.getEphemerisSourceLabel(pvtSnapshot.ephemerisSource)
                    } else {
                        val (orbitRadius, orbitSpeed) = getOrbitRadiusAndSpeed(
                            constellation,
                            svid
                        )
                        spd = orbitSpeed

                        currentLocation?.let { loc ->
                            val pos = calculateSatellitePosition(
                                observerLat = loc.latitude,
                                observerLon = loc.longitude,
                                azimuthDegrees = azimuthDegrees,
                                elevationDegrees = elevationDegrees,
                                orbitRadius = orbitRadius
                            )
                            lat = pos.latitude
                            lon = pos.longitude
                            alt = pos.altitude
                        }
                    }

                    satellites.add(
                        SatelliteInfo(
                            svid = svid,
                            constellationType = constellation,
                            elevationDegrees = elevationDegrees,
                            azimuthDegrees = azimuthDegrees,
                            cn0DbHz = status.getCn0DbHz(i),
                            usedInFix = status.usedInFix(i),
                            carrierFrequencyHz = freq,
                            latitude = lat,
                            longitude = lon,
                            altitude = alt,
                            speed = spd,
                            positionSource = positionSource,
                            ephemerisSource = ephemerisSource
                        )
                    )
                }
                earthRenderer.updateSatellites(satellites)
            }
        }
    }

    override fun FragmentGnssViewerBinding.initView() {
        val ctx = safeContext()
        Configuration.getInstance().userAgentValue = ctx.packageName
        Configuration.getInstance().load(
            requireActivity().applicationContext,
            ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(18.0)
        // Default position before location arrives
        binding.mapView.controller.setCenter(GeoPoint(21.028511, 105.804817)) // Hanoi fallback

        initOpenGLES()
        applyVisibilityState() // Restore UI state from is3DMode
        startResolutionSequence()
    }

    private val gpsResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        // After GPS resolution (Success or Cancel), proceed to Step 2: Permissions
        checkPermissionsAndSetup()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Step 2 Finished: Close resolve state and let MainActivity handle any missing items
        mainViewModel.isResolvingDeviceSettings.value = false
        setupLocationAndMap()
    }

    private fun startResolutionSequence() {
        mainViewModel.isResolvingDeviceSettings.value = true
        requestGpsResolution()
    }

    private fun requestGpsResolution() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = LocationServices.getSettingsClient(requireActivity())
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            // GPS already OK, proceed to Permissions
            checkPermissionsAndSetup()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                runCatching {
                    val intentSenderRequest =
                        androidx.activity.result.IntentSenderRequest.Builder(exception.resolution.intentSender)
                            .build()
                    gpsResolutionLauncher.launch(intentSenderRequest)
                }
            } else {
                // If not resolvable, just proceed to permissions
                checkPermissionsAndSetup()
            }
        }
    }

    private fun checkPermissionsAndSetup() {
        if (hasLocationPermission()) {
            // Both GPS and Permissions done
            mainViewModel.isResolvingDeviceSettings.value = false
            setupLocationManager()
            startLocationUpdates()
        } else {
            val permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            permissionLauncher.launch(permissions)
        }
    }

    private fun setupLocationManager() {
        if (!::locationManager.isInitialized) {
            locationManager =
                safeContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }

        val capabilities = runCatching { locationManager.gnssCapabilities }.getOrNull()
        chipsetSupportsSatellitePvt = capabilities?.hasSatellitePvt() == true
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        setupLocationManager()

        Log.d("LOCATION", "Starting location and GNSS updates...")

        // Request Location
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            1f,
            locationListener
        )
        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            1000L,
            1f,
            locationListener
        )

        // Request GNSS Status
        locationManager.registerGnssStatusCallback(safeContext().mainExecutor, gnssStatusCallback)
        registerGnssMeasurements()

        // Check last known location immediately if it's fresh (within 2 minutes)
        val lastKnownMap = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastKnownMap != null) {
            val locationAge = System.currentTimeMillis() - lastKnownMap.time
            if (locationAge < 120000) { // Fresh if less than 2 minutes old
                currentLocation = lastKnownMap
                updateMapLocation()
                // Center map once on initialization/resume if needed
                val point = GeoPoint(lastKnownMap.latitude, lastKnownMap.longitude)
                binding.mapView.controller.animateTo(point)
            }
        }
    }

    private fun stopLocationUpdates() {
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            if (gnssMeasurementsRegistered) {
                locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
                gnssMeasurementsRegistered = false
            }
        }
        latestSatellitePvt.clear()
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssMeasurements() {
        if (!::locationManager.isInitialized || gnssMeasurementsRegistered) return

        val capabilities = runCatching { locationManager.gnssCapabilities }.getOrNull()
        if (capabilities?.hasMeasurements() == false) {
            Log.i("GNSS_STATUS", "GNSS measurements are not supported on this device.")
            return
        }

        if (chipsetSupportsSatellitePvt) {
            Log.i("GNSS_STATUS", "Chipset reports SatellitePvt support. Registering measurements.")
        }

        val registered = runCatching {
            locationManager.registerGnssMeasurementsCallback(
                safeContext().mainExecutor,
                gnssMeasurementsCallback
            )
        }.onFailure { error ->
            Log.w("GNSS_STATUS", "Failed to register GNSS measurements callback", error)
        }.getOrDefault(false)

        gnssMeasurementsRegistered = registered
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationAndMap() {
        // This method is now legacy, replaced by startLocationUpdates and setupLocationManager
        checkPermissionsAndSetup()
    }

    private fun updateMapLocation() {
        val loc = currentLocation ?: return
        
        // Update ViewModel for real-time dialog updates
        mainViewModel.currentLocation.postValue(loc)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        mainViewModel.currentTime.postValue(sdf.format(Date(loc.time)))

        val point = GeoPoint(loc.latitude, loc.longitude)
        if (userMarker == null) {
            userMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // Resize icon to a fixed size
                val iconSize = 40.dp
                context?.let { ctx ->
                    getDrawable(ctx, R.drawable.ic_position)?.let { drawable ->
                        val bitmap = createBitmap(iconSize, iconSize)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        icon = bitmap.toDrawable(ctx.resources)
                    }
                }

                title = "Vị trí của bạn"
                setOnMarkerClickListener { _, _ ->
                    showLocationDetailsDialog(loc)
                    true
                }
            }
            binding.mapView.overlays.add(userMarker)
            binding.mapView.controller.setCenter(point)
        }
        userMarker?.position = point
        binding.mapView.invalidate()

        if (rendererSet) {
            val latToUse = if (useTestLocation) testLatitude else loc.latitude
            val lonToUse = if (useTestLocation) testLongitude else loc.longitude
            earthRenderer.updateUserLocation(latToUse, lonToUse)
        }
    }

    private fun showLocationDetailsDialog(loc: Location) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        val baseLocal = sdf.format(Date(loc.time))
        val tz = TimeZone.getDefault()
        val offsetMillis = tz.getOffset(loc.time)
        val sign = if (offsetMillis >= 0) "+" else "-"
        val offHours = abs(offsetMillis) / 3600000
        val offMinutes = (abs(offsetMillis) % 3600000) / 60000
        val utcSuffix = "UTC$sign" + String.format("%02d:%02d", offHours, offMinutes)
        val localTime = "$baseLocal $utcSuffix"
        checkIfFragmentAttached {
            Map2DInformationDialog.showDialog(
                fragmentManager = parentFragmentManager,
                loc = loc,
                time = localTime
            )
        }
    }

    private fun applyVisibilityState() {
        if (is3DMode) {
            binding.mapView.hide()
            binding.myGLSurfaceView.show()
            binding.myGLSurfaceView.alpha = 1f
        } else {
            binding.mapView.show()
            binding.mapView.alpha = 1f
            binding.myGLSurfaceView.hide()
        }
    }

    private fun toggle3DMode() {
        is3DMode = !is3DMode

        if (is3DMode) {
            binding.mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            binding.myGLSurfaceView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            binding.mapView.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.mapView.hide()
                    binding.mapView.setLayerType(View.LAYER_TYPE_NONE, null)

                    binding.myGLSurfaceView.show()
                    binding.myGLSurfaceView.alpha = 0f
                    binding.myGLSurfaceView.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            binding.myGLSurfaceView.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                        .start()
                }
                .start()

        } else {
            binding.myGLSurfaceView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            binding.mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            binding.myGLSurfaceView.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.myGLSurfaceView.hide()
                    binding.myGLSurfaceView.setLayerType(View.LAYER_TYPE_NONE, null)

                    binding.mapView.show()
                    binding.mapView.alpha = 0f
                    binding.mapView.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            binding.mapView.setLayerType(View.LAYER_TYPE_NONE, null)
                            binding.mapView.invalidate()
                        }
                        .start()
                }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentGnssViewerBinding.initListener() {
        // Overlay for double tap on MapView
        val mapOverlay = object : Overlay() {
            override fun onDoubleTap(e: MotionEvent, mapView: MapView): Boolean {
                toggle3DMode()
                return true
            }
        }
        binding.mapView.overlays.add(mapOverlay)

        // MapListener for zoom out to 3D
        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean = false
            override fun onZoom(event: ZoomEvent): Boolean {
                if (event.zoomLevel <= 3.0 && !is3DMode) {
                    toggle3DMode()
                    return true
                }
                return false
            }
        })

        // GestureDetector for 3D GLSurfaceView
        gestureDetector =
            GestureDetector(safeContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (is3DMode) toggle3DMode()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (is3DMode && rendererSet) {
                        val tappedSat = earthRenderer.handleTouch(
                            e.x,
                            e.y,
                            binding.myGLSurfaceView.width,
                            binding.myGLSurfaceView.height
                        )
                        if (tappedSat != null) {
                            showSatelliteDetailsDialog(tappedSat, earthRenderer.satelliteCount)
                        }
                    }
                    return super.onSingleTapConfirmed(e)
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (is3DMode && rendererSet) {
                        earthRenderer.velocityTheta = velocityX * 0.005f
                        earthRenderer.velocityPhi = velocityY * 0.005f
                        return true
                    }
                    return false
                }
            })

        scaleGestureDetector = ScaleGestureDetector(
            safeContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (is3DMode && rendererSet) {
                        earthRenderer.clearTargets()
                        earthRenderer.scaleFactor /= detector.scaleFactor
                        earthRenderer.scaleFactor = earthRenderer.scaleFactor.coerceIn(0.2f, 3.0f)
                    }
                    return true
                }
            })

        var previousX = 0f
        var previousY = 0f
        var isMultiTouch = false

        binding.myGLSurfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleGestureDetector.onTouchEvent(event)

            if (event.pointerCount > 1) {
                isMultiTouch = true
            }

            if (event.pointerCount == 1) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                        isMultiTouch = false
                        if (rendererSet) {
                            earthRenderer.clearTargets()
                            earthRenderer.velocityTheta = 0f
                            earthRenderer.velocityPhi = 0f
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isMultiTouch = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isMultiTouch) {
                            // Touch just became 1 pointer after zooming
                            previousX = event.x
                            previousY = event.y
                            isMultiTouch = false
                        } else {
                            val dx = event.x - previousX
                            val dy = event.y - previousY

                            earthRenderer.theta -= dx * 0.5f
                            earthRenderer.phi += dy * 0.5f

                            // Giới hạn phi để tránh nhảy ở cực (Gimbal lock/Up vector conflict)
                            earthRenderer.phi = earthRenderer.phi.coerceIn(-89.9f, 89.9f)

                            previousX = event.x
                            previousY = event.y
                        }
                    }
                }
            }
            true
        }

        icPin.setSingleClick {
            recenterMap()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recenterMap() {
        if (!hasLocationPermission()) return

        val loc = currentLocation ?: if (::locationManager.isInitialized) {
            (locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        } else null

        if (loc == null) {
            Toast.makeText(safeContext(), "Đang chờ vị trí...", Toast.LENGTH_SHORT).show()
            return
        }

        if (is3DMode) {
            if (rendererSet) {
                // Reset to user location on the 3D globe with smooth animation
                earthRenderer.smoothScrollTo(loc.latitude.toFloat(), loc.longitude.toFloat(), 1.0f)
            }
        } else {
            // Animate to user location on 2D map
            val point = GeoPoint(loc.latitude, loc.longitude)
            binding.mapView.controller.animateTo(point)
            if (binding.mapView.zoomLevelDouble < 15.0) {
                binding.mapView.controller.setZoom(18.0)
            }
        }
    }

    private fun showSatelliteDetailsDialog(sat: SatelliteInfo, totalSats: Int) {
        checkIfFragmentAttached {
            Map3DInformationDialog.showDialog(
                fragmentManager = parentFragmentManager,
                sat = sat,
                totalSats = totalSats
            )
        }
    }

    override fun initObserver() {
        mainViewModel.currentTab.observe(viewLifecycleOwner) { position ->
            if (position == 0) {
                // If on GNSS tab, restore the current mode
                applyVisibilityState()
            } else {
                // If on other tabs, FORCE HIDE GLSurfaceView to prevent punching through
                binding.myGLSurfaceView.hide()
            }
        }
    }
    private fun initOpenGLES() {
        val activityManager =
            safeContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configurationInfo = activityManager.deviceConfigurationInfo
        val supportsEs32 = configurationInfo.reqGlEsVersion >= 0x30002

        if (supportsEs32) {
            earthRenderer = EarthRenderer(safeContext())
            binding.myGLSurfaceView.setEGLContextClientVersion(3)
            binding.myGLSurfaceView.setZOrderMediaOverlay(true) // Fix overlap in ViewPager2
            binding.myGLSurfaceView.setRenderer(earthRenderer)
            rendererSet = true
        } else {
            Toast.makeText(
                safeContext(),
                "This device doesn't support OpenGL ES 3.2",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (rendererSet) {
            binding.myGLSurfaceView.onResume()
        }
        if (hasLocationPermission()) {
            startLocationUpdates()
        }
        startRealTimeTicker()
    }

    private fun startRealTimeTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                // Use system time for ticking, or location time if one is available and fresh
                val displayTime = currentLocation?.let { loc ->
                    val age = System.currentTimeMillis() - loc.time
                    if (age < 5000) loc.time else System.currentTimeMillis()
                } ?: System.currentTimeMillis()
                
                val baseTime = sdf.format(Date(displayTime))
                val tz = TimeZone.getDefault()
                val offsetMillis = tz.getOffset(displayTime)
                val sign = if (offsetMillis >= 0) "+" else "-"
                val offHours = Math.abs(offsetMillis) / 3600000
                val offMinutes = (Math.abs(offsetMillis) % 3600000) / 60000
                val utcSuffix = "UTC$sign" + String.format("%02d:%02d", offHours, offMinutes)
                mainViewModel.currentTime.value = "$baseTime $utcSuffix"
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        if (rendererSet) {
            binding.myGLSurfaceView.onPause()
        }
        stopLocationUpdates()
        currentLocation = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()
        userMarker = null
    }
}
