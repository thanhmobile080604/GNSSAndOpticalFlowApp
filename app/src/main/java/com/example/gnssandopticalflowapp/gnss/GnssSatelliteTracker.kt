package com.example.gnssandopticalflowapp.gnss

import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.example.gnssandopticalflowapp.gnss.gnss_source.CelesTrakSatelliteRepository
import com.example.gnssandopticalflowapp.gnss.gnss_source.GnssSatellitePVTResolver
import com.example.gnssandopticalflowapp.gnss.gnss_source.SatelliteCalculator
import com.example.gnssandopticalflowapp.model.OrbitRecord
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.model.SatelliteKey
import com.example.gnssandopticalflowapp.model.SatellitePvtSnapshot

class GnssSatelliteTracker {
    private val latestSatellitePvt = mutableMapOf<SatelliteKey, SatellitePvtSnapshot>()
    private val latestCelesTrakOrbits = mutableMapOf<SatelliteKey, OrbitRecord>()
    private val lastLoggedSource = mutableMapOf<SatelliteKey, String>()
    private var celesTrakRefreshInFlight = false

    fun updateMeasurements(eventArgs: GnssMeasurementsEvent) {
        val now = SystemClock.elapsedRealtimeNanos()
        for (measurement in eventArgs.measurements) {
            val satelliteKey = SatelliteKey(measurement.constellationType, measurement.svid)
            val snapshot = GnssSatellitePVTResolver.extractPvt(measurement) ?: continue
            latestSatellitePvt[satelliteKey] = snapshot.copy(capturedAtElapsedRealtimeNanos = now)
        }

        removeStalePvt(now)
    }

    suspend fun refreshCelesTrakDataIfNeeded(forceRefresh: Boolean = false) {
        if (celesTrakRefreshInFlight) return

        celesTrakRefreshInFlight = true
        try {
            val snapshot = CelesTrakSatelliteRepository.getSnapshot(forceRefresh) ?: return

            latestCelesTrakOrbits.clear()
            snapshot.records.forEach { (key, orbit) ->
                latestCelesTrakOrbits[SatelliteKey(key.constellationType, key.svid)] = orbit
            }
        } finally {
            celesTrakRefreshInFlight = false
        }
    }

    fun buildSatelliteInfo(status: GnssStatus, currentLocation: Location?): List<SatelliteInfo> {
        val now = SystemClock.elapsedRealtimeNanos()
        return buildList {
            for (index in 0 until status.satelliteCount) {
                add(buildSatelliteInfo(status, index, currentLocation, now))
            }
        }
    }

    fun clear() {
        latestSatellitePvt.clear()
        latestCelesTrakOrbits.clear()
        lastLoggedSource.clear()
        celesTrakRefreshInFlight = false
    }

    private fun buildSatelliteInfo(
        status: GnssStatus,
        index: Int,
        currentLocation: Location?,
        now: Long
    ): SatelliteInfo {
        val svid = status.getSvid(index)
        val constellation = status.getConstellationType(index)
        val satelliteKey = SatelliteKey(constellation, svid)
        val elevationDegrees = status.getElevationDegrees(index)
        val azimuthDegrees = status.getAzimuthDegrees(index)
        val freq = if (status.hasCarrierFrequencyHz(index)) status.getCarrierFrequencyHz(index) else 0f

        val resolvedPosition = resolvePosition(
            satelliteKey = satelliteKey,
            constellation = constellation,
            svid = svid,
            elevationDegrees = elevationDegrees,
            azimuthDegrees = azimuthDegrees,
            currentLocation = currentLocation,
            now = now
        )

        logSourceChange(satelliteKey, constellation, svid, resolvedPosition.positionSource)

        return SatelliteInfo(
            svid = svid,
            constellationType = constellation,
            elevationDegrees = elevationDegrees,
            azimuthDegrees = azimuthDegrees,
            cn0DbHz = status.getCn0DbHz(index),
            usedInFix = status.usedInFix(index),
            carrierFrequencyHz = freq,
            latitude = resolvedPosition.latitude,
            longitude = resolvedPosition.longitude,
            altitude = resolvedPosition.altitude,
            speed = resolvedPosition.speed,
            positionSource = resolvedPosition.positionSource,
            ephemerisSource = resolvedPosition.ephemerisSource
        )
    }

    private fun resolvePosition(
        satelliteKey: SatelliteKey,
        constellation: Int,
        svid: Int,
        elevationDegrees: Float,
        azimuthDegrees: Float,
        currentLocation: Location?,
        now: Long
    ): ResolvedSatellitePosition {
        val pvtSnapshot = latestSatellitePvt[satelliteKey]?.takeIf { snapshot ->
            now - snapshot.capturedAtElapsedRealtimeNanos <= PVT_STALE_THRESHOLD_NANOS
        }
        if (pvtSnapshot != null) {
            return resolveFromPvt(pvtSnapshot)
        }

        val celesTrakOrbit = latestCelesTrakOrbits[satelliteKey]
        if (celesTrakOrbit != null) {
            val celesTrakPosition = resolveFromCelesTrak(celesTrakOrbit)
            if (celesTrakPosition != null) return celesTrakPosition
        }

        return resolveApproximate(
            constellation = constellation,
            svid = svid,
            elevationDegrees = elevationDegrees,
            azimuthDegrees = azimuthDegrees,
            currentLocation = currentLocation
        )
    }

    private fun resolveFromPvt(snapshot: SatellitePvtSnapshot): ResolvedSatellitePosition {
        val pos = SatelliteCalculator.calculateSatellitePositionFromEcef(
            ecefX = snapshot.ecefX,
            ecefY = snapshot.ecefY,
            ecefZ = snapshot.ecefZ
        )

        return ResolvedSatellitePosition(
            latitude = pos.latitude,
            longitude = pos.longitude,
            altitude = pos.altitude,
            speed = SatelliteCalculator.calculateSpeedFromEcefVelocity(
                snapshot.velocityXMetersPerSecond,
                snapshot.velocityYMetersPerSecond,
                snapshot.velocityZMetersPerSecond
            ) ?: 0.0,
            positionSource = "Real GNSS PVT",
            ephemerisSource = GnssSatellitePVTResolver.getEphemerisSourceLabel(snapshot.ephemerisSource)
        )
    }

    private fun resolveFromCelesTrak(orbit: OrbitRecord): ResolvedSatellitePosition? {
        val orbitState = runCatching {
            SatelliteCalculator.calculateSatellitePositionFromMeanElements(
                epochUtcMillis = orbit.epochUtcMillis,
                meanMotionRevPerDay = orbit.meanMotionRevPerDay,
                eccentricity = orbit.eccentricity,
                inclinationDeg = orbit.inclinationDeg,
                raanDeg = orbit.raanDeg,
                argOfPerigeeDeg = orbit.argOfPerigeeDeg,
                meanAnomalyDeg = orbit.meanAnomalyDeg
            )
        }.getOrNull() ?: return null

        return ResolvedSatellitePosition(
            latitude = orbitState.position.latitude,
            longitude = orbitState.position.longitude,
            altitude = orbitState.position.altitude,
            speed = orbitState.speedMetersPerSecond,
            positionSource = "CelesTrak GP",
            ephemerisSource = buildEphemerisLabel(orbit)
        )
    }

    private fun resolveApproximate(
        constellation: Int,
        svid: Int,
        elevationDegrees: Float,
        azimuthDegrees: Float,
        currentLocation: Location?
    ): ResolvedSatellitePosition {
        val (orbitRadius, orbitSpeed) = SatelliteCalculator.getOrbitRadiusAndSpeed(constellation, svid)
        val loc = currentLocation
        if (loc == null) {
            return ResolvedSatellitePosition(speed = orbitSpeed)
        }

        val pos = SatelliteCalculator.calculateSatellitePosition(
            observerLat = loc.latitude,
            observerLon = loc.longitude,
            azimuthDegrees = azimuthDegrees,
            elevationDegrees = elevationDegrees,
            orbitRadius = orbitRadius
        )

        return ResolvedSatellitePosition(
            latitude = pos.latitude,
            longitude = pos.longitude,
            altitude = pos.altitude,
            speed = orbitSpeed
        )
    }

    private fun buildEphemerisLabel(orbit: OrbitRecord): String? {
        return buildString {
            orbit.noradCatalogId?.let {
                append("NORAD ")
                append(it)
            }
            if (orbit.objectName.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append(orbit.objectName)
            }
        }.ifBlank { null }
    }

    private fun removeStalePvt(now: Long) {
        latestSatellitePvt.entries.removeAll { (_, snapshot) ->
            now - snapshot.capturedAtElapsedRealtimeNanos > PVT_STALE_THRESHOLD_NANOS
        }
    }

    private fun logSourceChange(
        satelliteKey: SatelliteKey,
        constellation: Int,
        svid: Int,
        positionSource: String
    ) {
        if (lastLoggedSource[satelliteKey] == positionSource) return

        lastLoggedSource[satelliteKey] = positionSource
        Log.d("GNSS_SOURCE", "sat=$constellation/$svid source=$positionSource")
    }

    private data class ResolvedSatellitePosition(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val altitude: Double = 0.0,
        val speed: Double = 0.0,
        val positionSource: String = "Approximate",
        val ephemerisSource: String? = null
    )

    private companion object {
        const val PVT_STALE_THRESHOLD_NANOS = 10_000_000_000L
    }
}
