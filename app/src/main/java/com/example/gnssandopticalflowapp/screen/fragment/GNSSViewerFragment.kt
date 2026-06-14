package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.Constants
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentGnssViewerBinding
import com.example.gnssandopticalflowapp.function.gnss.renderer.EarthRenderer
import com.example.gnssandopticalflowapp.model.LiveRouteState
import com.example.gnssandopticalflowapp.model.RouteInfo
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.model.SearchPlace
import com.example.gnssandopticalflowapp.screen.dialog.ErrorGNSSDialog
import com.example.gnssandopticalflowapp.screen.dialog.Map2DInformationDialog
import com.example.gnssandopticalflowapp.screen.dialog.Map3DInformationDialog
import com.example.gnssandopticalflowapp.screen.viewmodel.GNSSViewerViewModel
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.R)
class GNSSViewerFragment :
    BaseFragment<FragmentGnssViewerBinding>(FragmentGnssViewerBinding::inflate) {
    private var rendererSet = false
    private lateinit var earthRenderer: EarthRenderer
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val viewerViewModel: GNSSViewerViewModel by viewModels()

    private lateinit var locationManager: LocationManager
    private var userMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var ignoreSearchTextChanges = false
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var externalOrbitRefreshJob: Job? = null
    private var gnssErrorDialogJob: Job? = null
    private var lastMap2DDialogShownAt = 0L
    private var searchResultsPanelAllowed = false
    private var wasKeyboardVisible = false
    private var isChoosingDestinationOnMap = false
    private val useTestLocation: Boolean = Constants.USE_FAKE_LOCATION
    private val externalOrbitRetryDelayMs = 30_000L
    private val externalOrbitRefreshAttempts = 3
    private var is3DMode: Boolean
        get() = viewerViewModel.is3DMode
        set(value) {
            viewerViewModel.is3DMode = value
        }
    private var currentLocation: Location?
        get() = viewerViewModel.currentLocation
        set(value) {
            viewerViewModel.currentLocation = value
        }
    private var selectedPlace: SearchPlace?
        get() = viewerViewModel.selectedPlace
        set(value) {
            viewerViewModel.selectedPlace = value
        }
    private var cachedRoute: RouteInfo?
        get() = viewerViewModel.cachedRoute
        set(value) {
            viewerViewModel.cachedRoute = value
        }
    private var gnssStatusRegistered: Boolean
        get() = viewerViewModel.gnssStatusRegistered
        set(value) {
            viewerViewModel.gnssStatusRegistered = value
        }
    private var gnssMeasurementsRegistered: Boolean
        get() = viewerViewModel.gnssMeasurementsRegistered
        set(value) {
            viewerViewModel.gnssMeasurementsRegistered = value
        }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = mainViewModel.getEffectiveLocation(location)
            updateMapLocation()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun hasLocationPermission(): Boolean {
        if (useTestLocation) return true

        return hasRealLocationPermission()
    }

    private fun hasRealLocationPermission(): Boolean {
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
            viewerViewModel.lastGnssMeasurementCount = eventArgs.measurements.size
            viewerViewModel.satelliteTracker.updateMeasurements(eventArgs)
        }
    }

    @SuppressLint("NewApi")
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            viewerViewModel.lastGnssStatusSatelliteCount = status.satelliteCount
            if (status.satelliteCount > 0) {
                cancelGnssErrorDialogCheck()
            }
            if (rendererSet) {
                val satellites = viewerViewModel.updateSatelliteSnapshot(status)
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
        binding.mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        binding.mapView.controller.setZoom(18.0)
        // Default position before location arrives
        binding.mapView.controller.setCenter(GeoPoint(21.028511, 105.804817)) // Hanoi fallback
        binding.searchResultsPanel.hide()
        binding.chooseOnMapBar.hide()
        binding.routeBottomBar.hide()
        binding.ivSearchClear.hide()
        setMapTargetPickerControlsVisible(false)
        binding.btnStartNavigation.isEnabled = false
        binding.btnStartNavigation.alpha = 0.55f

        initOpenGLES()
        applyVisibilityState() // Restore UI state from is3DMode
        setupMapModeSwitchOverlay()
        startResolutionSequence()

        listOf(
            searchBubble,
            currentLocationBubble,
            startNavigationBubble,
            resultBubble,
            cancelBubble,
            navigationBubble,
            chooseOnMapBubble,
            backBubble,
            checkBubble
        ).forEach { bubble ->
            bubble.bind(mapView)
            bubble.setElasticEnabled(true)

            if (bubble == startNavigationBubble) {
                startNavigationBubble.setTintColorRed(0.482f)
                startNavigationBubble.setTintColorGreen( 0.361f)
                startNavigationBubble.setTintColorBlue(1f)
                startNavigationBubble.setTintAlpha(0.45f)
            }
        }
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
        if (useTestLocation) {
            mainViewModel.isResolvingDeviceSettings.value = false
            startLocationUpdates()
            return
        }

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
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        setupLocationManager()
        viewerViewModel.lastGnssStatusSatelliteCount = 0
        viewerViewModel.lastGnssMeasurementCount = 0
        refreshExternalOrbitDataIfNeeded()

        if (useTestLocation) {
            currentLocation = mainViewModel.getEffectiveLocation(null)
            updateMapLocation()
            if (!hasRealLocationPermission()) return
        }

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
        gnssStatusRegistered = runCatching {
            locationManager.registerGnssStatusCallback(
                safeContext().mainExecutor,
                gnssStatusCallback
            )
        }.getOrDefault(false)
        registerGnssMeasurements()
        if (is3DMode) {
            scheduleGnssErrorDialogCheck()
        }

        // Check last known location immediately if it's fresh (within 2 minutes)
        val lastKnownMap = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastKnownMap != null) {
            val locationAge = System.currentTimeMillis() - lastKnownMap.time
            if (locationAge < 120000) { // Fresh if less than 2 minutes old
                currentLocation = mainViewModel.getEffectiveLocation(lastKnownMap)
                updateMapLocation()
                // Center map once on initialization/resume if needed
                val point = GeoPoint(currentLocation!!.latitude, currentLocation!!.longitude)
                binding.mapView.controller.animateTo(point)
            }
        }
    }

    private fun stopLocationUpdates() {
        cancelGnssErrorDialogCheck()
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
            if (gnssStatusRegistered) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                gnssStatusRegistered = false
            }
            if (gnssMeasurementsRegistered) {
                locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
                gnssMeasurementsRegistered = false
            }
        }
        externalOrbitRefreshJob?.cancel()
        externalOrbitRefreshJob = null
        viewerViewModel.resetGnssRuntimeState()
    }

    private fun refreshExternalOrbitDataIfNeeded(forceRefresh: Boolean = false) {
        if (!forceRefresh && externalOrbitRefreshJob?.isActive == true) return

        externalOrbitRefreshJob?.cancel()
        externalOrbitRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(externalOrbitRefreshAttempts) { attempt ->
                val forceAttemptRefresh = forceRefresh || attempt > 0
                val igsLoaded = viewerViewModel.satelliteTracker.refreshIgsBroadcastDataIfNeeded(
                    forceRefresh = forceAttemptRefresh
                )
                val celesTrakLoaded = viewerViewModel.satelliteTracker.refreshCelesTrakDataIfNeeded(
                    forceRefresh = forceAttemptRefresh
                )
                if (igsLoaded || celesTrakLoaded) return@launch

                if (attempt < externalOrbitRefreshAttempts - 1) {
                    Log.d(
                        "GNSS_ORBIT",
                        "retry in ${externalOrbitRetryDelayMs / 1000}s attempt=${attempt + 2}/$externalOrbitRefreshAttempts"
                    )
                    delay(externalOrbitRetryDelayMs)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssMeasurements() {
        if (!::locationManager.isInitialized || gnssMeasurementsRegistered) return

        val capabilities = readGnssCapabilitiesCompat()
        capabilities?.let { caps ->
            val hasMeasurements = readBooleanCapability(caps, "hasMeasurements")
            Log.d(
                "GNSS_CAPS",
                "hasMeasurements=${hasMeasurements ?: "unavailable"} " +
                    "hasSatellitePvt=${readCapabilityText(caps, "hasSatellitePvt")}"
            )
            if (hasMeasurements == false) {
                Log.d("GNSS_CAPS", "skip measurements callback: capability reports unsupported")
                return
            }
        }

        val registered = runCatching {
            locationManager.registerGnssMeasurementsCallback(
                safeContext().mainExecutor,
                gnssMeasurementsCallback
            )
        }.getOrDefault(false)

        gnssMeasurementsRegistered = registered
        Log.d("GNSS_CAPS", "measurements callback registered=$registered")
    }

    private fun readGnssCapabilitiesCompat(): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return runCatching {
            LocationManager::class.java
                .getMethod("getGnssCapabilities")
                .invoke(locationManager)
        }.getOrNull()
    }

    private fun readBooleanCapability(capabilities: Any, methodName: String): Boolean? {
        return runCatching {
            capabilities.javaClass
                .getMethod(methodName)
                .invoke(capabilities) as? Boolean
        }.getOrNull()
    }

    private fun readCapabilityText(capabilities: Any, methodName: String): String {
        return runCatching {
            capabilities.javaClass
                .getMethod(methodName)
                .invoke(capabilities)
                ?.toString()
                ?: "unavailable"
        }.getOrDefault("unavailable")
    }

    private fun scheduleGnssErrorDialogCheck() {
        if (useTestLocation || !hasLocationPermission() || hasGnssErrorDialogShownThisSession) return
        if (gnssErrorDialogJob?.isActive == true) return

        gnssErrorDialogJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(GNSS_ERROR_DIALOG_DELAY_MS)
            if (!is3DMode || hasGnssErrorDialogShownThisSession) return@launch
            if (hasUsableGnssFor3D()) return@launch

            showGnssErrorDialogOnce()
        }
    }

    private fun cancelGnssErrorDialogCheck() {
        gnssErrorDialogJob?.cancel()
        gnssErrorDialogJob = null
    }

    private fun hasUsableGnssFor3D(): Boolean {
        return viewerViewModel.hasUsableGnssFor3D()
    }

    private fun showGnssErrorDialogOnce() {
        if (hasGnssErrorDialogShownThisSession) return

        checkIfFragmentAttached {
            if (this@GNSSViewerFragment.parentFragmentManager.isStateSaved) return@checkIfFragmentAttached
            hasGnssErrorDialogShownThisSession = true
            ErrorGNSSDialog.show(this@GNSSViewerFragment.parentFragmentManager)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationAndMap() {
        // This method is now legacy, replaced by startLocationUpdates and setupLocationManager
        checkPermissionsAndSetup()
    }

    private fun updateMapLocation() {
        val loc = currentLocation ?: return

        // Update ViewModel for real-time dialog updates
        mainViewModel.postCurrentLocation(loc)
        mainViewModel.postCurrentTime(loc.time)

        val point = GeoPoint(loc.latitude, loc.longitude)
        if (userMarker == null) {
            userMarker = Marker(binding.mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // Resize icon to a fixed size
                val iconSize = 40.dp
                context?.let { ctx ->
                    getDrawable(ctx, R.drawable.ic_current_location)?.let { drawable ->
                        val bitmap = createBitmap(iconSize, iconSize)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        icon = bitmap.toDrawable(ctx.resources)
                    }
                }

                title = "Current location"
                setOnMarkerClickListener { _, _ ->
                    showLocationDetailsDialog(currentLocation ?: loc)
                    true
                }
            }
            binding.mapView.overlays.add(userMarker)
            binding.mapView.controller.setCenter(point)
        }

        userMarker?.position = point
        binding.mapView.invalidate()

        selectedPlace?.let { place ->
            val route = cachedRoute
            if (route != null) {
                updateRoutePreviewFromCachedRoute(route, loc)
            } else {
                updateRoutePreviewFromDirectDistance()
            }
            val shouldDrawRoute = routeLine != null
            if (cachedRoute == null && routeJob?.isActive != true) {
                requestRouteUpdate(force = true, drawRoute = shouldDrawRoute)
            }
            if (shouldDrawRoute) {
                requestRouteUpdate(force = false, drawRoute = true)
            }
        }

        if (rendererSet) {
            earthRenderer.updateUserLocation(loc.latitude, loc.longitude)
        }
    }

    private fun showLocationDetailsDialog(loc: Location) {
        val now = System.currentTimeMillis()
        if (now - lastMap2DDialogShownAt < MAP2D_DIALOG_DEBOUNCE_MS) return
        lastMap2DDialogShownAt = now

        val localTime = mainViewModel.formatDisplayTime(loc.time)
        checkIfFragmentAttached {
            if (this@GNSSViewerFragment.parentFragmentManager.isStateSaved) return@checkIfFragmentAttached
            Map2DInformationDialog.showDialog(
                fragmentManager = parentFragmentManager,
                loc = loc,
                time = localTime
            )
        }
    }

    private fun setupSearchInteractions() = with(binding) {
        etSearchLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                chooseOnMapBar.show()
                showSearchPanelForCurrentInput()
            }
        }

        etSearchLocation.setOnClickListener {
            chooseOnMapBar.show()
            showSearchPanelForCurrentInput()
        }

        etSearchLocation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (ignoreSearchTextChanges) return

                val query = s?.toString()?.trim().orEmpty()
                searchResultsPanelAllowed = etSearchLocation.hasFocus()
                ivSearchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE

                searchJob?.cancel()
                if (query.length < 3) {
                    if (query.isEmpty() && etSearchLocation.hasFocus()) {
                        showRecentSearches()
                    } else {
                        clearSearchResults()
                    }
                    return
                }

                showSearchMessage("Searching...")
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350L)
                    performPlaceSearch(query)
                }
            }
        })

        etSearchLocation.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearchLocation.text?.toString()?.trim().orEmpty()
                if (query.length >= 3) {
                    searchJob?.cancel()
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        performPlaceSearch(query)
                    }
                }
                hideKeyboard()
                true
            } else {
                false
            }
        }

        ivSearchClear.setSingleClick {
            if (selectedPlace != null) {
                resetRouteMode()
            } else {
                searchJob?.cancel()
                etSearchLocation.text?.clear()
                showRecentSearches()
            }
        }

        btnStartNavigation.setSingleClick {
            startNavigation()
        }

        routeBottomBar.setSingleClick {
            showSelectedRouteOnMap2D()
        }
    }

    private fun setupKeyboardVisibilityListener() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (wasKeyboardVisible && !keyboardVisible) {
                hideSearchResultsPanelForKeyboardDismiss()
            }
            wasKeyboardVisible = keyboardVisible
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun showSearchPanelForCurrentInput() = with(binding) {
        searchResultsPanelAllowed = true
        val query = etSearchLocation.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            showRecentSearches()
        } else if (searchResultsList.childCount > 0) {
            showSearchResultsPanelIfAllowed()
        }
    }

    private suspend fun performPlaceSearch(query: String) {
        val places = runCatching {
            viewerViewModel.searchPlaces(query)
        }.getOrElse {
            emptyList()
        }

        if (!isAdded || binding.etSearchLocation.text?.toString()?.trim() != query) return
        renderSearchResults(places)
    }

    private fun renderSearchResults(places: List<SearchPlace>) = with(binding.searchResultsList) {
        removeAllViews()
        if (places.isEmpty()) {
            showSearchMessage("No results")
            return
        }

        places.forEachIndexed { index, place ->
            addView(createSearchResultRow(place))
            if (index < places.lastIndex) {
                addView(View(context).apply {
                    setBackgroundColor(Color.argb(34, 255, 255, 255))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        marginStart = 14.dp
                        marginEnd = 14.dp
                    }
                })
            }
        }
        showSearchResultsPanelIfAllowed()
    }

    private fun createSearchResultRow(place: SearchPlace): TextView {
        return TextView(safeContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                58.dp
            )
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(16.dp, 0, 16.dp, 0)
            text = place.name
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { selectPlace(place) }
        }
    }

    private fun showSearchMessage(message: String) = with(binding) {
        searchResultsList.removeAllViews()
        searchResultsList.addView(TextView(safeContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52.dp
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dp, 0, 16.dp, 0)
            text = message
            textSize = 13f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        showSearchResultsPanelIfAllowed()
    }

    private fun clearSearchResults() {
        viewerViewModel.restoreSearchResultsWhenBackTo2D = false
        binding.searchResultsList.removeAllViews()
        binding.searchResultsPanel.hide()
    }

    private fun showSearchResultsPanelIfAllowed() {
        if (is3DMode) {
            viewerViewModel.restoreSearchResultsWhenBackTo2D = shouldRestoreSearchResultsAfter3D()
            binding.searchResultsPanel.hide()
        } else if (searchResultsPanelAllowed) {
            binding.searchResultsPanel.show()
        } else {
            binding.searchResultsPanel.hide()
        }
    }

    private fun shouldRestoreSearchResultsAfter3D(): Boolean {
        val query = binding.etSearchLocation.text?.toString()?.trim().orEmpty()
        return viewerViewModel.shouldRestoreSearchResultsAfter3D(
            query = query,
            searchResultCount = binding.searchResultsList.childCount
        )
    }

    private fun restoreSearchResultsAfter3DIfNeeded() {
        if (!viewerViewModel.consumeRestoreSearchResultsAfter3D()) return

        val query = binding.etSearchLocation.text?.toString()?.trim().orEmpty()
        if (query.isEmpty() || selectedPlace != null) return

        searchResultsPanelAllowed = true
        binding.ivSearchClear.show()
        if (binding.searchResultsList.childCount > 0) {
            binding.searchResultsPanel.show()
            return
        }

        if (query.length >= 3) {
            showSearchMessage("Searching...")
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                performPlaceSearch(query)
            }
        }
    }

    private fun showRecentSearches() {
        val recent = viewerViewModel.getRecentSearches()
        if (recent.isNotEmpty()) {
            renderSearchResults(recent)
        } else {
            clearSearchResults()
        }
    }

    private fun selectPlace(
        place: SearchPlace,
        saveRecentSearch: Boolean = true,
        moveCameraToPlace: Boolean = true
    ) {
        if (saveRecentSearch) {
            viewerViewModel.saveRecentSearch(place)
        }
        viewerViewModel.selectPlace(place)
        routeJob?.cancel()
        clearRouteLine()

        ignoreSearchTextChanges = true
        binding.etSearchLocation.setText(shortPlaceName(place.name))
        binding.etSearchLocation.setSelection(binding.etSearchLocation.text?.length ?: 0)
        ignoreSearchTextChanges = false
        binding.ivSearchClear.show()
        clearSearchResults()
        hideKeyboard()

        val point = GeoPoint(place.latitude, place.longitude)
        updateTargetMarker(place, point)
        if (is3DMode) toggle3DMode()
        if (moveCameraToPlace) {
            binding.mapView.controller.setZoom(17.0)
            binding.mapView.controller.animateTo(point)
        }

        binding.btnStartNavigation.text = "Start"
        updateRoutePreviewFromDirectDistance()
        currentLocation?.let { loc ->
            drawRouteLine(listOf(GeoPoint(loc.latitude, loc.longitude), point))
        }
        requestRouteUpdate(force = true, drawRoute = true)
    }

    private fun updateTargetMarker(place: SearchPlace, point: GeoPoint) {
        if (targetMarker == null) {
            targetMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = buildMarkerIcon(R.drawable.ic_target_location, 46)
            }
            binding.mapView.overlays.add(targetMarker)
        }

        targetMarker?.position = point
        targetMarker?.title = shortPlaceName(place.name)
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

    private fun hideKeyboard() {
        val inputMethodManager =
            safeContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(binding.etSearchLocation.windowToken, 0)
        binding.etSearchLocation.clearFocus()
        hideSearchResultsPanelForKeyboardDismiss()
    }

    private fun hideSearchResultsPanelForKeyboardDismiss() {
        searchResultsPanelAllowed = false
        binding.etSearchLocation.clearFocus()
        binding.searchResultsPanel.hide()
        binding.chooseOnMapBar.hide()
    }

    private fun resetRouteMode() = with(binding) {
        if (isChoosingDestinationOnMap) {
            exitMapTargetPickerMode()
        }

        searchJob?.cancel()
        routeJob?.cancel()
        viewerViewModel.resetRouteState()

        clearRouteLine()
        targetMarker?.let { mapView.overlays.remove(it) }
        targetMarker = null
        mapView.invalidate()

        ignoreSearchTextChanges = true
        etSearchLocation.text?.clear()
        ignoreSearchTextChanges = false

        ivSearchClear.hide()
        clearSearchResults()
        routeBottomBar.hide()
        tvRouteTitle.text = "Destination"
        tvRouteMeta.text = "0 m - 0 min"
        btnStartNavigation.text = "Start"
        btnStartNavigation.isEnabled = false
        btnStartNavigation.alpha = 0.55f
        hideKeyboard()
    }

    private fun startNavigation() {
        val place = selectedPlace
        val loc = currentLocation
        if (place == null) {
            Toast.makeText(safeContext(), "Choose a destination first", Toast.LENGTH_SHORT).show()
            return
        }
        if (loc == null) {
            Toast.makeText(safeContext(), "Waiting for current location...", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val origin = GeoPoint(loc.latitude, loc.longitude)
        val destination = GeoPoint(place.latitude, place.longitude)
        val routePoints = selectedRoutePoints().takeIf { it.size > 1 }
            ?: listOf(origin, destination)
        val distanceMeters = cachedRoute?.distanceMeters
            ?: viewerViewModel.directDistanceMeters(
                loc.latitude,
                loc.longitude,
                place.latitude,
                place.longitude
            )

        mainViewModel.liveRouteState = LiveRouteState(
            destination = place,
            startLocation = Location(loc),
            routePoints = routePoints,
            distanceMeters = distanceMeters
        )
        if (is3DMode) toggle3DMode()
        navigateToLiveRouting()
    }

    private fun navigateToLiveRouting() {
        val options = NavOptions.Builder()
            .setEnterAnim(R.anim.enter_from_bottom)
            .setExitAnim(R.anim.fade_out)
            .setPopEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.exit_to_bottom)
            .build()
        findNavController().navigate(R.id.liveRoutingFragment, null, options)
    }

    private fun updateRoutePreviewFromDirectDistance() {
        val place = selectedPlace ?: return
        val loc = currentLocation
        val distance = if (loc != null) {
            viewerViewModel.directDistanceMeters(
                loc.latitude,
                loc.longitude,
                place.latitude,
                place.longitude
            )
        } else {
            0.0
        }

        binding.tvRouteTitle.text = shortPlaceName(place.name)
        binding.tvRouteMeta.text = if (loc != null) {
            formatDistance(distance)
        } else {
            "Waiting for current location"
        }
        binding.btnStartNavigation.isEnabled = loc != null
        binding.btnStartNavigation.alpha = if (loc != null) 1f else 0.55f
        if (!is3DMode && !isChoosingDestinationOnMap) {
            binding.routeBottomBar.show()
        }
    }

    private fun updateRoutePreviewFromCachedRoute(route: RouteInfo, currentLoc: Location) {
        val place = selectedPlace ?: return
        val currentPoint = GeoPoint(currentLoc.latitude, currentLoc.longitude)
        val remainingDistance = viewerViewModel.remainingDistanceOnRoute(route, currentPoint)

        binding.tvRouteTitle.text = shortPlaceName(place.name)
        binding.tvRouteMeta.text = formatDistance(remainingDistance)
        binding.btnStartNavigation.isEnabled = true
        binding.btnStartNavigation.alpha = 1f
        if (!is3DMode && !isChoosingDestinationOnMap) {
            binding.routeBottomBar.show()
        }
    }

    private fun requestRouteUpdate(force: Boolean, drawRoute: Boolean) {
        val loc = currentLocation ?: return
        val place = selectedPlace ?: return
        val origin = GeoPoint(loc.latitude, loc.longitude)
        val destination = GeoPoint(place.latitude, place.longitude)
        if (!viewerViewModel.shouldRequestRouteUpdate(origin, force, routeJob?.isActive == true)) {
            return
        }

        routeJob?.cancel()
        routeJob = viewLifecycleOwner.lifecycleScope.launch {
            val route = runCatching {
                viewerViewModel.fetchRoute(origin, destination)
            }.getOrNull()
            val activePlace = selectedPlace
            if (!isAdded ||
                activePlace == null ||
                activePlace.latitude != destination.latitude ||
                activePlace.longitude != destination.longitude
            ) {
                return@launch
            }

            if (route != null && route.points.isNotEmpty()) {
                cachedRoute = route
                updateRouteSummary(route)
                if (drawRoute) {
                    drawRouteLine(route.points)
                }
            } else {
                updateRoutePreviewFromDirectDistance()
                if (drawRoute) {
                    drawRouteLine(listOf(origin, destination))
                }
            }
        }
    }

    private fun updateRouteSummary(route: RouteInfo) {
        binding.tvRouteMeta.text = formatDistance(route.distanceMeters)
        binding.btnStartNavigation.isEnabled = true
        binding.btnStartNavigation.alpha = 1f
        if (!is3DMode && !isChoosingDestinationOnMap) {
            binding.routeBottomBar.show()
        }
    }

    private fun showSelectedRouteOnMap2D() {
        val points = selectedRoutePoints()
        if (points.isEmpty()) return

        val switchedFrom3D = is3DMode
        if (switchedFrom3D) toggle3DMode()
        if (routeLine == null && points.size > 1) {
            drawRouteLine(points)
        }
        clearSearchResults()
        hideKeyboard()

        binding.mapView.postDelayed(
            { zoomToRoutePoints(points) },
            if (switchedFrom3D) 350L else 0L
        )
    }

    private fun selectedRoutePoints(): List<GeoPoint> {
        return viewerViewModel.selectedRoutePoints(routeLine?.actualPoints?.toList())
    }

    private fun zoomToRoutePoints(points: List<GeoPoint>) {
        if (points.isEmpty()) return

        val bounds = if (points.size == 1) {
            val point = points.first()
            BoundingBox(
                point.latitude + 0.001,
                point.longitude + 0.001,
                point.latitude - 0.001,
                point.longitude - 0.001
            )
        } else {
            BoundingBox.fromGeoPointsSafe(points)
        }

        binding.mapView.zoomToBoundingBox(bounds, true, 72.dp)
    }

    private fun drawRouteLine(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (routeLine == null) {
            routeLine = Polyline(binding.mapView).apply {
                outlinePaint.color = Color.rgb(123, 92, 255)
                outlinePaint.strokeWidth = 9f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                setOnClickListener { _, _, _ ->
                    showSelectedRouteOnMap2D()
                    true
                }
            }
            binding.mapView.overlays.add(0, routeLine)
        }

        routeLine?.setPoints(points)
        binding.mapView.invalidate()
    }

    private fun clearRouteLine() {
        routeLine?.let { binding.mapView.overlays.remove(it) }
        routeLine = null
        binding.mapView.invalidate()
    }

    private fun formatDistance(distanceMeters: Double): String {
        return if (distanceMeters < 1000.0) {
            "${distanceMeters.toInt().coerceAtLeast(0)} m"
        } else {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        }
    }

    private fun shortPlaceName(name: String): String {
        return name.substringBefore(",").trim().ifBlank { name }
    }

    private fun applyVisibilityState() {
        if (is3DMode) {
            binding.mapView.hide()
            binding.myGLSurfaceView.show()
            binding.myGLSurfaceView.alpha = 1f
            setTwoDControlsVisible(false)
            mainViewModel.isGnss3DMode.value = true
            scheduleGnssErrorDialogCheck()
        } else {
            binding.mapView.show()
            binding.mapView.alpha = 1f
            binding.myGLSurfaceView.hide()
            setTwoDControlsVisible(true)
            mainViewModel.isGnss3DMode.value = false
            cancelGnssErrorDialogCheck()
        }
    }

    private fun toggle3DMode() {
        if (isChoosingDestinationOnMap) return

        is3DMode = !is3DMode

        if (is3DMode) {
            scheduleGnssErrorDialogCheck()
            setTwoDControlsVisible(false)
            mainViewModel.isGnss3DMode.value = true
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
            cancelGnssErrorDialogCheck()
            mainViewModel.isGnss3DMode.value = false
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
                            setTwoDControlsVisible(true)
                            binding.mapView.invalidate()
                        }
                        .start()
                }
                .start()
        }
    }

    private fun setTwoDControlsVisible(visible: Boolean) = with(binding) {
        if (visible) {
            if (isChoosingDestinationOnMap) {
                setMapTargetPickerControlsVisible(true)
                return@with
            }

            searchBar.show()
            searchBubble.show()
            resultBubble.show()
            navigationBubble.show()
            arBubble.hide()
            icAr.hide()
            currentLocationBubbleNormal.hide()
            currentLocationBubble.show()
            icPin.show()
            icPin.imageTintList = ColorStateList.valueOf(Color.BLACK)
            restoreSearchResultsAfter3DIfNeeded()
            if (selectedPlace != null) {
                routeBottomBar.show()
            }
        } else {
            viewerViewModel.restoreSearchResultsWhenBackTo2D =
                binding.searchResultsPanel.isVisible && shouldRestoreSearchResultsAfter3D()
            searchBar.hide()
            searchBubble.hide()
            arBubble.show()
            icAr.show()
            currentLocationBubbleNormal.show()
            currentLocationBubble.hide()
            searchResultsPanel.hide()
            icPin.show()
            icPin.imageTintList = ColorStateList.valueOf(Color.WHITE)
            routeBottomBar.hide()
            setMapTargetPickerControlsVisible(false)
            hideKeyboard()
        }
    }

    private fun enterMapTargetPickerMode() {
        if (is3DMode || isChoosingDestinationOnMap) return

        isChoosingDestinationOnMap = true
        searchJob?.cancel()
        hideKeyboard()
        searchResultsPanelAllowed = false
        binding.searchResultsPanel.hide()
        setMapTargetPickerControlsVisible(true)
    }

    private fun exitMapTargetPickerMode() {
        if (!isChoosingDestinationOnMap) return

        isChoosingDestinationOnMap = false
        setMapTargetPickerControlsVisible(false)
        setTwoDControlsVisible(true)
    }

    private fun confirmMapTargetSelection() {
        if (!isChoosingDestinationOnMap || is3DMode) return

        val targetPoint = currentMapCenterPoint()

        // Show a brief loading if possible, or just proceed with background geocoding
        lifecycleScope.launch {
            val address = viewerViewModel.reverseGeocode(targetPoint.latitude, targetPoint.longitude)
            val finalName = address ?: MAP_PICKED_PLACE_NAME

            val targetPlace = SearchPlace(
                name = finalName,
                latitude = targetPoint.latitude,
                longitude = targetPoint.longitude
            )

            exitMapTargetPickerMode()
            selectPlace(
                place = targetPlace,
                saveRecentSearch = false,
                moveCameraToPlace = false
            )
        }
    }

    private fun currentMapCenterPoint(): GeoPoint {
        val center = binding.mapView.mapCenter
        return GeoPoint(center.latitude, center.longitude)
    }

    private fun setMapTargetPickerControlsVisible(visible: Boolean) = with(binding) {
        if (visible) {
            searchBar.hide()
            searchBubble.hide()
            chooseOnMapBar.hide()
            resultBubble.hide()
            searchResultsPanel.hide()
            navigationBubble.hide()
            routeBottomBar.hide()
            currentLocationBubble.hide()
            currentLocationBubbleNormal.hide()
            icPin.hide()
            arBubble.hide()
            icAr.hide()
            mapTargetCenterIcon.show()
            backBubble.show()
            icClose.show()
            checkBubble.show()
            icCheck.show()
        } else {
            mapTargetCenterIcon.hide()
            backBubble.hide()
            icClose.hide()
            checkBubble.hide()
            icCheck.hide()
        }

        setMapOverlaysSuppressedForTargetPicker(visible)
    }

    private fun setMapOverlaysSuppressedForTargetPicker(suppressed: Boolean) {
        val enabled = !suppressed
        userMarker?.setEnabled(enabled)
        targetMarker?.setEnabled(enabled)
        routeLine?.setEnabled(enabled)
        binding.mapView.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentGnssViewerBinding.initListener() {
        setupSearchInteractions()
        setupKeyboardVisibilityListener()

        val mapTapDetector =
            GestureDetector(safeContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    return handleUserMarkerTap(e)
                }
            })

        binding.mapView.setOnTouchListener { _, event ->
            mapTapDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (binding.etSearchLocation.hasFocus() || binding.searchResultsPanel.isVisible) {
                    hideKeyboard()
                }
            }
            false
        }

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
                        if (binding.etSearchLocation.hasFocus() || binding.searchResultsPanel.isVisible) {
                            hideKeyboard()
                            binding.searchResultsPanel.hide()
                        }
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
          //  dumpLatestSatelliteSources()
        }

        icAr.setSingleClick {
            navigateTo(R.id.gnssARFragment)
        }

        chooseOnMapBubble.setSingleClick {
            enterMapTargetPickerMode()
        }

        chooseOnMapBar.setSingleClick {
            enterMapTargetPickerMode()
        }

        icClose.setSingleClick {
            exitMapTargetPickerMode()
        }

        backBubble.setSingleClick {
            exitMapTargetPickerMode()
        }

        icCheck.setSingleClick {
            confirmMapTargetSelection()
        }

        checkBubble.setSingleClick {
            confirmMapTargetSelection()
        }
    }

    private fun setupMapModeSwitchOverlay() {
        val mapOverlay = object : Overlay() {
            override fun onDoubleTap(e: MotionEvent, mapView: MapView): Boolean {
                if (isChoosingDestinationOnMap) return false

                toggle3DMode()
                return true
            }
        }
        binding.mapView.overlays.add(mapOverlay)
    }

    private fun handleUserMarkerTap(event: MotionEvent): Boolean {
        if (isChoosingDestinationOnMap) return false

        val loc = currentLocation ?: return false
        if (!isTapInsideUserMarker(event)) return false

        showLocationDetailsDialog(loc)
        return true
    }

    private fun isTapInsideUserMarker(event: MotionEvent): Boolean {
        val marker = userMarker ?: return false
        val markerPosition = marker.position ?: return false
        val markerPoint = android.graphics.Point()
        binding.mapView.projection.toPixels(markerPosition, markerPoint)

        val iconWidth = marker.icon?.intrinsicWidth?.takeIf { it > 0 } ?: 40.dp
        val iconHeight = marker.icon?.intrinsicHeight?.takeIf { it > 0 } ?: 40.dp
        val horizontalLimit = (iconWidth / 2f).coerceAtLeast(24.dp.toFloat())
        val topLimit = iconHeight.toFloat().coerceAtLeast(48.dp.toFloat())
        val bottomLimit = 16.dp.toFloat()

        val horizontalDistance = kotlin.math.abs(event.x - markerPoint.x)
        val distanceAboveAnchor = markerPoint.y - event.y
        return horizontalDistance <= horizontalLimit &&
            distanceAboveAnchor <= topLimit &&
            distanceAboveAnchor >= -bottomLimit
    }

    private fun dumpLatestSatelliteSources() {
        val satellites = viewerViewModel.latestSatelliteSnapshot
        Log.d(
            "GNSS_SOURCE_DUMP",
            "satellites=${satellites.size} lastStatus=${viewerViewModel.lastGnssStatusSatelliteCount} " +
                "lastMeasurements=${viewerViewModel.lastGnssMeasurementCount}"
        )
        if (satellites.isEmpty()) return

        satellites
            .groupingBy { it.positionSource }
            .eachCount()
            .forEach { (source, count) ->
                Log.d("GNSS_SOURCE_DUMP", "source=$source count=$count")
            }

        satellites
            .sortedWith(compareBy<SatelliteInfo> { it.constellationType }.thenBy { it.svid })
            .forEach { sat ->
                Log.d(
                    "GNSS_SOURCE_DUMP",
                    "${constellationLabel(sat.constellationType)} svid=${sat.svid} " +
                        "source=${sat.positionSource} " +
                        "eph=${sat.ephemerisSource ?: "-"} " +
                        "used=${sat.usedInFix} " +
                        "cn0=${"%.1f".format(Locale.US, sat.cn0DbHz)} " +
                        "el=${"%.1f".format(Locale.US, sat.elevationDegrees)} " +
                        "az=${"%.1f".format(Locale.US, sat.azimuthDegrees)}"
                )
            }
    }

    private fun constellationLabel(constellationType: Int): String {
        return when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "CONST_$constellationType"
        }
    }

    @SuppressLint("MissingPermission")
    private fun recenterMap() {
        if (!hasLocationPermission()) return

        val loc = currentLocation ?: if (::locationManager.isInitialized) {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null || useTestLocation) mainViewModel.getEffectiveLocation(lastKnown) else null
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
                cancelGnssErrorDialogCheck()
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
        resetRouteModeAfterLiveRoutingIfNeeded()
        if (rendererSet) {
            binding.myGLSurfaceView.onResume()
        }
        if (hasLocationPermission()) {
            startLocationUpdates()
        }
        if (is3DMode) {
            scheduleGnssErrorDialogCheck()
        }
        startRealTimeTicker()
    }

    private fun resetRouteModeAfterLiveRoutingIfNeeded() {
        if (!mainViewModel.resetGnssViewerRouteOnResume) return
        mainViewModel.resetGnssViewerRouteOnResume = false
        mainViewModel.liveRouteState = null
        resetRouteMode()
    }

    private fun startRealTimeTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                // Use system time for ticking, or location time if one is available and fresh
                val displayTime = currentLocation?.let { loc ->
                    val age = System.currentTimeMillis() - loc.time
                    if (age < 5000) loc.time else System.currentTimeMillis()
                } ?: System.currentTimeMillis()
                mainViewModel.setCurrentTime(displayTime)
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelGnssErrorDialogCheck()
        binding.mapView.onPause()
        if (rendererSet) {
            binding.myGLSurfaceView.onPause()
        }
        stopLocationUpdates()
        currentLocation = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isChoosingDestinationOnMap = false
        cancelGnssErrorDialogCheck()
        mainViewModel.isGnss3DMode.value = false
        searchJob?.cancel()
        routeJob?.cancel()
        stopLocationUpdates()
        userMarker = null
        targetMarker = null
        routeLine = null
    }

    private companion object {
        const val GNSS_ERROR_DIALOG_DELAY_MS = 10_000L
        const val MAP2D_DIALOG_DEBOUNCE_MS = 500L
        const val MAP_PICKED_PLACE_NAME = "Selected map point"
        var hasGnssErrorDialogShownThisSession = false
    }
}
