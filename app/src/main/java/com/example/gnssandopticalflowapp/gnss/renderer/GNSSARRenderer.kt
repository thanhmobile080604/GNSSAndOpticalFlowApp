package com.example.gnssandopticalflowapp.gnss.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.GnssStatus
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GNSSARRenderer : GLSurfaceView.Renderer {

    private var sceneProgram: Int = 0
    private var positionHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    private var cameraProgram: Int = 0
    private var cameraPositionHandle: Int = 0
    private var cameraTexCoordHandle: Int = 0
    private var cameraTextureHandle: Int = 0
    private var cameraTextureId: Int = 0

    private var textProgram: Int = 0
    private var textPositionHandle: Int = 0
    private var textTexCoordHandle: Int = 0
    private var textMvpMatrixHandle: Int = 0
    private var textTextureHandle: Int = 0

    @Volatile
    private var arSession: Session? = null
    @Volatile
    private var displayRotation: Int = Surface.ROTATION_0
    @Volatile
    private var displayGeometryDirty = true

    private var cameraTextureSet = false
    private var backgroundTexCoordsInitialized = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val projectionMatrix = FloatArray(16)
    private val arCoreViewMatrix = FloatArray(16)
    private val localViewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private var localToArCoreMatrix: FloatArray? = null

    private lateinit var cameraQuadBuffer: FloatBuffer
    private lateinit var cameraTexCoordBuffer: FloatBuffer
    private lateinit var textQuadBuffer: FloatBuffer
    private lateinit var textTexCoordBuffer: FloatBuffer

    private lateinit var sphereBuffer: FloatBuffer
    private var sphereVertexCount = 0

    private lateinit var satFillBuffer: FloatBuffer
    private lateinit var satStrokeBuffer: FloatBuffer
    private var satFillVertexCount = 0
    private var satStrokeVertexCount = 0

    private val satellites = mutableListOf<SatelliteInfo>()
    private val textTextureCache = mutableMapOf<String, TextTexture>()

    private val sphereColor = floatArrayOf(0.0f, 0.55f, 1.0f, 0.8f)
    private val satStrokeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

    @Volatile
    private var worldYawDegrees = 0f
    @Volatile
    private var observerPosition: ObserverPosition? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        cameraProgram = buildProgram(cameraVertexShaderCode, cameraFragmentShaderCode)
        cameraPositionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        cameraTexCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        cameraTextureHandle = GLES20.glGetUniformLocation(cameraProgram, "uCameraTexture")

        sceneProgram = buildProgram(vertexShaderCode, fragmentShaderCode)
        positionHandle = GLES20.glGetAttribLocation(sceneProgram, "vPosition")
        colorHandle = GLES20.glGetUniformLocation(sceneProgram, "vColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(sceneProgram, "uMVPMatrix")

        textProgram = buildProgram(textVertexShaderCode, textFragmentShaderCode)
        textPositionHandle = GLES20.glGetAttribLocation(textProgram, "aPosition")
        textTexCoordHandle = GLES20.glGetAttribLocation(textProgram, "aTexCoord")
        textMvpMatrixHandle = GLES20.glGetUniformLocation(textProgram, "uMVPMatrix")
        textTextureHandle = GLES20.glGetUniformLocation(textProgram, "uTextTexture")

        cameraTextureId = createCameraTexture()
        cameraTextureSet = false
        backgroundTexCoordsInitialized = false
        displayGeometryDirty = true
        localToArCoreMatrix = null
        textTextureCache.clear()

        cameraQuadBuffer = createFloatBuffer(CAMERA_QUAD_COORDS)
        cameraTexCoordBuffer = createFloatBuffer(FloatArray(CAMERA_QUAD_COORDS.size))
        textQuadBuffer = createFloatBuffer(TEXT_QUAD_COORDS)
        textTexCoordBuffer = createFloatBuffer(TEXT_QUAD_TEX_COORDS)
        generateSphere()
        generateSatelliteGeometry()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        displayGeometryDirty = true
        backgroundTexCoordsInitialized = false
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = arSession ?: return
        try {
            if (!cameraTextureSet) {
                session.setCameraTextureName(cameraTextureId)
                cameraTextureSet = true
            }

            updateDisplayGeometryIfNeeded(session)

            val frame = session.update()
            drawCameraBackground(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE, FAR_PLANE)
            camera.getViewMatrix(arCoreViewMatrix, 0)

            if (localToArCoreMatrix == null) {
                localToArCoreMatrix = buildLocalToArCoreMatrix(camera.pose)
            }

            Matrix.multiplyMM(localViewMatrix, 0, arCoreViewMatrix, 0, localToArCoreMatrix, 0)
            Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, localViewMatrix, 0)

            drawScene()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "ARCore camera is not available", e)
            arSession = null
        } catch (e: Exception) {
            Log.e(TAG, "ARCore draw frame failed: ${e.message}", e)
        }
    }

    fun setSession(session: Session?) {
        arSession = session
        cameraTextureSet = false
        displayGeometryDirty = true
    }

    fun updateDisplayRotation(rotation: Int) {
        displayRotation = rotation
        displayGeometryDirty = true
        backgroundTexCoordsInitialized = false
    }

    fun resetWorld() {
        localToArCoreMatrix = null
        worldYawDegrees = 0f
    }

    fun updateWorldYawDegrees(yawDegrees: Float) {
        worldYawDegrees = normalizeDegrees(yawDegrees)
    }

    fun updateUserLocation(location: Location?) {
        observerPosition = location?.let { loc ->
            val altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0
            val ecef = geodeticToEcef(loc.latitude, loc.longitude, altitudeMeters)
            ObserverPosition(
                latitudeRad = Math.toRadians(loc.latitude),
                longitudeRad = Math.toRadians(loc.longitude),
                ecefX = ecef[0],
                ecefY = ecef[1],
                ecefZ = ecef[2]
            )
        }
    }

    fun updateSatellites(newSatellites: List<SatelliteInfo>) {
        synchronized(satellites) {
            satellites.clear()
            satellites.addAll(newSatellites)
        }
    }

    private fun updateDisplayGeometryIfNeeded(session: Session) {
        if (!displayGeometryDirty || viewportWidth <= 0 || viewportHeight <= 0) return

        session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
        displayGeometryDirty = false
    }

    private fun drawCameraBackground(frame: Frame) {
        if (!backgroundTexCoordsInitialized || frame.hasDisplayGeometryChanged()) {
            cameraQuadBuffer.position(0)
            cameraTexCoordBuffer.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                cameraQuadBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraTexCoordBuffer
            )
            backgroundTexCoordsInitialized = true
        }

        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(cameraProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureHandle, 0)

        GLES20.glEnableVertexAttribArray(cameraPositionHandle)
        cameraQuadBuffer.position(0)
        GLES20.glVertexAttribPointer(cameraPositionHandle, 2, GLES20.GL_FLOAT, false, 0, cameraQuadBuffer)

        GLES20.glEnableVertexAttribArray(cameraTexCoordHandle)
        cameraTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(cameraTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, cameraTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(cameraPositionHandle)
        GLES20.glDisableVertexAttribArray(cameraTexCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawScene() {
        GLES20.glUseProgram(sceneProgram)

        drawSphere()
        drawGridLabels()

        synchronized(satellites) {
            for (sat in satellites) {
                drawSatellite(sat)
            }
        }
    }

    private fun drawSphere() {
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, sphereBuffer)
        GLES20.glUniform4fv(colorHandle, 1, sphereColor, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, vpMatrix, 0)
        GLES20.glLineWidth(GRID_LINE_WIDTH)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, sphereVertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawSatellite(sat: SatelliteInfo) {
        val placement = buildSatellitePlacementFromResolvedPosition(sat) ?: return

        GLES20.glUseProgram(sceneProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, placement.iconModelMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvp, 0)

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, satFillBuffer)
        val satFillColor = getSatelliteColor(sat)
        GLES20.glUniform4fv(colorHandle, 1, satFillColor, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, satFillVertexCount)

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, satStrokeBuffer)
        GLES20.glUniform4fv(colorHandle, 1, satStrokeColor, 0)
        GLES20.glLineWidth(SATELLITE_STROKE_WIDTH)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, satStrokeVertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)

        drawText(getSatelliteLabel(sat), placement.textModelMatrix, placement.labelWorldHeight)
    }

    private fun buildSatellitePlacementFromResolvedPosition(sat: SatelliteInfo): SatellitePlacement? {
        val observer = observerPosition ?: return null
        if (!hasResolvedSatellitePosition(sat)) return null

        val satEcef = geodeticToEcef(sat.latitude, sat.longitude, sat.altitude)
        val dx = satEcef[0] - observer.ecefX
        val dy = satEcef[1] - observer.ecefY
        val dz = satEcef[2] - observer.ecefZ

        val sinLat = sin(observer.latitudeRad)
        val cosLat = cos(observer.latitudeRad)
        val sinLon = sin(observer.longitudeRad)
        val cosLon = cos(observer.longitudeRad)

        val east = -sinLon * dx + cosLon * dy
        val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz
        val rangeMeters = sqrt(east * east + north * north + up * up)
        if (rangeMeters < MIN_RANGE_METERS) return null

        val yawRad = Math.toRadians(worldYawDegrees.toDouble())
        val localX = east * cos(yawRad) - north * sin(yawRad)
        val localY = east * sin(yawRad) + north * cos(yawRad)
        val localZ = up
        val localLength = sqrt(localX * localX + localY * localY + localZ * localZ)
        if (localLength < MIN_RANGE_METERS) return null

        val radial = normalize(
            floatArrayOf(
                (localX / localLength).toFloat(),
                (localY / localLength).toFloat(),
                (localZ / localLength).toFloat()
            )
        )
        val renderRadius = getSatelliteRenderRadius(rangeMeters)
        val satellitePosition = floatArrayOf(
            radial[0] * renderRadius,
            radial[1] * renderRadius,
            radial[2] * renderRadius
        )
        sat.worldX = satellitePosition[0]
        sat.worldY = satellitePosition[1]
        sat.worldZ = satellitePosition[2]

        val localAzDegrees = Math.toDegrees(atan2(localX, localY)).toFloat()
        val elevationDegrees = Math.toDegrees(asin((localZ / localLength).coerceIn(-1.0, 1.0))).toFloat()
        val scale = getSatelliteVisualScale(rangeMeters)
        val rollDegrees = getSatellitePlaneRoll(localAzDegrees, elevationDegrees)

        val iconMatrix = buildPositionBasis(
            position = satellitePosition,
            forwardOffset = SATELLITE_FRONT_OFFSET,
            rollDegrees = rollDegrees,
            axisScale = scale
        ).modelMatrix
        val textMatrix = buildPositionBasis(
            position = satellitePosition,
            forwardOffset = SATELLITE_FRONT_OFFSET + SATELLITE_LABEL_FORWARD_OFFSET,
            rollDegrees = rollDegrees,
            axisScale = 1.0f
        ).modelMatrix

        return SatellitePlacement(
            iconModelMatrix = iconMatrix,
            textModelMatrix = textMatrix,
            labelWorldHeight = SATELLITE_LABEL_HEIGHT * scale
        )
    }

    private fun drawGridLabels() {
        for (azimuth in GRID_LONGITUDE_LABELS) {
            drawTextAtSkyPosition(
                text = azimuth.toString(),
                localAzDegrees = azimuth.toFloat(),
                elevationDegrees = 0f,
                radius = SKY_RADIUS + GRID_LABEL_RADIUS_OFFSET,
                worldHeight = GRID_LABEL_HEIGHT,
                forwardOffset = GRID_LABEL_FORWARD_OFFSET,
                rollDegrees = 0f
            )
        }

        for (elevation in GRID_LATITUDE_LABELS) {
            drawTextAtSkyPosition(
                text = elevation.toString(),
                localAzDegrees = 0f,
                elevationDegrees = elevation.toFloat(),
                radius = SKY_RADIUS + GRID_LABEL_RADIUS_OFFSET,
                worldHeight = GRID_LABEL_HEIGHT,
                forwardOffset = GRID_LABEL_FORWARD_OFFSET,
                rollDegrees = 0f
            )
        }
    }

    private fun drawTextAtSkyPosition(
        text: String,
        localAzDegrees: Float,
        elevationDegrees: Float,
        radius: Float,
        worldHeight: Float,
        forwardOffset: Float,
        rollDegrees: Float
    ) {
        val skyBasis = buildSkyBasis(
            localAzDegrees = localAzDegrees,
            elevationDegrees = elevationDegrees,
            radius = radius,
            forwardOffset = forwardOffset,
            rollDegrees = rollDegrees
        )

        drawText(text, skyBasis.modelMatrix, worldHeight)
    }

    private fun buildSkyBasis(
        localAzDegrees: Float,
        elevationDegrees: Float,
        radius: Float,
        forwardOffset: Float,
        rollDegrees: Float,
        axisScale: Float = 1.0f
    ): SkyBasis {
        val azRad = Math.toRadians(localAzDegrees.toDouble())
        val elRad = Math.toRadians(elevationDegrees.toDouble())
        val radial = normalize(
            floatArrayOf(
                (cos(elRad) * sin(azRad)).toFloat(),
                (cos(elRad) * cos(azRad)).toFloat(),
                sin(elRad).toFloat()
            )
        )
        return buildRadialBasis(radial, radius, forwardOffset, rollDegrees, axisScale)
    }

    private fun buildPositionBasis(
        position: FloatArray,
        forwardOffset: Float,
        rollDegrees: Float,
        axisScale: Float = 1.0f
    ): SkyBasis {
        val radial = normalize(position)
        val radius = vectorLength(position)
        return buildRadialBasis(radial, radius, forwardOffset, rollDegrees, axisScale)
    }

    private fun buildRadialBasis(
        radial: FloatArray,
        radius: Float,
        forwardOffset: Float,
        rollDegrees: Float,
        axisScale: Float
    ): SkyBasis {
        val normal = floatArrayOf(-radial[0], -radial[1], -radial[2])
        var right = cross(floatArrayOf(0f, 0f, 1f), normal)
        right = if (vectorLength(right) < MIN_VECTOR_LENGTH) {
            floatArrayOf(1f, 0f, 0f)
        } else {
            normalize(right)
        }
        val up = normalize(cross(normal, right))
        if (rollDegrees != 0f) {
            val rollRad = Math.toRadians(rollDegrees.toDouble())
            val cosRoll = cos(rollRad).toFloat()
            val sinRoll = sin(rollRad).toFloat()
            val rolledRight = floatArrayOf(
                right[0] * cosRoll + up[0] * sinRoll,
                right[1] * cosRoll + up[1] * sinRoll,
                right[2] * cosRoll + up[2] * sinRoll
            )
            val rolledUp = floatArrayOf(
                up[0] * cosRoll - right[0] * sinRoll,
                up[1] * cosRoll - right[1] * sinRoll,
                up[2] * cosRoll - right[2] * sinRoll
            )
            right = normalize(rolledRight)
            val up = normalize(rolledUp)
            return buildSkyBasisMatrix(radial, normal, right, up, radius, forwardOffset, axisScale)
        }

        return buildSkyBasisMatrix(radial, normal, right, up, radius, forwardOffset, axisScale)
    }

    private fun buildSkyBasisMatrix(
        radial: FloatArray,
        normal: FloatArray,
        right: FloatArray,
        up: FloatArray,
        radius: Float,
        forwardOffset: Float,
        axisScale: Float
    ): SkyBasis {
        val x = radial[0] * radius + normal[0] * forwardOffset
        val y = radial[1] * radius + normal[1] * forwardOffset
        val z = radial[2] * radius + normal[2] * forwardOffset

        val modelMatrix = FloatArray(16).apply {
            this[0] = right[0] * axisScale
            this[1] = right[1] * axisScale
            this[2] = right[2] * axisScale
            this[3] = 0f

            this[4] = up[0] * axisScale
            this[5] = up[1] * axisScale
            this[6] = up[2] * axisScale
            this[7] = 0f

            this[8] = normal[0] * axisScale
            this[9] = normal[1] * axisScale
            this[10] = normal[2] * axisScale
            this[11] = 0f

            this[12] = x
            this[13] = y
            this[14] = z
            this[15] = 1f
        }

        return SkyBasis(modelMatrix)
    }

    private fun drawText(text: String, modelMatrix: FloatArray, worldHeight: Float) {
        val texture = getTextTexture(text)
        val worldWidth = worldHeight * texture.aspectRatio
        drawTexturedRect(
            left = -worldWidth / 2f,
            right = worldWidth / 2f,
            bottom = -worldHeight / 2f,
            top = worldHeight / 2f,
            textureId = texture.textureId,
            modelMatrix = modelMatrix
        )
    }

    private fun drawTexturedRect(
        left: Float,
        right: Float,
        bottom: Float,
        top: Float,
        textureId: Int,
        modelMatrix: FloatArray,
        rotationDeg: Float = 0f
    ) {
        val rectModelMatrix = modelMatrix.copyOf()
        val centerX = (left + right) / 2f
        val centerY = (bottom + top) / 2f
        val halfW = (right - left) / 2f
        val halfH = (top - bottom) / 2f

        Matrix.translateM(rectModelMatrix, 0, centerX, centerY, 0f)
        if (rotationDeg != 0f) {
            Matrix.rotateM(rectModelMatrix, 0, rotationDeg, 0f, 0f, 1f)
        }

        textQuadBuffer.clear()
        textQuadBuffer.put(
            floatArrayOf(
                -halfW, -halfH, 0f, // BL
                -halfW, halfH, 0f,  // TL
                halfW, -halfH, 0f,  // BR
                halfW, halfH, 0f    // TR
            )
        )
        textQuadBuffer.position(0)

        textTexCoordBuffer.clear()
        textTexCoordBuffer.put(
            floatArrayOf(
                0f, 1f, // BL
                0f, 0f, // TL
                1f, 1f, // BR
                1f, 0f  // TR
            )
        )
        textTexCoordBuffer.position(0)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, rectModelMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glUseProgram(textProgram)
        GLES20.glUniformMatrix4fv(textMvpMatrixHandle, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textTextureHandle, 0)

        GLES20.glEnableVertexAttribArray(textPositionHandle)
        GLES20.glVertexAttribPointer(textPositionHandle, 3, GLES20.GL_FLOAT, false, 0, textQuadBuffer)

        GLES20.glEnableVertexAttribArray(textTexCoordHandle)
        GLES20.glVertexAttribPointer(textTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(textTexCoordHandle)
        GLES20.glDisableVertexAttribArray(textPositionHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
    }

    private fun buildLocalToArCoreMatrix(cameraPose: Pose): FloatArray {
        val translation = cameraPose.translation
        val forward = FloatArray(3)
        cameraPose.getTransformedAxis(2, -1f, forward, 0)
        forward[1] = 0f

        val forwardLength = vectorLength(forward)
        val yAxis = if (forwardLength < MIN_VECTOR_LENGTH) {
            floatArrayOf(0f, 0f, -1f)
        } else {
            floatArrayOf(
                forward[0] / forwardLength,
                forward[1] / forwardLength,
                forward[2] / forwardLength
            )
        }
        val zAxis = floatArrayOf(0f, 1f, 0f)
        val xAxis = normalize(cross(yAxis, zAxis))

        return FloatArray(16).apply {
            this[0] = xAxis[0]
            this[1] = xAxis[1]
            this[2] = xAxis[2]
            this[3] = 0f

            this[4] = yAxis[0]
            this[5] = yAxis[1]
            this[6] = yAxis[2]
            this[7] = 0f

            this[8] = zAxis[0]
            this[9] = zAxis[1]
            this[10] = zAxis[2]
            this[11] = 0f

            this[12] = translation[0]
            this[13] = translation[1]
            this[14] = translation[2]
            this[15] = 1f
        }
    }

    private fun hasResolvedSatellitePosition(sat: SatelliteInfo): Boolean {
        return sat.altitude > MIN_SATELLITE_ALTITUDE_METERS &&
            sat.latitude in -90.0..90.0 &&
            sat.longitude in -180.0..180.0
    }

    private fun geodeticToEcef(latitudeDegrees: Double, longitudeDegrees: Double, altitudeMeters: Double): DoubleArray {
        val latRad = Math.toRadians(latitudeDegrees)
        val lonRad = Math.toRadians(longitudeDegrees)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinLon = sin(lonRad)
        val cosLon = cos(lonRad)
        val primeVerticalRadius = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)

        return doubleArrayOf(
            (primeVerticalRadius + altitudeMeters) * cosLat * cosLon,
            (primeVerticalRadius + altitudeMeters) * cosLat * sinLon,
            (primeVerticalRadius * (1.0 - WGS84_E2) + altitudeMeters) * sinLat
        )
    }

    private fun getSatelliteRangeRatio(rangeMeters: Double): Float {
        return ((rangeMeters - SATELLITE_RANGE_NEAR_METERS) /
            (SATELLITE_RANGE_FAR_METERS - SATELLITE_RANGE_NEAR_METERS))
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    private fun getSatelliteRenderRadius(rangeMeters: Double): Float {
        val ratio = getSatelliteRangeRatio(rangeMeters)
        return SATELLITE_RENDER_RADIUS_NEAR +
            (SATELLITE_RENDER_RADIUS_FAR - SATELLITE_RENDER_RADIUS_NEAR) * ratio
    }

    private fun getSatelliteVisualScale(rangeMeters: Double): Float {
        val ratio = getSatelliteRangeRatio(rangeMeters)
        return SATELLITE_SCALE_NEAR +
            (SATELLITE_SCALE_FAR - SATELLITE_SCALE_NEAR) * ratio
    }

    private fun generateSphere() {
        val vertices = mutableListOf<Float>()

        for (i in 0..SPHERE_LAT_SEGMENTS) {
            val lat = i.toDouble() / SPHERE_LAT_SEGMENTS * (PI / 2.0)
            val z = SKY_RADIUS.toDouble() * sin(lat)
            val ringRadius = SKY_RADIUS.toDouble() * cos(lat)

            for (j in 0..SPHERE_LON_SEGMENTS) {
                val lng = 2.0 * PI * j.toDouble() / SPHERE_LON_SEGMENTS
                val x = sin(lng)
                val y = cos(lng)

                val nextLng = 2.0 * PI * (j + 1).toDouble() / SPHERE_LON_SEGMENTS
                val nextX = sin(nextLng)
                val nextY = cos(nextLng)

                vertices.add((x * ringRadius).toFloat())
                vertices.add((y * ringRadius).toFloat())
                vertices.add(z.toFloat())
                vertices.add((nextX * ringRadius).toFloat())
                vertices.add((nextY * ringRadius).toFloat())
                vertices.add(z.toFloat())

                if (i > 0) {
                    val previousLat = (i - 1).toDouble() / SPHERE_LAT_SEGMENTS * (PI / 2.0)
                    val previousZ = SKY_RADIUS.toDouble() * sin(previousLat)
                    val previousRingRadius = SKY_RADIUS.toDouble() * cos(previousLat)

                    vertices.add((x * previousRingRadius).toFloat())
                    vertices.add((y * previousRingRadius).toFloat())
                    vertices.add(previousZ.toFloat())
                    vertices.add((x * ringRadius).toFloat())
                    vertices.add((y * ringRadius).toFloat())
                    vertices.add(z.toFloat())
                }
            }
        }

        sphereVertexCount = vertices.size / 3
        sphereBuffer = createFloatBuffer(vertices.toFloatArray())
    }

    private fun generateSatelliteGeometry() {
        val fills = mutableListOf<Float>()
        val strokes = mutableListOf<Float>()
        val size = 2.0f

        addRect(fills, strokes, -size, -size, size, size)
        addRect(fills, strokes, -3f * size, -size / 2f, -size, size / 2f)
        addRect(fills, strokes, size, -size / 2f, 3f * size, size / 2f)

        satFillVertexCount = fills.size / 3
        satFillBuffer = createFloatBuffer(fills.toFloatArray())

        satStrokeVertexCount = strokes.size / 3
        satStrokeBuffer = createFloatBuffer(strokes.toFloatArray())
    }

    private fun addRect(
        fills: MutableList<Float>,
        strokes: MutableList<Float>,
        left: Float,
        bottom: Float,
        right: Float,
        top: Float
    ) {
        fills.addAll(
            listOf(
                left, bottom, 0f, right, bottom, 0f, left, top, 0f,
                left, top, 0f, right, bottom, 0f, right, top, 0f
            )
        )

        strokes.addAll(
            listOf(
                left, bottom, 0f, right, bottom, 0f,
                right, bottom, 0f, right, top, 0f,
                right, top, 0f, left, top, 0f,
                left, top, 0f, left, bottom, 0f
            )
        )
    }

    private fun createCameraTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return textures[0]
    }

    private fun getTextTexture(text: String): TextTexture {
        textTextureCache[text]?.let { return it }

        val texture = createTextTexture(text)
        textTextureCache[text] = texture
        return texture
    }

    private fun createTextTexture(text: String): TextTexture {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TEXT_TEXTURE_TEXT_SIZE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val strokePaint = Paint(fillPaint).apply {
            color = Color.argb(190, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = TEXT_TEXTURE_STROKE_WIDTH
        }

        val bounds = android.graphics.Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        val width = (fillPaint.measureText(text) + TEXT_TEXTURE_PADDING * 2f).toInt().coerceAtLeast(2)
        val height = (bounds.height() + TEXT_TEXTURE_PADDING * 2f).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = TEXT_TEXTURE_PADDING - bounds.top
        canvas.drawText(text, TEXT_TEXTURE_PADDING.toFloat(), baseline.toFloat(), strokePaint)
        canvas.drawText(text, TEXT_TEXTURE_PADDING.toFloat(), baseline.toFloat(), fillPaint)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val aspectRatio = width.toFloat() / height.toFloat()
        bitmap.recycle()
        return TextTexture(textures[0], aspectRatio)
    }

    private fun buildProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    private fun createFloatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(values)
                position(0)
            }
        }
    }

    private fun normalizeDegrees(degrees: Float): Float {
        var normalized = degrees % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val length = vectorLength(vector)
        if (length < MIN_VECTOR_LENGTH) return floatArrayOf(1f, 0f, 0f)
        return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
    }

    private fun vectorLength(vector: FloatArray): Float {
        return sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
    }

    private fun getSatelliteColor(sat: SatelliteInfo): FloatArray {
        val color = when (sat.constellationType) {
            GnssStatus.CONSTELLATION_GPS -> floatArrayOf(0.0f, 0.5f, 1.0f, 1.0f)
            GnssStatus.CONSTELLATION_GLONASS -> floatArrayOf(1.0f, 0.2f, 0.2f, 1.0f)
            GnssStatus.CONSTELLATION_GALILEO -> floatArrayOf(0.2f, 1.0f, 0.2f, 1.0f)
            GnssStatus.CONSTELLATION_BEIDOU -> floatArrayOf(1.0f, 0.8f, 0.0f, 1.0f)
            GnssStatus.CONSTELLATION_QZSS -> floatArrayOf(1.0f, 0.5f, 0.0f, 1.0f)
            GnssStatus.CONSTELLATION_IRNSS -> floatArrayOf(0.8f, 0.0f, 0.8f, 1.0f)
            else -> floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        }

        if (!sat.usedInFix) {
            color[0] *= 0.3f
            color[1] *= 0.3f
            color[2] *= 0.3f
        }
        return color
    }

    private fun getSatelliteLabel(sat: SatelliteInfo): String {
        return "${getConstellationName(sat.constellationType)} ${sat.svid}"
    }

    private fun getSatellitePlaneRoll(localAzDegrees: Float, elevationDegrees: Float): Float {
        val azWave = sin(Math.toRadians((localAzDegrees * 2f).toDouble())).toFloat() * SATELLITE_MAX_ROLL_DEGREES
        val elevationBias = ((elevationDegrees - 45f) / 45f) * SATELLITE_ELEVATION_ROLL_BIAS_DEGREES
        return (azWave + elevationBias).coerceIn(-SATELLITE_MAX_ROLL_DEGREES, SATELLITE_MAX_ROLL_DEGREES)
    }

    private fun getConstellationName(constellationType: Int): String {
        return when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            else -> "Sat"
        }
    }

    private val cameraVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;

        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val cameraFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uCameraTexture;
        varying vec2 vTexCoord;

        void main() {
            gl_FragColor = texture2D(uCameraTexture, vTexCoord);
        }
    """.trimIndent()

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;

        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;

        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val textVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;

        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val textFragmentShaderCode = """
        precision mediump float;
        uniform sampler2D uTextTexture;
        varying vec2 vTexCoord;

        void main() {
            gl_FragColor = texture2D(uTextTexture, vTexCoord);
        }
    """.trimIndent()

    private data class TextTexture(
        val textureId: Int,
        val aspectRatio: Float
    )

    private data class SkyBasis(
        val modelMatrix: FloatArray
    )

    private data class SatellitePlacement(
        val iconModelMatrix: FloatArray,
        val textModelMatrix: FloatArray,
        val labelWorldHeight: Float
    )

    private data class ObserverPosition(
        val latitudeRad: Double,
        val longitudeRad: Double,
        val ecefX: Double,
        val ecefY: Double,
        val ecefZ: Double
    )

    private companion object {
        const val TAG = "GNSSARRenderer"
        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 1000.0f
        const val SKY_RADIUS = 100.0f
        const val SPHERE_LAT_SEGMENTS = 9
        const val SPHERE_LON_SEGMENTS = 36
        const val MIN_VECTOR_LENGTH = 0.001f
        const val GRID_LINE_WIDTH = 5.0f
        const val SATELLITE_STROKE_WIDTH = 4.0f
        const val SATELLITE_FRONT_OFFSET = 2.5f
        const val SATELLITE_MAX_ROLL_DEGREES = 32.0f
        const val SATELLITE_ELEVATION_ROLL_BIAS_DEGREES = 10.0f
        const val SATELLITE_LABEL_HEIGHT = 2.0f
        const val SATELLITE_LABEL_FORWARD_OFFSET = 0.18f
        const val SATELLITE_RENDER_RADIUS_NEAR = 62.0f
        const val SATELLITE_RENDER_RADIUS_FAR = 98.0f
        const val SATELLITE_SCALE_NEAR = 1.25f
        const val SATELLITE_SCALE_FAR = 0.65f
        const val GRID_LABEL_HEIGHT = 3.2f
        const val GRID_LABEL_RADIUS_OFFSET = 3.0f
        const val GRID_LABEL_FORWARD_OFFSET = 0.12f
        const val TEXT_TEXTURE_TEXT_SIZE = 42.0f
        const val TEXT_TEXTURE_STROKE_WIDTH = 5.0f
        const val TEXT_TEXTURE_PADDING = 10
        const val WGS84_A = 6378137.0
        const val WGS84_E2 = 6.69437999014e-3
        const val MIN_RANGE_METERS = 1.0
        const val MIN_SATELLITE_ALTITUDE_METERS = 100_000.0
        const val SATELLITE_RANGE_NEAR_METERS = 18_000_000.0
        const val SATELLITE_RANGE_FAR_METERS = 42_000_000.0

        val CAMERA_QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
        )

        val TEXT_QUAD_COORDS = floatArrayOf(
            -0.5f, -0.5f, 0.0f,
            0.5f, -0.5f, 0.0f,
            -0.5f, 0.5f, 0.0f,
            0.5f, 0.5f, 0.0f
        )

        val TEXT_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )

        val GRID_LONGITUDE_LABELS = intArrayOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
        val GRID_LATITUDE_LABELS = intArrayOf(30, 60, 90)
    }
}
