package com.example.gnssandopticalflowapp.screen.viewmodel

import android.app.Application
import android.location.GnssStatus
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import com.example.gnssandopticalflowapp.function.gnss.MapPlaceRepository
import com.example.gnssandopticalflowapp.function.gnss.MapRouteRepository
import com.example.gnssandopticalflowapp.function.gnss.GnssSatelliteTracker
import com.example.gnssandopticalflowapp.model.RouteInfo
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.model.SearchPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

class GNSSViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val placeRepository = MapPlaceRepository(application)
    private val routeRepository = MapRouteRepository()

    val satelliteTracker = GnssSatelliteTracker()

    var is3DMode = false
    var currentLocation: Location? = null
    var selectedPlace: SearchPlace? = null
    var cachedRoute: RouteInfo? = null
    var restoreSearchResultsWhenBackTo2D = false
    var gnssStatusRegistered = false
    var gnssMeasurementsRegistered = false
    var lastGnssStatusSatelliteCount = 0
    var lastGnssMeasurementCount = 0
    var latestSatelliteSnapshot: List<SatelliteInfo> = emptyList()

    private var lastRouteRequestAt = 0L
    private var lastRouteOrigin: GeoPoint? = null

    suspend fun searchPlaces(query: String): List<SearchPlace> {
        return withContext(Dispatchers.IO) {
            placeRepository.searchPlaces(query)
        }
    }

    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return withContext(Dispatchers.IO) {
            placeRepository.reverseGeocode(latitude, longitude)
        }
    }

    fun getRecentSearches(): List<SearchPlace> = placeRepository.getRecentSearches()

    fun saveRecentSearch(place: SearchPlace) {
        placeRepository.saveRecentSearch(place)
    }

    fun selectPlace(place: SearchPlace) {
        selectedPlace = place
        cachedRoute = null
        resetRouteRequestThrottle()
    }

    fun resetRouteState() {
        selectedPlace = null
        cachedRoute = null
        resetRouteRequestThrottle()
    }

    suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint): RouteInfo? {
        return withContext(Dispatchers.IO) {
            routeRepository.fetchRoute(origin, destination)
        }
    }

    fun shouldRequestRouteUpdate(
        origin: GeoPoint,
        force: Boolean,
        routeRequestInFlight: Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        val movedMeters = lastRouteOrigin?.distanceToAsDouble(origin) ?: Double.MAX_VALUE
        val isTooSoon = now - lastRouteRequestAt < ROUTE_REFRESH_INTERVAL_MS

        if (!force && routeRequestInFlight) return false
        if (!force && isTooSoon && movedMeters < ROUTE_REFRESH_DISTANCE_METERS) return false

        lastRouteRequestAt = now
        lastRouteOrigin = origin
        return true
    }

    fun selectedRoutePoints(existingRoutePoints: List<GeoPoint>?): List<GeoPoint> {
        existingRoutePoints?.takeIf { it.isNotEmpty() }?.let { return it }
        cachedRoute?.points?.takeIf { it.isNotEmpty() }?.let { return it }

        val place = selectedPlace ?: return emptyList()
        val destination = GeoPoint(place.latitude, place.longitude)
        val loc = currentLocation ?: return listOf(destination)
        return listOf(GeoPoint(loc.latitude, loc.longitude), destination)
    }

    fun remainingDistanceOnRoute(route: RouteInfo, currentPoint: GeoPoint): Double {
        var minDistance = Double.MAX_VALUE
        var closestIndex = 0

        route.points.forEachIndexed { index, point ->
            val distance = currentPoint.distanceToAsDouble(point)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }

        var remainingDistance = minDistance
        for (index in closestIndex until route.points.size - 1) {
            remainingDistance += route.points[index].distanceToAsDouble(route.points[index + 1])
        }
        return remainingDistance
    }

    fun directDistanceMeters(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            startLatitude,
            startLongitude,
            endLatitude,
            endLongitude,
            results
        )
        return results[0].toDouble()
    }

    fun shouldRestoreSearchResultsAfter3D(
        query: String,
        searchResultCount: Int
    ): Boolean {
        return query.isNotEmpty() &&
            selectedPlace == null &&
            searchResultCount > 0
    }

    fun consumeRestoreSearchResultsAfter3D(): Boolean {
        return restoreSearchResultsWhenBackTo2D.also {
            restoreSearchResultsWhenBackTo2D = false
        }
    }

    fun updateSatelliteSnapshot(status: GnssStatus): List<SatelliteInfo> {
        val satellites = satelliteTracker.buildSatelliteInfo(status, currentLocation)
        latestSatelliteSnapshot = satellites
        return satellites
    }

    fun resetGnssRuntimeState() {
        lastGnssStatusSatelliteCount = 0
        lastGnssMeasurementCount = 0
        latestSatelliteSnapshot = emptyList()
        satelliteTracker.clear()
    }

    fun hasUsableGnssFor3D(): Boolean {
        return gnssStatusRegistered && lastGnssStatusSatelliteCount > 0
    }

    private fun resetRouteRequestThrottle() {
        lastRouteRequestAt = 0L
        lastRouteOrigin = null
    }

    private companion object {
        const val ROUTE_REFRESH_INTERVAL_MS = 8000L
        const val ROUTE_REFRESH_DISTANCE_METERS = 25.0
    }
}
