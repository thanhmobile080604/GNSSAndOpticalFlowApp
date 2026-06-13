package com.example.gnssandopticalflowapp.screen.viewmodel

import android.location.Location
import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.model.LiveRouteState
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LiveRoutingViewModel : ViewModel() {
    enum class OpticalMode {
        KLT,
        FARNEBACK_VECTOR,
        FARNEBACK_HEATMAP
    }

    data class InitialRouteUi(
        val destinationPoint: GeoPoint,
        val routePoints: List<GeoPoint>,
        val navigation: NavigationSnapshot
    )

    data class NavigationSnapshot(
        val point: GeoPoint,
        val headingDeg: Double,
        val speedMps: Double,
        val opticalAssistPoints: List<GeoPoint>? = null
    )

    data class AssistDecision(
        val active: Boolean,
        val changed: Boolean
    )

    data class GnssUpdateResult(
        val accepted: Boolean,
        val navigation: NavigationSnapshot?,
        val assistDecision: AssistDecision
    )

    data class TickResult(
        val navigation: NavigationSnapshot?,
        val speedMps: Double,
        val assistDecision: AssistDecision
    )

    data class CameraPanelState(
        val visible: Boolean,
        val showHandle: Boolean
    )

    var routeState: LiveRouteState? = null
        private set
    var activeOpticalMode = OpticalMode.KLT
        private set
    var gnssAssistActive = false
        private set
    var cameraDismissedForCurrentGnssLoss = false
        private set
    var cameraPanelVisible = false
        private set

    private val opticalAssistPoints = ArrayList<GeoPoint>(512)

    private var currentPoint: GeoPoint? = null
    private var currentHeadingDeg = 0.0
    private var lastTrueSpeedMps = 0.0
    private var deadReckoningSpeedMps = 0.0
    private var lastAcceptedGnssMs = 0L
    private var lastGnssSatelliteCount = Int.MAX_VALUE
    private var lastGnssStatusMs = 0L
    private var testGnssSuppressed = false

    private var flowFrameCount = 0
    private var lastFlowFrameTimeMs = 0L
    private var emaFlowMagPxPerSec = 0.0
    private var emaFlowDxPxPerSec = 0.0
    private var emaFlowDyPxPerSec = 0.0
    private var lastFlowSampleMs = 0L
    private var lastFlowConfidence = 0.0

    fun initialize(state: LiveRouteState, nowMs: Long = System.currentTimeMillis()): InitialRouteUi {
        routeState = state
        opticalAssistPoints.clear()
        gnssAssistActive = false
        cameraDismissedForCurrentGnssLoss = false
        cameraPanelVisible = false
        testGnssSuppressed = false
        resetOpticalRuntime()

        val startPoint = GeoPoint(state.startLocation.latitude, state.startLocation.longitude)
        val destinationPoint = GeoPoint(state.destination.latitude, state.destination.longitude)
        val routePoints = state.routePoints.takeIf { it.size > 1 }
            ?: listOf(startPoint, destinationPoint)

        currentPoint = startPoint
        currentHeadingDeg = when {
            state.startLocation.hasBearing() -> normalizeDeg(state.startLocation.bearing.toDouble())
            routePoints.size > 1 -> bearingBetween(routePoints[0], routePoints[1])
            else -> 0.0
        }
        lastTrueSpeedMps = if (state.startLocation.hasSpeed()) {
            state.startLocation.speed.toDouble().coerceAtLeast(0.0)
        } else {
            0.0
        }
        lastAcceptedGnssMs = nowMs

        return InitialRouteUi(
            destinationPoint = destinationPoint,
            routePoints = routePoints,
            navigation = NavigationSnapshot(
                point = startPoint,
                headingDeg = currentHeadingDeg,
                speedMps = lastTrueSpeedMps
            )
        )
    }

    fun restoreOrInitialize(state: LiveRouteState, nowMs: Long = System.currentTimeMillis()): InitialRouteUi {
        val existingState = routeState
        val point = currentPoint
        if (existingState == null || point == null) {
            return initialize(state, nowMs)
        }

        val startPoint = GeoPoint(existingState.startLocation.latitude, existingState.startLocation.longitude)
        val destinationPoint = GeoPoint(existingState.destination.latitude, existingState.destination.longitude)
        val routePoints = existingState.routePoints.takeIf { it.size > 1 }
            ?: listOf(startPoint, destinationPoint)

        return InitialRouteUi(
            destinationPoint = destinationPoint,
            routePoints = routePoints,
            navigation = NavigationSnapshot(
                point = point,
                headingDeg = currentHeadingDeg,
                speedMps = if (gnssAssistActive) deadReckoningSpeedMps else lastTrueSpeedMps,
                opticalAssistPoints = opticalAssistPoints.takeIf { it.size >= 2 }?.toList()
            )
        )
    }

    fun clearRoute() {
        routeState = null
        gnssAssistActive = false
        cameraDismissedForCurrentGnssLoss = false
        cameraPanelVisible = false
        opticalAssistPoints.clear()
    }

    fun setActiveOpticalMode(mode: OpticalMode) {
        activeOpticalMode = mode
        resetOpticalRuntime()
    }

    fun dismissCameraPanel() {
        cameraDismissedForCurrentGnssLoss = true
        cameraPanelVisible = false
    }

    fun requestCameraPanel() {
        cameraDismissedForCurrentGnssLoss = false
        cameraPanelVisible = true
    }

    fun cameraPanelState(): CameraPanelState {
        return CameraPanelState(
            visible = cameraPanelVisible,
            showHandle = !cameraPanelVisible && gnssAssistActive && cameraDismissedForCurrentGnssLoss
        )
    }

    fun onGnssStatusChanged(satelliteCount: Int, nowMs: Long = System.currentTimeMillis()): AssistDecision {
        lastGnssSatelliteCount = satelliteCount
        lastGnssStatusMs = nowMs
        return evaluateGnssAssist(nowMs)
    }

    fun onGnssProviderDisabled(nowMs: Long = System.currentTimeMillis()): AssistDecision {
        return evaluateGnssAssist(nowMs)
    }

    fun onLocationUpdate(location: Location, nowMs: Long = System.currentTimeMillis()): GnssUpdateResult {
        if (TEST_GNSS_DROPOUT && testGnssSuppressed) {
            return GnssUpdateResult(
                accepted = false,
                navigation = null,
                assistDecision = evaluateGnssAssist(nowMs)
            )
        }
        if (!isLocationUsableForGnss(location, nowMs)) {
            return GnssUpdateResult(
                accepted = false,
                navigation = null,
                assistDecision = evaluateGnssAssist(nowMs)
            )
        }

        val point = GeoPoint(location.latitude, location.longitude)
        val previousPoint = currentPoint
        val previousGnssMs = lastAcceptedGnssMs
        val previousDistance = previousPoint?.distanceToAsDouble(point) ?: 0.0

        lastTrueSpeedMps = when {
            location.hasSpeed() -> location.speed.toDouble()
            previousPoint != null &&
                previousGnssMs > 0L &&
                nowMs > previousGnssMs &&
                previousDistance >= MIN_GNSS_DISTANCE_FOR_DERIVED_SPEED_M ->
                previousDistance / ((nowMs - previousGnssMs) / 1000.0)
            else -> 0.0
        }
            .let { speed -> if (speed < GNSS_STATIONARY_SPEED_FLOOR_MPS) 0.0 else speed }
            .coerceIn(0.0, MAX_NAVIGATION_SPEED_MPS)

        currentHeadingDeg = when {
            location.hasBearing() && lastTrueSpeedMps > MIN_BEARING_SPEED_MPS ->
                normalizeDeg(location.bearing.toDouble())
            previousPoint != null && previousDistance > 0.75 ->
                bearingBetween(previousPoint, point)
            else -> currentHeadingDeg
        }

        currentPoint = point
        lastAcceptedGnssMs = nowMs
        return GnssUpdateResult(
            accepted = true,
            navigation = NavigationSnapshot(
                point = point,
                headingDeg = currentHeadingDeg,
                speedMps = lastTrueSpeedMps
            ),
            assistDecision = setGnssAssistActive(false)
        )
    }

    fun onTick(
        nowMs: Long,
        dtSec: Double,
        yawRateDegPerSec: Double
    ): TickResult {
        val assistDecision = setGnssAssistActive(!hasCurrentlyUsableGnss(nowMs))
        if (!assistDecision.active) {
            return TickResult(
                navigation = null,
                speedMps = lastTrueSpeedMps,
                assistDecision = assistDecision
            )
        }

        val navigation = integrateDeadReckoning(
            nowMs = nowMs,
            dtSec = dtSec,
            yawRateDegPerSec = yawRateDegPerSec
        )
        return TickResult(
            navigation = navigation,
            speedMps = navigation?.speedMps ?: deadReckoningSpeedMps,
            assistDecision = assistDecision
        )
    }

    fun toggleTestGnssDropout(nowMs: Long = System.currentTimeMillis()): AssistDecision {
        testGnssSuppressed = !testGnssSuppressed
        return evaluateGnssAssist(nowMs)
    }

    fun onFlowFrameStarted(): Boolean {
        flowFrameCount++
        return flowFrameCount % FEATURE_UPDATE_INTERVAL == 0
    }

    fun onOpticalMetrics(metrics: OpticalFlowMetrics, nowMs: Long = System.currentTimeMillis()) {
        val dtMs = if (lastFlowFrameTimeMs > 0) nowMs - lastFlowFrameTimeMs else 0L
        if (dtMs in 1..500) {
            val dtSec = dtMs / 1000.0
            val mag = metrics.avgMagnitude / dtSec
            val dx = metrics.avgDx / dtSec
            val dy = metrics.avgDy / dtSec
            emaFlowMagPxPerSec = EMA_ALPHA * mag + (1 - EMA_ALPHA) * emaFlowMagPxPerSec
            emaFlowDxPxPerSec = EMA_ALPHA * dx + (1 - EMA_ALPHA) * emaFlowDxPxPerSec
            emaFlowDyPxPerSec = EMA_ALPHA * dy + (1 - EMA_ALPHA) * emaFlowDyPxPerSec
            lastFlowConfidence = metrics.confidence
            lastFlowSampleMs = nowMs
        }
        lastFlowFrameTimeMs = nowMs
    }

    private fun integrateDeadReckoning(
        nowMs: Long,
        dtSec: Double,
        yawRateDegPerSec: Double
    ): NavigationSnapshot? {
        val point = currentPoint ?: return null
        val flowFresh = nowMs - lastFlowSampleMs < FLOW_STALE_MS
        val forwardFlowPxPerSec = kotlin.math.abs(emaFlowDyPxPerSec)
        val lateralFlowPxPerSec = kotlin.math.abs(emaFlowDxPxPerSec)
        val hasForwardFlow = flowFresh &&
            forwardFlowPxPerSec >= FORWARD_FLOW_STILL_PX_PER_SEC &&
            forwardFlowPxPerSec >= lateralFlowPxPerSec * MIN_FORWARD_TO_LATERAL_FLOW_RATIO &&
            lastFlowConfidence >= MIN_FLOW_CONFIDENCE

        deadReckoningSpeedMps = if (hasForwardFlow) {
            ((forwardFlowPxPerSec - FORWARD_FLOW_STILL_PX_PER_SEC) * FLOW_PX_PER_SEC_TO_MPS)
                .coerceIn(0.0, MAX_DEAD_RECKONING_SPEED_MPS)
        } else {
            0.0
        }

        if (kotlin.math.abs(yawRateDegPerSec) >= MIN_YAW_RATE_TO_UPDATE_HEADING_DEG_SEC || hasForwardFlow) {
            currentHeadingDeg = normalizeDeg(
                currentHeadingDeg + yawRateDegPerSec * dtSec + emaFlowDxPxPerSec * FLOW_YAW_GAIN * dtSec
            )
        }

        if (deadReckoningSpeedMps <= 0.0) {
            return NavigationSnapshot(
                point = point,
                headingDeg = currentHeadingDeg,
                speedMps = 0.0
            )
        }

        val nextPoint = offsetPoint(point, deadReckoningSpeedMps * dtSec, currentHeadingDeg)
        currentPoint = nextPoint

        val assistPoints = appendOpticalAssistPoint(nextPoint)
        return NavigationSnapshot(
            point = nextPoint,
            headingDeg = currentHeadingDeg,
            speedMps = deadReckoningSpeedMps,
            opticalAssistPoints = assistPoints
        )
    }

    private fun appendOpticalAssistPoint(point: GeoPoint): List<GeoPoint>? {
        val last = opticalAssistPoints.lastOrNull()
        if (last != null && last.distanceToAsDouble(point) < DEAD_RECKONING_APPEND_DISTANCE_M) {
            return null
        }

        if (opticalAssistPoints.isEmpty()) {
            currentPoint?.let { opticalAssistPoints.add(it) }
        }
        opticalAssistPoints.add(point)
        return opticalAssistPoints.toList()
    }

    private fun evaluateGnssAssist(nowMs: Long): AssistDecision {
        return setGnssAssistActive(!hasCurrentlyUsableGnss(nowMs))
    }

    private fun setGnssAssistActive(active: Boolean): AssistDecision {
        val changed = gnssAssistActive != active
        if (changed) {
            gnssAssistActive = active
            if (active && opticalAssistPoints.isEmpty()) {
                currentPoint?.let { opticalAssistPoints.add(it) }
            }
            if (active && !cameraDismissedForCurrentGnssLoss) {
                cameraPanelVisible = true
            }
            if (!active) {
                cameraDismissedForCurrentGnssLoss = false
                cameraPanelVisible = false
            }
        }
        return AssistDecision(active = gnssAssistActive, changed = changed)
    }

    private fun isLocationUsableForGnss(location: Location, nowMs: Long): Boolean {
        val accuracyOk = !location.hasAccuracy() || location.accuracy <= MAX_USABLE_GNSS_ACCURACY_M
        val satelliteStatusFresh = nowMs - lastGnssStatusMs <= GNSS_STATUS_STALE_MS
        val satellitesOk = !satelliteStatusFresh || lastGnssSatelliteCount >= MIN_USABLE_GNSS_SATELLITES
        return accuracyOk && satellitesOk
    }

    private fun hasCurrentlyUsableGnss(nowMs: Long): Boolean {
        if (TEST_GNSS_DROPOUT && testGnssSuppressed) return false
        val ageMs = nowMs - lastAcceptedGnssMs
        return lastAcceptedGnssMs > 0L && ageMs <= GNSS_LOCATION_STALE_MS
    }

    private fun resetOpticalRuntime() {
        flowFrameCount = 0
        lastFlowFrameTimeMs = 0L
        emaFlowMagPxPerSec = 0.0
        emaFlowDxPxPerSec = 0.0
        emaFlowDyPxPerSec = 0.0
        lastFlowSampleMs = 0L
        lastFlowConfidence = 0.0
    }

    private fun bearingBetween(from: GeoPoint, to: GeoPoint): Double {
        val results = FloatArray(2)
        Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            results
        )
        return normalizeDeg(results.getOrElse(1) { 0f }.toDouble())
    }

    private fun offsetPoint(origin: GeoPoint, distanceMeters: Double, bearingDeg: Double): GeoPoint {
        val angularDistance = distanceMeters / EARTH_RADIUS_M
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearingRad)
        )
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )

        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    companion object {
        const val LOCATION_UPDATE_MS = 1000L
        const val TICK_MS = 50L
        const val TEST_GNSS_DROPOUT = true
        const val TEST_GNSS_DROPOUT_INTERVAL_MS = 10_000L
        const val FLOW_SENSITIVITY = 85

        private const val GNSS_LOCATION_STALE_MS = 5_000L
        private const val GNSS_STATUS_STALE_MS = 5_000L
        private const val MIN_USABLE_GNSS_SATELLITES = 4
        private const val MAX_USABLE_GNSS_ACCURACY_M = 25f
        private const val MIN_BEARING_SPEED_MPS = 0.35
        private const val GNSS_STATIONARY_SPEED_FLOOR_MPS = 0.20
        private const val MIN_GNSS_DISTANCE_FOR_DERIVED_SPEED_M = 1.5
        private const val MAX_NAVIGATION_SPEED_MPS = 45.0

        private const val FEATURE_UPDATE_INTERVAL = 30
        private const val EMA_ALPHA = 0.25
        private const val FLOW_STALE_MS = 650L
        private const val FORWARD_FLOW_STILL_PX_PER_SEC = 18.0
        private const val MIN_FORWARD_TO_LATERAL_FLOW_RATIO = 0.75
        private const val MIN_FLOW_CONFIDENCE = 25.0
        private const val FLOW_PX_PER_SEC_TO_MPS = 0.004
        private const val FLOW_YAW_GAIN = 0.004
        private const val MIN_YAW_RATE_TO_UPDATE_HEADING_DEG_SEC = 0.5
        private const val MAX_DEAD_RECKONING_SPEED_MPS = 4.5
        private const val DEAD_RECKONING_APPEND_DISTANCE_M = 0.35
        private const val EARTH_RADIUS_M = 6_378_137.0
    }
}
