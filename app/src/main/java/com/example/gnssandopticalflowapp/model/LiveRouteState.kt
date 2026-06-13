package com.example.gnssandopticalflowapp.model

import android.location.Location
import org.osmdroid.util.GeoPoint

data class LiveRouteState(
    val destination: SearchPlace,
    val startLocation: Location,
    val routePoints: List<GeoPoint>,
    val distanceMeters: Double?
)
