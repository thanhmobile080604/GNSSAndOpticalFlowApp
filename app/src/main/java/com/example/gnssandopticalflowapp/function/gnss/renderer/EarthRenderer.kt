package com.example.gnssandopticalflowapp.function.gnss.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.location.GnssStatus
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import androidx.core.graphics.createBitmap
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.model.SatRenderState
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import com.example.gnssandopticalflowapp.model.createSphere
import com.example.gnssandopticalflowapp.model.skyboxVertices
import com.example.gnssandopticalflowapp.util.LoggerConfig
import com.example.gnssandopticalflowapp.util.ShaderHelper
import com.example.gnssandopticalflowapp.util.ShaderReader
import com.example.gnssandopticalflowapp.util.TextureLoader
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Calendar
import java.util.TimeZone
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class EarthRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private lateinit var sphereVertices: FloatArray
    private lateinit var sphereIndices: IntArray

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    private var timeElapsed: Float = 0.0f
    private var animationSpeed: Float = 0.1f

    private var program = 0
    private var atmosphereProgram = 0
    private var blurProgram = 0
    private var compositeProgram = 0
    private var countryLabelProgram = 0
    private var countryLabelMvpHandle = 0
    private var countryLabelTextureHandle = 0
    private var countryLabelAlphaHandle = 0
    private var vbo = 0
    private var vao = 0
    private var ebo = 0
    private var screenQuadVao = 0
    private var screenQuadVbo = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var atmosphereBlurWidth = 0
    private var atmosphereBlurHeight = 0
    private var atmosphereFboA = 0
    private var atmosphereFboB = 0
    private var atmosphereTextureA = 0
    private var atmosphereTextureB = 0

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
    private var earthNightTextureId = 0
    private var moonTextureId = 0
    private var sunTextureId = 0

    private var satProgram = 0
    private var satellites = listOf<SatelliteInfo>()
    private var satelliteFillVao = 0
    private var satelliteFillVbo = 0
    private var satelliteStrokeVao = 0
    private var satelliteStrokeVbo = 0
    private var satelliteFillVertexCount = 0
    private var satelliteStrokeVertexCount = 0

    // Ring for user location
    private var ringVAO = 0
    private var ringVBO = 0
    private val ringVertexCount = 360

    private var renderSatellites = mutableMapOf<String, SatRenderState>()
    val satelliteCount: Int get() = satellites.size
    private val satLock = Any()
    private val countryTextTextures = mutableMapOf<String, TextTexture>()
    private val countryLabelQuadBuffer = ByteBuffer
        .allocateDirect(COUNTRY_LABEL_QUAD_VERTICES.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(COUNTRY_LABEL_QUAD_VERTICES)
        .apply { position(0) }
    private val countryLabelTexCoordBuffer = ByteBuffer
        .allocateDirect(COUNTRY_LABEL_TEX_COORDS.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(COUNTRY_LABEL_TEX_COORDS)
        .apply { position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        resetAtmosphereBlurStateForNewContext()
        countryTextTextures.clear()

        val sphere = createSphere(radius = 0.1f, stacks = 62, slices = 62)
        sphereVertices = sphere.vertices
        sphereIndices = sphere.indices

        GLES32.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES32.glEnable(GLES32.GL_DEPTH_TEST)

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

        val atmosphereVertexShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.atmosphere_vertex_shader)
        val atmosphereFragmentShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.atmosphere_fragment_shader)
        atmosphereProgram = ShaderHelper.buildProgram(
            atmosphereVertexShaderSource,
            atmosphereFragmentShaderSource
        )

        val screenQuadVertexShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.screen_quad_vertex_shader)
        val gaussianBlurFragmentShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.gaussian_blur_fragment_shader)
        blurProgram = ShaderHelper.buildProgram(
            screenQuadVertexShaderSource,
            gaussianBlurFragmentShaderSource
        )
        val additiveCompositeFragmentShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.additive_composite_fragment_shader)
        compositeProgram = ShaderHelper.buildProgram(
            screenQuadVertexShaderSource,
            additiveCompositeFragmentShaderSource
        )
        setupScreenQuad()

        val countryLabelVertexShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.country_label_vertex_shader)
        val countryLabelFragmentShaderSource =
            ShaderReader.readTextFileFromResource(context, R.raw.country_label_fragment_shader)
        countryLabelProgram = ShaderHelper.buildProgram(
            countryLabelVertexShaderSource,
            countryLabelFragmentShaderSource
        )
        countryLabelMvpHandle = GLES32.glGetUniformLocation(countryLabelProgram, "uMvpMatrix")
        countryLabelTextureHandle = GLES32.glGetUniformLocation(countryLabelProgram, "uTextTexture")
        countryLabelAlphaHandle = GLES32.glGetUniformLocation(countryLabelProgram, "uAlpha")

        val satVertexSrc = ShaderReader.readTextFileFromResource(context, R.raw.sat_vertex_shader)
        val satFragSrc = ShaderReader.readTextFileFromResource(context, R.raw.sat_fragment_shader)
        satProgram = ShaderHelper.buildProgram(satVertexSrc, satFragSrc)
        setupSatelliteGeometry()

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

        earthTextureId = TextureLoader.loadTexture2D(context, R.drawable.earth_texture_day)
        earthNightTextureId = TextureLoader.loadTexture2D(context, R.drawable.earth_texture_night)
        moonTextureId = TextureLoader.loadTexture2D(context, R.drawable.moon_texture)
        sunTextureId = TextureLoader.loadTexture2D(context, R.drawable.sun_texture)

        // Ring for user location
        val ringVertices = FloatArray(ringVertexCount * 3)
        for (i in 0 until ringVertexCount) {
            val angle = Math.toRadians(i.toDouble())
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
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        val aspectRatio = if (surfaceWidth > surfaceHeight) {
            surfaceWidth.toFloat() / surfaceHeight
        } else {
            surfaceHeight.toFloat() / surfaceWidth
        }

        Matrix.perspectiveM(projectionMatrix, 0, 45f, 1/aspectRatio, 0.1f, 20f)

        GLES32.glUseProgram(program)
        val uniformLocation = GLES32.glGetUniformLocation(program, "projectionMatrix")
        GLES32.glUniformMatrix4fv(uniformLocation, 1, false, projectionMatrix, 0)

        GLES32.glViewport(0, 0, surfaceWidth, surfaceHeight)
        setupAtmosphereBlurTargets(surfaceWidth, surfaceHeight)
    }

    private fun setupScreenQuad() {
        val quadVertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        val vaoBuffer = IntArray(1)
        val vboBuffer = IntArray(1)
        GLES32.glGenVertexArrays(1, vaoBuffer, 0)
        GLES32.glGenBuffers(1, vboBuffer, 0)
        screenQuadVao = vaoBuffer[0]
        screenQuadVbo = vboBuffer[0]

        val vertexBuffer = ByteBuffer
            .allocateDirect(quadVertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        vertexBuffer.position(0)

        GLES32.glBindVertexArray(screenQuadVao)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, screenQuadVbo)
        GLES32.glBufferData(
            GLES32.GL_ARRAY_BUFFER,
            quadVertices.size * Float.SIZE_BYTES,
            vertexBuffer,
            GLES32.GL_STATIC_DRAW
        )
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 0)
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(
            1,
            2,
            GLES32.GL_FLOAT,
            false,
            4 * Float.SIZE_BYTES,
            2 * Float.SIZE_BYTES
        )
        GLES32.glEnableVertexAttribArray(1)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
        GLES32.glBindVertexArray(0)
    }

    private fun setupAtmosphereBlurTargets(width: Int, height: Int) {
        val targetWidth = (width / ATMOSPHERE_BLUR_DOWNSCALE).coerceAtLeast(1)
        val targetHeight = (height / ATMOSPHERE_BLUR_DOWNSCALE).coerceAtLeast(1)
        if (
            atmosphereTextureA != 0 &&
            atmosphereTextureB != 0 &&
            atmosphereBlurWidth == targetWidth &&
            atmosphereBlurHeight == targetHeight
        ) {
            return
        }

        deleteAtmosphereBlurTargets()
        atmosphereBlurWidth = targetWidth
        atmosphereBlurHeight = targetHeight

        val first = createAtmosphereBlurTarget(targetWidth, targetHeight)
        val second = createAtmosphereBlurTarget(targetWidth, targetHeight)
        atmosphereTextureA = first.first
        atmosphereFboA = first.second
        atmosphereTextureB = second.first
        atmosphereFboB = second.second

        if (atmosphereTextureA == 0 || atmosphereTextureB == 0) {
            Log.w(TAG, "Atmosphere blur FBO setup failed; using direct halo fallback")
            deleteAtmosphereBlurTargets()
        }
    }

    private fun deleteAtmosphereBlurTargets() {
        intArrayOf(atmosphereFboA, atmosphereFboB)
            .filter { it != 0 }
            .takeIf { it.isNotEmpty() }
            ?.let { GLES32.glDeleteFramebuffers(it.size, it.toIntArray(), 0) }
        intArrayOf(atmosphereTextureA, atmosphereTextureB)
            .filter { it != 0 }
            .takeIf { it.isNotEmpty() }
            ?.let { GLES32.glDeleteTextures(it.size, it.toIntArray(), 0) }

        atmosphereFboA = 0
        atmosphereFboB = 0
        atmosphereTextureA = 0
        atmosphereTextureB = 0
    }

    private fun resetAtmosphereBlurStateForNewContext() {
        atmosphereFboA = 0
        atmosphereFboB = 0
        atmosphereTextureA = 0
        atmosphereTextureB = 0
        atmosphereBlurWidth = 0
        atmosphereBlurHeight = 0
    }

    private fun createAtmosphereBlurTarget(width: Int, height: Int): Pair<Int, Int> {
        val textureBuffer = IntArray(1)
        GLES32.glGenTextures(1, textureBuffer, 0)
        val textureId = textureBuffer[0]
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexImage2D(
            GLES32.GL_TEXTURE_2D,
            0,
            GLES32.GL_RGBA,
            width,
            height,
            0,
            GLES32.GL_RGBA,
            GLES32.GL_UNSIGNED_BYTE,
            null as Buffer?
        )

        val fboBuffer = IntArray(1)
        GLES32.glGenFramebuffers(1, fboBuffer, 0)
        val fboId = fboBuffer[0]
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboId)
        GLES32.glFramebufferTexture2D(
            GLES32.GL_FRAMEBUFFER,
            GLES32.GL_COLOR_ATTACHMENT0,
            GLES32.GL_TEXTURE_2D,
            textureId,
            0
        )

        val status = GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)

        if (status != GLES32.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Incomplete atmosphere blur FBO status=$status")
            GLES32.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES32.glDeleteTextures(1, intArrayOf(textureId), 0)
            return 0 to 0
        }

        return textureId to fboId
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT or GLES32.GL_DEPTH_BUFFER_BIT)

        updateCameraMotion()
        updateSceneTime()

        Matrix.setIdentityM(viewMatrix, 0)

        // Tính vị trí camera theo tọa độ hình cầu
        val radius = scaleFactor
        val camX = (radius * cos(Math.toRadians(phi.toDouble())) * sin(Math.toRadians(theta.toDouble()))).toFloat()
        val camY = (radius * sin(Math.toRadians(phi.toDouble()))).toFloat()
        val camZ = (radius * cos(Math.toRadians(phi.toDouble())) * cos(Math.toRadians(theta.toDouble()))).toFloat()

        Matrix.setLookAtM(viewMatrix, 0,
            camX, camY, camZ,  // camera position
            0f, 0f, 0f,        // looking at origin (Earth)
            0f, 1f, 0f)        // up vector

        val cameraPosition = SceneVector(camX, camY, camZ)
        val sunPosition = calculateSunPosition()
        val moonPosition = calculateMoonPosition()

        drawSkybox()
        drawEarth(cameraPosition, sunPosition)

        drawEarthAtmosphereHalo(
            camX = cameraPosition.x,
            camY = cameraPosition.y,
            camZ = cameraPosition.z,
            lightX = sunPosition.x,
            lightY = sunPosition.y,
            lightZ = sunPosition.z
        )
        drawCountryLabels(cameraPosition.x, cameraPosition.y, cameraPosition.z)

        drawMoon(moonPosition)

        drawSun(sunPosition)

        drawSatellites()
        drawUserLocationMarker()

        GLES32.glBindVertexArray(0)
    }

    private fun updateCameraMotion() {
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
    }

    private fun updateSceneTime() {
        timeElapsed += animationSpeed * 0.016f
        if (timeElapsed > 1.0f) {
            timeElapsed -= 1.0f
        } else if (timeElapsed < 0.0f) {
            timeElapsed += 1.0f
        }
    }

    private fun setupSatelliteGeometry() {
        val fills = mutableListOf<Float>()
        val strokes = mutableListOf<Float>()

        addSatelliteBox(
            fills = fills,
            strokes = strokes,
            minX = -SATELLITE_BODY_HALF_WIDTH,
            minY = -SATELLITE_BODY_HALF_HEIGHT,
            minZ = -SATELLITE_BODY_HALF_DEPTH,
            maxX = SATELLITE_BODY_HALF_WIDTH,
            maxY = SATELLITE_BODY_HALF_HEIGHT,
            maxZ = SATELLITE_BODY_HALF_DEPTH
        )
        addSatellitePanel(fills, strokes, -SATELLITE_PANEL_OUTER_X, -SATELLITE_PANEL_HALF_HEIGHT, -SATELLITE_PANEL_INNER_X, SATELLITE_PANEL_HALF_HEIGHT)
        addSatellitePanel(fills, strokes, SATELLITE_PANEL_INNER_X, -SATELLITE_PANEL_HALF_HEIGHT, SATELLITE_PANEL_OUTER_X, SATELLITE_PANEL_HALF_HEIGHT)
        addLine(strokes, -SATELLITE_BODY_HALF_WIDTH, 0f, 0f, -SATELLITE_PANEL_INNER_X, 0f, 0f)
        addLine(strokes, SATELLITE_BODY_HALF_WIDTH, 0f, 0f, SATELLITE_PANEL_INNER_X, 0f, 0f)

        satelliteFillVertexCount = fills.size / 3
        val fillBufferIds = createPositionOnlyVertexArray(fills.toFloatArray())
        satelliteFillVao = fillBufferIds.first
        satelliteFillVbo = fillBufferIds.second

        satelliteStrokeVertexCount = strokes.size / 3
        val strokeBufferIds = createPositionOnlyVertexArray(strokes.toFloatArray())
        satelliteStrokeVao = strokeBufferIds.first
        satelliteStrokeVbo = strokeBufferIds.second
    }

    private fun createPositionOnlyVertexArray(vertices: FloatArray): Pair<Int, Int> {
        val vaoBuffer = IntArray(1)
        val vboBuffer = IntArray(1)
        GLES32.glGenVertexArrays(1, vaoBuffer, 0)
        GLES32.glGenBuffers(1, vboBuffer, 0)

        val vertexBuffer = ByteBuffer
            .allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        GLES32.glBindVertexArray(vaoBuffer[0])
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vboBuffer[0])
        GLES32.glBufferData(
            GLES32.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            vertexBuffer,
            GLES32.GL_STATIC_DRAW
        )
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 3 * Float.SIZE_BYTES, 0)
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
        GLES32.glBindVertexArray(0)

        return vaoBuffer[0] to vboBuffer[0]
    }

    private fun addSatelliteBox(
        fills: MutableList<Float>,
        strokes: MutableList<Float>,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float
    ) {
        addQuad(fills, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ)
        addQuad(fills, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ)
        addQuad(fills, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ)
        addQuad(fills, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ)
        addQuad(fills, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ)
        addQuad(fills, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ)

        addLine(strokes, minX, minY, minZ, maxX, minY, minZ)
        addLine(strokes, maxX, minY, minZ, maxX, maxY, minZ)
        addLine(strokes, maxX, maxY, minZ, minX, maxY, minZ)
        addLine(strokes, minX, maxY, minZ, minX, minY, minZ)
        addLine(strokes, minX, minY, maxZ, maxX, minY, maxZ)
        addLine(strokes, maxX, minY, maxZ, maxX, maxY, maxZ)
        addLine(strokes, maxX, maxY, maxZ, minX, maxY, maxZ)
        addLine(strokes, minX, maxY, maxZ, minX, minY, maxZ)
        addLine(strokes, minX, minY, minZ, minX, minY, maxZ)
        addLine(strokes, maxX, minY, minZ, maxX, minY, maxZ)
        addLine(strokes, maxX, maxY, minZ, maxX, maxY, maxZ)
        addLine(strokes, minX, maxY, minZ, minX, maxY, maxZ)
    }

    private fun addSatellitePanel(
        fills: MutableList<Float>,
        strokes: MutableList<Float>,
        left: Float,
        bottom: Float,
        right: Float,
        top: Float
    ) {
        addQuad(fills, left, bottom, 0f, right, bottom, 0f, right, top, 0f, left, top, 0f)
        addLine(strokes, left, bottom, 0f, right, bottom, 0f)
        addLine(strokes, right, bottom, 0f, right, top, 0f)
        addLine(strokes, right, top, 0f, left, top, 0f)
        addLine(strokes, left, top, 0f, left, bottom, 0f)
        addLine(strokes, left, 0f, 0f, right, 0f, 0f)
    }

    private fun addQuad(
        vertices: MutableList<Float>,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        x3: Float,
        y3: Float,
        z3: Float,
        x4: Float,
        y4: Float,
        z4: Float
    ) {
        vertices.addAll(
            listOf(
                x1, y1, z1, x2, y2, z2, x3, y3, z3,
                x1, y1, z1, x3, y3, z3, x4, y4, z4
            )
        )
    }

    private fun addLine(
        vertices: MutableList<Float>,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float
    ) {
        vertices.addAll(listOf(x1, y1, z1, x2, y2, z2))
    }

    private fun drawEarth(cameraPosition: SceneVector, sunPosition: SceneVector) {
        GLES32.glUseProgram(program)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, earthTextureId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "bodyTexture"), 0)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE1)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, earthNightTextureId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "nightTexture"), 1)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "bodyType"), 0)
        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)

        Matrix.setIdentityM(modelMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, modelMatrix, 0)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(program, "lightColor"), 1f, 1f, 1f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(program, "lightPos"), sunPosition.x, sunPosition.y, sunPosition.z)
        GLES32.glUniform3f(
            GLES32.glGetUniformLocation(program, "viewPos"),
            cameraPosition.x,
            cameraPosition.y,
            cameraPosition.z
        )

        drawSphereMesh()
    }

    private fun calculateSunPosition(): SceneVector {
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val jd = utcCalendar.timeInMillis / 86400000.0 + 2440587.5
        val dJD = jd - 2451545.0

        val lSun = normalizeOrbitDegrees(280.466 + 0.9856474 * dJD)
        val mSun = normalizeOrbitDegrees(357.529 + 0.9856003 * dJD)
        val cSun = (1.915 * sin(Math.toRadians(mSun)) +
            0.020 * sin(Math.toRadians(2 * mSun))) * (1 - 0.003 * Math.toRadians(mSun))
        val lambdaSun = Math.toRadians(lSun + cSun)
        val betaSun = 0.0
        val eps = Math.toRadians(23.439 - 0.0000004 * dJD)
        val sinDeltaSun = sin(betaSun) * cos(eps) + cos(betaSun) * sin(eps) * sin(lambdaSun)
        val deltaSun = asin(sinDeltaSun)
        val alphaSun = atan2(sin(lambdaSun) * cos(eps) - tan(betaSun) * sin(eps), cos(lambdaSun))
        val tSun = dJD / 36525.0
        val gmstSun = 280.46061837 + 360.98564736629 * dJD + 0.000387933 * tSun * tSun -
            tSun * tSun * tSun / 38710000.0
        val gmstRadSun = Math.toRadians(gmstSun % 360.0)
        val sunLonRad = alphaSun - gmstRadSun

        return SceneVector(
            x = (10.0 * cos(deltaSun) * sin(sunLonRad)).toFloat(),
            y = (10.0 * sin(deltaSun)).toFloat(),
            z = (10.0 * cos(deltaSun) * cos(sunLonRad)).toFloat()
        )
    }

    private fun drawMoon(moonPosition: SceneVector) {
        val moonModelMatrix = FloatArray(16)
        Matrix.setIdentityM(moonModelMatrix, 0)
        Matrix.translateM(moonModelMatrix, 0, moonPosition.x, moonPosition.y, moonPosition.z)
        Matrix.scaleM(moonModelMatrix, 0, MOON_SCALE, MOON_SCALE, MOON_SCALE)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        val mClip = FloatArray(4)
        Matrix.multiplyMV(
            mClip,
            0,
            vpMatrix,
            0,
            floatArrayOf(moonPosition.x, moonPosition.y, moonPosition.z, 1f),
            0
        )
        if (mClip[3] <= 0) {
            Log.d(TAG, "Moon is behind camera")
        }

        GLES32.glUseProgram(program)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, moonTextureId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "bodyTexture"), 0)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "bodyType"), 1)
        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, moonModelMatrix, 0)

        drawSphereMesh()
    }

    private fun calculateMoonPosition(): SceneVector {
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val jd = utcCalendar.timeInMillis / 86400000.0 + 2440587.5
        val dJD = jd - 2451545.0

        val lMoon = normalizeOrbitDegrees(218.32 + 13.176396 * dJD)
        val mMoon = normalizeOrbitDegrees(134.96 + 13.064993 * dJD)
        val fMoon = normalizeOrbitDegrees(93.27 + 13.229350 * dJD)
        val lambdaMoon = Math.toRadians(
            lMoon + 6.289 * sin(Math.toRadians(mMoon)) +
                0.214 * sin(Math.toRadians(2 * mMoon)) +
                0.658 * sin(Math.toRadians(2 * fMoon))
        )
        val betaMoon = Math.toRadians(
            5.128 * sin(Math.toRadians(fMoon)) +
                0.281 * sin(Math.toRadians(mMoon + fMoon)) +
                0.278 * sin(Math.toRadians(mMoon - fMoon))
        )
        val epsMoon = Math.toRadians(23.439 - 0.0000004 * dJD)
        val sinDeltaMoon = sin(betaMoon) * cos(epsMoon) + cos(betaMoon) * sin(epsMoon) * sin(lambdaMoon)
        val deltaMoon = asin(sinDeltaMoon)
        val alphaMoon = atan2(sin(lambdaMoon) * cos(epsMoon) - tan(betaMoon) * sin(epsMoon), cos(lambdaMoon))
        val t = dJD / 36525.0
        val gmst = 280.46061837 + 360.98564736629 * dJD + 0.000387933 * t * t -
            t * t * t / 38710000.0
        val gmstRad = Math.toRadians(gmst % 360.0)
        val moonLonRad = alphaMoon - gmstRad

        return SceneVector(
            x = (MOON_ORBIT_RADIUS * cos(deltaMoon) * sin(moonLonRad)).toFloat(),
            y = (MOON_ORBIT_RADIUS * sin(deltaMoon)).toFloat(),
            z = (MOON_ORBIT_RADIUS * cos(deltaMoon) * cos(moonLonRad)).toFloat()
        )
    }

    private fun drawSun(sunPosition: SceneVector) {
        val sunModelMatrix = FloatArray(16)
        Matrix.setIdentityM(sunModelMatrix, 0)

        val lightLength = vectorLength(floatArrayOf(sunPosition.x, sunPosition.y, sunPosition.z))
            .coerceAtLeast(MIN_VECTOR_LENGTH)
        val sX = (sunPosition.x / lightLength) * SUN_ORBIT_RADIUS
        val sY = (sunPosition.y / lightLength) * SUN_ORBIT_RADIUS
        val sZ = (sunPosition.z / lightLength) * SUN_ORBIT_RADIUS

        Matrix.translateM(sunModelMatrix, 0, sX, sY, sZ)
        Matrix.scaleM(sunModelMatrix, 0, SUN_SCALE, SUN_SCALE, SUN_SCALE)

        GLES32.glUseProgram(program)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sunTextureId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "bodyTexture"), 0)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "bodyType"), 2)
        GLES32.glUniformMatrix4fv(1, 1, false, viewMatrix, 0)
        GLES32.glUniformMatrix4fv(0, 1, false, sunModelMatrix, 0)

        drawSphereMesh()
    }

    private fun drawSphereMesh() {
        GLES32.glBindVertexArray(vao)
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
        GLES32.glBindVertexArray(0)
    }

    private fun drawSatellites() {
        if (satelliteFillVao == 0 || satelliteStrokeVao == 0) return

        val uniforms = prepareSatelliteProgram()

        synchronized(satLock) {
            for (state in renderSatellites.values) {
                val sat = state.info
                state.rX = state.tX
                state.rY = state.tY
                state.rZ = state.tZ

                sat.worldX = state.rX
                sat.worldY = state.rY
                sat.worldZ = state.rZ

                val satModelMatrix = buildSatelliteModelMatrix(state, sat)
                GLES32.glUniformMatrix4fv(uniforms.modelMatrix, 1, false, satModelMatrix, 0)

                GLES32.glBindVertexArray(satelliteFillVao)
                GLES32.glUniform4fv(uniforms.color, 1, getSatelliteColor(sat), 0)
                GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, satelliteFillVertexCount)

                GLES32.glBindVertexArray(satelliteStrokeVao)
                GLES32.glUniform4fv(uniforms.color, 1, SATELLITE_STROKE_COLOR, 0)
                GLES32.glLineWidth(SATELLITE_STROKE_WIDTH)
                GLES32.glDrawArrays(GLES32.GL_LINES, 0, satelliteStrokeVertexCount)
            }
        }

        GLES32.glBindVertexArray(0)
    }

    private fun drawUserLocationMarker() {
        val lat = userLat ?: return
        val lon = userLon ?: return
        val uniforms = prepareSatelliteProgram()

        val rUser = 0.101f
        val radLat = Math.toRadians(lat)
        val radLon = Math.toRadians(lon)

        val userX = (rUser * cos(radLat) * sin(radLon)).toFloat()
        val userY = (rUser * sin(radLat)).toFloat()
        val userZ = (rUser * cos(radLat) * cos(radLon)).toFloat()

        val userModelMatrix = FloatArray(16)
        Matrix.setIdentityM(userModelMatrix, 0)
        Matrix.translateM(userModelMatrix, 0, userX, userY, userZ)
        Matrix.scaleM(userModelMatrix, 0, USER_MARKER_SCALE, USER_MARKER_SCALE, USER_MARKER_SCALE)
        GLES32.glUniformMatrix4fv(uniforms.modelMatrix, 1, false, userModelMatrix, 0)
        GLES32.glUniform4fv(uniforms.color, 1, USER_MARKER_COLOR, 0)
        GLES32.glBindVertexArray(vao)
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)

        GLES32.glBindVertexArray(ringVAO)
        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)
        GLES32.glLineWidth(USER_RING_LINE_WIDTH)

        for (i in 0 until USER_RING_COUNT) {
            val offset = i * USER_RING_PULSE_OFFSET_MS
            val pulseTime = ((System.currentTimeMillis() + offset) % USER_RING_PULSE_DURATION_MS).toFloat() /
                USER_RING_PULSE_DURATION_MS.toFloat()

            val ringModelMatrix = FloatArray(16)
            Matrix.setIdentityM(ringModelMatrix, 0)

            val ringR = rUser + USER_RING_SURFACE_OFFSET
            val rX = (ringR * cos(radLat) * sin(radLon)).toFloat()
            val rY = (ringR * sin(radLat)).toFloat()
            val rZ = (ringR * cos(radLat) * cos(radLon)).toFloat()
            Matrix.translateM(ringModelMatrix, 0, rX, rY, rZ)
            Matrix.rotateM(ringModelMatrix, 0, lon.toFloat(), 0f, 1f, 0f)
            Matrix.rotateM(ringModelMatrix, 0, -lat.toFloat(), 1f, 0f, 0f)

            val currentScale = pulseTime * USER_RING_MAX_SCALE
            Matrix.scaleM(ringModelMatrix, 0, currentScale, currentScale, currentScale)
            GLES32.glUniformMatrix4fv(uniforms.modelMatrix, 1, false, ringModelMatrix, 0)

            val alpha = 1.0f - pulseTime
            GLES32.glUniform4fv(uniforms.color, 1, floatArrayOf(0.0f, 1.0f, 1.0f, alpha), 0)
            GLES32.glDrawArrays(GLES32.GL_LINE_LOOP, 0, ringVertexCount)
        }

        GLES32.glDisable(GLES32.GL_BLEND)
        GLES32.glBindVertexArray(0)
    }

    private fun prepareSatelliteProgram(): SatelliteUniformLocations {
        GLES32.glUseProgram(satProgram)

        val projLocSat = GLES32.glGetUniformLocation(satProgram, "projectionMatrix")
        val viewLocSat = GLES32.glGetUniformLocation(satProgram, "viewMatrix")
        val modelLocSat = GLES32.glGetUniformLocation(satProgram, "modelMatrix")
        val colorLocSat = GLES32.glGetUniformLocation(satProgram, "color")

        GLES32.glUniformMatrix4fv(projLocSat, 1, false, projectionMatrix, 0)
        GLES32.glUniformMatrix4fv(viewLocSat, 1, false, viewMatrix, 0)

        return SatelliteUniformLocations(modelLocSat, colorLocSat)
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

    private fun buildSatelliteModelMatrix(state: SatRenderState, sat: SatelliteInfo): FloatArray {
        val position = floatArrayOf(state.rX, state.rY, state.rZ)
        val radial = normalizeVector(position)
        val latRad = Math.toRadians(sat.latitude)
        val lonRad = Math.toRadians(sat.longitude)
        val east = normalizeVector(
            floatArrayOf(
                cos(lonRad).toFloat(),
                0f,
                (-sin(lonRad)).toFloat()
            )
        )
        val north = normalizeVector(
            floatArrayOf(
                (-sin(latRad) * sin(lonRad)).toFloat(),
                cos(latRad).toFloat(),
                (-sin(latRad) * cos(lonRad)).toFloat()
            )
        )
        val azRad = Math.toRadians(sat.azimuthDegrees.toDouble())
        val cosAz = cos(azRad).toFloat()
        val sinAz = sin(azRad).toFloat()
        val headingCandidate = floatArrayOf(
            north[0] * cosAz + east[0] * sinAz,
            north[1] * cosAz + east[1] * sinAz,
            north[2] * cosAz + east[2] * sinAz
        )
        val panelAxis = if (vectorLength(headingCandidate) < MIN_VECTOR_LENGTH) {
            fallbackTangentAxis(radial)
        } else {
            normalizeVector(headingCandidate)
        }
        val elevationRad = Math.toRadians(sat.elevationDegrees.coerceIn(-90f, 90f).toDouble())
        val bodyUp = normalizeVector(rotateVectorAroundAxis(radial, panelAxis, elevationRad))
        val forwardCandidate = cross(panelAxis, bodyUp)
        val forward = if (vectorLength(forwardCandidate) < MIN_VECTOR_LENGTH) {
            normalizeVector(cross(panelAxis, fallbackTangentAxis(panelAxis)))
        } else {
            normalizeVector(forwardCandidate)
        }
        val correctedBodyUp = normalizeVector(cross(forward, panelAxis))

        return buildBasisModelMatrix(
            position = position,
            right = panelAxis,
            up = correctedBodyUp,
            forward = forward,
            scale = SATELLITE_MODEL_SCALE
        )
    }

    private fun buildBasisModelMatrix(
        position: FloatArray,
        right: FloatArray,
        up: FloatArray,
        forward: FloatArray,
        scale: Float
    ): FloatArray {
        return FloatArray(16).apply {
            this[0] = right[0] * scale
            this[1] = right[1] * scale
            this[2] = right[2] * scale
            this[3] = 0f

            this[4] = up[0] * scale
            this[5] = up[1] * scale
            this[6] = up[2] * scale
            this[7] = 0f

            this[8] = forward[0] * scale
            this[9] = forward[1] * scale
            this[10] = forward[2] * scale
            this[11] = 0f

            this[12] = position[0]
            this[13] = position[1]
            this[14] = position[2]
            this[15] = 1f
        }
    }

    private fun rotateVectorAroundAxis(vector: FloatArray, axis: FloatArray, angleRad: Double): FloatArray {
        val normalizedAxis = normalizeVector(axis)
        val cosAngle = cos(angleRad).toFloat()
        val sinAngle = sin(angleRad).toFloat()
        val crossPart = cross(normalizedAxis, vector)
        val dotPart = dot(normalizedAxis, vector)
        val oneMinusCos = 1f - cosAngle

        return floatArrayOf(
            vector[0] * cosAngle + crossPart[0] * sinAngle + normalizedAxis[0] * dotPart * oneMinusCos,
            vector[1] * cosAngle + crossPart[1] * sinAngle + normalizedAxis[1] * dotPart * oneMinusCos,
            vector[2] * cosAngle + crossPart[2] * sinAngle + normalizedAxis[2] * dotPart * oneMinusCos
        )
    }

    private fun fallbackTangentAxis(reference: FloatArray): FloatArray {
        val worldUpCandidate = cross(WORLD_UP, reference)
        if (vectorLength(worldUpCandidate) >= MIN_VECTOR_LENGTH) {
            return normalizeVector(worldUpCandidate)
        }
        return normalizeVector(cross(floatArrayOf(1f, 0f, 0f), reference))
    }

    private fun normalizeOrbitDegrees(value: Double): Double {
        var normalized = value % 360.0
        if (normalized < 0.0) normalized += 360.0
        return normalized
    }

    private fun drawSkybox() {
        GLES32.glDepthFunc(GLES32.GL_LEQUAL)
        GLES32.glDepthMask(false)
        GLES32.glUseProgram(skyboxProgram)

        val viewLoc = GLES32.glGetUniformLocation(skyboxProgram, "view")
        val projLoc = GLES32.glGetUniformLocation(skyboxProgram, "projection")

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
        GLES32.glUniform1i(GLES32.glGetUniformLocation(skyboxProgram, "skybox"), 0)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, 36)
        GLES32.glBindVertexArray(0)

        GLES32.glDepthMask(true)
        GLES32.glDepthFunc(GLES32.GL_LESS)
    }

    private fun drawCountryLabels(camX: Float, camY: Float, camZ: Float) {
        if (countryLabelProgram == 0) return

        val forward = normalizeVector(floatArrayOf(-camX, -camY, -camZ))
        val rightCandidate = cross(forward, WORLD_UP)
        val right = if (vectorLength(rightCandidate) < MIN_VECTOR_LENGTH) {
            floatArrayOf(1f, 0f, 0f)
        } else {
            normalizeVector(rightCandidate)
        }
        val up = normalizeVector(cross(right, forward))
        val cameraPos = floatArrayOf(camX, camY, camZ)
        val labelWorldHeight = (COUNTRY_LABEL_WORLD_HEIGHT * scaleFactor)
            .coerceIn(COUNTRY_LABEL_MIN_WORLD_HEIGHT, COUNTRY_LABEL_MAX_WORLD_HEIGHT)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)
        GLES32.glDepthMask(false)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glUseProgram(countryLabelProgram)

        GLES32.glEnableVertexAttribArray(0)
        countryLabelQuadBuffer.position(0)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 0, countryLabelQuadBuffer)
        GLES32.glEnableVertexAttribArray(1)
        countryLabelTexCoordBuffer.position(0)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 0, countryLabelTexCoordBuffer)

        for (label in COUNTRY_LABELS) {
            val normal = label.surfaceNormal()
            val position = floatArrayOf(
                normal[0] * COUNTRY_LABEL_RADIUS,
                normal[1] * COUNTRY_LABEL_RADIUS,
                normal[2] * COUNTRY_LABEL_RADIUS
            )
            val toCamera = normalizeVector(
                floatArrayOf(
                    cameraPos[0] - position[0],
                    cameraPos[1] - position[1],
                    cameraPos[2] - position[2]
                )
            )
            val visibility = dot(normal, toCamera)
            if (visibility < COUNTRY_LABEL_MIN_VISIBILITY) continue

            val textTexture = getCountryTextTexture(label.name)
            val labelWorldWidth = labelWorldHeight * textTexture.aspectRatio
            val modelMatrix = buildCountryLabelModelMatrix(
                position = position,
                right = right,
                up = up,
                forward = forward,
                width = labelWorldWidth,
                height = labelWorldHeight
            )
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            GLES32.glUniformMatrix4fv(countryLabelMvpHandle, 1, false, mvpMatrix, 0)
            GLES32.glUniform1f(
                countryLabelAlphaHandle,
                smoothStep(COUNTRY_LABEL_MIN_VISIBILITY, COUNTRY_LABEL_FULL_VISIBILITY, visibility)
            )
            GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textTexture.textureId)
            GLES32.glUniform1i(countryLabelTextureHandle, 0)
            GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
        GLES32.glDisableVertexAttribArray(1)
        GLES32.glDisableVertexAttribArray(0)
        GLES32.glEnable(GLES32.GL_DEPTH_TEST)
        GLES32.glDepthMask(true)
        GLES32.glDisable(GLES32.GL_BLEND)
    }

    private fun buildCountryLabelModelMatrix(
        position: FloatArray,
        right: FloatArray,
        up: FloatArray,
        forward: FloatArray,
        width: Float,
        height: Float
    ): FloatArray {
        return FloatArray(16).apply {
            this[0] = right[0] * width
            this[1] = right[1] * width
            this[2] = right[2] * width
            this[3] = 0f

            this[4] = up[0] * height
            this[5] = up[1] * height
            this[6] = up[2] * height
            this[7] = 0f

            this[8] = -forward[0]
            this[9] = -forward[1]
            this[10] = -forward[2]
            this[11] = 0f

            this[12] = position[0]
            this[13] = position[1]
            this[14] = position[2]
            this[15] = 1f
        }
    }

    private fun getCountryTextTexture(text: String): TextTexture {
        countryTextTextures[text]?.let { return it }

        val texture = createCountryTextTexture(text)
        countryTextTextures[text] = texture
        return texture
    }

    private fun createCountryTextTexture(text: String): TextTexture {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(232, 248, 252, 255)
            textSize = COUNTRY_LABEL_TEXT_SIZE_PX
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val strokePaint = Paint(fillPaint).apply {
            color = Color.argb(210, 2, 8, 18)
            style = Paint.Style.STROKE
            strokeWidth = COUNTRY_LABEL_STROKE_WIDTH_PX
        }

        val bounds = Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        val width = (fillPaint.measureText(text) + COUNTRY_LABEL_TEXTURE_PADDING_PX * 2f)
            .toInt()
            .coerceAtLeast(2)
        val height = (bounds.height() + COUNTRY_LABEL_TEXTURE_PADDING_PX * 2f)
            .toInt()
            .coerceAtLeast(2)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val baseline = COUNTRY_LABEL_TEXTURE_PADDING_PX - bounds.top
        canvas.drawText(text, COUNTRY_LABEL_TEXTURE_PADDING_PX.toFloat(), baseline.toFloat(), strokePaint)
        canvas.drawText(text, COUNTRY_LABEL_TEXTURE_PADDING_PX.toFloat(), baseline.toFloat(), fillPaint)

        val textures = IntArray(1)
        GLES32.glGenTextures(1, textures, 0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textures[0])
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)

        val aspectRatio = width.toFloat() / height.toFloat()
        bitmap.recycle()
        return TextTexture(textures[0], aspectRatio)
    }

    private fun CountryLabel.surfaceNormal(): FloatArray {
        val latRad = Math.toRadians(latitude)
        val lonRad = Math.toRadians(longitude)
        return floatArrayOf(
            (cos(latRad) * sin(lonRad)).toFloat(),
            sin(latRad).toFloat(),
            (cos(latRad) * cos(lonRad)).toFloat()
        )
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }

    private fun vectorLength(v: FloatArray): Float {
        return sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
    }

    private fun normalizeVector(v: FloatArray): FloatArray {
        val length = vectorLength(v)
        if (length < MIN_VECTOR_LENGTH) return floatArrayOf(0f, 1f, 0f)
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun drawEarthAtmosphereHalo(
        camX: Float,
        camY: Float,
        camZ: Float,
        lightX: Float,
        lightY: Float,
        lightZ: Float
    ) {
        if (!hasAtmosphereBlurTargets()) {
            setupAtmosphereBlurTargets(surfaceWidth, surfaceHeight)
        }
        if (!hasAtmosphereBlurTargets()) {
            renderAtmosphereLayers(camX, camY, camZ, lightX, lightY, lightZ)
            return
        }

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, atmosphereFboA)
        GLES32.glViewport(0, 0, atmosphereBlurWidth, atmosphereBlurHeight)
        GLES32.glClearColor(0f, 0f, 0f, 0f)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        renderAtmosphereLayers(camX, camY, camZ, lightX, lightY, lightZ)

        repeat(ATMOSPHERE_BLUR_PASSES) {
            renderBlurPass(
                sourceTexture = atmosphereTextureA,
                targetFbo = atmosphereFboB,
                directionX = 1f,
                directionY = 0f
            )
            renderBlurPass(
                sourceTexture = atmosphereTextureB,
                targetFbo = atmosphereFboA,
                directionX = 0f,
                directionY = 1f
            )
        }

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES32.glClearColor(0f, 0f, 0f, 1f)
        compositeAtmosphereGlow()
    }

    private fun hasAtmosphereBlurTargets(): Boolean {
        return atmosphereTextureA != 0 &&
            atmosphereTextureB != 0 &&
            atmosphereFboA != 0 &&
            atmosphereFboB != 0 &&
            atmosphereBlurWidth > 0 &&
            atmosphereBlurHeight > 0
    }

    private fun renderAtmosphereLayers(
        camX: Float,
        camY: Float,
        camZ: Float,
        lightX: Float,
        lightY: Float,
        lightZ: Float
    ) {
        GLES32.glUseProgram(atmosphereProgram)

        GLES32.glUniformMatrix4fv(
            GLES32.glGetUniformLocation(atmosphereProgram, "projectionMatrix"),
            1,
            false,
            projectionMatrix,
            0
        )
        GLES32.glUniformMatrix4fv(
            GLES32.glGetUniformLocation(atmosphereProgram, "viewMatrix"),
            1,
            false,
            viewMatrix,
            0
        )
        GLES32.glUniform3f(
            GLES32.glGetUniformLocation(atmosphereProgram, "lightPos"),
            lightX,
            lightY,
            lightZ
        )
        GLES32.glUniform3f(
            GLES32.glGetUniformLocation(atmosphereProgram, "viewPos"),
            camX,
            camY,
            camZ
        )
        GLES32.glUniform1f(
            GLES32.glGetUniformLocation(atmosphereProgram, "haloStrength"),
            ATMOSPHERE_HALO_STRENGTH
        )
        GLES32.glUniform1f(
            GLES32.glGetUniformLocation(atmosphereProgram, "time"),
            timeElapsed
        )

        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE)
        GLES32.glDepthMask(false)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_CULL_FACE)

        GLES32.glBindVertexArray(vao)
        for (index in ATMOSPHERE_LAYER_SCALES.indices) {
            val scale = ATMOSPHERE_LAYER_SCALES[index]
            val atmosphereModelMatrix = FloatArray(16)
            Matrix.setIdentityM(atmosphereModelMatrix, 0)
            Matrix.scaleM(atmosphereModelMatrix, 0, scale, scale, scale)

            GLES32.glUniformMatrix4fv(
                GLES32.glGetUniformLocation(atmosphereProgram, "modelMatrix"),
                1,
                false,
                atmosphereModelMatrix,
                0
            )
            GLES32.glUniform1f(
                GLES32.glGetUniformLocation(atmosphereProgram, "layerStrength"),
                ATMOSPHERE_LAYER_STRENGTHS[index]
            )
            GLES32.glUniform1f(
                GLES32.glGetUniformLocation(atmosphereProgram, "layerIndex"),
                index.toFloat()
            )
            GLES32.glDrawElements(GLES32.GL_TRIANGLES, sphereIndices.size, GLES32.GL_UNSIGNED_INT, 0)
        }
        GLES32.glBindVertexArray(0)

        GLES32.glEnable(GLES32.GL_DEPTH_TEST)
        GLES32.glDepthMask(true)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)
        GLES32.glDisable(GLES32.GL_BLEND)
    }

    private fun renderBlurPass(
        sourceTexture: Int,
        targetFbo: Int,
        directionX: Float,
        directionY: Float
    ) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, targetFbo)
        GLES32.glViewport(0, 0, atmosphereBlurWidth, atmosphereBlurHeight)
        GLES32.glClearColor(0f, 0f, 0f, 0f)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_BLEND)

        GLES32.glUseProgram(blurProgram)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sourceTexture)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(blurProgram, "imageTexture"), 0)
        GLES32.glUniform2f(
            GLES32.glGetUniformLocation(blurProgram, "texelSize"),
            1f / atmosphereBlurWidth,
            1f / atmosphereBlurHeight
        )
        GLES32.glUniform2f(
            GLES32.glGetUniformLocation(blurProgram, "direction"),
            directionX,
            directionY
        )
        drawScreenQuad()
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
    }

    private fun compositeAtmosphereGlow() {
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDepthMask(false)
        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendFunc(GLES32.GL_ONE, GLES32.GL_ONE)

        GLES32.glUseProgram(compositeProgram)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, atmosphereTextureA)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(compositeProgram, "glowTexture"), 0)
        GLES32.glUniform1f(
            GLES32.glGetUniformLocation(compositeProgram, "intensity"),
            ATMOSPHERE_BLOOM_INTENSITY
        )
        drawScreenQuad()

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)
        GLES32.glDisable(GLES32.GL_BLEND)
        GLES32.glDepthMask(true)
        GLES32.glEnable(GLES32.GL_DEPTH_TEST)
    }

    private fun drawScreenQuad() {
        GLES32.glBindVertexArray(screenQuadVao)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
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

    fun updateSatellites(sats: List<SatelliteInfo>) {
        synchronized(satLock) {
            satellites = sats
            val newKeys = mutableSetOf<String>()
            for (sat in sats) {
                val key = "${sat.constellationType}_${sat.svid}"
                newKeys.add(key)

                // Close proportion: Earth is 0.1f. We pull orbits much closer (0.15f - 0.17f) instead of physically correct 0.41f
                val normalizedAlt = (sat.altitude / 35786000.0).coerceIn(0.0, 1.0)
                val rSat = 0.15f + (0.02f * normalizedAlt).toFloat()
                val latRad = Math.toRadians(sat.latitude)
                val lonRad = Math.toRadians(sat.longitude)

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

    fun handleTouch(x: Float, y: Float, width: Int, height: Int): SatelliteInfo? {
        var closestSat: SatelliteInfo? = null
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

    private data class CountryLabel(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    private data class TextTexture(
        val textureId: Int,
        val aspectRatio: Float
    )

    private data class SceneVector(
        val x: Float,
        val y: Float,
        val z: Float
    )

    private data class SatelliteUniformLocations(
        val modelMatrix: Int,
        val color: Int
    )

    private companion object {
        val ATMOSPHERE_LAYER_SCALES = floatArrayOf(1.012f, 1.028f, 1.052f, 1.086f, 1.14f, 1.23f)
        val ATMOSPHERE_LAYER_STRENGTHS = floatArrayOf(0.92f, 0.70f, 0.48f, 0.28f, 0.15f, 0.065f)
        const val ATMOSPHERE_HALO_STRENGTH = 1.12f
        const val ATMOSPHERE_BLUR_DOWNSCALE = 3
        const val ATMOSPHERE_BLUR_PASSES = 4
        const val ATMOSPHERE_BLOOM_INTENSITY = 1.7f
        val COUNTRY_LABEL_QUAD_VERTICES = floatArrayOf(
            -0.5f, -0.5f, 0f,
            -0.5f, 0.5f, 0f,
            0.5f, -0.5f, 0f,
            0.5f, 0.5f, 0f
        )
        val COUNTRY_LABEL_TEX_COORDS = floatArrayOf(
            0f, 1f,
            0f, 0f,
            1f, 1f,
            1f, 0f
        )
        val WORLD_UP = floatArrayOf(0f, 1f, 0f)
        val COUNTRY_LABELS = listOf(
            CountryLabel("Canada", 56.0, -106.0),
            CountryLabel("United States", 39.0, -98.0),
            CountryLabel("Mexico", 23.0, -102.0),
            CountryLabel("Brazil", -10.0, -52.0),
            CountryLabel("Argentina", -38.0, -63.0),
            CountryLabel("Chile", -30.0, -71.0),
            CountryLabel("Peru", -9.0, -75.0),
            CountryLabel("Colombia", 4.0, -74.0),
            CountryLabel("United Kingdom", 54.0, -2.0),
            CountryLabel("France", 46.0, 2.0),
            CountryLabel("Spain", 40.0, -4.0),
            CountryLabel("Germany", 51.0, 10.0),
            CountryLabel("Italy", 42.0, 12.0),
            CountryLabel("Norway", 62.0, 10.0),
            CountryLabel("Sweden", 62.0, 15.0),
            CountryLabel("Finland", 64.0, 26.0),
            CountryLabel("Poland", 52.0, 19.0),
            CountryLabel("Ukraine", 49.0, 32.0),
            CountryLabel("Russia", 61.0, 90.0),
            CountryLabel("Turkey", 39.0, 35.0),
            CountryLabel("Morocco", 31.0, -7.0),
            CountryLabel("Algeria", 28.0, 3.0),
            CountryLabel("Egypt", 27.0, 30.0),
            CountryLabel("Nigeria", 9.0, 8.0),
            CountryLabel("Ethiopia", 9.0, 40.0),
            CountryLabel("Kenya", 0.0, 38.0),
            CountryLabel("DR Congo", -3.0, 24.0),
            CountryLabel("South Africa", -30.0, 25.0),
            CountryLabel("Saudi Arabia", 24.0, 45.0),
            CountryLabel("Iran", 32.0, 53.0),
            CountryLabel("Kazakhstan", 48.0, 67.0),
            CountryLabel("Mongolia", 46.0, 103.0),
            CountryLabel("Pakistan", 30.0, 70.0),
            CountryLabel("India", 22.0, 79.0),
            CountryLabel("China", 35.0, 104.0),
            CountryLabel("Japan", 37.0, 138.0),
            CountryLabel("Korea", 36.0, 128.0),
            CountryLabel("Thailand", 15.0, 101.0),
            CountryLabel("Vietnam", 16.0, 108.0),
            CountryLabel("Malaysia", 4.0, 102.0),
            CountryLabel("Philippines", 13.0, 122.0),
            CountryLabel("Indonesia", -2.0, 118.0),
            CountryLabel("Australia", -25.0, 134.0),
            CountryLabel("New Zealand", -41.0, 174.0)
        )
        const val COUNTRY_LABEL_RADIUS = 0.1035f
        const val COUNTRY_LABEL_WORLD_HEIGHT = 0.0062f
        const val COUNTRY_LABEL_MIN_WORLD_HEIGHT = 0.0045f
        const val COUNTRY_LABEL_MAX_WORLD_HEIGHT = 0.012f
        const val COUNTRY_LABEL_MIN_VISIBILITY = 0.16f
        const val COUNTRY_LABEL_FULL_VISIBILITY = 0.40f
        const val COUNTRY_LABEL_TEXT_SIZE_PX = 42f
        const val COUNTRY_LABEL_STROKE_WIDTH_PX = 5f
        const val COUNTRY_LABEL_TEXTURE_PADDING_PX = 10
        const val MIN_VECTOR_LENGTH = 0.000001f
        const val MOON_ORBIT_RADIUS = 0.3f
        const val MOON_SCALE = 0.2727f
        const val SUN_ORBIT_RADIUS = 3.0f
        const val SUN_SCALE = 3.0f
        const val SATELLITE_MODEL_SCALE = 0.008f
        const val SATELLITE_BODY_HALF_WIDTH = 0.18f
        const val SATELLITE_BODY_HALF_HEIGHT = 0.50f
        const val SATELLITE_BODY_HALF_DEPTH = 0.10f
        const val SATELLITE_PANEL_INNER_X = 0.34f
        const val SATELLITE_PANEL_OUTER_X = 1.08f
        const val SATELLITE_PANEL_HALF_HEIGHT = 0.30f
        const val SATELLITE_STROKE_WIDTH = 2.2f
        const val USER_MARKER_SCALE = 0.01f
        const val USER_RING_LINE_WIDTH = 3.0f
        const val USER_RING_COUNT = 2
        const val USER_RING_MAX_SCALE = 0.01f
        const val USER_RING_SURFACE_OFFSET = 0.0001f
        const val USER_RING_PULSE_DURATION_MS = 2000L
        const val USER_RING_PULSE_OFFSET_MS = 1000L
        val SATELLITE_STROKE_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        val USER_MARKER_COLOR = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f)
        const val TAG = "EarthRenderer"
    }
}
