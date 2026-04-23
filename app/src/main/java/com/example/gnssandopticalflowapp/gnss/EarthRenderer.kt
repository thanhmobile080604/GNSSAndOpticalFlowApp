package com.example.gnssandopticalflowapp.gnss

import android.content.Context
import android.opengl.GLES32
import android.opengl.GLES32.GL_COLOR_BUFFER_BIT
import android.opengl.GLES32.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES32.GL_DEPTH_TEST
import android.opengl.GLES32.glClear
import android.opengl.GLES32.glClearColor
import android.opengl.GLES32.glGetUniformLocation
import android.opengl.GLES32.glUniform3f
import android.opengl.GLES32.glViewport
import android.opengl.GLSurfaceView.Renderer
import android.opengl.Matrix
import android.util.Log
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.model.SatRenderState
import com.example.gnssandopticalflowapp.model.createSphere
import com.example.gnssandopticalflowapp.model.skyboxVertices
import com.example.gnssandopticalflowapp.util.LoggerConfig
import com.example.gnssandopticalflowapp.util.ShaderHelper
import com.example.gnssandopticalflowapp.util.ShaderReader
import com.example.gnssandopticalflowapp.util.TextureLoader
import java.lang.Math.toRadians
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan


class EarthRenderer(private val context: Context) : Renderer {
    private lateinit var sphereVertices: FloatArray
    private lateinit var sphereIndices: IntArray

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    private var timeElapsed: Float = 0.0f
    private var animationSpeed: Float = 0.1f

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private var ebo = 0

    private var skyboxVAO = 0
    private var skyboxVBO = 0
    private var skyboxProgram = 0
    private var skyboxTexture = 0

    var scaleFactor = 1.0f

    var theta = 0f
    var phi = 0f

    var velocityTheta = 0f
    var velocityPhi = 0f

    private var targetPhi: Float? = null
    private var targetTheta: Float? = null
    private var targetScale: Float? = null

    private var userLat: Double? = null
    private var userLon: Double? = null
    private var isCameraInitialized = false

    private var earthTextureId = 0
    private var moonTextureId = 0
    private var sunTextureId = 0

    private var satProgram = 0
    private var satellites = listOf<com.example.gnssandopticalflowapp.model.SatelliteInfo>()

    // Ring for user location
    private var ringVAO = 0
    private var ringVBO = 0
    private val ringVertexCount = 360

    private var renderSatellites = mutableMapOf<String, SatRenderState>()
    val satelliteCount: Int get() = satellites.size
    private val satLock = Any()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val sphere = createSphere(radius = 0.1f, stacks = 62, slices = 62)
        sphereVertices = sphere.vertices
        sphereIndices = sphere.indices

        glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES32.glEnable(GL_DEPTH_TEST)

        val skyboxVertexShaderSource = ShaderReader.readTextFileFromResource(context, R.raw.skybox_vertex_shader)
        val skyboxFragmentShaderSource = ShaderReader.readTextFileFromResource(context, R.raw.skybox_fragment_shader)
        skyboxProgram = ShaderHelper.buildProgram(skyboxVertexShaderSource, skyboxFragmentShaderSource)

        // Tạo VAO, VBO cho skybox
        val vaoBufferSkybox = IntBuffer.allocate(1)
        val vboBufferSkybox = IntBuffer.allocate(1)
        GLES32.glGenVertexArrays(1, vaoBufferSkybox)
        GLES32.glGenBuffers(1, vboBufferSkybox)
        skyboxVAO = vaoBufferSkybox.get(0)
        skyboxVBO = vboBufferSkybox.get(0)

        GLES32.glBindVertexArray(skyboxVAO)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, skyboxVBO)
        val vertexBufferSkybox = ByteBuffer.allocateDirect(skyboxVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(skyboxVertices)
        vertexBufferSkybox.position(0)
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, skyboxVertices.size * 4, vertexBufferSkybox, GLES32.GL_STATIC_DRAW)

        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 3 * 4, 0)
        GLES32.glBindVertexArray(0)

        val faces = listOf(
            R.drawable.skybox_right,
            R.drawable.skybox_left,
            R.drawable.skybox_up,
            R.drawable.skybox_down,
            R.drawable.skybox_front,
            R.drawable.skybox_back
        )
        val cubeMapTexture = TextureLoader.loadCubeMap(faces, context)
        skyboxTexture = cubeMapTexture
        GLES32.glBindTexture(GLES32.GL_TEXTURE_CUBE_MAP, cubeMapTexture)

        val vertexShaderSource = ShaderReader.readTextFileFromResource(context, R.raw.vertex_shader)
        val fragmentShaderSource = ShaderReader.readTextFileFromResource(context, R.raw.fragment_shader)
        program = ShaderHelper.buildProgram(vertexShaderSource, fragmentShaderSource)
        if (LoggerConfig.ON) {
            ShaderHelper.validateProgram(program)
        }

        val satVertexSrc = ShaderReader.readTextFileFromResource(context, R.raw.sat_vertex_shader)
        val satFragSrc = ShaderReader.readTextFileFromResource(context, R.raw.sat_fragment_shader)
        satProgram = ShaderHelper.buildProgram(satVertexSrc, satFragSrc)

        GLES32.glUseProgram(program)

        val vaoBuffer = IntBuffer.allocate(1)
        val vboBuffer = IntBuffer.allocate(1)
        val eboBuffer = IntBuffer.allocate(1)
        GLES32.glGenVertexArrays(1, vaoBuffer)
        GLES32.glGenBuffers(1, vboBuffer)
        GLES32.glGenBuffers(1, eboBuffer)
        vao = vaoBuffer.get(0)
        vbo = vboBuffer.get(0)
        ebo = eboBuffer.get(0)

        GLES32.glBindVertexArray(vao)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vbo)
        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(sphereVertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(sphereVertices)
        vertexBuffer.position(0)
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, sphereVertices.size * Float.SIZE_BYTES, vertexBuffer, GLES32.GL_STATIC_DRAW)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 8 * Float.SIZE_BYTES, 0)
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 8 * Float.SIZE_BYTES, 3 * Float.SIZE_BYTES)
        GLES32.glEnableVertexAttribArray(1)
        GLES32.glVertexAttribPointer(2, 3, GLES32.GL_FLOAT, false, 8 * Float.SIZE_BYTES, 5 * Float.SIZE_BYTES)
        GLES32.glEnableVertexAttribArray(2)

        val indicesBuffer: IntBuffer = ByteBuffer
            .allocateDirect(sphereIndices.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        indicesBuffer.put(sphereIndices)
        indicesBuffer.position(0)
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES32.glBufferData(GLES32.GL_ELEMENT_ARRAY_BUFFER, sphereIndices.size * Int.SIZE_BYTES, indicesBuffer, GLES32.GL_STATIC_DRAW)

        earthTextureId = TextureLoader.loadTexture2D(context, R.drawable.earth_texture)
        moonTextureId = TextureLoader.loadTexture2D(context, R.drawable.moon_texture)
        sunTextureId = TextureLoader.loadTexture2D(context, R.drawable.sun_texture)
        
        // Ring for user location
        val ringVertices = FloatArray(ringVertexCount * 3)
        for (i in 0 until ringVertexCount) {
            val angle = toRadians(i.toDouble())
            ringVertices[i * 3] = cos(angle).toFloat()
            ringVertices[i * 3 + 1] = sin(angle).toFloat()
            ringVertices[i * 3 + 2] = 0f
        }
        
        val vaoRingBuffer = IntBuffer.allocate(1)
        val vboRingBuffer = IntBuffer.allocate(1)
        GLES32.glGenVertexArrays(1, vaoRingBuffer)
        GLES32.glGenBuffers(1, vboRingBuffer)
        ringVAO = vaoRingBuffer.get(0)
        ringVBO = vboRingBuffer.get(0)

        GLES32.glBindVertexArray(ringVAO)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, ringVBO)
        val ringFB = ByteBuffer.allocateDirect(ringVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(ringVertices)
        ringFB.position(0)
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, ringVertices.size * 4, ringFB, GLES32.GL_STATIC_DRAW)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 3 * 4, 0)
        GLES32.glEnableVertexAttribArray(0)

        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
        GLES32.glBindVertexArray(0)
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val aspectRatio = if (width > height) width.toFloat() / height else height.toFloat() / width

        Matrix.perspectiveM(projectionMatrix, 0, 45f, 1/aspectRatio, 0.1f, 20f)
        
        GLES32.glUseProgram(program)
        val uniformLocation = glGetUniformLocation(program, "projectionMatrix")
        GLES32.glUniformMatrix4fv(uniformLocation, 1, false, projectionMatrix, 0)
        
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Smooth transition logic
        targetPhi?.let { tPhi ->
            phi += (tPhi - phi) * 0.1f
            if (abs(phi - tPhi) < 0.01f) {
                phi = tPhi
                targetPhi = null
            }
        }
        targetTheta?.let { tTheta ->
            var diff = tTheta - theta
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f

            theta += diff * 0.1f
            if (abs(diff) < 0.01f) {
                theta = tTheta
                targetTheta = null
            }
        }
        targetScale?.let { tScale ->
            scaleFactor += (tScale - scaleFactor) * 0.1f
            if (abs(scaleFactor - tScale) < 0.001f) {
                scaleFactor = tScale
                targetScale = null
            }
        }

        if (targetTheta == null && targetPhi == null) {
            if (abs(velocityTheta) > 0.01f || abs(velocityPhi) > 0.01f) {
                theta -= velocityTheta
                phi += velocityPhi
                phi = phi.coerceIn(-89.9f, 89.9f)

                velocityTheta *= 0.95f
                velocityPhi *= 0.95f
            } else {
                velocityTheta = 0f
                velocityPhi = 0f
            }
        } else {
            velocityTheta = 0f
            velocityPhi = 0f
        }

        timeElapsed += animationSpeed * 0.016f
        if (timeElapsed > 1.0f) {
            timeElapsed -= 1.0f
        } else if (timeElapsed < 0.0f) {
            timeElapsed += 1.0f
        }

        GLES32.glUseProgram(program)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, earthTextureId)
        GLES32.glUniform1i(glGetUniformLocation(program, "bodyTexture"), 0)
        GLES32.glUniform1i(glGetUniformLocation(program, "bodyType"), 0) // Earth

        Matrix.setIdentityM(viewMatrix, 0)
//        Matrix.setLookAtM(viewMatrix, 0,
//            0f, 0f, -scaleFactor,
//            0f, 0f, 0f,
//            0f, 1f, 0f)

        // Tính vị trí camera theo tọa độ hình cầu
        val radius = scaleFactor
        val camX = (radius * cos(toRadians(phi.toDouble())) * sin(toRadians(theta.toDouble()))).toFloat()
        val camY = (radius * sin(toRadians(phi.toDouble()))).toFloat()
        val camZ = (radius * cos(toRadians(phi.toDouble())) * cos(toRadians(theta.toDouble()))).toFloat()

        Matrix.setLookAtM(viewMatrix, 0,
            camX, camY, camZ,  // camera position
            0f, 0f, 0f,        // looking at origin (Earth)
            0f, 1f, 0f)        // up vector

        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)

        Matrix.setIdentityM(modelMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, modelMatrix, 0)

        // Add light based on GMT - use same UTC time as moon for consistency
        val utcCalendarSun = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val utcTimeMillisSun = utcCalendarSun.timeInMillis
        val jdSun = utcTimeMillisSun / 86400000.0 + 2440587.5
        val dJDSun = jdSun - 2451545.0

        // Sun orbital elements (more accurate)
        var lSun = 280.466 + 0.9856474 * dJDSun  // Mean longitude
        var mSun = 357.529 + 0.9856003 * dJDSun  // Mean anomaly
        var wSun = 282.940 + 4.70935e-5 * dJDSun  // Perihelion

        lSun %= 360.0; if (lSun < 0) lSun += 360.0
        mSun %= 360.0; if (mSun < 0) mSun += 360.0
        wSun %= 360.0; if (wSun < 0) wSun += 360.0

        // Calculate ecliptic longitude with equation of center
        val cSun = (1.915 * sin(toRadians(mSun)) + 
                    0.020 * sin(toRadians(2 * mSun))) * (1 - 0.003 * toRadians(mSun))
        val lambdaSun = toRadians(lSun + cSun)
        
        // Sun's ecliptic latitude is essentially 0 (sun is on ecliptic)
        val betaSun = 0.0

        // Ecliptic to Equatorial (same obliquity as moon)
        val eps = toRadians(23.439 - 0.0000004 * dJDSun)
        val sinDeltaSun = sin(betaSun) * cos(eps) + cos(betaSun) * sin(eps) * sin(lambdaSun)
        val deltaSun = asin(sinDeltaSun)
        val alphaSun = atan2(sin(lambdaSun) * cos(eps) - tan(betaSun) * sin(eps), cos(lambdaSun))

        // Use same sidereal time calculation as moon
        val tSun = dJDSun / 36525.0
        val gmstSun = 280.46061837 + 360.98564736629 * dJDSun + 0.000387933 * tSun * tSun - tSun * tSun * tSun / 38710000.0
        val gmstDegSun = gmstSun % 360.0
        val gmstRadSun = toRadians(gmstDegSun)
        val sunLonRad = alphaSun - gmstRadSun

        // Calculate sun position in same coordinate system as moon
        val lightX = (10.0 * cos(deltaSun) * sin(sunLonRad)).toFloat()
        val lightY = (10.0 * sin(deltaSun)).toFloat()
        val lightZ = (10.0 * cos(deltaSun) * cos(sunLonRad)).toFloat()

        glUniform3f(glGetUniformLocation(program, "lightColor"), 1f, 1f, 1f)
        glUniform3f(glGetUniformLocation(program, "lightPos"), lightX, lightY, lightZ)
        glUniform3f(glGetUniformLocation(program, "viewPos"), camX, camY, camZ)

        GLES32.glBindVertexArray(vao)
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
        GLES32.glBindVertexArray(0)

        // Draw Moon
        // Use UTC time instead of local time
        val utcCalendarMoon = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val utcTimeMillisMoon = utcCalendarMoon.timeInMillis
        val jdMoon = utcTimeMillisMoon / 86400000.0 + 2440587.5
        val dJDMoon = jdMoon - 2451545.0

        // Moon orbital elements (more accurate)
        var lMoon = 218.32 + 13.176396 * dJDMoon  // Mean longitude
        var mMoon = 134.96 + 13.064993 * dJDMoon  // Mean anomaly
        var fMoon = 93.27 + 13.229350 * dJDMoon    // Argument of latitude
        var omegaMoon = 125.08 - 0.0529539 * dJDMoon  // Ascending node
        var wMoon = 318.06 + 0.1643573 * dJDMoon     // Perigee

        lMoon %= 360.0; if (lMoon < 0) lMoon += 360.0
        mMoon %= 360.0; if (mMoon < 0) mMoon += 360.0
        fMoon %= 360.0; if (fMoon < 0) fMoon += 360.0
        omegaMoon %= 360.0; if (omegaMoon < 0) omegaMoon += 360.0
        wMoon %= 360.0; if (wMoon < 0) wMoon += 360.0

        // Calculate ecliptic longitude with more terms
        val lambdaMoon = toRadians(lMoon + 6.289 * sin(toRadians(mMoon)) + 
                                         0.214 * sin(toRadians(2 * mMoon)) +
                                         0.658 * sin(toRadians(2 * fMoon)))
        val betaMoon = toRadians(5.128 * sin(toRadians(fMoon)) +
                                       0.281 * sin(toRadians(mMoon + fMoon)) +
                                       0.278 * sin(toRadians(mMoon - fMoon)))

        // Ecliptic to Equatorial
        val epsMoon = toRadians(23.439 - 0.0000004 * dJDMoon)  // Obliquity with small correction
        val sinDeltaMoon = sin(betaMoon) * cos(epsMoon) + cos(betaMoon) * sin(epsMoon) * sin(lambdaMoon)
        val deltaMoon = asin(sinDeltaMoon)
        val alphaMoon = atan2(sin(lambdaMoon) * cos(epsMoon) - tan(betaMoon) * sin(epsMoon), cos(lambdaMoon))

        // Improved sidereal time calculation
        val t = dJDMoon / 36525.0
        val gmst = 280.46061837 + 360.98564736629 * dJDMoon + 0.000387933 * t * t - t * t * t / 38710000.0
        val gmstDeg = gmst % 360.0
        val gmstRad = toRadians(gmstDeg)
        val moonLonRad = alphaMoon - gmstRad

        val rMoonDist = 0.1f * 3.0f // Brought much closer to Earth for visibility (was 60.33f)
        val mX = (rMoonDist * cos(deltaMoon) * sin(moonLonRad)).toFloat()
        val mY = (rMoonDist * sin(deltaMoon)).toFloat()
        val mZ = (rMoonDist * cos(deltaMoon) * cos(moonLonRad)).toFloat()

        val moonModelMatrix = FloatArray(16)
        Matrix.setIdentityM(moonModelMatrix, 0)
        Matrix.translateM(moonModelMatrix, 0, mX, mY, mZ)
        val moonScale = 0.2727f // Scale exactly to correct ratio (Moon is ~27.27% of Earth's size)
        Matrix.scaleM(moonModelMatrix, 0, moonScale, moonScale, moonScale)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        val mPos = floatArrayOf(mX, mY, mZ, 1f)
        val mClip = FloatArray(4)
        Matrix.multiplyMV(mClip, 0, vpMatrix, 0, mPos, 0)
        if (mClip[3] <= 0) {
            Log.d("EarthRenderer", "Moon is behind camera")
        }

        GLES32.glUseProgram(program)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, moonTextureId)
        GLES32.glUniform1i(glGetUniformLocation(program, "bodyTexture"), 0)
        GLES32.glUniform1i(glGetUniformLocation(program, "bodyType"), 1) // Moon
        
        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, moonModelMatrix, 0)

        GLES32.glBindVertexArray(vao)
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
        GLES32.glBindVertexArray(0)

        // Draw Sun
        val sunModelMatrix = FloatArray(16)
        Matrix.setIdentityM(sunModelMatrix, 0)

        val lightLength = sqrt((lightX * lightX + lightY * lightY + lightZ * lightZ).toDouble()).toFloat()
        val rSunDist = 0.1f * 30.0f // Sun distance mapping
        val sX = (lightX / lightLength) * rSunDist
        val sY = (lightY / lightLength) * rSunDist
        val sZ = (lightZ / lightLength) * rSunDist

        Matrix.translateM(sunModelMatrix, 0, sX, sY, sZ)
        
        val sunScale = 3.0f // Make Sun bigger than Earth (scale visual)
        Matrix.scaleM(sunModelMatrix, 0, sunScale, sunScale, sunScale)

        GLES32.glUseProgram(program)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sunTextureId)
        GLES32.glUniform1i(glGetUniformLocation(program, "bodyTexture"), 0)
        GLES32.glUniform1i(glGetUniformLocation(program, "bodyType"), 2) // Sun
        
        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, sunModelMatrix, 0)

        GLES32.glBindVertexArray(vao)
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
        GLES32.glBindVertexArray(0)

        // Vẽ skybox
        GLES32.glDepthFunc(GLES32.GL_LEQUAL)  // đổi depth function để skybox vẽ phía sau mọi thứ
        GLES32.glDepthMask(false)
        GLES32.glUseProgram(skyboxProgram)

        // Lấy uniform location cho view và projection
        val viewLoc = glGetUniformLocation(skyboxProgram, "view")
        val projLoc = glGetUniformLocation(skyboxProgram, "projection")

        // Loại bỏ thành phần dịch chuyển trong view matrix (chỉ lấy rotation để quay quanh camera)
        val viewNoTranslation = FloatArray(16)
        System.arraycopy(viewMatrix, 0, viewNoTranslation, 0, 16)
        viewNoTranslation[12] = 0f
        viewNoTranslation[13] = 0f
        viewNoTranslation[14] = 0f

        GLES32.glUniformMatrix4fv(viewLoc, 1, false, viewNoTranslation, 0)
        GLES32.glUniformMatrix4fv(projLoc, 1, false, projectionMatrix, 0)

        GLES32.glBindVertexArray(skyboxVAO)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_CUBE_MAP, skyboxTexture)
        GLES32.glUniform1i(glGetUniformLocation(skyboxProgram, "skybox"), 0)

        GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, 36)
        GLES32.glBindVertexArray(0)
        GLES32.glDepthMask(true)
        GLES32.glDepthFunc(GLES32.GL_LESS) // reset lại depth func mặc định

        // Draw Satellites
        GLES32.glUseProgram(satProgram)
        GLES32.glBindVertexArray(vao) // reuse sphere VAO

        val projLocSat = glGetUniformLocation(satProgram, "projectionMatrix")
        val viewLocSat = glGetUniformLocation(satProgram, "viewMatrix")
        val modelLocSat = glGetUniformLocation(satProgram, "modelMatrix")
        val colorLocSat = glGetUniformLocation(satProgram, "color")

        GLES32.glUniformMatrix4fv(projLocSat, 1, false, projectionMatrix, 0)
        GLES32.glUniformMatrix4fv(viewLocSat, 1, false, viewMatrix, 0)

        synchronized(satLock) {
            for (state in renderSatellites.values) {
                val sat = state.info
                // Instant update (removed LERP flying effect)
                state.rX = state.tX
                state.rY = state.tY
                state.rZ = state.tZ

                // update for touch handling later
                sat.worldX = state.rX
                sat.worldY = state.rY
                sat.worldZ = state.rZ

                val satModelMatrix = FloatArray(16)
                Matrix.setIdentityM(satModelMatrix, 0)
                
                // Translate
                Matrix.translateM(satModelMatrix, 0, state.rX, state.rY, state.rZ)
                // Scale down sphere
                val scale = 0.04f // increased to 0.04f for better visibility
                Matrix.scaleM(satModelMatrix, 0, scale, scale, scale)

                GLES32.glUniformMatrix4fv(modelLocSat, 1, false, satModelMatrix, 0)

                // Set color based on constellation
                val color = when (sat.constellationType) {
                    android.location.GnssStatus.CONSTELLATION_GPS -> floatArrayOf(0.0f, 0.5f, 1.0f, 1.0f) // Blue
                    android.location.GnssStatus.CONSTELLATION_GLONASS -> floatArrayOf(1.0f, 0.2f, 0.2f, 1.0f) // Red
                    android.location.GnssStatus.CONSTELLATION_GALILEO -> floatArrayOf(0.2f, 1.0f, 0.2f, 1.0f) // Green
                    android.location.GnssStatus.CONSTELLATION_BEIDOU -> floatArrayOf(1.0f, 0.8f, 0.0f, 1.0f) // Yellow
                    android.location.GnssStatus.CONSTELLATION_QZSS -> floatArrayOf(1.0f, 0.5f, 0.0f, 1.0f) // Orange
                    android.location.GnssStatus.CONSTELLATION_IRNSS -> floatArrayOf(0.8f, 0.0f, 0.8f, 1.0f) // Purple
                    else -> floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White
                }
                
                // Dim if not used in fix
                if (!sat.usedInFix) {
                    color[0] *= 0.3f; color[1] *= 0.3f; color[2] *= 0.3f
                }

                GLES32.glUniform4fv(colorLocSat, 1, color, 0)

                GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
            }
        }

        // Draw user location marker
        userLat?.let { lat ->
            userLon?.let { lon ->
                val rUser = 0.101f // Slightly above earth
                // In OpenGL mapping, if Y is up and texture is standard equirectangular, 
                // X = sin(lon)*cos(lat), Y = sin(lat), Z = cos(lon)*cos(lat)
                // However, the Earth texture usually has Prime Meridian at center of X axis image.
                // We'll use the same formula as the camera
                val radLat = toRadians(lat)
                val radLon = toRadians(lon)

                val userX = (rUser * cos(radLat) * sin(radLon)).toFloat()
                val userY = (rUser * sin(radLat)).toFloat()
                val userZ = (rUser * cos(radLat) * cos(radLon)).toFloat()

                val userModelMatrix = FloatArray(16)
                Matrix.setIdentityM(userModelMatrix, 0)
                Matrix.translateM(userModelMatrix, 0, userX, userY, userZ)
                Matrix.scaleM(userModelMatrix, 0, 0.01f, 0.01f, 0.01f)
                GLES32.glUniformMatrix4fv(modelLocSat, 1, false, userModelMatrix, 0)
                // Cyan color for user
                GLES32.glUniform4fv(colorLocSat, 1, floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f), 0)
                GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)

                // Draw pulsating rings
                GLES32.glBindVertexArray(ringVAO)
                GLES32.glEnable(GLES32.GL_BLEND)
                GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)
                GLES32.glLineWidth(3.0f)

                val maxRingScale = 0.01f
                for (i in 0 until 2) {
                    val offset = i * 1000L
                    val pulseTime = ((System.currentTimeMillis() + offset) % 2000L).toFloat() / 2000f

                    val ringModelMatrix = FloatArray(16)
                    Matrix.setIdentityM(ringModelMatrix, 0)

                    val ringR = rUser + 0.0001f // slightly above the earth surface to avoid z-fighting
                    val rX = (ringR * cos(radLat) * sin(radLon)).toFloat()
                    val rY = (ringR * sin(radLat)).toFloat()
                    val rZ = (ringR * cos(radLat) * cos(radLon)).toFloat()
                    Matrix.translateM(ringModelMatrix, 0, rX, rY, rZ)

                    Matrix.rotateM(ringModelMatrix, 0, lon.toFloat(), 0f, 1f, 0f)
                    Matrix.rotateM(ringModelMatrix, 0, -lat.toFloat(), 1f, 0f, 0f)

                    val currentScale = pulseTime * maxRingScale
                    Matrix.scaleM(ringModelMatrix, 0, currentScale, currentScale, currentScale)

                    GLES32.glUniformMatrix4fv(modelLocSat, 1, false, ringModelMatrix, 0)

                    // Linear fade out
                    val alpha = 1.0f - pulseTime
                    GLES32.glUniform4fv(colorLocSat, 1, floatArrayOf(0.0f, 1.0f, 1.0f, alpha), 0)

                    GLES32.glDrawArrays(GLES32.GL_LINE_LOOP, 0, ringVertexCount)
                }

                GLES32.glDisable(GLES32.GL_BLEND)
                GLES32.glBindVertexArray(vao) // Restore sphere VAO
            }
        }

        GLES32.glBindVertexArray(0)
    }

    fun updateUserLocation(lat: Double, lon: Double) {
        userLat = lat
        userLon = lon
        if (!isCameraInitialized) {
            // Set camera to look at user location
            phi = lat.toFloat().coerceIn(-89.9f, 89.9f)
            theta = lon.toFloat()
            isCameraInitialized = true
        }
    }

    fun smoothScrollTo(lat: Float, lon: Float, scale: Float) {
        targetPhi = lat.coerceIn(-89.9f, 89.9f)
        targetTheta = lon
        targetScale = scale
    }

    fun clearTargets() {
        targetPhi = null
        targetTheta = null
        targetScale = null
    }

    fun updateSatellites(sats: List<com.example.gnssandopticalflowapp.model.SatelliteInfo>) {
        synchronized(satLock) {
            satellites = sats
            val newKeys = mutableSetOf<String>()
            for (sat in sats) {
                val key = "${sat.constellationType}_${sat.svid}"
                newKeys.add(key)
                
                // Close proportion: Earth is 0.1f. We pull orbits much closer (0.15f - 0.17f) instead of physically correct 0.41f
                val normalizedAlt = (sat.altitude / 35786000.0).coerceIn(0.0, 1.0)
                val rSat = 0.15f + (0.02f * normalizedAlt).toFloat()
                val latRad = toRadians(sat.latitude)
                val lonRad = toRadians(sat.longitude)
                
                val tx = (rSat * cos(latRad) * sin(lonRad)).toFloat()
                val ty = (rSat * sin(latRad)).toFloat()
                val tz = (rSat * cos(latRad) * cos(lonRad)).toFloat()
                
                // Set these as final fallback, though they update in render loop
                sat.worldX = tx
                sat.worldY = ty
                sat.worldZ = tz
                
                if (renderSatellites.containsKey(key)) {
                    val state = renderSatellites[key]!!
                    state.tX = tx
                    state.tY = ty
                    state.tZ = tz
                    state.info = sat
                } else {
                    renderSatellites[key] = SatRenderState(tx, ty, tz, tx, ty, tz, sat)
                }
            }
            
            // Remove old sats
            val it = renderSatellites.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (!newKeys.contains(entry.key)) {
                    it.remove()
                }
            }
        }
    }

    fun handleTouch(x: Float, y: Float, width: Int, height: Int): com.example.gnssandopticalflowapp.model.SatelliteInfo? {
        var closestSat: com.example.gnssandopticalflowapp.model.SatelliteInfo? = null
        var minDistance = Float.MAX_VALUE
        
        // Touch threshold in pixels
        val touchRadius = 50f 

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        synchronized(satLock) {
            for (state in renderSatellites.values) {
                val sat = state.info
                val satModelMatrix = FloatArray(16)
                Matrix.setIdentityM(satModelMatrix, 0)
                
                val posVec = floatArrayOf(state.rX, state.rY, state.rZ, 1.0f)
                val rotatedPos = FloatArray(4)
                Matrix.multiplyMV(rotatedPos, 0, satModelMatrix, 0, posVec, 0)

                val clipCoords = FloatArray(4)
                Matrix.multiplyMV(clipCoords, 0, vpMatrix, 0, rotatedPos, 0)

                if (clipCoords[3] <= 0) continue // Behind camera

                val ndcX = clipCoords[0] / clipCoords[3]
                val ndcY = clipCoords[1] / clipCoords[3]
                val ndcZ = clipCoords[2] / clipCoords[3]

                if (ndcZ < -1 || ndcZ > 1) continue // Clipped by depth
                
                // Convert NDC to screen coords
                val screenX = (ndcX + 1.0f) / 2.0f * width
                val screenY = (1.0f - ndcY) / 2.0f * height // OpenGL Y is bottom-up, touch Y is top-down

                val dx = x - screenX
                val dy = y - screenY
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                // If within touch radius, check depth (closer satellites prioritize)
                if (dist < touchRadius && clipCoords[3] < minDistance) {
                    minDistance = clipCoords[3]
                    closestSat = sat
                }
            }
        }
        return closestSat
    }
}
