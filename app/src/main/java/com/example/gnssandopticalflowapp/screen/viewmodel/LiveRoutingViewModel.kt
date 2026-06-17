package com.example.gnssandopticalflowapp.screen.viewmodel

import android.location.Location
import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.model.LiveRouteState
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import com.example.gnssandopticalflowapp.model.RouteInfo
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class LiveRoutingViewModel : ViewModel() {
    enum class OpticalMode {
        KLT,
        FARNEBACK_VECTOR
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
        val opticalAssistSegments: List<List<GeoPoint>>? = null,
        val gnssTravelPathSegments: List<List<GeoPoint>>? = null,
        val weakGnssPoints: List<GeoPoint>? = null,
        val strongGnssPoints: List<GeoPoint>? = null,
        val remainingRoutePoints: List<GeoPoint>? = null
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

    /** Realtime internal state for the on-screen debug HUD (diagnosing GNSS-outage tracking). */
    data class DebugSnapshot(
        val assistActive: Boolean,
        val gpsSpeedMps: Double,
        val estSpeedMps: Double,
        val flowSpeedMps: Double,
        val flowUsable: Boolean,
        val scaleRatio: Double,
        val scaleConfidence: Double,
        val axisConfidence: Double,
        val accelTrust: Double,
        val longitudinalAccelMps2: Double,
        val uncertaintyM: Double,
        val lateralCoherence: Double,
        val opticalYawRateDegPerSec: Double,
        val yawScale: Double
    )

    private data class VisualOdometry(
        val usable: Boolean,
        val speedMps: Double,
        val deltaHeadingDeg: Double,
        val opticalYawRateDegPerSec: Double,
        val translationPxPerSec: Double,
        val lateralPxPerSec: Double,
        val quality: Double
    )

    private data class RouteProjection(
        val point: GeoPoint,
        val segmentIndex: Int,
        val distanceAlongRouteM: Double,
        val distanceFromRouteM: Double,
        val segmentHeadingDeg: Double
    )

    private data class MapMatchResult(
        val point: GeoPoint,
        val headingDeg: Double,
        val confidence: Double
    )

    private data class FusedPose(
        val point: GeoPoint,
        val headingDeg: Double,
        val mapMatchConfidence: Double
    )

    private data class VehicleProfile(
        val maxDriveAccelMps2: Double,
        val maxBrakeAccelMps2: Double,
        val speedRiseLimitMps2: Double,
        val speedDropLimitMps2: Double,
        val forwardAxisAlpha: Double,
        val forwardAxisConfidenceStep: Double,
        val maxInertialTrust: Double,
        val zuptEnterSpeedMps: Double,
        val zuptAccelMps2: Double,
        val lowSpeedYawGainMax: Double
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

    val currentRouteOrigin: GeoPoint?
        get() = currentPoint
    val destinationPoint: GeoPoint?
        get() = routeState?.destination?.let { destination ->
            GeoPoint(destination.latitude, destination.longitude)
        }

    private val gnssTravelPathSegments = ArrayList<ArrayList<GeoPoint>>()
    private val weakGnssPoints = ArrayList<GeoPoint>()
    private val strongGnssPoints = ArrayList<GeoPoint>()
    private val opticalAssistSegments = ArrayList<ArrayList<GeoPoint>>()

    private var currentPoint: GeoPoint? = null
    private var currentHeadingDeg = 0.0
    private var lastTrueSpeedMps = 0.0
    private var deadReckoningSpeedMps = 0.0
    private var vehicleDeadReckoningSpeedMps = 0.0
    private var positionUncertaintyM = INITIAL_POSITION_UNCERTAINTY_M
    private var lastAcceptedGnssMs = 0L
    private var lastGnssSatelliteCount = Int.MAX_VALUE
    private var lastGnssStatusMs = 0L
    private var testGnssSuppressed = false
    private var gnssTravelSegmentOpen = true
    private var offRouteSampleCount = 0
    private var lastRouteDeviationSampleMs = 0L
    private var lastRouteRefreshStartedMs = 0L

    private var flowFrameCount = 0
    private var lastFlowFrameTimeMs = 0L
    private var emaFlowMagPxPerSec = 0.0
    private var emaFlowDxPxPerSec = 0.0
    private var emaFlowDyPxPerSec = 0.0
    private var emaFlowCoherence = 0.0
    private var lastFlowSampleMs = 0L
    private var lastFlowConfidence = 0.0
    private var dynamicFlowToMpsRatio = FLOW_PX_PER_SEC_TO_MPS
    private var dynamicFlowToYawRatio = FLOW_YAW_RATE_GAIN_DEG_PER_PX_SEC
    private var cameraSpeedScaleConfidence = INITIAL_CAMERA_SPEED_SCALE_CONFIDENCE
    private var lastVisualSpeedMps = 0.0
    private var lastVisualUsable = false
    private var lastOpticalYawRateDegPerSec = 0.0
    private val emaAccelDevice = DoubleArray(3)
    private var hasAccelSample = false
    private val forwardAxisDevice = DoubleArray(3)
    private var forwardAxisConfidence = 0.0
    private var longitudinalAccelBiasMps2 = 0.0
    private var prevGnssSpeedForAccelMps = 0.0
    private var prevGnssHeadingDeg = 0.0
    private var hasPrevGnssAccelRef = false
    private var stationaryHoldMs = 0L
    private val vehicleProfile = VEHICLE_PROFILE

    fun initialize(state: LiveRouteState, nowMs: Long = System.currentTimeMillis()): InitialRouteUi {
        routeState = state
        gnssTravelPathSegments.clear()
        weakGnssPoints.clear()
        strongGnssPoints.clear()
        opticalAssistSegments.clear()
        gnssAssistActive = false
        cameraDismissedForCurrentGnssLoss = false
        cameraPanelVisible = false
        testGnssSuppressed = false
        gnssTravelSegmentOpen = true
        offRouteSampleCount = 0
        lastRouteDeviationSampleMs = 0L
        lastRouteRefreshStartedMs = 0L
        resetOpticalRuntime()
        resetCameraSpeedScale()
        resetDeadReckoningRuntime()
        resetInertialRuntime()

        val startPoint = GeoPoint(state.startLocation.latitude, state.startLocation.longitude)
        val destinationPoint = GeoPoint(state.destination.latitude, state.destination.longitude)
        val routePoints = state.routePoints.takeIf { it.size > 1 }
            ?: listOf(startPoint, destinationPoint)

        currentPoint = startPoint
        startNewSegment(gnssTravelPathSegments, startPoint)
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
        positionUncertaintyM = if (state.startLocation.hasAccuracy()) {
            state.startLocation.accuracy.toDouble().coerceAtLeast(MIN_POSITION_UNCERTAINTY_M)
        } else {
            INITIAL_POSITION_UNCERTAINTY_M
        }
        vehicleDeadReckoningSpeedMps = lastTrueSpeedMps
        deadReckoningSpeedMps = lastTrueSpeedMps
        lastAcceptedGnssMs = nowMs

        return InitialRouteUi(
            destinationPoint = destinationPoint,
            routePoints = routePoints,
            navigation = NavigationSnapshot(
                point = startPoint,
                headingDeg = currentHeadingDeg,
                speedMps = lastTrueSpeedMps,
                gnssTravelPathSegments = snapshotSegments(gnssTravelPathSegments),
                weakGnssPoints = weakGnssPoints.toList(),
                strongGnssPoints = strongGnssPoints.toList(),
                remainingRoutePoints = remainingRouteFrom(startPoint)
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
                opticalAssistSegments = snapshotSegments(opticalAssistSegments),
                gnssTravelPathSegments = snapshotSegments(gnssTravelPathSegments),
                weakGnssPoints = weakGnssPoints.toList(),
                strongGnssPoints = strongGnssPoints.toList(),
                remainingRoutePoints = remainingRouteFrom(point)
            )
        )
    }

    fun clearRoute() {
        routeState = null
        gnssAssistActive = false
        cameraDismissedForCurrentGnssLoss = false
        cameraPanelVisible = false
        gnssTravelSegmentOpen = true
        offRouteSampleCount = 0
        lastRouteDeviationSampleMs = 0L
        gnssTravelPathSegments.clear()
        weakGnssPoints.clear()
        strongGnssPoints.clear()
        opticalAssistSegments.clear()
        resetCameraSpeedScale()
        resetDeadReckoningRuntime()
        resetInertialRuntime()
    }

    fun setActiveOpticalMode(mode: OpticalMode) {
        if (activeOpticalMode == mode) return

        activeOpticalMode = mode
        resetOpticalRuntime()
        resetCameraSpeedScale()
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
        if (!isLocationUsableForGnss(location, nowMs)) {
            return GnssUpdateResult(
                accepted = false,
                navigation = null,
                assistDecision = evaluateGnssAssist(nowMs)
            )
        }

        val point = GeoPoint(location.latitude, location.longitude)
        if (TEST_GNSS_DROPOUT && testGnssSuppressed) {
            return GnssUpdateResult(
                accepted = false,
                navigation = null,
                assistDecision = evaluateGnssAssist(nowMs)
            )
        }

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
        vehicleDeadReckoningSpeedMps = lastTrueSpeedMps
        deadReckoningSpeedMps = lastTrueSpeedMps
        positionUncertaintyM = if (location.hasAccuracy()) {
            location.accuracy.toDouble().coerceAtLeast(MIN_POSITION_UNCERTAINTY_M)
        } else {
            GNSS_FIX_UNCERTAINTY_M
        }

        currentPoint = point
        lastAcceptedGnssMs = nowMs

        learnForwardAxisFromGnss(
            currentSpeedMps = lastTrueSpeedMps,
            currentHeadingDegValue = currentHeadingDeg,
            previousGnssMs = previousGnssMs,
            nowMs = nowMs
        )

        val gnssPath = if (!gnssTravelSegmentOpen) {
            gnssTravelSegmentOpen = true
            strongGnssPoints.add(point)
            startNewSegment(gnssTravelPathSegments, point)
            snapshotSegments(gnssTravelPathSegments)
        } else {
            appendPathPoint(gnssTravelPathSegments, point, GNSS_PATH_APPEND_DISTANCE_M)
        }

        return GnssUpdateResult(
            accepted = true,
            navigation = NavigationSnapshot(
                point = point,
                headingDeg = currentHeadingDeg,
                speedMps = lastTrueSpeedMps,
                gnssTravelPathSegments = gnssPath,
                weakGnssPoints = weakGnssPoints.toList(),
                strongGnssPoints = strongGnssPoints.toList(),
                remainingRoutePoints = remainingRouteFrom(point)
            ),
            assistDecision = setGnssAssistActive(false)
        )
    }

    fun onTick(
        nowMs: Long,
        dtSec: Double,
        yawRateDegPerSec: Double,
        horizontalAccelDevice: FloatArray = NO_ACCEL_SAMPLE
    ): TickResult {
        updateImuAccel(horizontalAccelDevice)
        val visualOdometry = resolveVisualOdometry(nowMs, dtSec, yawRateDegPerSec)
        lastVisualSpeedMps = visualOdometry.speedMps
        lastVisualUsable = visualOdometry.usable
        val assistDecision = setGnssAssistActive(!hasCurrentlyUsableGnss(nowMs))
        if (!assistDecision.active) {
            calibrateCameraSpeedScale(visualOdometry)
            vehicleDeadReckoningSpeedMps = lastTrueSpeedMps
            deadReckoningSpeedMps = lastTrueSpeedMps
            return TickResult(
                navigation = null,
                speedMps = lastTrueSpeedMps,
                assistDecision = assistDecision
            )
        }

        val navigation = integrateDeadReckoning(
            nowMs = nowMs,
            dtSec = dtSec,
            yawRateDegPerSec = yawRateDegPerSec,
            visualOdometry = visualOdometry
        )
        return TickResult(
            navigation = navigation,
            speedMps = navigation?.speedMps ?: deadReckoningSpeedMps,
            assistDecision = assistDecision
        )
    }

    fun updateRouteDeviation(nowMs: Long = System.currentTimeMillis()): Boolean {
        val state = routeState ?: return false
        val point = currentPoint ?: return false
        val destination = destinationPoint ?: return false
        if (point.distanceToAsDouble(destination) <= ARRIVAL_DISTANCE_M) {
            offRouteSampleCount = 0
            return false
        }

        val offRouteDistance = distanceToRouteMeters(point, state.routePoints)
        if (offRouteDistance <= ROUTE_DEVIATION_DISTANCE_M) {
            offRouteSampleCount = 0
            return false
        }

        if (nowMs - lastRouteDeviationSampleMs < ROUTE_DEVIATION_SAMPLE_INTERVAL_MS) {
            return false
        }
        lastRouteDeviationSampleMs = nowMs
        offRouteSampleCount += 1
        return offRouteSampleCount >= ROUTE_DEVIATION_REQUIRED_SAMPLES &&
            nowMs - lastRouteRefreshStartedMs >= ROUTE_REFRESH_COOLDOWN_MS
    }

    fun markRouteRefreshStarted(nowMs: Long = System.currentTimeMillis()) {
        lastRouteRefreshStartedMs = nowMs
    }

    fun applyRoute(route: RouteInfo): NavigationSnapshot? {
        val state = routeState ?: return null
        val point = currentPoint ?: return null
        routeState = state.copy(
            routePoints = route.points,
            distanceMeters = route.distanceMeters
        )
        offRouteSampleCount = 0
        lastRouteDeviationSampleMs = 0L
        return NavigationSnapshot(
            point = point,
            headingDeg = currentHeadingDeg,
            speedMps = if (gnssAssistActive) deadReckoningSpeedMps else lastTrueSpeedMps,
            opticalAssistSegments = snapshotSegments(opticalAssistSegments),
            gnssTravelPathSegments = snapshotSegments(gnssTravelPathSegments),
            weakGnssPoints = weakGnssPoints.toList(),
            strongGnssPoints = strongGnssPoints.toList(),
            remainingRoutePoints = remainingRouteFrom(point)
        )
    }

    fun setTestGnssSuppressed(suppressed: Boolean, nowMs: Long = System.currentTimeMillis()): AssistDecision {
        testGnssSuppressed = suppressed
        return evaluateGnssAssist(nowMs)
    }

    /** GNSS healthy and ~zero speed: the only safe moment to (re)learn the gyro zero-rate bias. */
    fun isStationaryForBias(nowMs: Long = System.currentTimeMillis()): Boolean {
        return hasCurrentlyUsableGnss(nowMs) && lastTrueSpeedMps < GNSS_STATIONARY_SPEED_FLOOR_MPS
    }

    fun onFlowFrameStarted(): Boolean {
        flowFrameCount++
        return flowFrameCount % FEATURE_UPDATE_INTERVAL == 0
    }

    fun debugSnapshot(): DebugSnapshot {
        return DebugSnapshot(
            assistActive = gnssAssistActive,
            gpsSpeedMps = lastTrueSpeedMps,
            estSpeedMps = if (gnssAssistActive) deadReckoningSpeedMps else lastTrueSpeedMps,
            flowSpeedMps = lastVisualSpeedMps,
            flowUsable = lastVisualUsable,
            scaleRatio = dynamicFlowToMpsRatio,
            scaleConfidence = cameraSpeedScaleConfidence,
            axisConfidence = forwardAxisConfidence,
            accelTrust = longitudinalAccelTrust(),
            longitudinalAccelMps2 = currentLongitudinalAccel(),
            uncertaintyM = positionUncertaintyM,
            lateralCoherence = emaFlowCoherence,
            opticalYawRateDegPerSec = lastOpticalYawRateDegPerSec,
            yawScale = dynamicFlowToYawRatio
        )
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
            emaFlowCoherence = EMA_ALPHA * metrics.lateralCoherence + (1 - EMA_ALPHA) * emaFlowCoherence
            lastFlowConfidence = metrics.confidence
            lastFlowSampleMs = nowMs
        }
        lastFlowFrameTimeMs = nowMs
    }

    private fun integrateDeadReckoning(
        nowMs: Long,
        dtSec: Double,
        yawRateDegPerSec: Double,
        visualOdometry: VisualOdometry
    ): NavigationSnapshot? {
        val origin = currentPoint ?: return null

        val longitudinalAccelMps2 = currentLongitudinalAccel()
        val accelTrust = longitudinalAccelTrust()
        val stationary = detectStationary(visualOdometry, yawRateDegPerSec, nowMs)
        deadReckoningSpeedMps = estimateVehicleDeadReckoningSpeed(
            visualOdometry = visualOdometry,
            nowMs = nowMs,
            dtSec = dtSec,
            longitudinalAccelMps2 = longitudinalAccelMps2,
            accelTrust = accelTrust,
            stationary = stationary
        )
        updateDeadReckoningUncertainty(visualOdometry, dtSec)

        val yawAbs = abs(yawRateDegPerSec)
        val stepDistanceMeters = deadReckoningSpeedMps * dtSec
        val headingDeltaDeg = resolveVehicleHeadingDelta(
            origin = origin,
            distanceMeters = stepDistanceMeters,
            dtSec = dtSec,
            yawRateDegPerSec = yawRateDegPerSec,
            visualOdometry = visualOdometry
        )
        if (
            abs(headingDeltaDeg) >= MIN_HEADING_DELTA_TO_APPLY_DEG ||
            yawAbs >= MIN_YAW_RATE_TO_UPDATE_HEADING_DEG_SEC ||
            visualOdometry.usable
        ) {
            currentHeadingDeg = normalizeDeg(currentHeadingDeg + headingDeltaDeg)
        }

        if (deadReckoningSpeedMps <= 0.0) {
            return NavigationSnapshot(
                point = origin,
                headingDeg = currentHeadingDeg,
                speedMps = 0.0,
                opticalAssistSegments = snapshotSegments(opticalAssistSegments),
                gnssTravelPathSegments = snapshotSegments(gnssTravelPathSegments),
                weakGnssPoints = weakGnssPoints.toList(),
                strongGnssPoints = strongGnssPoints.toList(),
                remainingRoutePoints = remainingRouteFrom(origin)
            )
        }

        val predictedPoint = offsetPoint(
            origin = origin,
            distanceMeters = deadReckoningSpeedMps * dtSec,
            bearingDeg = currentHeadingDeg
        )
        val fusedPose = fusePredictedPoseWithRoute(
            origin = origin,
            predictedPoint = predictedPoint,
            predictedHeadingDeg = currentHeadingDeg,
            distanceMeters = stepDistanceMeters,
            visualOdometry = visualOdometry
        )
        currentPoint = fusedPose.point
        currentHeadingDeg = fusedPose.headingDeg
        if (fusedPose.mapMatchConfidence > 0.0) {
            positionUncertaintyM = (
                positionUncertaintyM *
                    (1.0 - fusedPose.mapMatchConfidence * ROUTE_MATCH_UNCERTAINTY_REDUCTION)
                ).coerceAtLeast(MIN_POSITION_UNCERTAINTY_M)
        }

        val assistPoints = appendPathPoint(
            opticalAssistSegments,
            fusedPose.point,
            DEAD_RECKONING_APPEND_DISTANCE_M
        )
        return NavigationSnapshot(
            point = fusedPose.point,
            headingDeg = currentHeadingDeg,
            speedMps = deadReckoningSpeedMps,
            opticalAssistSegments = assistPoints,
            gnssTravelPathSegments = snapshotSegments(gnssTravelPathSegments),
            weakGnssPoints = weakGnssPoints.toList(),
            strongGnssPoints = strongGnssPoints.toList(),
            remainingRoutePoints = remainingRouteFrom(fusedPose.point)
        )
    }

    private fun resolveVisualOdometry(
        nowMs: Long,
        dtSec: Double,
        yawRateDegPerSec: Double
    ): VisualOdometry {
        val flowFresh = nowMs - lastFlowSampleMs < FLOW_STALE_MS
        val forwardFlowPxPerSec = abs(emaFlowDyPxPerSec)
        val signedLateralFlowPxPerSec = emaFlowDxPxPerSec
        val lateralFlowPxPerSec = abs(signedLateralFlowPxPerSec)
        val yawAbs = abs(yawRateDegPerSec)
        val translationFlowPxPerSec = when {
            !flowFresh || lastFlowConfidence < MIN_FLOW_CONFIDENCE -> 0.0
            yawAbs < ROTATION_SUPPRESSION_YAW_RATE_DEG_SEC ->
                max(emaFlowMagPxPerSec, forwardFlowPxPerSec)
            else -> max(
                forwardFlowPxPerSec,
                emaFlowMagPxPerSec - lateralFlowPxPerSec * ROTATION_LATERAL_FLOW_DISCOUNT
            )
        }
        val usable = translationFlowPxPerSec >= TRANSLATION_FLOW_STILL_PX_PER_SEC
        val effectiveTranslationPxPerSec = effectiveTranslationFlowPxPerSec(translationFlowPxPerSec)
        val speedMps = if (usable) {
            (effectiveTranslationPxPerSec * dynamicFlowToMpsRatio)
                .coerceIn(0.0, MAX_NAVIGATION_SPEED_MPS)
        } else {
            0.0
        }
        val confidenceScore = (lastFlowConfidence / 100.0).coerceIn(0.0, 1.0)
        val quality = if (usable) {
            val flowScore = (
                translationFlowPxPerSec /
                    (TRANSLATION_FLOW_STILL_PX_PER_SEC + FLOW_FULL_QUALITY_PX_PER_SEC)
                ).coerceIn(0.0, 1.0)
            (0.65 * confidenceScore + 0.35 * flowScore).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val turnFlowConfident = flowFresh && lastFlowConfidence >= MIN_FLOW_CONFIDENCE
        val lateralCoherence = emaFlowCoherence
        val opticalTurnDetected = turnFlowConfident &&
            abs(lateralCoherence) >= FLOW_TURN_MIN_COHERENCE &&
            lateralFlowPxPerSec >= MIN_LATERAL_FLOW_FOR_TURN_PX_PER_SEC
        calibrateOpticalYawScale(
            signedLateralPxPerSec = signedLateralFlowPxPerSec,
            coherence = lateralCoherence,
            rawGyroYawDegPerSec = yawRateDegPerSec,
            flowConfident = turnFlowConfident
        )
        val opticalYawRateDegPerSec = if (opticalTurnDetected) {
            (FLOW_YAW_SIGN * signedLateralFlowPxPerSec * dynamicFlowToYawRatio)
                .coerceIn(-MAX_OPTICAL_YAW_RATE_DEG_SEC, MAX_OPTICAL_YAW_RATE_DEG_SEC)
        } else {
            0.0
        }
        lastOpticalYawRateDegPerSec = opticalYawRateDegPerSec

        val yawMag = abs(yawRateDegPerSec)
        val amplifiedGyroYaw = if (yawMag > GYRO_YAW_NOISE_FLOOR_DEG_SEC) {
            val gain = lowSpeedYawGain(deadReckoningSpeedMps)
            val excess = yawMag - GYRO_YAW_NOISE_FLOOR_DEG_SEC
            val boostedMag = GYRO_YAW_NOISE_FLOOR_DEG_SEC + excess * gain
            if (yawRateDegPerSec >= 0.0) boostedMag else -boostedMag
        } else {
            yawRateDegPerSec
        }

        return VisualOdometry(
            usable = usable,
            speedMps = speedMps,
            deltaHeadingDeg = fuseYawRate(
                gyroYawDegPerSec = amplifiedGyroYaw,
                opticalYawDegPerSec = opticalYawRateDegPerSec,
                opticalTurnDetected = opticalTurnDetected
            ) * dtSec,
            opticalYawRateDegPerSec = opticalYawRateDegPerSec,
            translationPxPerSec = translationFlowPxPerSec,
            lateralPxPerSec = signedLateralFlowPxPerSec,
            quality = quality
        )
    }

    /** Fuse gyro + optical yaw: same sign -> larger magnitude; opposite -> trust optical; else gyro. */
    private fun fuseYawRate(
        gyroYawDegPerSec: Double,
        opticalYawDegPerSec: Double,
        opticalTurnDetected: Boolean
    ): Double {
        if (!opticalTurnDetected || opticalYawDegPerSec == 0.0) return gyroYawDegPerSec
        return if (gyroYawDegPerSec * opticalYawDegPerSec >= 0.0) {
            if (abs(opticalYawDegPerSec) >= abs(gyroYawDegPerSec)) opticalYawDegPerSec else gyroYawDegPerSec
        } else {
            opticalYawDegPerSec
        }
    }

    /** Learn the px/s -> deg/s optical-yaw scale from the gyro on clear turns (slow EMA, gated). */
    private fun calibrateOpticalYawScale(
        signedLateralPxPerSec: Double,
        coherence: Double,
        rawGyroYawDegPerSec: Double,
        flowConfident: Boolean
    ) {
        if (!flowConfident) return
        if (abs(rawGyroYawDegPerSec) < OPTICAL_YAW_CALIB_MIN_GYRO_DEG_SEC) return
        if (abs(signedLateralPxPerSec) < OPTICAL_YAW_CALIB_MIN_FLOW_PX_PER_SEC) return
        if (abs(coherence) < FLOW_TURN_MIN_COHERENCE) return
        if (rawGyroYawDegPerSec * (FLOW_YAW_SIGN * signedLateralPxPerSec) <= 0.0) return

        val observed = (abs(rawGyroYawDegPerSec) / abs(signedLateralPxPerSec))
            .coerceIn(MIN_FLOW_YAW_RATE_GAIN, MAX_FLOW_YAW_RATE_GAIN)
        dynamicFlowToYawRatio = (
            OPTICAL_YAW_SCALE_ALPHA * observed +
                (1.0 - OPTICAL_YAW_SCALE_ALPHA) * dynamicFlowToYawRatio
            ).coerceIn(MIN_FLOW_YAW_RATE_GAIN, MAX_FLOW_YAW_RATE_GAIN)
    }

    private fun calibrateCameraSpeedScale(visualOdometry: VisualOdometry) {
        if (!visualOdometry.usable || lastTrueSpeedMps < MIN_CAMERA_CALIBRATION_SPEED_MPS) return
        if (visualOdometry.translationPxPerSec < MIN_CAMERA_CALIBRATION_FLOW_PX_PER_SEC) return

        val currentRatio = lastTrueSpeedMps /
            effectiveTranslationFlowPxPerSec(visualOdometry.translationPxPerSec).coerceAtLeast(1.0)
        if (currentRatio.isNaN() || currentRatio.isInfinite()) return

        val ratio = currentRatio.coerceIn(MIN_FLOW_PX_PER_SEC_TO_MPS, MAX_FLOW_PX_PER_SEC_TO_MPS)
        val alpha = if (cameraSpeedScaleConfidence < CAMERA_SPEED_SCALE_FAST_CONFIDENCE) {
            CAMERA_SPEED_SCALE_FAST_ALPHA
        } else {
            CAMERA_SPEED_SCALE_ALPHA
        }
        dynamicFlowToMpsRatio = (
            alpha * ratio +
                (1.0 - alpha) * dynamicFlowToMpsRatio
            ).coerceIn(MIN_FLOW_PX_PER_SEC_TO_MPS, MAX_FLOW_PX_PER_SEC_TO_MPS)
        cameraSpeedScaleConfidence = (
            cameraSpeedScaleConfidence + CAMERA_SPEED_SCALE_CONFIDENCE_STEP * visualOdometry.quality
            ).coerceIn(0.0, 1.0)
    }

    /**
     * Visual-inertial vehicle speed during a GNSS outage: accelerometer propagates speed at tick
     * rate, optical flow pulls it back to an absolute scale, ZUPT snaps to zero when stopped, and it
     * falls back to the decayed last-GNSS speed when neither sensor is trustworthy.
     */
    private fun estimateVehicleDeadReckoningSpeed(
        visualOdometry: VisualOdometry,
        nowMs: Long,
        dtSec: Double,
        longitudinalAccelMps2: Double,
        accelTrust: Double,
        stationary: Boolean
    ): Double {
        if (stationary) {
            stationaryHoldMs += (dtSec * 1000.0).toLong()
            vehicleDeadReckoningSpeedMps = 0.0
            return 0.0
        }
        stationaryHoldMs = 0L

        val priorSpeed = decayedLastGnssSpeed(nowMs)
        val currentSpeed = vehicleDeadReckoningSpeedMps.takeIf { it > 0.0 } ?: priorSpeed

        val inertialSpeed = if (accelTrust > 0.0) {
            (currentSpeed + longitudinalAccelMps2 * accelTrust * dtSec)
                .coerceIn(0.0, MAX_NAVIGATION_SPEED_MPS)
        } else {
            currentSpeed
        }

        val scaleConfidenceWeight = (
            VEHICLE_MIN_FLOW_WEIGHT_WHEN_UNCALIBRATED +
                (1.0 - VEHICLE_MIN_FLOW_WEIGHT_WHEN_UNCALIBRATED) * cameraSpeedScaleConfidence
            ).coerceIn(0.0, 1.0)
        val visualWeight = (VEHICLE_FLOW_WEIGHT * visualOdometry.quality * scaleConfidenceWeight)
            .coerceIn(0.0, VEHICLE_FLOW_WEIGHT)
        var targetSpeed = when {
            visualOdometry.usable ->
                (visualWeight * visualOdometry.speedMps +
                    (1.0 - visualWeight) * inertialSpeed)
                    .coerceIn(0.0, MAX_NAVIGATION_SPEED_MPS)
            accelTrust > 0.0 -> inertialSpeed
            else -> priorSpeed
        }

        if (
            visualOdometry.usable &&
            visualOdometry.speedMps < priorSpeed &&
            cameraSpeedScaleConfidence < CAMERA_SPEED_SCALE_TRUSTED_CONFIDENCE &&
            accelTrust < INERTIAL_PRIOR_GUARD_DISABLE_TRUST
        ) {
            val priorGuard = (
                VEHICLE_UNCALIBRATED_PRIOR_SPEED_FLOOR -
                    VEHICLE_UNCALIBRATED_PRIOR_SPEED_FLOOR_RELIEF * cameraSpeedScaleConfidence
                ).coerceIn(0.0, 1.0)
            targetSpeed = targetSpeed.coerceAtLeast(priorSpeed * priorGuard)
        }

        if (
            accelTrust >= INERTIAL_TRUST_FOR_BRAKE &&
            longitudinalAccelMps2 > -INERTIAL_BRAKE_EVIDENCE_MPS2
        ) {
            targetSpeed = targetSpeed.coerceAtLeast(inertialSpeed)
        }

        val dropLimit = if (visualOdometry.usable || accelTrust >= INERTIAL_TRUST_FOR_BRAKE) {
            vehicleProfile.speedDropLimitMps2
        } else {
            VEHICLE_COAST_DECAY_MPS2
        }
        vehicleDeadReckoningSpeedMps = approachSpeed(
            current = currentSpeed,
            target = targetSpeed,
            dtSec = dtSec,
            maxRiseMps2 = vehicleProfile.speedRiseLimitMps2,
            maxDropMps2 = dropLimit
        ).coerceIn(0.0, MAX_NAVIGATION_SPEED_MPS)

        return if (vehicleDeadReckoningSpeedMps < VEHICLE_STOP_SPEED_FLOOR_MPS) {
            0.0
        } else {
            vehicleDeadReckoningSpeedMps
        }
    }

    private fun effectiveTranslationFlowPxPerSec(translationPxPerSec: Double): Double {
        return (translationPxPerSec - TRANSLATION_FLOW_STILL_PX_PER_SEC)
            .coerceAtLeast(0.0)
    }

    private fun resolveVehicleHeadingDelta(
        origin: GeoPoint,
        distanceMeters: Double,
        dtSec: Double,
        yawRateDegPerSec: Double,
        visualOdometry: VisualOdometry
    ): Double {
        val sensorDelta = visualOdometry.deltaHeadingDeg
        val baseHeading = normalizeDeg(currentHeadingDeg + sensorDelta)
        val routeDelta = routeHeadingAssistDelta(
            origin = origin,
            baseHeadingDeg = baseHeading,
            distanceMeters = distanceMeters,
            dtSec = dtSec,
            yawRateDegPerSec = yawRateDegPerSec,
            visualOdometry = visualOdometry
        )
        return sensorDelta + routeDelta
    }

    private fun routeHeadingAssistDelta(
        origin: GeoPoint,
        baseHeadingDeg: Double,
        distanceMeters: Double,
        dtSec: Double,
        yawRateDegPerSec: Double,
        visualOdometry: VisualOdometry
    ): Double {
        if (dtSec <= 0.0) return 0.0
        if (deadReckoningSpeedMps < ROUTE_HEADING_ASSIST_MIN_SPEED_MPS) return 0.0

        val originProjection = projectOnRoute(origin) ?: return 0.0
        val distanceGate = (
            ROUTE_MATCH_BASE_DISTANCE_GATE_M +
                positionUncertaintyM * ROUTE_MATCH_UNCERTAINTY_GATE_RATIO
            ).coerceIn(ROUTE_MATCH_BASE_DISTANCE_GATE_M, ROUTE_MATCH_MAX_DISTANCE_GATE_M)
        if (originProjection.distanceFromRouteM > distanceGate) return 0.0

        val lookAheadMeters = (distanceMeters + deadReckoningSpeedMps * ROUTE_HEADING_LOOKAHEAD_SEC)
            .coerceIn(ROUTE_HEADING_MIN_LOOKAHEAD_M, ROUTE_HEADING_MAX_LOOKAHEAD_M)
        val routePose = pointAtRouteDistance(originProjection.distanceAlongRouteM + lookAheadMeters)
            ?: return 0.0
        val headingGap = signedHeadingDelta(baseHeadingDeg, routePose.segmentHeadingDeg)
        if (abs(headingGap) < ROUTE_HEADING_ASSIST_MIN_GAP_DEG) return 0.0

        val turnRateEvidence = yawRateDegPerSec + visualOdometry.opticalYawRateDegPerSec
        val hasOppositeTurnEvidence =
            abs(turnRateEvidence) >= ROUTE_HEADING_OPPOSITE_TURN_EVIDENCE_DEG_SEC &&
                turnRateEvidence * headingGap < 0.0
        if (hasOppositeTurnEvidence) return 0.0

        val lateralRatio = abs(visualOdometry.lateralPxPerSec) /
            visualOdometry.translationPxPerSec.coerceAtLeast(1.0)
        val visualTurnStrength = (lateralRatio / ROUTE_HEADING_FULL_LATERAL_RATIO)
            .coerceIn(0.0, 1.0)
        val routeContinuity = (1.0 - originProjection.distanceFromRouteM / distanceGate)
            .coerceIn(0.0, 1.0)

        val cameraQuality = if (visualOdometry.usable) {
            visualOdometry.quality
        } else {
            ROUTE_HEADING_NO_CAMERA_QUALITY
        }
        val assistStrength = (
            ROUTE_HEADING_BASE_ASSIST_STRENGTH +
                ROUTE_HEADING_VISUAL_ASSIST_STRENGTH * visualTurnStrength
            ) * cameraQuality * routeContinuity

        val maxAssistDelta = ROUTE_HEADING_MAX_ASSIST_DEG_SEC * dtSec
        return (headingGap * assistStrength)
            .coerceIn(-maxAssistDelta, maxAssistDelta)
    }

    private fun updateDeadReckoningUncertainty(
        visualOdometry: VisualOdometry,
        dtSec: Double
    ) {
        val sensorGrowthMps = if (visualOdometry.usable) {
            DR_VISUAL_UNCERTAINTY_GROWTH_MPS
        } else {
            DR_NO_CAMERA_UNCERTAINTY_GROWTH_MPS
        }
        val distanceGrowth = deadReckoningSpeedMps * dtSec *
            if (visualOdometry.usable) DR_VISUAL_DISTANCE_ERROR_RATIO else DR_NO_CAMERA_DISTANCE_ERROR_RATIO
        positionUncertaintyM = (
            positionUncertaintyM +
                sensorGrowthMps * dtSec +
                distanceGrowth
            ).coerceIn(MIN_POSITION_UNCERTAINTY_M, MAX_POSITION_UNCERTAINTY_M)
    }

    private fun decayedLastGnssSpeed(nowMs: Long): Double {
        if (lastTrueSpeedMps <= 0.0 || lastAcceptedGnssMs <= 0L) return 0.0

        val outageSec = ((nowMs - lastAcceptedGnssMs).coerceAtLeast(0L)) / 1000.0
        return (lastTrueSpeedMps * exp(-outageSec / LAST_GNSS_SPEED_DECAY_SEC))
            .coerceIn(0.0, MAX_NAVIGATION_SPEED_MPS)
    }

    private fun approachSpeed(
        current: Double,
        target: Double,
        dtSec: Double,
        maxRiseMps2: Double,
        maxDropMps2: Double
    ): Double {
        val delta = target - current
        val limit = if (delta >= 0.0) maxRiseMps2 * dtSec else maxDropMps2 * dtSec
        return current + delta.coerceIn(-limit, limit)
    }

    private fun fusePredictedPoseWithRoute(
        origin: GeoPoint,
        predictedPoint: GeoPoint,
        predictedHeadingDeg: Double,
        distanceMeters: Double,
        visualOdometry: VisualOdometry
    ): FusedPose {
        val mapMatch = mapMatchPredictedPose(
            origin = origin,
            predictedPoint = predictedPoint,
            predictedHeadingDeg = predictedHeadingDeg,
            distanceMeters = distanceMeters,
            visualOdometry = visualOdometry
        ) ?: return FusedPose(
            point = predictedPoint,
            headingDeg = predictedHeadingDeg,
            mapMatchConfidence = 0.0
        )

        val snapBoost = vehicleSnapBoost(deadReckoningSpeedMps)
        val maxPointBlend = ROUTE_MATCH_MAX_POINT_BLEND +
            (VEHICLE_SNAP_MAX_POINT_BLEND - ROUTE_MATCH_MAX_POINT_BLEND) * snapBoost
        val effectiveConfidence = mapMatch.confidence +
            (1.0 - mapMatch.confidence) * VEHICLE_SNAP_CONFIDENCE_LIFT * snapBoost
        val pointBlend = (effectiveConfidence * maxPointBlend)
            .coerceIn(0.0, maxPointBlend)
        val headingBlendRatio = ROUTE_MATCH_HEADING_BLEND_RATIO +
            (VEHICLE_SNAP_HEADING_BLEND_RATIO - ROUTE_MATCH_HEADING_BLEND_RATIO) * snapBoost
        val headingBlend = pointBlend * headingBlendRatio
        return FusedPose(
            point = interpolatePoint(predictedPoint, mapMatch.point, pointBlend),
            headingDeg = interpolateHeading(predictedHeadingDeg, mapMatch.headingDeg, headingBlend),
            mapMatchConfidence = mapMatch.confidence
        )
    }

    /** Ramps 0 -> 1 from the vehicle gate (~6 km/h) to cruising speed; scales how hard to snap to road. */
    private fun vehicleSnapBoost(speedMps: Double): Double {
        if (speedMps < VEHICLE_SNAP_SPEED_MPS) return 0.0
        val range = (VEHICLE_SNAP_FULL_SPEED_MPS - VEHICLE_SNAP_SPEED_MPS).coerceAtLeast(0.01)
        return ((speedMps - VEHICLE_SNAP_SPEED_MPS) / range).coerceIn(0.0, 1.0)
    }

    /** Gyro-yaw gain (>=1), largest near standstill, fading to 1.0 at the fade speed (slow tight corners). */
    private fun lowSpeedYawGain(speedMps: Double): Double {
        val maxGain = vehicleProfile.lowSpeedYawGainMax
        if (maxGain <= 1.0 || speedMps >= LOW_SPEED_YAW_GAIN_FADE_MPS) return 1.0
        val slowness = ((LOW_SPEED_YAW_GAIN_FADE_MPS - speedMps) / LOW_SPEED_YAW_GAIN_FADE_MPS)
            .coerceIn(0.0, 1.0)
        return 1.0 + (maxGain - 1.0) * slowness
    }

    private fun mapMatchPredictedPose(
        origin: GeoPoint,
        predictedPoint: GeoPoint,
        predictedHeadingDeg: Double,
        distanceMeters: Double,
        visualOdometry: VisualOdometry
    ): MapMatchResult? {
        val originProjection = projectOnRoute(origin)
        val predictedProjection = projectOnRoute(predictedPoint) ?: return null
        val distanceGate = (
            ROUTE_MATCH_BASE_DISTANCE_GATE_M +
                positionUncertaintyM * ROUTE_MATCH_UNCERTAINTY_GATE_RATIO
            ).coerceIn(ROUTE_MATCH_BASE_DISTANCE_GATE_M, ROUTE_MATCH_MAX_DISTANCE_GATE_M)

        val routeDistance = if (
            originProjection != null &&
            originProjection.distanceFromRouteM <= distanceGate
        ) {
            originProjection.distanceAlongRouteM + distanceMeters
        } else if (predictedProjection.distanceFromRouteM <= distanceGate) {
            predictedProjection.distanceAlongRouteM
        } else {
            return null
        }

        val routePose = pointAtRouteDistance(routeDistance) ?: predictedProjection
        val lateralDistance = predictedPoint.distanceToAsDouble(routePose.point)
        val headingError = absHeadingDelta(predictedHeadingDeg, routePose.segmentHeadingDeg)
        val distanceScore = (1.0 - lateralDistance / distanceGate).coerceIn(0.0, 1.0)
        val headingScore = (1.0 - headingError / ROUTE_MATCH_HEADING_GATE_DEG).coerceIn(0.0, 1.0)
        val continuityScore = originProjection
            ?.let { (1.0 - it.distanceFromRouteM / distanceGate).coerceIn(0.0, 1.0) }
            ?: 0.35
        val visualScore = if (visualOdometry.usable) visualOdometry.quality else ROUTE_MATCH_NO_CAMERA_VISUAL_SCORE
        val confidence = (
            distanceScore * ROUTE_MATCH_DISTANCE_WEIGHT +
                headingScore * ROUTE_MATCH_HEADING_WEIGHT +
                continuityScore * ROUTE_MATCH_CONTINUITY_WEIGHT +
                visualScore * ROUTE_MATCH_VISUAL_WEIGHT
            ).coerceIn(0.0, 1.0)

        if (
            confidence < ROUTE_MATCH_MIN_CONFIDENCE ||
            distanceScore <= 0.0 ||
            headingScore <= ROUTE_MATCH_MIN_HEADING_SCORE
        ) {
            return null
        }

        return MapMatchResult(
            point = routePose.point,
            headingDeg = routePose.segmentHeadingDeg,
            confidence = confidence
        )
    }

    private fun projectOnRoute(point: GeoPoint): RouteProjection? {
        val routePoints = routeState?.routePoints.orEmpty()
        if (routePoints.size < 2) return null

        var traveledMeters = 0.0
        var best: RouteProjection? = null
        for (index in 0 until routePoints.lastIndex) {
            val start = routePoints[index]
            val end = routePoints[index + 1]
            val segmentMeters = start.distanceToAsDouble(end)
            if (segmentMeters <= 0.01) continue

            val metersPerDegreeLatitude = 111_132.0
            val metersPerDegreeLongitude = 111_320.0 * cos(Math.toRadians(point.latitude))
            val startX = (start.longitude - point.longitude) * metersPerDegreeLongitude
            val startY = (start.latitude - point.latitude) * metersPerDegreeLatitude
            val endX = (end.longitude - point.longitude) * metersPerDegreeLongitude
            val endY = (end.latitude - point.latitude) * metersPerDegreeLatitude
            val segmentX = endX - startX
            val segmentY = endY - startY
            val segmentLengthSq = segmentX * segmentX + segmentY * segmentY
            val projectionRatio = if (segmentLengthSq <= 0.01) {
                0.0
            } else {
                (-(startX * segmentX + startY * segmentY) / segmentLengthSq).coerceIn(0.0, 1.0)
            }
            val projected = interpolatePoint(start, end, projectionRatio)
            val distanceFromRoute = point.distanceToAsDouble(projected)
            val candidate = RouteProjection(
                point = projected,
                segmentIndex = index,
                distanceAlongRouteM = traveledMeters + segmentMeters * projectionRatio,
                distanceFromRouteM = distanceFromRoute,
                segmentHeadingDeg = bearingBetween(start, end)
            )
            if (best == null || candidate.distanceFromRouteM < best.distanceFromRouteM) {
                best = candidate
            }
            traveledMeters += segmentMeters
        }
        return best
    }

    private fun pointAtRouteDistance(distanceAlongRouteM: Double): RouteProjection? {
        val routePoints = routeState?.routePoints.orEmpty()
        if (routePoints.size < 2) return null

        val targetDistance = distanceAlongRouteM.coerceAtLeast(0.0)
        var traveledMeters = 0.0
        for (index in 0 until routePoints.lastIndex) {
            val start = routePoints[index]
            val end = routePoints[index + 1]
            val segmentMeters = start.distanceToAsDouble(end)
            if (segmentMeters <= 0.01) continue

            val segmentEndDistance = traveledMeters + segmentMeters
            if (targetDistance <= segmentEndDistance) {
                val segmentRatio = ((targetDistance - traveledMeters) / segmentMeters)
                    .coerceIn(0.0, 1.0)
                return RouteProjection(
                    point = interpolatePoint(start, end, segmentRatio),
                    segmentIndex = index,
                    distanceAlongRouteM = targetDistance,
                    distanceFromRouteM = 0.0,
                    segmentHeadingDeg = bearingBetween(start, end)
                )
            }
            traveledMeters = segmentEndDistance
        }

        val lastIndex = routePoints.lastIndex
        return RouteProjection(
            point = routePoints[lastIndex],
            segmentIndex = lastIndex - 1,
            distanceAlongRouteM = traveledMeters,
            distanceFromRouteM = 0.0,
            segmentHeadingDeg = bearingBetween(routePoints[lastIndex - 1], routePoints[lastIndex])
        )
    }

    private fun interpolatePoint(start: GeoPoint, end: GeoPoint, ratio: Double): GeoPoint {
        val clampedRatio = ratio.coerceIn(0.0, 1.0)
        return GeoPoint(
            start.latitude + (end.latitude - start.latitude) * clampedRatio,
            start.longitude + (end.longitude - start.longitude) * clampedRatio
        )
    }

    private fun appendPathPoint(
        segments: ArrayList<ArrayList<GeoPoint>>,
        point: GeoPoint,
        minDistanceMeters: Double
    ): List<List<GeoPoint>>? {
        if (segments.isEmpty()) {
            startNewSegment(segments, point)
            return snapshotSegments(segments)
        }

        val pathPoints = segments.last()
        val last = pathPoints.lastOrNull()
        if (last != null && last.distanceToAsDouble(point) < minDistanceMeters) {
            return null
        }
        pathPoints.add(point)
        return snapshotSegments(segments)
    }

    private fun startNewSegment(
        segments: ArrayList<ArrayList<GeoPoint>>,
        startPoint: GeoPoint
    ) {
        segments.add(arrayListOf(startPoint))
    }

    private fun snapshotSegments(
        segments: List<List<GeoPoint>>
    ): List<List<GeoPoint>>? {
        return segments.mapNotNull { segment ->
            segment.takeIf { it.size >= 2 }?.toList()
        }.takeIf { it.isNotEmpty() }
    }

    private fun remainingRouteFrom(point: GeoPoint): List<GeoPoint> {
        val points = routeState?.routePoints.orEmpty()
        if (points.size < 2) return points

        val projection = projectOnRoute(point)
        if (projection != null && projection.distanceFromRouteM <= ROUTE_REMAINING_PROJECTION_DISTANCE_M) {
            val remaining = ArrayList<GeoPoint>(points.size - projection.segmentIndex + 1)
            remaining.add(projection.point)
            for (index in (projection.segmentIndex + 1)..points.lastIndex) {
                remaining.add(points[index])
            }
            return remaining
        }

        var minDistance = Double.MAX_VALUE
        var closestIndex = 0
        points.forEachIndexed { index, routePoint ->
            val distance = point.distanceToAsDouble(routePoint)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }
        val remaining = ArrayList<GeoPoint>(points.size - closestIndex + 1)
        remaining.add(point)
        val nextIndex = (closestIndex + 1).coerceAtMost(points.lastIndex)
        for (index in nextIndex..points.lastIndex) {
            remaining.add(points[index])
        }
        return remaining
    }

    private fun distanceToRouteMeters(point: GeoPoint, routePoints: List<GeoPoint>): Double {
        if (routePoints.isEmpty()) return Double.MAX_VALUE
        if (routePoints.size == 1) return point.distanceToAsDouble(routePoints.first())

        var minDistance = Double.MAX_VALUE
        for (index in 0 until routePoints.lastIndex) {
            minDistance = minDistance.coerceAtMost(
                distanceToSegmentMeters(point, routePoints[index], routePoints[index + 1])
            )
        }
        return minDistance
    }

    private fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val metersPerDegreeLatitude = 111_132.0
        val metersPerDegreeLongitude = 111_320.0 * cos(Math.toRadians(point.latitude))
        val startX = (start.longitude - point.longitude) * metersPerDegreeLongitude
        val startY = (start.latitude - point.latitude) * metersPerDegreeLatitude
        val endX = (end.longitude - point.longitude) * metersPerDegreeLongitude
        val endY = (end.latitude - point.latitude) * metersPerDegreeLatitude
        val segmentX = endX - startX
        val segmentY = endY - startY
        val segmentLengthSq = segmentX * segmentX + segmentY * segmentY
        if (segmentLengthSq <= 0.01) return point.distanceToAsDouble(start)

        val projection = (-(startX * segmentX + startY * segmentY) / segmentLengthSq)
            .coerceIn(0.0, 1.0)
        val closestX = startX + segmentX * projection
        val closestY = startY + segmentY * projection
        return sqrt(closestX * closestX + closestY * closestY)
    }

    private fun evaluateGnssAssist(nowMs: Long): AssistDecision {
        return setGnssAssistActive(!hasCurrentlyUsableGnss(nowMs))
    }

    private fun setGnssAssistActive(active: Boolean): AssistDecision {
        val changed = gnssAssistActive != active
        if (changed) {
            gnssAssistActive = active
            if (active) {
                positionUncertaintyM = positionUncertaintyM.coerceAtLeast(GNSS_TO_DR_INITIAL_UNCERTAINTY_M)
                currentPoint?.let { 
                    startNewSegment(opticalAssistSegments, it)
                    weakGnssPoints.add(it)
                }
                gnssTravelSegmentOpen = false
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
        emaFlowCoherence = 0.0
        lastFlowSampleMs = 0L
        lastFlowConfidence = 0.0
        lastOpticalYawRateDegPerSec = 0.0
    }

    private fun resetCameraSpeedScale() {
        dynamicFlowToMpsRatio = FLOW_PX_PER_SEC_TO_MPS
        dynamicFlowToYawRatio = FLOW_YAW_RATE_GAIN_DEG_PER_PX_SEC
        cameraSpeedScaleConfidence = INITIAL_CAMERA_SPEED_SCALE_CONFIDENCE
    }

    private fun resetDeadReckoningRuntime() {
        deadReckoningSpeedMps = 0.0
        vehicleDeadReckoningSpeedMps = 0.0
        positionUncertaintyM = INITIAL_POSITION_UNCERTAINTY_M
    }

    private fun resetInertialRuntime() {
        emaAccelDevice.fill(0.0)
        hasAccelSample = false
        forwardAxisDevice.fill(0.0)
        forwardAxisConfidence = 0.0
        longitudinalAccelBiasMps2 = 0.0
        prevGnssSpeedForAccelMps = 0.0
        prevGnssHeadingDeg = 0.0
        hasPrevGnssAccelRef = false
        stationaryHoldMs = 0L
    }

    /** Smooths the device-frame horizontal acceleration so it represents the recent (~0.3 s) trend. */
    private fun updateImuAccel(horizontalAccelDevice: FloatArray) {
        if (horizontalAccelDevice.size < 3) return
        val ax = horizontalAccelDevice[0].toDouble()
        val ay = horizontalAccelDevice[1].toDouble()
        val az = horizontalAccelDevice[2].toDouble()
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return

        if (!hasAccelSample) {
            emaAccelDevice[0] = ax
            emaAccelDevice[1] = ay
            emaAccelDevice[2] = az
            hasAccelSample = true
            return
        }
        emaAccelDevice[0] = (1.0 - ACCEL_EMA_ALPHA) * emaAccelDevice[0] + ACCEL_EMA_ALPHA * ax
        emaAccelDevice[1] = (1.0 - ACCEL_EMA_ALPHA) * emaAccelDevice[1] + ACCEL_EMA_ALPHA * ay
        emaAccelDevice[2] = (1.0 - ACCEL_EMA_ALPHA) * emaAccelDevice[2] + ACCEL_EMA_ALPHA * az
    }

    /**
     * Learns the device-frame "vehicle forward" unit vector (and the longitudinal accel bias) from
     * GNSS truth. Yaw misalignment between the phone and the car is observable whenever the
     * longitudinal acceleration changes, so we only update on straight-line segments with a clear
     * acceleration/braking event and align the forward axis with the measured horizontal acceleration.
     */
    private fun learnForwardAxisFromGnss(
        currentSpeedMps: Double,
        currentHeadingDegValue: Double,
        previousGnssMs: Long,
        nowMs: Long
    ) {
        val hadPrev = hasPrevGnssAccelRef
        val prevSpeed = prevGnssSpeedForAccelMps
        val prevHeading = prevGnssHeadingDeg
        prevGnssSpeedForAccelMps = currentSpeedMps
        prevGnssHeadingDeg = currentHeadingDegValue
        hasPrevGnssAccelRef = true

        if (!hadPrev || !hasAccelSample) return
        if (previousGnssMs <= 0L || nowMs <= previousGnssMs) return
        val dtGnssSec = (nowMs - previousGnssMs) / 1000.0
        if (dtGnssSec < MIN_ALIGN_DT_SEC || dtGnssSec > MAX_ALIGN_DT_SEC) return
        if (currentSpeedMps < MIN_FORWARD_AXIS_SPEED_MPS) return
        if (abs(signedHeadingDelta(prevHeading, currentHeadingDegValue)) > MAX_FORWARD_AXIS_HEADING_CHANGE_DEG) return

        val gnssLongAccel = (currentSpeedMps - prevSpeed) / dtGnssSec
        if (abs(gnssLongAccel) < MIN_FORWARD_AXIS_ACCEL_MPS2) return

        val ax = emaAccelDevice[0]
        val ay = emaAccelDevice[1]
        val az = emaAccelDevice[2]
        val accelMag = sqrt(ax * ax + ay * ay + az * az)
        if (accelMag < MIN_FORWARD_AXIS_ACCEL_MPS2) return

        val sign = if (gnssLongAccel >= 0.0) 1.0 else -1.0
        val dirX = ax / accelMag * sign
        val dirY = ay / accelMag * sign
        val dirZ = az / accelMag * sign

        val axisAlpha = vehicleProfile.forwardAxisAlpha
        val confidenceStep = vehicleProfile.forwardAxisConfidenceStep
        if (forwardAxisConfidence <= 0.0) {
            forwardAxisDevice[0] = dirX
            forwardAxisDevice[1] = dirY
            forwardAxisDevice[2] = dirZ
            forwardAxisConfidence = confidenceStep
        } else {
            val blendedX = (1.0 - axisAlpha) * forwardAxisDevice[0] + axisAlpha * dirX
            val blendedY = (1.0 - axisAlpha) * forwardAxisDevice[1] + axisAlpha * dirY
            val blendedZ = (1.0 - axisAlpha) * forwardAxisDevice[2] + axisAlpha * dirZ
            val blendedMag = sqrt(blendedX * blendedX + blendedY * blendedY + blendedZ * blendedZ)
            if (blendedMag > 1e-6) {
                forwardAxisDevice[0] = blendedX / blendedMag
                forwardAxisDevice[1] = blendedY / blendedMag
                forwardAxisDevice[2] = blendedZ / blendedMag
            }
            forwardAxisConfidence = (forwardAxisConfidence + confidenceStep).coerceAtMost(1.0)
        }

        if (forwardAxisConfidence >= FORWARD_AXIS_BIAS_MIN_CONFIDENCE) {
            val measuredLongAccel = ax * forwardAxisDevice[0] +
                ay * forwardAxisDevice[1] +
                az * forwardAxisDevice[2]
            val residual = measuredLongAccel - gnssLongAccel
            longitudinalAccelBiasMps2 = (
                (1.0 - FORWARD_AXIS_BIAS_ALPHA) * longitudinalAccelBiasMps2 +
                    FORWARD_AXIS_BIAS_ALPHA * residual
                ).coerceIn(-MAX_ACCEL_BIAS_MPS2, MAX_ACCEL_BIAS_MPS2)
        }
    }

    /** Bias-corrected longitudinal acceleration (m/s^2), positive = accelerating forward. */
    private fun currentLongitudinalAccel(): Double {
        if (!hasAccelSample || forwardAxisConfidence <= 0.0) return 0.0
        val projected = emaAccelDevice[0] * forwardAxisDevice[0] +
            emaAccelDevice[1] * forwardAxisDevice[1] +
            emaAccelDevice[2] * forwardAxisDevice[2]
        return (projected - longitudinalAccelBiasMps2)
            .coerceIn(-vehicleProfile.maxBrakeAccelMps2, vehicleProfile.maxDriveAccelMps2)
    }

    private fun longitudinalAccelTrust(): Double {
        if (!hasAccelSample) return 0.0
        return forwardAxisConfidence.coerceIn(0.0, vehicleProfile.maxInertialTrust)
    }

    private fun detectStationary(
        visualOdometry: VisualOdometry,
        yawRateDegPerSec: Double,
        nowMs: Long
    ): Boolean {
        if (visualOdometry.usable) return false
        val slowEnough = vehicleDeadReckoningSpeedMps < vehicleProfile.zuptEnterSpeedMps &&
            decayedLastGnssSpeed(nowMs) < vehicleProfile.zuptEnterSpeedMps
        if (!slowEnough) return false
        if (abs(yawRateDegPerSec) > ZUPT_YAW_RATE_DEG_SEC) return false
        if (hasAccelSample) {
            val accelMag = sqrt(
                emaAccelDevice[0] * emaAccelDevice[0] +
                    emaAccelDevice[1] * emaAccelDevice[1] +
                    emaAccelDevice[2] * emaAccelDevice[2]
            )

            if (accelMag > vehicleProfile.zuptAccelMps2) return false
        }
        return true
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

    private fun interpolateHeading(fromDeg: Double, toDeg: Double, ratio: Double): Double {
        val delta = signedHeadingDelta(fromDeg, toDeg)
        return normalizeDeg(fromDeg + delta * ratio.coerceIn(0.0, 1.0))
    }

    private fun absHeadingDelta(fromDeg: Double, toDeg: Double): Double {
        return abs(signedHeadingDelta(fromDeg, toDeg))
    }

    private fun signedHeadingDelta(fromDeg: Double, toDeg: Double): Double {
        var delta = (toDeg - fromDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    companion object {
        // === Runtime cadence & GNSS-dropout simulation — tune when changing update rate / test ===
        const val LOCATION_UPDATE_MS = 1000L
        const val TICK_MS = 50L
        const val TEST_GNSS_DROPOUT = true
        const val TEST_GNSS_PRESENT_MS = 3_000L
        const val TEST_GNSS_ABSENT_MS = 10_000L

        // === GNSS gating & quality — tune when fixes are wrongly accepted/rejected ===
        private const val GNSS_LOCATION_STALE_MS = 5_000L
        private const val GNSS_STATUS_STALE_MS = 5_000L
        private const val MIN_USABLE_GNSS_SATELLITES = 4
        private const val MAX_USABLE_GNSS_ACCURACY_M = 25f
        private const val MIN_BEARING_SPEED_MPS = 0.35
        private const val GNSS_STATIONARY_SPEED_FLOOR_MPS = 0.20
        private const val MIN_GNSS_DISTANCE_FOR_DERIVED_SPEED_M = 1.5
        private const val MAX_NAVIGATION_SPEED_MPS = 45.0

        // === Re-route / arrival — tune when off-route refresh fires too eagerly/late ===
        private const val ROUTE_DEVIATION_DISTANCE_M = 35.0
        private const val ROUTE_DEVIATION_REQUIRED_SAMPLES = 2
        private const val ROUTE_DEVIATION_SAMPLE_INTERVAL_MS = 1_000L
        private const val ROUTE_REFRESH_COOLDOWN_MS = 12_000L
        private const val ARRIVAL_DISTANCE_M = 18.0

        // === Position uncertainty (± error radius) — tune growth/decay behaviour ===
        private const val INITIAL_POSITION_UNCERTAINTY_M = 8.0
        private const val MIN_POSITION_UNCERTAINTY_M = 3.0
        private const val GNSS_FIX_UNCERTAINTY_M = 6.0
        private const val GNSS_TO_DR_INITIAL_UNCERTAINTY_M = 8.0
        private const val MAX_POSITION_UNCERTAINTY_M = 180.0
        private const val DR_VISUAL_UNCERTAINTY_GROWTH_MPS = 0.8
        private const val DR_NO_CAMERA_UNCERTAINTY_GROWTH_MPS = 2.2
        private const val DR_VISUAL_DISTANCE_ERROR_RATIO = 0.08
        private const val DR_NO_CAMERA_DISTANCE_ERROR_RATIO = 0.25

        // === Optical-flow sampling & speed scale — tune when camera speed reads wrong ===
        const val FLOW_SENSITIVITY = 100
        private const val FEATURE_UPDATE_INTERVAL = 30
        private const val EMA_ALPHA = 0.45
        private const val FLOW_STALE_MS = 650L
        private const val TRANSLATION_FLOW_STILL_PX_PER_SEC = 4.0
        private const val FLOW_FULL_QUALITY_PX_PER_SEC = 60.0
        private const val MIN_FLOW_CONFIDENCE = 5.0
        private const val FLOW_PX_PER_SEC_TO_MPS = 0.075
        private const val MIN_FLOW_PX_PER_SEC_TO_MPS = 0.006
        private const val MAX_FLOW_PX_PER_SEC_TO_MPS = 0.30
        private const val ROTATION_SUPPRESSION_YAW_RATE_DEG_SEC = 12.0
        private const val ROTATION_LATERAL_FLOW_DISCOUNT = 0.12
        private const val MIN_CAMERA_CALIBRATION_SPEED_MPS = 2.0
        private const val MIN_CAMERA_CALIBRATION_FLOW_PX_PER_SEC = 10.0
        private const val CAMERA_SPEED_SCALE_ALPHA = 0.16
        private const val CAMERA_SPEED_SCALE_FAST_ALPHA = 0.42
        private const val CAMERA_SPEED_SCALE_FAST_CONFIDENCE = 0.35
        private const val CAMERA_SPEED_SCALE_CONFIDENCE_STEP = 0.10
        private const val CAMERA_SPEED_SCALE_TRUSTED_CONFIDENCE = 0.65
        private const val INITIAL_CAMERA_SPEED_SCALE_CONFIDENCE = 0.15

        // === Turn detection: optical pan + gyro yaw fusion — tune when turns missed/over-read ===
        private const val FLOW_TURN_MIN_COHERENCE = 0.35
        private const val MIN_LATERAL_FLOW_FOR_TURN_PX_PER_SEC = 6.0
        private const val FLOW_YAW_SIGN = -1.0 // flip if turns steer the wrong way
        private const val FLOW_YAW_RATE_GAIN_DEG_PER_PX_SEC = 0.40
        private const val MIN_FLOW_YAW_RATE_GAIN = 0.08
        private const val MAX_FLOW_YAW_RATE_GAIN = 1.6
        private const val MAX_OPTICAL_YAW_RATE_DEG_SEC = 45.0
        private const val OPTICAL_YAW_CALIB_MIN_GYRO_DEG_SEC = 6.0
        private const val OPTICAL_YAW_CALIB_MIN_FLOW_PX_PER_SEC = 8.0
        private const val OPTICAL_YAW_SCALE_ALPHA = 0.12
        private const val GYRO_YAW_NOISE_FLOOR_DEG_SEC = 1.5
        private const val LOW_SPEED_YAW_GAIN_FADE_MPS = 4.0 // ~14 km/h
        private const val MIN_YAW_RATE_TO_UPDATE_HEADING_DEG_SEC = 0.5
        private const val MIN_HEADING_DELTA_TO_APPLY_DEG = 0.02

        // === Vehicle speed dead-reckoning fusion — tune the flow/inertial/prior blend ===
        private const val VEHICLE_FLOW_WEIGHT = 0.78
        private const val VEHICLE_MIN_FLOW_WEIGHT_WHEN_UNCALIBRATED = 0.28
        private const val VEHICLE_UNCALIBRATED_PRIOR_SPEED_FLOOR = 0.88
        private const val VEHICLE_UNCALIBRATED_PRIOR_SPEED_FLOOR_RELIEF = 0.55
        private const val VEHICLE_COAST_DECAY_MPS2 = 0.9
        private const val VEHICLE_STOP_SPEED_FLOOR_MPS = 0.20
        private const val LAST_GNSS_SPEED_DECAY_SEC = 14.0

        // === Inertial (IMU longitudinal) dead-reckoning — tune accel trust / forward-axis learning ===
        private const val ACCEL_EMA_ALPHA = 0.18
        private const val MAX_ACCEL_BIAS_MPS2 = 2.5
        private const val MIN_FORWARD_AXIS_SPEED_MPS = 3.0
        private const val MIN_FORWARD_AXIS_ACCEL_MPS2 = 0.45
        private const val MAX_FORWARD_AXIS_HEADING_CHANGE_DEG = 6.0
        private const val FORWARD_AXIS_BIAS_MIN_CONFIDENCE = 0.5
        private const val FORWARD_AXIS_BIAS_ALPHA = 0.05
        private const val MIN_ALIGN_DT_SEC = 0.2
        private const val MAX_ALIGN_DT_SEC = 3.0
        private const val INERTIAL_PRIOR_GUARD_DISABLE_TRUST = 0.5
        private const val INERTIAL_TRUST_FOR_BRAKE = 0.5
        private const val INERTIAL_BRAKE_EVIDENCE_MPS2 = 0.6
        private const val ZUPT_YAW_RATE_DEG_SEC = 4.0

        // === Map-matching / road snap — tune when the path floats off / snaps too hard ===
        private const val ROUTE_MATCH_BASE_DISTANCE_GATE_M = 10.0
        private const val ROUTE_MATCH_MAX_DISTANCE_GATE_M = 45.0
        private const val ROUTE_MATCH_UNCERTAINTY_GATE_RATIO = 0.6
        private const val ROUTE_MATCH_HEADING_GATE_DEG = 82.0
        private const val ROUTE_MATCH_MIN_CONFIDENCE = 0.52
        private const val ROUTE_MATCH_MIN_HEADING_SCORE = 0.15
        private const val ROUTE_MATCH_DISTANCE_WEIGHT = 0.40
        private const val ROUTE_MATCH_HEADING_WEIGHT = 0.30
        private const val ROUTE_MATCH_CONTINUITY_WEIGHT = 0.20
        private const val ROUTE_MATCH_VISUAL_WEIGHT = 0.10
        private const val ROUTE_MATCH_NO_CAMERA_VISUAL_SCORE = 0.20
        private const val ROUTE_MATCH_MAX_POINT_BLEND = 0.86
        private const val ROUTE_MATCH_HEADING_BLEND_RATIO = 0.45
        private const val ROUTE_MATCH_UNCERTAINTY_REDUCTION = 0.28
        private const val VEHICLE_SNAP_SPEED_MPS = 1.667 // ~6 km/h
        private const val VEHICLE_SNAP_FULL_SPEED_MPS = 3.0 // ~10.8 km/h
        private const val VEHICLE_SNAP_MAX_POINT_BLEND = 0.985
        private const val VEHICLE_SNAP_CONFIDENCE_LIFT = 0.55
        private const val VEHICLE_SNAP_HEADING_BLEND_RATIO = 0.72
        private const val ROUTE_REMAINING_PROJECTION_DISTANCE_M = 60.0

        // === Route-heading assist — tune how strongly the planned road bends the heading ===
        private const val ROUTE_HEADING_LOOKAHEAD_SEC = 1.45
        private const val ROUTE_HEADING_MIN_LOOKAHEAD_M = 6.0
        private const val ROUTE_HEADING_MAX_LOOKAHEAD_M = 28.0
        private const val ROUTE_HEADING_ASSIST_MIN_GAP_DEG = 1.2
        private const val ROUTE_HEADING_MAX_ASSIST_DEG_SEC = 18.0
        private const val ROUTE_HEADING_OPPOSITE_TURN_EVIDENCE_DEG_SEC = 2.0
        private const val ROUTE_HEADING_FULL_LATERAL_RATIO = 0.45
        private const val ROUTE_HEADING_BASE_ASSIST_STRENGTH = 0.22
        private const val ROUTE_HEADING_VISUAL_ASSIST_STRENGTH = 0.46
        private const val ROUTE_HEADING_ASSIST_MIN_SPEED_MPS = 0.6
        private const val ROUTE_HEADING_NO_CAMERA_QUALITY = 0.45

        // === Path drawing & geo (rarely tuned) ===
        private const val DEAD_RECKONING_APPEND_DISTANCE_M = 0.35
        private const val GNSS_PATH_APPEND_DISTANCE_M = 0.75
        private const val EARTH_RADIUS_M = 6_378_137.0

        private val NO_ACCEL_SAMPLE = FloatArray(3)
        private val VEHICLE_PROFILE = VehicleProfile(
            maxDriveAccelMps2 = 6.0,
            maxBrakeAccelMps2 = 9.0,
            speedRiseLimitMps2 = 6.0,
            speedDropLimitMps2 = 8.0,
            forwardAxisAlpha = 0.10,
            forwardAxisConfidenceStep = 0.03,
            maxInertialTrust = 0.70,
            zuptEnterSpeedMps = 1.8,
            zuptAccelMps2 = 1.2,
            lowSpeedYawGainMax = 6.0
        )
    }
}
