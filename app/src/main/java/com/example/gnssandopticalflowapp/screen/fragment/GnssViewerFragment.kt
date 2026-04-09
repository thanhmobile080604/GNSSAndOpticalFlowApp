package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentGnssViewerBinding
import com.example.gnssandopticalflowapp.gnss.EarthRenderer
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.screen.dialog.NoLocationDialog
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

@RequiresApi(Build.VERSION_CODES.R)
class GnssViewerFragment :
    BaseFragment<FragmentGnssViewerBinding>(FragmentGnssViewerBinding::inflate) {
    private var rendererSet = false
    private lateinit var earthRenderer: EarthRenderer
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var is3DMode = false
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var userMarker: Marker? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            setupLocationAndMap()
        } else {
            showNoLocationDialog()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            updateMapLocation()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                checkGpsStatus()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return permissions.all { ActivityCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }
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
                    val freq = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f
                    val svid = status.getSvid(i)
                    val constellation = status.getConstellationType(i)
                    
                    Log.v("GNSS_SAT", "Sat index $i: SVID=$svid, Constellation=$constellation, CN0=${status.getCn0DbHz(i)}, UsedInFix=${status.usedInFix(i)}")
                    
                    val elevationDegrees = status.getElevationDegrees(i)
                    val azimuthDegrees = status.getAzimuthDegrees(i)
                    
                    var lat = 0.0
                    var lon = 0.0
                    var alt = 0.0
                    var spd = 0.0
                    
                    val (orbitRadius, orbitSpeed) = com.example.gnssandopticalflowapp.gnss.SatelliteCalculator.getOrbitRadiusAndSpeed(constellation, svid)
                    spd = orbitSpeed
                    
                    currentLocation?.let { loc ->
                        val pos = com.example.gnssandopticalflowapp.gnss.SatelliteCalculator.calculateSatellitePosition(
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
                    
                    satellites.add(SatelliteInfo(
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
                        speed = spd
                    ))
                }
                earthRenderer.updateSatellites(satellites)
            }
        }
    }

    override fun FragmentGnssViewerBinding.initView() {
        Configuration.getInstance().load(requireActivity().applicationContext, requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(18.0)
        // Default position before location arrives
        binding.mapView.controller.setCenter(GeoPoint(21.028511, 105.804817)) // Hanoi fallback

        initOpenGLES()
        
        // Restore 3D mode state if previously enabled
        if (is3DMode) {
            binding.mapView.hide()
            binding.myGLSurfaceView.show()
            binding.myGLSurfaceView.alpha = 1f
        } else {
            binding.mapView.show()
            binding.mapView.alpha = 1f
            binding.myGLSurfaceView.hide()
        }

        checkPermissionsAndSetup()
    }

    private fun checkPermissionsAndSetup() {
        if (hasLocationPermission()) {
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
            locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        setupLocationManager()

        Log.d("LOCATION", "Starting location and GNSS updates...")

        // Request Location
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)

        // Request GNSS Status
        locationManager.registerGnssStatusCallback(requireContext().mainExecutor, gnssStatusCallback)

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
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationAndMap() {
        // This method is now legacy, replaced by startLocationUpdates and setupLocationManager
        checkPermissionsAndSetup()
    }

    private fun updateMapLocation() {
        val loc = currentLocation ?: return
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
            earthRenderer.updateUserLocation(loc.latitude, loc.longitude)
        }
    }

    private fun showLocationDetailsDialog(loc: Location) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        val localTime = sdf.format(Date(loc.time))

        val details = """
            Vĩ độ (Lat): ${loc.latitude}
            Kinh độ (Lon): ${loc.longitude}
            Thời gian: $localTime
            Vận tốc: ${loc.speed} m/s
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Thông tin vị trí")
            .setMessage(details)
            .setPositiveButton("Đóng", null)
            .show()
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
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (is3DMode) toggle3DMode()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (is3DMode && rendererSet) {
                    val tappedSat = earthRenderer.handleTouch(e.x, e.y, binding.myGLSurfaceView.width, binding.myGLSurfaceView.height)
                    if (tappedSat != null) {
                        showSatelliteDetailsDialog(tappedSat, earthRenderer.satelliteCount)
                    }
                }
                return super.onSingleTapConfirmed(e)
            }
        })

        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
                        if (rendererSet) earthRenderer.clearTargets()
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
            Toast.makeText(requireContext(), "Đang chờ vị trí...", Toast.LENGTH_SHORT).show()
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
        val constellation = when (sat.constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "Unknown"
        }

        val formattedLat = String.format(Locale.getDefault(), "%.4f", sat.latitude)
        val formattedLon = String.format(Locale.getDefault(), "%.4f", sat.longitude)
        val formattedAlt = String.format(Locale.getDefault(), "%,d", sat.altitude.toLong())
        val formattedSpeedSpeed = String.format(Locale.getDefault(), "%,.1f km/s (%,.0f km/h)", sat.speed / 1000.0, sat.speed * 3.6)

        val details = """
            Tổng số vệ tinh: $totalSats
            SVID: ${sat.svid}
            Chòm sao: $constellation
            Cường độ tín hiệu (Cn0DbHz): ${sat.cn0DbHz}
            Góc ngẩng: ${sat.elevationDegrees}°
            Phương vị: ${sat.azimuthDegrees}°
            Tần số sóng mang: ${if (sat.carrierFrequencyHz > 0) "${sat.carrierFrequencyHz} Hz" else "N/A"}
            Trạng thái sử dụng: ${if (sat.usedInFix) "Đang sử dụng" else "Không"}
            
            [Dữ liệu ước tính từ quỹ đạo]
            Vĩ độ: $formattedLat°
            Kinh độ: $formattedLon°
            Độ cao: $formattedAlt mét
            Tốc độ: $formattedSpeedSpeed
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Vệ tinh $constellation SVID: ${sat.svid}")
            .setMessage(details)
            .setPositiveButton("Đóng", null)
            .show()
    }

    override fun initObserver() {}

    private fun checkGpsStatus() {
        if (!hasLocationPermission()) {
            showNoLocationDialog()
            return
        }
        if (::locationManager.isInitialized) {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (!isGpsEnabled) {
                showNoLocationDialog()
            }
        }
    }

    private fun showNoLocationDialog() {
        if (childFragmentManager.findFragmentByTag("NoLocationDialog") == null) {
            NoLocationDialog().show(childFragmentManager, "NoLocationDialog")
        }
    }

    private fun initOpenGLES() {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configurationInfo = activityManager.deviceConfigurationInfo
        val supportsEs32 = configurationInfo.reqGlEsVersion >= 0x30002

        if (supportsEs32) {
            earthRenderer = EarthRenderer(requireContext())
            binding.myGLSurfaceView.setEGLContextClientVersion(3)
            binding.myGLSurfaceView.setZOrderMediaOverlay(true) // Fix overlap in ViewPager2
            binding.myGLSurfaceView.setRenderer(earthRenderer)
            rendererSet = true
        } else {
            Toast.makeText(requireContext(), "This device doesn't support OpenGL ES 3.2", Toast.LENGTH_SHORT).show()
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
        checkGpsStatus()
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
