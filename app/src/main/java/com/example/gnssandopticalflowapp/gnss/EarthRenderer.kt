package com.example.gnssandopticalflowapp.gnss

import android.content.Context
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glUniform3f
import android.opengl.GLES32.glClearColor
import android.opengl.GLES32
import android.opengl.GLES32.GL_COLOR_BUFFER_BIT
import android.opengl.GLES32.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES32.GL_DEPTH_TEST
import android.opengl.GLES32.glClear
import android.opengl.GLES32.glEnable
import android.opengl.GLES32.glViewport
import android.opengl.GLSurfaceView.Renderer
import android.opengl.Matrix
import android.util.Log
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.model.createSphere
import com.example.gnssandopticalflowapp.model.skyboxVertices
import com.example.gnssandopticalflowapp.util.LoggerConfig
import com.example.gnssandopticalflowapp.util.ShaderHelper
import com.example.gnssandopticalflowapp.util.ShaderReader
import com.example.gnssandopticalflowapp.util.TextureLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class EarthRenderer(private val context: Context) : Renderer {
    private lateinit var sphereVertices: FloatArray
    private lateinit var sphereIndices: IntArray

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)

    private var timeElapsed: Float = 0.0f
    private var animationSpeed: Float = 0.1f

    private var program = 0
    private var VBO = 0
    private var VAO = 0
    private var EBO = 0

    private var skyboxVAO = 0
    private var skyboxVBO = 0
    private var skyboxProgram = 0
    private var skyboxTexture = 0

    var xRotation = 0f
    var yRotation = 0f
    var scaleFactor = 1.0f

    var theta = 0f
    var phi = 0f

    private var targetPhi: Float? = null
    private var targetTheta: Float? = null
    private var targetScale: Float? = null

    private var userLat: Double? = null
    private var userLon: Double? = null
    private var isCameraInitialized = false

    private var earthTextureId = 0

    private var satProgram = 0
    private var satellites = listOf<com.example.gnssandopticalflowapp.model.SatelliteInfo>()
    val satelliteCount: Int get() = satellites.size
    private val satLock = Any()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val sphere = createSphere(radius = 0.1f, stacks = 62, slices = 62)
        sphereVertices = sphere.vertices
        sphereIndices = sphere.indices

        glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        glEnable(GL_DEPTH_TEST)

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

        val faces = listOf<Int>(
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
        VAO = vaoBuffer.get(0)
        VBO = vboBuffer.get(0)
        EBO = eboBuffer.get(0)

        GLES32.glBindVertexArray(VAO)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, VBO)
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
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, EBO)
        GLES32.glBufferData(GLES32.GL_ELEMENT_ARRAY_BUFFER, sphereIndices.size * Int.SIZE_BYTES, indicesBuffer, GLES32.GL_STATIC_DRAW)

        earthTextureId = TextureLoader.loadTexture2D(context, R.drawable.earth_texture)
        
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
        GLES32.glBindVertexArray(0)
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val aspectRatio = if (width > height) width.toFloat() / height else height.toFloat() / width

        Matrix.perspectiveM(projectionMatrix, 0, 45f, 1/aspectRatio, 0.1f, 10f)
        val uniformLocation = glGetUniformLocation(program, "projectionMatrix")
        GLES32.glUniformMatrix4fv(uniformLocation, 1, false, projectionMatrix, 0)

        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Smooth transition logic
        targetPhi?.let { tPhi ->
            phi += (tPhi - phi) * 0.1f
            if (Math.abs(phi - tPhi) < 0.01f) {
                phi = tPhi
                targetPhi = null
            }
        }
        targetTheta?.let { tTheta ->
            var diff = tTheta - theta
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f

            theta += diff * 0.1f
            if (Math.abs(diff) < 0.01f) {
                theta = tTheta
                targetTheta = null
            }
        }
        targetScale?.let { tScale ->
            scaleFactor += (tScale - scaleFactor) * 0.1f
            if (Math.abs(scaleFactor - tScale) < 0.001f) {
                scaleFactor = tScale
                targetScale = null
            }
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
        GLES32.glUniform1i(glGetUniformLocation(program, "earthTexture"), 0)

        Matrix.setIdentityM(viewMatrix, 0)
//        Matrix.setLookAtM(viewMatrix, 0,
//            0f, 0f, -scaleFactor,
//            0f, 0f, 0f,
//            0f, 1f, 0f)

        // Tính vị trí camera theo tọa độ hình cầu
        val radius = scaleFactor
        val camX = (radius * cos(Math.toRadians(phi.toDouble())) * sin(Math.toRadians(theta.toDouble()))).toFloat()
        val camY = (radius * sin(Math.toRadians(phi.toDouble()))).toFloat()
        val camZ = (radius * cos(Math.toRadians(phi.toDouble())) * cos(Math.toRadians(theta.toDouble()))).toFloat()

        Matrix.setLookAtM(viewMatrix, 0,
            camX, camY, camZ,  // camera position
            0f, 0f, 0f,        // looking at origin (Earth)
            0f, 1f, 0f)        // up vector

        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)

        Matrix.setIdentityM(modelMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, modelMatrix, 0)

        // Add light based on GMT
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"))
        val hours = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minutes = calendar.get(java.util.Calendar.MINUTE)
        val timeInHours = hours + minutes / 60.0f
        val dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR)

        // Calculate sun position
        // Declination (approx)
        val decRad =
            asin(0.39795 * cos(0.2163108 + 2 * atan(0.9671396 * tan(0.00860 * (dayOfYear - 186)))))
        // Longitude
        val sunLonRaw = (12.0f - timeInHours) * 15.0f
        val sunLonRad = Math.toRadians(sunLonRaw.toDouble())

        val lightX = (Math.cos(decRad) * Math.sin(sunLonRad) * 10.0).toFloat()
        val lightY = (Math.sin(decRad) * 10.0).toFloat()
        val lightZ = (Math.cos(decRad) * Math.cos(sunLonRad) * 10.0).toFloat()

        glUniform3f(glGetUniformLocation(program, "lightColor"), 1f, 1f, 1f)
        glUniform3f(glGetUniformLocation(program, "lightPos"), lightX, lightY, lightZ)
        glUniform3f(glGetUniformLocation(program, "viewPos"), camX, camY, camZ)

        GLES32.glBindVertexArray(VAO)
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
        GLES32.glBindVertexArray(VAO) // reuse sphere VAO

        val projLocSat = glGetUniformLocation(satProgram, "projectionMatrix")
        val viewLocSat = glGetUniformLocation(satProgram, "viewMatrix")
        val modelLocSat = glGetUniformLocation(satProgram, "modelMatrix")
        val colorLocSat = glGetUniformLocation(satProgram, "color")

        GLES32.glUniformMatrix4fv(projLocSat, 1, false, projectionMatrix, 0)
        GLES32.glUniformMatrix4fv(viewLocSat, 1, false, viewMatrix, 0)

        synchronized(satLock) {
            for (sat in satellites) {
                val r = 0.15f // satellite orbit radius
                val radAz = Math.toRadians(sat.azimuthDegrees.toDouble())
                val radEl = Math.toRadians(sat.elevationDegrees.toDouble())
                
                // Map elevation & azimuth to a 3D position
                sat.worldX = (r * cos(radEl) * sin(radAz)).toFloat()
                sat.worldY = (r * Math.abs(sin(radEl))).toFloat() // keep above equator
                sat.worldZ = (r * cos(radEl) * cos(radAz)).toFloat()

                val satModelMatrix = FloatArray(16)
                Matrix.setIdentityM(satModelMatrix, 0)
                
                // Translate
                Matrix.translateM(satModelMatrix, 0, sat.worldX, sat.worldY, sat.worldZ)
                // Scale down sphere
                Matrix.scaleM(satModelMatrix, 0, 0.03f, 0.03f, 0.03f)

                GLES32.glUniformMatrix4fv(modelLocSat, 1, false, satModelMatrix, 0)

                // Set color based on constellation
                val color = when (sat.constellationType) {
                    android.location.GnssStatus.CONSTELLATION_GPS -> floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f) // Blue
                    android.location.GnssStatus.CONSTELLATION_GLONASS -> floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f) // Red
                    android.location.GnssStatus.CONSTELLATION_GALILEO -> floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Green
                    android.location.GnssStatus.CONSTELLATION_BEIDOU -> floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f) // Yellow
                    else -> floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White
                }
                
                // Dim if not used in fix
                if (!sat.usedInFix) {
                    color[3] = 0.3f // alpha (requires blending enabled, but we can just darken rgb)
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
                val radLat = Math.toRadians(lat)
                val radLon = Math.toRadians(lon)

                val userX = (rUser * cos(radLat) * sin(radLon)).toFloat()
                val userY = (rUser * sin(radLat)).toFloat()
                val userZ = (rUser * cos(radLat) * cos(radLon)).toFloat()

                val userModelMatrix = FloatArray(16)
                Matrix.setIdentityM(userModelMatrix, 0)
                Matrix.translateM(userModelMatrix, 0, userX, userY, userZ)
                Matrix.scaleM(userModelMatrix, 0, 0.04f, 0.04f, 0.04f)

                GLES32.glUniformMatrix4fv(modelLocSat, 1, false, userModelMatrix, 0)
                // Cyan color for user
                GLES32.glUniform4fv(colorLocSat, 1, floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f), 0)

                GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
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
            for (sat in satellites) {
                val satModelMatrix = FloatArray(16)
                Matrix.setIdentityM(satModelMatrix, 0)
                
                val posVec = floatArrayOf(sat.worldX, sat.worldY, sat.worldZ, 1.0f)
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
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

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