package com.example.gnssandopticalflowapp.gnss

import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.example.gnssandopticalflowapp.gnss.gnss_source.celes_trak.CelesTrakSatelliteRepository
import com.example.gnssandopticalflowapp.gnss.gnss_source.pvt.GnssSatellitePVTResolver
import com.example.gnssandopticalflowapp.gnss.gnss_source.approximation.SatelliteCalculator
import com.example.gnssandopticalflowapp.gnss.gnss_source.celes_trak.Sgp4OrbitPropagator
import com.example.gnssandopticalflowapp.gnss.gnss_source.igs.IgsBroadcastEphemerisPropagator
import com.example.gnssandopticalflowapp.gnss.gnss_source.igs.IgsBroadcastEphemerisRepository
import com.example.gnssandopticalflowapp.model.BroadcastEphemerisRecord
import com.example.gnssandopticalflowapp.model.OrbitRecord
import com.example.gnssandopticalflowapp.model.ResolvedSatellitePosition
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.model.SatelliteKey
import com.example.gnssandopticalflowapp.model.SatellitePvtSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GnssSatelliteTracker {
    private val latestSatellitePvt = mutableMapOf<SatelliteKey, SatellitePvtSnapshot>()
    private val latestBroadcastEphemerides = mutableMapOf<SatelliteKey, List<BroadcastEphemerisRecord>>()
    private val latestCelesTrakOrbits = mutableMapOf<SatelliteKey, OrbitRecord>()
    private val lastLoggedSource = mutableMapOf<SatelliteKey, String>()
    private val lastLoggedCelesTrakMiss = mutableSetOf<SatelliteKey>()
    private var igsRefreshInFlight = false
    private var celesTrakRefreshInFlight = false
    private var loggedEmptyCelesTrakCache = false
    private var lastPvtProbeLogElapsedRealtimeNanos = 0L

    fun updateMeasurements(eventArgs: GnssMeasurementsEvent) {
        val now = SystemClock.elapsedRealtimeNanos()
        var pvtSnapshotCount = 0
        val probeReasons = linkedMapOf<String, Int>()

        for (measurement in eventArgs.measurements) {
            val satelliteKey = SatelliteKey(measurement.constellationType, measurement.svid)
            val probe = GnssSatellitePVTResolver.probeMeasurement(measurement)
            val snapshot = probe.snapshot
            if (snapshot == null) {
                probeReasons[probe.reason] = (probeReasons[probe.reason] ?: 0) + 1
                continue
            }

            pvtSnapshotCount++
            latestSatellitePvt[satelliteKey] = snapshot.copy(capturedAtElapsedRealtimeNanos = now)
        }

        logPvtProbeSummaryIfNeeded(
            now = now,
            measurementCount = eventArgs.measurements.size,
            pvtSnapshotCount = pvtSnapshotCount,
            probeReasons = probeReasons
        )
        removeStalePvt(now)
    }

    suspend fun refreshIgsBroadcastDataIfNeeded(forceRefresh: Boolean = false): Boolean {
        if (igsRefreshInFlight) return latestBroadcastEphemerides.isNotEmpty()

        igsRefreshInFlight = true
        try {
            val snapshot = IgsBroadcastEphemerisRepository.getSnapshot(forceRefresh) ?: return false

            latestBroadcastEphemerides.clear()
            snapshot.records.forEach { (key, records) ->
                latestBroadcastEphemerides[key] = records
            }
            Log.d(
                "GNSS_IGS",
                "loaded records=${latestBroadcastEphemerides.values.sumOf { it.size }}"
            )
            return latestBroadcastEphemerides.isNotEmpty()
        } finally {
            igsRefreshInFlight = false
        }
    }

    suspend fun refreshCelesTrakDataIfNeeded(forceRefresh: Boolean = false): Boolean {
        if (celesTrakRefreshInFlight) return latestCelesTrakOrbits.isNotEmpty()

        celesTrakRefreshInFlight = true
        try {
            val snapshot = CelesTrakSatelliteRepository.getSnapshot(forceRefresh) ?: return false

            latestCelesTrakOrbits.clear()
            snapshot.records.forEach { (key, orbit) ->
                latestCelesTrakOrbits[SatelliteKey(key.constellationType, key.svid)] = orbit
            }
            lastLoggedCelesTrakMiss.clear()
            loggedEmptyCelesTrakCache = latestCelesTrakOrbits.isEmpty()
            Log.d("GNSS_CELESTRAK", "loaded records=${latestCelesTrakOrbits.size}")
            return latestCelesTrakOrbits.isNotEmpty()
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
        latestBroadcastEphemerides.clear()
        latestCelesTrakOrbits.clear()
        lastLoggedSource.clear()
        lastLoggedCelesTrakMiss.clear()
        igsRefreshInFlight = false
        loggedEmptyCelesTrakCache = false
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

        val broadcastRecord = selectBroadcastEphemeris(satelliteKey, System.currentTimeMillis())
        if (broadcastRecord != null) {
            val broadcastPosition = resolveFromBroadcastEphemeris(broadcastRecord)
            if (broadcastPosition != null) return broadcastPosition
        }

        val celesTrakOrbit = latestCelesTrakOrbits[satelliteKey]
        if (celesTrakOrbit != null) {
            val celesTrakPosition = resolveFromCelesTrak(celesTrakOrbit)
            if (celesTrakPosition != null) return celesTrakPosition
        } else {
            logCelesTrakMissIfNeeded(satelliteKey, constellation, svid)
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

    private fun selectBroadcastEphemeris(
        satelliteKey: SatelliteKey,
        observationUtcMillis: Long
    ): BroadcastEphemerisRecord? {
        return latestBroadcastEphemerides[satelliteKey]
            ?.minByOrNull { record -> kotlin.math.abs(observationUtcMillis - record.epochUtcMillis) }
            ?.takeIf { record ->
                kotlin.math.abs(observationUtcMillis - record.epochUtcMillis) <= BROADCAST_MAX_AGE_MS
            }
    }

    private fun resolveFromBroadcastEphemeris(
        record: BroadcastEphemerisRecord
    ): ResolvedSatellitePosition? {
        val orbitState = IgsBroadcastEphemerisPropagator.propagate(record) ?: return null
        return ResolvedSatellitePosition(
            latitude = orbitState.position.latitude,
            longitude = orbitState.position.longitude,
            altitude = orbitState.position.altitude,
            speed = orbitState.speedMetersPerSecond,
            positionSource = "IGS Broadcast",
            ephemerisSource = buildBroadcastEphemerisLabel(record)
        )
    }

    private fun resolveFromCelesTrak(orbit: OrbitRecord): ResolvedSatellitePosition? {
        val sgp4State = Sgp4OrbitPropagator.propagate(orbit)
        val orbitState = sgp4State ?: runCatching {
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
            positionSource = if (sgp4State != null) "CelesTrak SGP4" else "CelesTrak GP",
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

    private fun buildBroadcastEphemerisLabel(record: BroadcastEphemerisRecord): String {
        val epochUtc = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(record.epochUtcMillis))

        return "RINEX NAV | ${record.sourceName} | ${record.satelliteId} | epoch $epochUtc"
    }

    private fun removeStalePvt(now: Long) {
        latestSatellitePvt.entries.removeAll { (_, snapshot) ->
            now - snapshot.capturedAtElapsedRealtimeNanos > PVT_STALE_THRESHOLD_NANOS
        }
    }

    private fun logPvtProbeSummaryIfNeeded(
        now: Long,
        measurementCount: Int,
        pvtSnapshotCount: Int,
        probeReasons: Map<String, Int>
    ) {
        if (measurementCount == 0) return
        if (now - lastPvtProbeLogElapsedRealtimeNanos < PVT_PROBE_LOG_INTERVAL_NANOS) return

        lastPvtProbeLogElapsedRealtimeNanos = now
        val reasonSummary = probeReasons.entries
            .joinToString(separator = "; ") { (reason, count) -> "$count x $reason" }
            .ifBlank { "none" }
        Log.d(
            "GNSS_PVT",
            "measurements=$measurementCount pvt=$pvtSnapshotCount reasons=$reasonSummary"
        )
    }

    private fun logCelesTrakMissIfNeeded(
        satelliteKey: SatelliteKey,
        constellation: Int,
        svid: Int
    ) {
        if (!isCelesTrakSupported(constellation)) return
        if (latestCelesTrakOrbits.isEmpty()) {
            if (!loggedEmptyCelesTrakCache) {
                loggedEmptyCelesTrakCache = true
                Log.d("GNSS_CELESTRAK", "orbit cache empty; waiting for CelesTrak refresh")
            }
            return
        }
        if (satelliteKey in lastLoggedCelesTrakMiss) return

        lastLoggedCelesTrakMiss += satelliteKey
        Log.d(
            "GNSS_CELESTRAK",
            "no orbit match for sat=$constellation/$svid records=${latestCelesTrakOrbits.size}"
        )
    }

    private fun isCelesTrakSupported(constellation: Int): Boolean {
        return constellation == GnssStatus.CONSTELLATION_GPS ||
            constellation == GnssStatus.CONSTELLATION_GALILEO ||
            constellation == GnssStatus.CONSTELLATION_BEIDOU
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

    private companion object {
        const val PVT_STALE_THRESHOLD_NANOS = 10_000_000_000L
        const val PVT_PROBE_LOG_INTERVAL_NANOS = 10_000_000_000L
        const val BROADCAST_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}
