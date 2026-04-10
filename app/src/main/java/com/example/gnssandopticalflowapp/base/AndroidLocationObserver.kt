package com.example.gnssandopticalflowapp.base

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidLocationObserver(
    private val context: Context
) : LocationObserver {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val locationPermissionState = MutableStateFlow(checkLocationPermission())
    private val gpsEnabledState = MutableStateFlow(checkGpsEnabled())

    override val isLocationPermitted: Flow<Boolean> = locationPermissionState.asStateFlow()

    override val isGpsEnabled: Flow<Boolean> = gpsEnabledState.asStateFlow()

    override fun refreshPermissionState() {
        locationPermissionState.value = checkLocationPermission()
    }

    override fun refreshGpsState() {
        gpsEnabledState.value = checkGpsEnabled()
    }

    fun getCurrentPermissionState(): Boolean {
        return locationPermissionState.value
    }

    fun getCurrentGpsState(): Boolean {
        return gpsEnabledState.value
    }

    private fun checkLocationPermission(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocationGranted || coarseLocationGranted
    }

    private fun checkGpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }
}