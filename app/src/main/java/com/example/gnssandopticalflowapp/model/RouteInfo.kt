package com.example.gnssandopticalflowapp.model

import org.osmdroid.util.GeoPoint

data class RouteInfo(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double
)