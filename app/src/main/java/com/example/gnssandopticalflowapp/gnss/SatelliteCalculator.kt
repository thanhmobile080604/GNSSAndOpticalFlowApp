package com.example.gnssandopticalflowapp.gnss

import android.location.GnssStatus
import com.example.gnssandopticalflowapp.model.SatellitePositionResult
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SatelliteCalculator {
    private const val EARTH_RADIUS_M = 6378137.0
    private const val MU = 3.986004418e14 // Earth's gravitational constant (m^3/s^2)

    /**
     * Returns the approximate orbital radius (from Earth's center in meters) and the 
     * orbital speed (in m/s) based on the constellation type and SVID.
     */
    fun getOrbitRadiusAndSpeed(constellationType: Int, svid: Int): Pair<Double, Double> {
        val radius: Double = when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> EARTH_RADIUS_M + 20200000.0 // MEO ~20,200 km
            GnssStatus.CONSTELLATION_GLONASS -> EARTH_RADIUS_M + 19100000.0 // MEO ~19,100 km
            GnssStatus.CONSTELLATION_GALILEO -> EARTH_RADIUS_M + 23222000.0 // MEO ~23,222 km
            GnssStatus.CONSTELLATION_BEIDOU -> {
                // Approximate grouping of BeiDou satellites (GEO, IGSO, MEO)
                if (svid in 1..5 || svid >= 59) {
                    EARTH_RADIUS_M + 35786000.0 // GEO/IGSO ~35,786 km
                } else if (svid in listOf(6, 7, 8, 9, 10, 13, 16, 38, 39, 40)) {
                    EARTH_RADIUS_M + 35786000.0 // IGSO
                } else {
                    EARTH_RADIUS_M + 21528000.0 // MEO ~21,528 km
                }
            }
            GnssStatus.CONSTELLATION_QZSS -> EARTH_RADIUS_M + 35786000.0 // Geosynchronous ~35,786 km average
            GnssStatus.CONSTELLATION_IRNSS -> EARTH_RADIUS_M + 35786000.0 // Geosynchronous ~35,786 km
            GnssStatus.CONSTELLATION_SBAS -> EARTH_RADIUS_M + 35786000.0 // GEO ~35,786 km
            else -> EARTH_RADIUS_M + 20200000.0 // Default to GPS MEO
        }

        val speed = sqrt(MU / radius) // m/s
        return Pair(radius, speed)
    }

    /**
     * Calculates the Latitude, Longitude, Altitude, and ECEF coordinates 
     * of a satellite given the observer's location, azimuth, and elevation.
     */
    fun calculateSatellitePosition(
        observerLat: Double,
        observerLon: Double,
        azimuthDegrees: Float,
        elevationDegrees: Float,
        orbitRadius: Double
    ): SatellitePositionResult {
        // Convert to radians
        val latRad = Math.toRadians(observerLat)
        val lonRad = Math.toRadians(observerLon)
        val azRad = Math.toRadians(azimuthDegrees.toDouble())
        val elRad = Math.toRadians(elevationDegrees.toDouble())

        // 1. Calculate range from observer to satellite using law of cosines on the sphere
        // R_sat^2 = R_earth^2 + r^2 - 2 * R_earth * r * cos(90 + El)
        // r^2 + 2 * R_earth * sin(El) * r + R_earth^2 - R_sat^2 = 0
        val sinEl = sin(elRad)
        val a = 1.0
        val b = 2.0 * EARTH_RADIUS_M * sinEl
        val c = EARTH_RADIUS_M * EARTH_RADIUS_M - orbitRadius * orbitRadius

        val discriminant = b * b - 4 * a * c
        val range = if (discriminant >= 0) {
            (-b + sqrt(discriminant)) / (2.0 * a)
        } else {
            orbitRadius - EARTH_RADIUS_M // Fallback to vertical if bizarre math error
        }

        // 2. Convert to Topocentric ENU (East, North, Up) coordinates from observer
        val e = range * cos(elRad) * sin(azRad)
        val n = range * cos(elRad) * cos(azRad)
        val u = range * sinEl

        // 3. User ECEF coordinates (assuming sphere for simplicity)
        val userX = EARTH_RADIUS_M * cos(latRad) * cos(lonRad)
        val userY = EARTH_RADIUS_M * cos(latRad) * sin(lonRad)
        val userZ = EARTH_RADIUS_M * sin(latRad)

        // 4. Transform ENU to ECEF for the satellite
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinLon = sin(lonRad)
        val cosLon = cos(lonRad)

        val ecefX = userX - sinLon * e - sinLat * cosLon * n + cosLat * cosLon * u
        val ecefY = userY + cosLon * e - sinLat * sinLon * n + cosLat * sinLon * u
        val ecefZ = userZ + cosLat * n + sinLat * u

        // 5. Convert Satellite ECEF back to LLA (Sphere approximation)
        val p = sqrt(ecefX * ecefX + ecefY * ecefY)
        val satLat = Math.toDegrees(atan2(ecefZ, p))
        val satLon = Math.toDegrees(atan2(ecefY, ecefX))
        val satAlt = sqrt(ecefX * ecefX + ecefY * ecefY + ecefZ * ecefZ) - EARTH_RADIUS_M

        return SatellitePositionResult(
            latitude = satLat,
            longitude = satLon,
            altitude = satAlt,
            ecefX = ecefX,
            ecefY = ecefY,
            ecefZ = ecefZ
        )
    }
}
