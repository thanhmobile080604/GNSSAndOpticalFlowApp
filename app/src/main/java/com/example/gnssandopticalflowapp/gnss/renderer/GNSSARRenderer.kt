package com.example.gnssandopticalflowapp.gnss.renderer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GNSSARRenderer : GLSurfaceView.Renderer {

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    private val projectionMatrix = FloatArray(16)
    private var viewMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val vpMatrix = FloatArray(16)

    // Objects
    private lateinit var sphereBuffer: FloatBuffer
    private var sphereVertexCount = 0

    private lateinit var satFillBuffer: FloatBuffer
    private lateinit var satStrokeBuffer: FloatBuffer
    private var satFillVertexCount = 0
    private var satStrokeVertexCount = 0

    private val satellites = mutableListOf<SatelliteInfo>()

    // Colors
    private val sphereColor = floatArrayOf(0.2f, 0.5f, 1.0f, 0.4f) // Blue wireframe
    private val satColor = floatArrayOf(0.2f, 0.6f, 1.0f, 0.8f) // Satellite fill
    private val satStrokeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White stroke

    private val RADIUS = 100.0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) // Transparent background
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        generateSphere()
        generateSatelliteGeometry()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.1f, 1000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)

        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Draw Sphere
        drawSphere()

        // Draw Satellites
        synchronized(satellites) {
            for (sat in satellites) {
                drawSatellite(sat)
            }
        }
    }

    fun updateViewMatrix(newViewMatrix: FloatArray) {
        System.arraycopy(newViewMatrix, 0, viewMatrix, 0, 16)
    }

    fun updateSatellites(newSatellites: List<SatelliteInfo>) {
        synchronized(satellites) {
            satellites.clear()
            satellites.addAll(newSatellites)
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
        val azRad = Math.toRadians(sat.azimuthDegrees.toDouble())
        val elRad = Math.toRadians(sat.elevationDegrees.toDouble())

        // OpenGL: North=-Z, East=+X, Up=+Y.
        // azimuth is clockwise from North.
        val x = (RADIUS * cos(elRad) * sin(azRad)).toFloat()
        val y = (RADIUS * sin(elRad)).toFloat()
        val z = (-RADIUS * cos(elRad) * cos(azRad)).toFloat()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)

        // Billboard: Face the origin (camera is at origin)
        Matrix.rotateM(modelMatrix, 0, -sat.azimuthDegrees, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, sat.elevationDegrees, 1f, 0f, 0f)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvp, 0)

        // Fill
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, satFillBuffer)
        GLES20.glUniform4fv(colorHandle, 1, satColor, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, satFillVertexCount)

        // Stroke
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, satStrokeBuffer)
        GLES20.glUniform4fv(colorHandle, 1, satStrokeColor, 0)
        GLES20.glLineWidth(3f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, satStrokeVertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun generateSphere() {
        val vertices = mutableListOf<Float>()
        val lats = 9 // Quarter circle (hemisphere)
        val lons = 36

        for (i in 0..lats) {
            val lat1 = i.toDouble() / lats * (PI / 2)
            val y1 = RADIUS * sin(lat1)
            val zr1 = RADIUS * cos(lat1)

            for (j in 0..lons) {
                val lng = 2 * PI * j.toDouble() / lons
                val x = sin(lng)
                val z = -cos(lng)
                
                val nextLng = 2 * PI * (j + 1).toDouble() / lons
                val nextX = sin(nextLng)
                val nextZ = -cos(nextLng)
                
                // Lat segment
                vertices.add((x * zr1).toFloat()); vertices.add(y1.toFloat()); vertices.add((z * zr1).toFloat())
                vertices.add((nextX * zr1).toFloat()); vertices.add(y1.toFloat()); vertices.add((nextZ * zr1).toFloat())
                
                // Lon segment
                if (i > 0) {
                     val lat0 = (i - 1).toDouble() / lats * (PI / 2)
                     val y0 = RADIUS * sin(lat0)
                     val zr0 = RADIUS * cos(lat0)
                     vertices.add((x * zr0).toFloat()); vertices.add(y0.toFloat()); vertices.add((z * zr0).toFloat())
                     vertices.add((x * zr1).toFloat()); vertices.add(y1.toFloat()); vertices.add((z * zr1).toFloat())
                }
            }
        }

        sphereVertexCount = vertices.size / 3
        sphereBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(vertices.toFloatArray())
                position(0)
            }
        }
    }

    private fun generateSatelliteGeometry() {
        val fills = mutableListOf<Float>()
        val strokes = mutableListOf<Float>()

        val s = 2.0f // Scale

        // Center square
        addRect(fills, strokes, -s, -s, s, s)

        // Left rect
        addRect(fills, strokes, -3*s, -s/2, -s, s/2)

        // Right rect
        addRect(fills, strokes, s, -s/2, 3*s, s/2)

        satFillVertexCount = fills.size / 3
        satFillBuffer = ByteBuffer.allocateDirect(fills.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(fills.toFloatArray())
                position(0)
            }
        }

        satStrokeVertexCount = strokes.size / 3
        satStrokeBuffer = ByteBuffer.allocateDirect(strokes.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(strokes.toFloatArray())
                position(0)
            }
        }
    }

    private fun addRect(fills: MutableList<Float>, strokes: MutableList<Float>, left: Float, bottom: Float, right: Float, top: Float) {
        fills.addAll(listOf(
            left, bottom, 0f, right, bottom, 0f, left, top, 0f,
            left, top, 0f, right, bottom, 0f, right, top, 0f
        ))

        strokes.addAll(listOf(
            left, bottom, 0f, right, bottom, 0f,
            right, bottom, 0f, right, top, 0f,
            right, top, 0f, left, top, 0f,
            left, top, 0f, left, bottom, 0f
        ))
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

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
}
