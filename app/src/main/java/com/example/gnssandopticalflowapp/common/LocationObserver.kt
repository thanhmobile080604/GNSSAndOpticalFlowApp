package com.example.gnssandopticalflowapp.common

import kotlinx.coroutines.flow.Flow

interface LocationObserver {
    val isLocationPermitted: Flow<Boolean>
    val isGpsEnabled: Flow<Boolean>

    fun refreshPermissionState()
    fun refreshGpsState()
}