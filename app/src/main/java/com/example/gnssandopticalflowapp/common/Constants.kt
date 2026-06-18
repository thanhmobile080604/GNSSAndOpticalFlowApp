package com.example.gnssandopticalflowapp.common

object Constants {
    // Debug: log true-GNSS vs dead-reckoned tracks to a .txt in the app's external files dir for
    // accuracy analysis (see RouteDebugLogger). Turn off for release.
    const val DEBUG_ROUTE_LOG = true

    const val USE_FAKE_LOCATION = false
    const val FAKE_LOCATION_PROVIDER = "test_new_york"
    const val FAKE_LOCATION_LATITUDE = 40.712776
    const val FAKE_LOCATION_LONGITUDE = -74.005974
    const val FAKE_LOCATION_ALTITUDE = 10.0
    const val FAKE_LOCATION_ACCURACY_METERS = 5f
    const val FAKE_LOCATION_SPEED_METERS_PER_SECOND = 0f
    const val FAKE_LOCATION_BEARING_DEGREES = 0f
    const val FAKE_LOCATION_TIME_ZONE_ID = "America/New_York"
}
