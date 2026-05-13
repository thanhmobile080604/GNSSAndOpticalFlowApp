package com.example.gnssandopticalflowapp.gnss.renderer

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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

    private lateinit var sphereBuffer: FloatBuffer
    private var sphereVertexCount = 0

    private lateinit var satFillBuffer: FloatBuffer
    private lateinit var satStrokeBuffer: FloatBuffer
    private var satFillVertexCount = 0
    private var satStrokeVertexCount = 0

    private val satellites = mutableListOf<SatelliteInfo>()

    private val sphereColor = floatArrayOf(0.2f, 0.5f, 1.0f, 0.4f)
    private val satColor = floatArrayOf(0.2f, 0.6f, 1.0f, 0.85f)
    private val satStrokeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

    @Volatile
    private var worldYawDegrees = 0f

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

        cameraTextureId = createCameraTexture()
        cameraTextureSet = false
        backgroundTexCoordsInitialized = false
        displayGeometryDirty = true
        localToArCoreMatrix = null

        cameraQuadBuffer = createFloatBuffer(CAMERA_QUAD_COORDS)
        cameraTexCoordBuffer = createFloatBuffer(FloatArray(CAMERA_QUAD_COORDS.size))
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
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, sphereVertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawSatellite(sat: SatelliteInfo) {
        val localAzDegrees = sat.azimuthDegrees - worldYawDegrees
        val localAzRad = Math.toRadians(localAzDegrees.toDouble())
        val elRad = Math.toRadians(sat.elevationDegrees.toDouble())

        val x = (SKY_RADIUS.toDouble() * cos(elRad) * sin(localAzRad)).toFloat()
        val y = (SKY_RADIUS.toDouble() * cos(elRad) * cos(localAzRad)).toFloat()
        val z = (SKY_RADIUS.toDouble() * sin(elRad)).toFloat()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, -localAzDegrees, 0f, 0f, 1f)
        Matrix.rotateM(modelMatrix, 0, sat.elevationDegrees - 90f, 1f, 0f, 0f)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvp, 0)

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, satFillBuffer)
        GLES20.glUniform4fv(colorHandle, 1, satColor, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, satFillVertexCount)

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, satStrokeBuffer)
        GLES20.glUniform4fv(colorHandle, 1, satStrokeColor, 0)
        GLES20.glLineWidth(3f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, satStrokeVertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
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

    private companion object {
        const val TAG = "GNSSARRenderer"
        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 1000.0f
        const val SKY_RADIUS = 100.0f
        const val SPHERE_LAT_SEGMENTS = 9
        const val SPHERE_LON_SEGMENTS = 36
        const val MIN_VECTOR_LENGTH = 0.001f

        val CAMERA_QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
        )
    }
}
