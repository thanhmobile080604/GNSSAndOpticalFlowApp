package com.example.gnssandopticalflowapp.gnss

import android.location.GnssStatus
import com.example.gnssandopticalflowapp.model.SatellitePositionResult
import java.lang.Math.cbrt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SatelliteCalculator {
    private const val EARTH_RADIUS_M = 6378137.0
    private const val WGS84_A = 6378137.0
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_B = WGS84_A * (1.0 - WGS84_F)
    private const val WGS84_E2 = 1.0 - (WGS84_B * WGS84_B) / (WGS84_A * WGS84_A)
    private const val WGS84_EP2 = (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_B * WGS84_B)
    private const val MU = 3.986004418e14 // Earth's gravitational constant (m^3/s^2)
    private const val SECONDS_PER_DAY = 86400.0

    data class OrbitStateResult(
        val position: SatellitePositionResult,
        val speedMetersPerSecond: Double
    )

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

    fun calculateSatellitePositionFromEcef(
        ecefX: Double,
        ecefY: Double,
        ecefZ: Double
    ): SatellitePositionResult {
        val p = sqrt((ecefX * ecefX) + (ecefY * ecefY))
        val theta = atan2(ecefZ * WGS84_A, p * WGS84_B)
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)

        val latitude = atan2(
            ecefZ + (WGS84_EP2 * WGS84_B * sinTheta * sinTheta * sinTheta),
            p - (WGS84_E2 * WGS84_A * cosTheta * cosTheta * cosTheta)
        )
        val longitude = atan2(ecefY, ecefX)
        val sinLatitude = sin(latitude)
        val radiusOfCurvature = WGS84_A / sqrt(1.0 - (WGS84_E2 * sinLatitude * sinLatitude))
        val altitude = (p / cos(latitude)) - radiusOfCurvature

        return SatellitePositionResult(
            latitude = Math.toDegrees(latitude),
            longitude = Math.toDegrees(longitude),
            altitude = altitude,
            ecefX = ecefX,
            ecefY = ecefY,
            ecefZ = ecefZ
        )
    }

    fun calculateSpeedFromEcefVelocity(
        velocityX: Double?,
        velocityY: Double?,
        velocityZ: Double?
    ): Double? {
        if (velocityX == null || velocityY == null || velocityZ == null) return null
        return sqrt(
            (velocityX * velocityX) +
                (velocityY * velocityY) +
                (velocityZ * velocityZ)
        )
    }

    fun calculateSatellitePositionFromMeanElements(
        epochUtcMillis: Long,
        meanMotionRevPerDay: Double,
        eccentricity: Double,
        inclinationDeg: Double,
        raanDeg: Double,
        argOfPerigeeDeg: Double,
        meanAnomalyDeg: Double,
        observationUtcMillis: Long = System.currentTimeMillis()
    ): OrbitStateResult {
        val meanMotionRadPerSec = meanMotionRevPerDay * (2.0 * Math.PI) / SECONDS_PER_DAY
        val semiMajorAxis = cbrt(MU / (meanMotionRadPerSec * meanMotionRadPerSec))

        val deltaSeconds = (observationUtcMillis - epochUtcMillis) / 1000.0
        val meanAnomalyRad = normalizeRadians(
            Math.toRadians(meanAnomalyDeg) + (meanMotionRadPerSec * deltaSeconds)
        )

        val eccentricAnomaly = solveKepler(meanAnomalyRad, eccentricity)
        val trueAnomaly = 2.0 * atan2(
            sqrt(1.0 + eccentricity) * sin(eccentricAnomaly / 2.0),
            sqrt(1.0 - eccentricity) * cos(eccentricAnomaly / 2.0)
        )

        val orbitalRadius = semiMajorAxis * (1.0 - (eccentricity * cos(eccentricAnomaly)))
        val argumentOfLatitude = Math.toRadians(argOfPerigeeDeg) + trueAnomaly
        val inclinationRad = Math.toRadians(inclinationDeg)
        val raanRad = Math.toRadians(raanDeg)

        val cosRaan = cos(raanRad)
        val sinRaan = sin(raanRad)
        val cosInclination = cos(inclinationRad)
        val sinInclination = sin(inclinationRad)
        val cosArgumentOfLatitude = cos(argumentOfLatitude)
        val sinArgumentOfLatitude = sin(argumentOfLatitude)

        val eciX = orbitalRadius * (
            (cosRaan * cosArgumentOfLatitude) -
                (sinRaan * sinArgumentOfLatitude * cosInclination)
            )
        val eciY = orbitalRadius * (
            (sinRaan * cosArgumentOfLatitude) +
                (cosRaan * sinArgumentOfLatitude * cosInclination)
            )
        val eciZ = orbitalRadius * sinArgumentOfLatitude * sinInclination

        // CelesTrak GP OMM data is in TEME/SGP4 terms. This rotation gives a practical
        // Earth-fixed position for visualization, not a full broadcast-ephemeris solution.
        val earthRotationAngle = calculateGreenwichSiderealAngleRadians(observationUtcMillis)
        val cosTheta = cos(earthRotationAngle)
        val sinTheta = sin(earthRotationAngle)
        val ecefX = (cosTheta * eciX) + (sinTheta * eciY)
        val ecefY = (-sinTheta * eciX) + (cosTheta * eciY)
        val ecefZ = eciZ

        val speed = sqrt(MU * ((2.0 / orbitalRadius) - (1.0 / semiMajorAxis)))

        return OrbitStateResult(
            position = calculateSatellitePositionFromEcef(
                ecefX = ecefX,
                ecefY = ecefY,
                ecefZ = ecefZ
            ),
            speedMetersPerSecond = speed
        )
    }

    private fun solveKepler(meanAnomaly: Double, eccentricity: Double): Double {
        var eccentricAnomaly = if (eccentricity < 0.8) meanAnomaly else Math.PI
        repeat(10) {
            val numerator = eccentricAnomaly - (eccentricity * sin(eccentricAnomaly)) - meanAnomaly
            val denominator = 1.0 - (eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly -= numerator / denominator
        }
        return eccentricAnomaly
    }

    private fun calculateGreenwichSiderealAngleRadians(utcMillis: Long): Double {
        val julianDate = 2440587.5 + (utcMillis / 1000.0) / SECONDS_PER_DAY
        val julianCenturies = (julianDate - 2451545.0) / 36525.0
        val gmstDegrees = 280.46061837 +
            (360.98564736629 * (julianDate - 2451545.0)) +
            (0.000387933 * julianCenturies.pow(2.0)) -
            (julianCenturies.pow(3.0) / 38710000.0)
        return normalizeRadians(Math.toRadians(gmstDegrees))
    }

    private fun normalizeRadians(value: Double): Double {
        val twoPi = 2.0 * Math.PI
        var normalized = value % twoPi
        if (normalized < 0.0) normalized += twoPi
        return normalized
    }
}
