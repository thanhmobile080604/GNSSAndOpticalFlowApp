package com.example.gnssandopticalflowapp.model


data class RouteLatLng(val lat: Double, val lon: Double)

data class RouteSession(
    val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val destinationName: String,
    val start: RouteLatLng,
    val destination: RouteLatLng,
    val routePoints: List<RouteLatLng>,
    val gnssTravelSegments: List<List<RouteLatLng>>,
    val opticalAssistSegments: List<List<RouteLatLng>>,
    val weakPoints: List<RouteLatLng>,
    val strongPoints: List<RouteLatLng>
) {
    fun toSummary(): RouteSessionSummary {
        return RouteSessionSummary(
            id = id,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            destinationName = destinationName,
            outagePointCount = opticalAssistSegments.sumOf { it.size },
            gnssPointCount = gnssTravelSegments.sumOf { it.size }
        )
    }
}

/** Lightweight row model for the RouteSessionListFragment list (no full geometry). */
data class RouteSessionSummary(
    val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val destinationName: String,
    val outagePointCount: Int,
    val gnssPointCount: Int
)
