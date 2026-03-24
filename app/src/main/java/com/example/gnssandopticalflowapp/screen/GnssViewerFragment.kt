package com.example.gnssandopticalflowapp.screen

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.databinding.FragmentGnssViewerBinding
import com.example.gnssandopticalflowapp.gnss.EarthRenderer
import com.example.gnssandopticalflowapp.model.SatelliteInfo
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
            Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            updateMapLocation()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("NewApi")
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (rendererSet) {
                val satellites = mutableListOf<SatelliteInfo>()
                for (i in 0 until status.satelliteCount) {
                    val freq = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f
                    satellites.add(SatelliteInfo(
                        svid = status.getSvid(i),
                        constellationType = status.getConstellationType(i),
                        elevationDegrees = status.getElevationDegrees(i),
                        azimuthDegrees = status.getAzimuthDegrees(i),
                        cn0DbHz = status.getCn0DbHz(i),
                        usedInFix = status.usedInFix(i),
                        carrierFrequencyHz = freq
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
        checkPermissionsAndSetup()
    }

    private fun checkPermissionsAndSetup() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (permissions.all { ActivityCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }) {
            setupLocationAndMap()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationAndMap() {
        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Request Location
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)
        
        // Request GNSS Status
        locationManager.registerGnssStatusCallback(requireContext().mainExecutor, gnssStatusCallback)
        
        val lastKnownMap = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
        if (lastKnownMap != null) {
            currentLocation = lastKnownMap
            updateMapLocation()
        }
    }

    private fun updateMapLocation() {
        val loc = currentLocation ?: return
        val point = GeoPoint(loc.latitude, loc.longitude)
        if (userMarker == null) {
            userMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = requireContext().getDrawable(android.R.drawable.ic_menu_mylocation)
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
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val utcTime = sdf.format(Date(loc.time))

        val details = """
            Vĩ độ (Lat): ${loc.latitude}
            Kinh độ (Lon): ${loc.longitude}
            Thời gian: $utcTime
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
            binding.mapView.animate().alpha(0f).setDuration(300).withEndAction {
                binding.mapView.visibility = View.GONE
            }
            binding.myGLSurfaceView.visibility = View.VISIBLE
            binding.myGLSurfaceView.alpha = 0f
            binding.myGLSurfaceView.animate().alpha(1f).setDuration(300).start()
        } else {
            binding.myGLSurfaceView.animate().alpha(0f).setDuration(300).withEndAction {
                binding.myGLSurfaceView.visibility = View.GONE
            }
            binding.mapView.visibility = View.VISIBLE
            binding.mapView.alpha = 0f
            binding.mapView.animate().alpha(1f).setDuration(300).start()
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
                    earthRenderer.scaleFactor *= detector.scaleFactor
                    earthRenderer.scaleFactor = earthRenderer.scaleFactor.coerceIn(0.2f, 3.0f)
                }
                return true
            }
        })

        var previousX = 0f
        var previousY = 0f

        binding.myGLSurfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleGestureDetector.onTouchEvent(event)

            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - previousX
                        val dy = event.y - previousY

                        earthRenderer.theta += dx * 0.5f
                        earthRenderer.phi -= dy * 0.5f
                        earthRenderer.phi = earthRenderer.phi.coerceIn(-89f, 89f)

                        previousX = event.x
                        previousY = event.y
                    }
                }
            }
            true
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
        
        // Approximate distance based on elevation (simplification)
        // Lat, Lon altitude would normally require knowing the satellite's exact ephemeris, but we can't get that from GnssStatus easily.
        // We will show NA if not available, since GnssStatus only gives Az, El. (User prompt asked for lat, lon, alt, speed, dist, but GnssStatus doesn't provide them. I'll mock NA or note limitation).
        
        val details = """
            Tổng số vệ tinh: $totalSats
            SVID: ${sat.svid}
            Chòm sao: $constellation
            Cường độ tín hiệu (Cn0DbHz): ${sat.cn0DbHz}
            Góc ngẩng: ${sat.elevationDegrees}°
            Phương vị: ${sat.azimuthDegrees}°
            Tần số sóng mang: ${if (sat.carrierFrequencyHz > 0) "${sat.carrierFrequencyHz} Hz" else "N/A"}
            Trạng thái sử dụng: ${if (sat.usedInFix) "Đang sử dụng" else "Không"}
            
            Vĩ độ, Kinh độ, Độ cao, Tốc độ, Khoảng cách: Không có sẵn từ GnssStatus API.
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Vệ tinh $constellation SVID: ${sat.svid}")
            .setMessage(details)
            .setPositiveButton("Đóng", null)
            .show()
    }

    override fun initObserver() {}

    private fun initOpenGLES() {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configurationInfo = activityManager.deviceConfigurationInfo
        val supportsEs32 = configurationInfo.reqGlEsVersion >= 0x30002

        if (supportsEs32) {
            earthRenderer = EarthRenderer(requireContext())
            binding.myGLSurfaceView.setEGLContextClientVersion(3)
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
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        if (rendererSet) {
            binding.myGLSurfaceView.onPause()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }
}
