package com.example.gnssandopticalflowapp.screen.test

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentFlowPathTrackerBinding
import com.example.gnssandopticalflowapp.function.optical_flow.classes.IMUEstimator
import com.example.gnssandopticalflowapp.function.optical_flow.classes.Farneback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Path tracker: nửa trên canvas vẽ quỹ đạo, nửa dưới camera optical flow.
 *
 * Dead reckoning indoor (không GNSS):
 *  - Tốc độ: từ IMUEstimator (tích phân gia tốc) + Zero-Velocity Update bằng
 *    optical flow (flow đứng yên => ép tốc độ = 0, triệt drift của IMU)
 *  - Heading: tích phân yaw rate ước lượng từ thành phần flow ngang
 *  - Vị trí: x += v*dt*sin(heading), y += v*dt*cos(heading)  (y = "bắc" local)
 *
 * Dừng > STOP_HOLD_MS => đánh dấu điểm dừng trên path.
 * Canvas hỗ trợ 1 ngón pan, 2 ngón rotate + pinch zoom.
 */
class FlowPathTrackerFragment :
    BaseFragment<FragmentFlowPathTrackerBinding>(FragmentFlowPathTrackerBinding::inflate) {

    // ---- Camera / optical flow ----
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var farneback: Farneback? = null
    private lateinit var imuEstimator: IMUEstimator
    private var overlayBitmap: Bitmap? = null
    private var flowFrameCount = 0
    private var lastFlowPosition: Point? = null
    private var lastFlowFrameTimeMs = 0L

    // Flow state (ghi từ camera thread, đọc từ ticker)
    @Volatile private var emaFlowMagPxPerSec = 0.0
    @Volatile private var emaFlowDxPxPerSec = 0.0
    @Volatile private var lastFlowSampleMs = 0L

    // ---- Tracking state ----
    private var tracking = false
    private var trackStartMs = 0L
    private var tickerJob: Job? = null
    private var lastTickMs = 0L

    private var posX = 0.0 // meters east (local)
    private var posY = 0.0 // meters north (local)
    private var headingDeg = 0.0
    private var totalDistanceM = 0.0
    private var lastAppendedX = 0.0
    private var lastAppendedY = 0.0

    // ---- Stop detection ----
    private var stillSinceMs = 0L
    private var inStopEpisode = false
    private var movedSinceLastStopM = 0.0

    // ================== Lifecycle ==================

    override fun FragmentFlowPathTrackerBinding.initView() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraView.scaleType = PreviewView.ScaleType.FILL_CENTER
        imuEstimator = IMUEstimator(safeContext().applicationContext)
        updateTrackInfo()
        checkCameraPermission()
    }

    override fun FragmentFlowPathTrackerBinding.initListener() {
        btnStartEnd.setSingleClick {
            if (tracking) endTracking() else startTracking()
        }
        btnRecenterCanvas.setSingleClick {
            trajectoryCanvas.recenter()
        }
    }

    override fun initObserver() = Unit

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) startCamera()
        if (::imuEstimator.isInitialized) imuEstimator.register()
        if (tracking) startTicker()
    }

    override fun onPause() {
        super.onPause()
        tickerJob?.cancel()
        tickerJob = null
        stopCamera()
        if (::imuEstimator.isInitialized) imuEstimator.unregister()
    }

    override fun onDestroyView() {
        stopCamera()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroyView()
    }

    // ================== Start / End tracking ==================

    private fun startTracking() {
        posX = 0.0
        posY = 0.0
        headingDeg = 0.0
        totalDistanceM = 0.0
        lastAppendedX = 0.0
        lastAppendedY = 0.0
        stillSinceMs = 0L
        inStopEpisode = false
        movedSinceLastStopM = 0.0
        trackStartMs = System.currentTimeMillis()
        lastTickMs = trackStartMs

        binding.trajectoryCanvas.reset()
        binding.trajectoryCanvas.addPoint(0f, 0f)

        tracking = true
        binding.btnStartEnd.text = "End"
        startTicker()
        Toast.makeText(safeContext(), "Tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun endTracking() {
        tracking = false
        tickerJob?.cancel()
        tickerJob = null
        binding.btnStartEnd.text = "Start"
        updateTrackInfo()
        Toast.makeText(
            safeContext(),
            String.format(Locale.US, "Tracked %.1f m, %d stops", totalDistanceM, binding.trajectoryCanvas.stopCount()),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ================== Dead reckoning ticker ==================

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewLifecycleOwner.lifecycleScope.launch {
            lastTickMs = System.currentTimeMillis()
            while (isActive) {
                integrateStep()
                delay(TICK_MS)
            }
        }
    }

    private fun integrateStep() {
        if (!tracking) return
        val nowMs = System.currentTimeMillis()
        val dtSec = ((nowMs - lastTickMs).coerceIn(1L, 500L)) / 1000.0
        lastTickMs = nowMs

        // ---- Tốc độ từ IMU ----
        val v = imuEstimator.getVelocity()
        val imuSpeed = sqrt(
            (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()
        )

        // ---- Zero-Velocity Update bằng optical flow ----
        // Flow gần đứng yên => chắc chắn điện thoại không di chuyển,
        // ép speed = 0 để triệt drift tích phân của IMU.
        val flowFresh = (nowMs - lastFlowSampleMs) < FLOW_STALE_MS
        val flowStill = !flowFresh || emaFlowMagPxPerSec < STILL_FLOW_PX_PER_SEC
        val speedMps = if (flowStill) 0.0 else imuSpeed.coerceIn(0.0, MAX_WALK_SPEED_MPS)

        // ---- Heading từ Gyroscope thay cho Optical Flow ----
        if (!flowStill) {
            val yawRate = imuEstimator.getYawRate()
            headingDeg = normalizeDeg(headingDeg + yawRate * dtSec)
        }

        // ---- Tích phân vị trí ----
        if (speedMps > 0.0) {
            val rad = Math.toRadians(headingDeg)
            val dist = speedMps * dtSec
            posX += dist * sin(rad)
            posY += dist * cos(rad)
            totalDistanceM += dist
            movedSinceLastStopM += dist
        }

        // ---- Append điểm vào canvas khi đã đi đủ xa ----
        val fromLast = hypot(posX - lastAppendedX, posY - lastAppendedY)
        if (fromLast >= APPEND_DISTANCE_M) {
            lastAppendedX = posX
            lastAppendedY = posY
            binding.trajectoryCanvas.currentHeadingDeg = headingDeg.toFloat()
            binding.trajectoryCanvas.addPoint(posX.toFloat(), posY.toFloat())
        } else {
            binding.trajectoryCanvas.currentHeadingDeg = headingDeg.toFloat()
        }

        // ---- Phát hiện điểm dừng ----
        detectStop(nowMs, speedMps)

        updateTrackInfo()
    }

    private fun detectStop(nowMs: Long, speedMps: Double) {
        val isStill = speedMps < STOP_SPEED_MPS
        if (isStill) {
            if (stillSinceMs == 0L) stillSinceMs = nowMs
            val stillFor = nowMs - stillSinceMs
            // Đứng yên đủ lâu + trước đó có di chuyển thực sự => đánh dấu 1 lần
            if (!inStopEpisode && stillFor >= STOP_HOLD_MS && movedSinceLastStopM >= MIN_MOVE_FOR_STOP_M) {
                inStopEpisode = true
                movedSinceLastStopM = 0.0
                binding.trajectoryCanvas.addStopMarker(posX.toFloat(), posY.toFloat())
            }
        } else {
            stillSinceMs = 0L
            if (inStopEpisode) inStopEpisode = false
        }
    }

    private fun updateTrackInfo() {
        if (!isAdded || view == null) return
        val elapsed = if (tracking) System.currentTimeMillis() - trackStartMs else 0L
        val totalSec = elapsed / 1000
        binding.tvTrackInfo.text = String.format(
            Locale.US,
            "%.1f m  •  %02d:%02d  •  %d stops",
            totalDistanceM,
            totalSec / 60,
            totalSec % 60,
            binding.trajectoryCanvas.stopCount()
        )
    }

    private fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    // ================== Camera + KLT (pipeline như các fragment khác) ==================

    private fun checkCameraPermission() {
        doRequestPermission(
            arrayOf(Manifest.permission.CAMERA),
            object : IPermissionListener {
                override fun onAllow() = startCamera()
                override fun onDenied() {
                    Toast.makeText(safeContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
                }
                override fun onNeverAskAgain(permission: String) {
                    Toast.makeText(safeContext(), "Enable camera permission in settings", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            safeContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        if (!::cameraExecutor.isInitialized || !hasCameraPermission()) return
        val context = safeContext()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isAdded || view == null) return@addListener
            val provider = future.get()
            cameraProvider = provider

            farneback = Farneback().apply {
                setMovingMode(true)
                setSensitivity(FLOW_SENSITIVITY)
            }
            lastFlowPosition = null
            lastFlowFrameTimeMs = 0L

            val rotation = binding.cameraView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = binding.cameraView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFlowFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Bind camera failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        farneback = null
        lastFlowPosition = null
    }

    private fun analyzeFlowFrame(imageProxy: ImageProxy) {
        var rgba: Mat? = null
        var processing: Mat? = null
        try {
            rgba = imageProxyToRgbaMat(imageProxy)
            processing = rotateFrameForDisplay(rgba, imageProxy.imageInfo.rotationDegrees)
            processFlowFrame(processing)
        } catch (e: Exception) {
            Log.e(TAG, "Flow frame failed: ${e.message}", e)
        } finally {
            if (processing !== rgba) processing?.release()
            rgba?.release()
            imageProxy.close()
        }
    }

    private fun processFlowFrame(frame: Mat) {
        val flow = farneback ?: return
        val nowMs = System.currentTimeMillis()

        flowFrameCount++
        if (flowFrameCount % FEATURE_UPDATE_INTERVAL == 0) flow.updateFeatures()

        val output = flow.run(frame) ?: return

        output.metrics?.let { metrics ->
            val dtMs = if (lastFlowFrameTimeMs > 0) nowMs - lastFlowFrameTimeMs else 0L
            if (dtMs in 1..500) {
                val dtSec = dtMs / 1000.0
                val dx = metrics.avgDx / dtSec
                val mag = metrics.avgMagnitude / dtSec

                emaFlowMagPxPerSec = EMA_ALPHA * mag + (1 - EMA_ALPHA) * emaFlowMagPxPerSec
                emaFlowDxPxPerSec = EMA_ALPHA * dx + (1 - EMA_ALPHA) * emaFlowDxPxPerSec
                lastFlowSampleMs = nowMs

                activity?.runOnUiThread {
                    if (isAdded && view != null) {
                        binding.tvFlowInfo.text = String.format(
                            Locale.US, "Farneback  flow=%.0f px/s  hdg=%.0f°",
                            emaFlowMagPxPerSec, headingDeg
                        )
                    }
                }
            }
            lastFlowFrameTimeMs = nowMs
        }

        output.ofFrame?.let { outFrame ->
            if (outFrame.empty()) return@let
            makeFrameOpaque(outFrame)
            val bitmap = getOrCreateOverlayBitmap(outFrame)
            Utils.matToBitmap(outFrame, bitmap)
            activity?.runOnUiThread {
                if (isAdded && view != null) {
                    binding.cameraOutputOverlay.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun getOrCreateOverlayBitmap(mat: Mat): Bitmap {
        val current = overlayBitmap
        if (current != null && current.width == mat.cols() && current.height == mat.rows()) {
            return current
        }
        return createBitmap(mat.cols(), mat.rows()).also { overlayBitmap = it }
    }

    private fun makeFrameOpaque(frame: Mat) {
        if (frame.channels() < 4) return
        val channels = mutableListOf<Mat>()
        Core.split(frame, channels)
        if (channels.size >= 4) {
            channels[3].setTo(Scalar(255.0))
            Core.merge(channels, frame)
        }
        channels.forEach { it.release() }
    }

    private fun imageProxyToRgbaMat(imageProxy: ImageProxy): Mat {
        val plane = imageProxy.planes.first()
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val bytesPerPixel = 4
        val rowSize = width * bytesPerPixel
        val rgbaBytes = ByteArray(rowSize * height)

        if (plane.rowStride == rowSize && plane.pixelStride == bytesPerPixel) {
            buffer.rewind()
            buffer.get(rgbaBytes, 0, rgbaBytes.size.coerceAtMost(buffer.remaining()))
        } else {
            val rowBuffer = ByteArray(plane.rowStride)
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                val bytesToRead = minOf(plane.rowStride, buffer.remaining())
                buffer.get(rowBuffer, 0, bytesToRead)
                System.arraycopy(rowBuffer, 0, rgbaBytes, row * rowSize, minOf(rowSize, bytesToRead))
            }
        }

        return Mat(height, width, CvType.CV_8UC4).apply { put(0, 0, rgbaBytes) }
    }

    private fun rotateFrameForDisplay(frame: Mat, rotationDegrees: Int): Mat {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return frame
        val rotated = Mat()
        when (normalized) {
            90 -> Core.rotate(frame, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(frame, rotated, Core.ROTATE_180)
            270 -> Core.rotate(frame, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> frame.copyTo(rotated)
        }
        return rotated
    }

    private companion object {
        const val TAG = "FlowPathTracker"
        const val TICK_MS = 50L                    // 20Hz tích phân
        const val EMA_ALPHA = 0.25
        const val FLOW_STALE_MS = 600L
        const val STILL_FLOW_PX_PER_SEC = 6.0      // dưới mức này coi như đứng yên (ZUPT)
        const val MAX_WALK_SPEED_MPS = 3.0         // chặn drift IMU bùng nổ
        const val APPEND_DISTANCE_M = 0.05         // 5cm mới thêm điểm vào path
        const val STOP_SPEED_MPS = 0.12
        const val STOP_HOLD_MS = 1500L             // đứng yên 1.5s => điểm dừng
        const val MIN_MOVE_FOR_STOP_M = 0.6        // phải đi >=0.6m mới tính stop tiếp theo
        const val FLOW_SENSITIVITY = 100
        const val FEATURE_UPDATE_INTERVAL = 30
    }
}
