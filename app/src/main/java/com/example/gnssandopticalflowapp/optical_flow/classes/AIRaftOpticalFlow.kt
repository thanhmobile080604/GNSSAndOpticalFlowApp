package com.example.gnssandopticalflowapp.optical_flow.classes

import android.content.Context
import android.util.Log
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import com.example.gnssandopticalflowapp.optical_flow.interfaces.FrameProgressAwareOpticalFlow
import com.example.gnssandopticalflowapp.optical_flow.interfaces.OpticalFlow
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AIRaftOpticalFlow(
    context: Context,
    private val statusSink: (String) -> Unit = {}
) : OpticalFlow, FrameProgressAwareOpticalFlow {
    class ModelMissingException : IllegalStateException(
        "RAFT ONNX model is missing from assets. Add optical_flow_estimation_raft_2023aug_int8bq.onnx to app/src/main/assets/models/."
    )

    enum class VisualizationMode {
        VECTORS,
        HEATMAP
    }

    private val appContext = context.applicationContext
    private val previousFrame = Mat()
    private val ofOutput = OFOutput()
    private var net: Net? = null
    private var outputLayerNames: List<String>? = null
    private var currentSensitivity = 50
    private var frameIndex = 0L
    private var drawStep = 30
    private var minMotionMagnitude = 0.40
    private var vectorDirectionSign = -1.0
    private var visualizationMode = VisualizationMode.VECTORS
    private var currentFrameNumber = 0L
    private var totalFrameCount = 0L
    private val vectorColor = Scalar(40.0, 255.0, 255.0, 255.0)
    private val dotRadius = 4
    private val vectorThickness = 4
    private val vectorLengthMultiplier = 3.6
    private val minDisplayVectorLength = 9.0

    private data class FlowStats(
        val avgMotion: Point?,
        val sampleCount: Int,
        val activeVectorCount: Int,
        val avgDx: Double,
        val avgDy: Double,
        val avgMagnitude: Double,
        val confidence: Double
    )

    override fun run(newFrame: Mat): OFOutput {
        val startNanos = System.nanoTime()
        if (newFrame.empty()) {
            Log.w(TAG, "run: received empty frame")
            return buildOutput(newFrame, null, startNanos, emptyStats())
        }

        val inputSizeChanged =
            previousFrame.rows() != newFrame.rows() || previousFrame.cols() != newFrame.cols()
        if (previousFrame.empty() || inputSizeChanged) {
            Log.d(
                TAG,
                "run: priming previous frame, size=${newFrame.cols()}x${newFrame.rows()}, " +
                    "previousEmpty=${previousFrame.empty()}, sizeChanged=$inputSizeChanged"
            )
            newFrame.copyTo(previousFrame)
            return buildOutput(newFrame, null, startNanos, emptyStats())
        }

        Log.d(
            TAG,
            "run: frame=${progressLabel()} internal=$frameIndex input=${newFrame.cols()}x${newFrame.rows()} " +
                "mode=$visualizationMode sensitivity=$currentSensitivity threshold=$minMotionMagnitude"
        )
        val outputs = infer(previousFrame, newFrame)
        val stats = try {
            drawFlow(outputs, newFrame)
        } finally {
            outputs.forEach { it.release() }
        }
        newFrame.copyTo(previousFrame)
        Log.d(
            TAG,
            "run: frame=${progressLabel()} internal=$frameIndex active=${stats.activeVectorCount}/${stats.sampleCount} " +
                "avgDx=${"%.3f".format(stats.avgDx)} avgDy=${"%.3f".format(stats.avgDy)} " +
                "mag=${"%.3f".format(stats.avgMagnitude)} conf=${"%.1f".format(stats.confidence)}"
        )

        return buildOutput(newFrame, stats.avgMotion, startNanos, stats)
    }

    override fun resetMotionVector() {
        previousFrame.release()
    }

    override fun updateFeatures() = Unit

    override fun updateFrameProgress(frameNumber: Long, totalFrames: Long) {
        currentFrameNumber = frameNumber.coerceAtLeast(0L)
        totalFrameCount = totalFrames.coerceAtLeast(0L)
        Log.d(TAG, "updateFrameProgress: frame=${progressLabel()}")
    }

    override fun setSensitivity(value: Int) {
        currentSensitivity = value.coerceIn(0, 100)
        val normalized = currentSensitivity / 100.0
        drawStep = (42 - (normalized * 22.0)).roundToInt().coerceIn(20, 42)
        minMotionMagnitude = (0.75 - (normalized * 0.55)).coerceIn(0.20, 0.75)
        Log.d(
            TAG,
            "setSensitivity: value=$currentSensitivity drawStep=$drawStep minMotionMagnitude=$minMotionMagnitude"
        )
    }

    override fun setMovingMode(isMoving: Boolean) {
        vectorDirectionSign = if (isMoving) 1.0 else -1.0
        Log.d(TAG, "setMovingMode: isMoving=$isMoving vectorDirectionSign=$vectorDirectionSign")
    }

    fun setVisualizationMode(mode: VisualizationMode) {
        visualizationMode = mode
        Log.d(TAG, "setVisualizationMode: mode=$mode")
    }

    fun prepare() {
        Log.d(TAG, "prepare: loading model if needed")
        reportStatus("Loading AI model...")
        ensureNet()
    }

    private fun infer(previous: Mat, current: Mat): List<Mat> {
        val activeNet = ensureNet()
        val prevBgr = Mat()
        val currentBgr = Mat()
        val prevBlob: Mat
        val currentBlob: Mat

        try {
            Log.d(
                TAG,
                "infer: blobFromImage prev=${previous.cols()}x${previous.rows()} current=${current.cols()}x${current.rows()} " +
                    "modelInput=${MODEL_INPUT_WIDTH}x${MODEL_INPUT_HEIGHT}"
            )
            convertToBgr(previous, prevBgr)
            convertToBgr(current, currentBgr)
            prevBlob = Dnn.blobFromImage(
                prevBgr,
                1.0,
                Size(MODEL_INPUT_WIDTH.toDouble(), MODEL_INPUT_HEIGHT.toDouble()),
                Scalar(0.0, 0.0, 0.0),
                true,
                false,
                CvType.CV_32F
            )
            currentBlob = Dnn.blobFromImage(
                currentBgr,
                1.0,
                Size(MODEL_INPUT_WIDTH.toDouble(), MODEL_INPUT_HEIGHT.toDouble()),
                Scalar(0.0, 0.0, 0.0),
                true,
                false,
                CvType.CV_32F
            )
        } finally {
            prevBgr.release()
            currentBgr.release()
        }

        try {
            Log.d(TAG, "infer: setInput names=[$FIRST_INPUT_NAME,$SECOND_INPUT_NAME]")
            activeNet.setInput(prevBlob, FIRST_INPUT_NAME)
            activeNet.setInput(currentBlob, SECOND_INPUT_NAME)
            val outputs = ArrayList<Mat>()
            val layerNames = outputLayerNames ?: activeNet.getUnconnectedOutLayersNames()
            Log.d(TAG, "infer: frame=${progressLabel()} forward outputLayers=$layerNames")
            val forwardStart = System.nanoTime()
            activeNet.forward(outputs, layerNames)
            val forwardMs = (System.nanoTime() - forwardStart) / 1_000_000.0
            Log.d(
                TAG,
                "infer: forward done outputs=${outputs.size} timeMs=${"%.2f".format(forwardMs)} " +
                    outputs.mapIndexed { index, mat -> "[$index]=${matShape(mat)}" }.joinToString(" ")
            )
            return outputs
        } finally {
            prevBlob.release()
            currentBlob.release()
        }
    }

    private fun ensureNet(): Net {
        net?.let { return it }
        val modelPath = ensureModelFile().absolutePath
        Log.i(TAG, "ensureNet: loading ONNX model from $modelPath")
        reportStatus("Loading AI model...")
        val loadStart = System.nanoTime()
        return Dnn.readNet(modelPath).also { loadedNet ->
            loadedNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
            loadedNet.setPreferableTarget(Dnn.DNN_TARGET_CPU)
            outputLayerNames = loadedNet.getUnconnectedOutLayersNames()
            net = loadedNet
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0
            Log.i(TAG, "ensureNet: model loaded in ${"%.2f".format(loadMs)} ms, outputs=$outputLayerNames")
            reportStatus("AI model ready")
        }
    }

    private fun ensureModelFile(): File {
        val assetPath = findBundledModelAsset(appContext) ?: run {
            Log.e(TAG, "ensureModelFile: no supported ONNX model found in assets")
            throw ModelMissingException()
        }
        val fileName = assetPath.substringAfterLast('/')
        val modelsDir = File(appContext.filesDir, MODEL_CACHE_DIR)
        if (!modelsDir.exists()) {
            Log.d(TAG, "ensureModelFile: creating model cache dir=${modelsDir.absolutePath}")
            modelsDir.mkdirs()
        }

        val outputFile = File(modelsDir, fileName)
        if (outputFile.exists() && outputFile.length() > 0L) {
            Log.d(TAG, "ensureModelFile: using cached model=${outputFile.absolutePath}, bytes=${outputFile.length()}")
            return outputFile
        }

        Log.i(TAG, "ensureModelFile: copying asset=$assetPath to ${outputFile.absolutePath}")
        reportStatus("Preparing AI model...")
        appContext.assets.open(assetPath).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "ensureModelFile: copied model bytes=${outputFile.length()}")
        return outputFile
    }

    private fun convertToBgr(source: Mat, target: Mat) {
        when (source.channels()) {
            4 -> Imgproc.cvtColor(source, target, Imgproc.COLOR_RGBA2BGR)
            3 -> Imgproc.cvtColor(source, target, Imgproc.COLOR_RGB2BGR)
            1 -> Imgproc.cvtColor(source, target, Imgproc.COLOR_GRAY2BGR)
            else -> source.copyTo(target)
        }
    }

    private fun drawFlow(outputs: List<Mat>, frame: Mat): FlowStats {
        Log.d(
            TAG,
            "drawFlow: outputs=${outputs.size} " +
                outputs.mapIndexed { index, mat -> "[$index]=${matShape(mat)}" }.joinToString(" ")
        )
        val flowOutput = selectFlowOutput(outputs) ?: return emptyStats()
        val flowShape = resolveFlowShape(flowOutput) ?: return emptyStats()
        Log.d(TAG, "drawFlow: selected=${matShape(flowOutput)} resolved=$flowShape")
        val data = FloatArray((flowOutput.total() * flowOutput.channels()).toInt())
        val startIndex = IntArray(flowOutput.dims()) { 0 }
        flowOutput.get(startIndex, data)

        if (visualizationMode == VisualizationMode.HEATMAP) {
            drawHeatmap(data, flowShape, frame)
        }

        return drawVectorGrid(data, flowShape, frame)
    }

    private fun selectFlowOutput(outputs: List<Mat>): Mat? {
        if (outputs.isEmpty()) {
            Log.w(TAG, "selectFlowOutput: no outputs from network")
            return null
        }

        val bestFlow = outputs
            .mapNotNull { output ->
                val shape = resolveFlowShape(output) ?: return@mapNotNull null
                output to shape
            }
            .maxByOrNull { (_, shape) -> shape.width * shape.height }

        if (bestFlow != null) {
            Log.d(TAG, "selectFlowOutput: using highest-resolution output=${matShape(bestFlow.first)}")
            return bestFlow.first
        }
        Log.w(TAG, "selectFlowOutput: no flow-shaped output found, using last=${matShape(outputs.last())}")
        return outputs.lastOrNull()
    }

    private fun resolveFlowShape(flow: Mat): FlowShape? {
        return when {
            flow.dims() >= 4 && flow.size(1) >= 2 -> {
                FlowShape(
                    width = flow.size(3),
                    height = flow.size(2),
                    layout = FlowLayout.NCHW
                )
            }
            flow.dims() == 3 && flow.size(0) >= 2 -> {
                FlowShape(
                    width = flow.size(2),
                    height = flow.size(1),
                    layout = FlowLayout.CHW
                )
            }
            flow.dims() == 3 && flow.size(2) >= 2 -> {
                FlowShape(
                    width = flow.size(1),
                    height = flow.size(0),
                    layout = FlowLayout.HWC
                )
            }
            flow.dims() == 2 && flow.channels() >= 2 -> {
                FlowShape(
                    width = flow.cols(),
                    height = flow.rows(),
                    layout = FlowLayout.HWC
                )
            }
            else -> null
        }?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun drawVectorGrid(data: FloatArray, shape: FlowShape, frame: Mat): FlowStats {
        val flowCols = shape.width
        val flowRows = shape.height
        val mapCols = frame.cols()
        val mapRows = frame.rows()
        val xScale = mapCols.toDouble() / flowCols.toDouble()
        val yScale = mapRows.toDouble() / flowRows.toDouble()
        val startX = computeCenteredGridStart(mapCols, drawStep)
        val startY = computeCenteredGridStart(mapRows, drawStep)
        val minMotionSquared = minMotionMagnitude * minMotionMagnitude
        var sumX = 0.0
        var sumY = 0.0
        var totalMagnitude = 0.0
        var gridSampleCount = 0
        var activeVectorCount = 0
        var screenY = startY

        while (screenY < mapRows) {
            var screenX = startX
            while (screenX < mapCols) {
                gridSampleCount++
                val flowX = (screenX / xScale).roundToInt().coerceIn(0, flowCols - 1)
                val flowY = (screenY / yScale).roundToInt().coerceIn(0, flowRows - 1)
                val fx = shape.u(data, flowX, flowY) * xScale
                val fy = shape.v(data, flowX, flowY) * yScale
                val magnitudeSquared = (fx * fx) + (fy * fy)
                val magnitude = sqrt(magnitudeSquared)

                if (magnitudeSquared >= minMotionSquared) {
                    if (visualizationMode == VisualizationMode.VECTORS) {
                        val start = Point(screenX.toDouble(), screenY.toDouble())
                        var displayFx = fx * vectorDirectionSign * vectorLengthMultiplier
                        var displayFy = fy * vectorDirectionSign * vectorLengthMultiplier
                        val displayMagnitude = sqrt((displayFx * displayFx) + (displayFy * displayFy))
                        if (displayMagnitude < minDisplayVectorLength && displayMagnitude > 0.0) {
                            val scaleUp = minDisplayVectorLength / displayMagnitude
                            displayFx *= scaleUp
                            displayFy *= scaleUp
                        }
                        Imgproc.line(
                            frame,
                            start,
                            Point(start.x + displayFx, start.y + displayFy),
                            vectorColor,
                            vectorThickness
                        )
                        Imgproc.circle(frame, start, dotRadius, vectorColor, -1)
                    }

                    sumX += fx * vectorDirectionSign
                    sumY += fy * vectorDirectionSign
                    totalMagnitude += magnitude
                    activeVectorCount++
                }

                screenX += drawStep
            }
            screenY += drawStep
        }

        if (activeVectorCount <= 0) {
            Log.d(TAG, "drawVectorGrid: no active vectors, samples=$gridSampleCount")
            return FlowStats(
                avgMotion = null,
                sampleCount = gridSampleCount,
                activeVectorCount = 0,
                avgDx = 0.0,
                avgDy = 0.0,
                avgMagnitude = 0.0,
                confidence = 0.0
            )
        }

        val avgDx = sumX / activeVectorCount.toDouble()
        val avgDy = sumY / activeVectorCount.toDouble()
        val activeRatio = activeVectorCount.toDouble() / gridSampleCount.coerceAtLeast(1).toDouble()
        Log.d(
            TAG,
            "drawVectorGrid: active=$activeVectorCount samples=$gridSampleCount avgDx=$avgDx avgDy=$avgDy " +
                "avgMag=${totalMagnitude / activeVectorCount.toDouble()} activeRatio=$activeRatio"
        )
        return FlowStats(
            avgMotion = Point(avgDx, avgDy),
            sampleCount = gridSampleCount,
            activeVectorCount = activeVectorCount,
            avgDx = avgDx,
            avgDy = avgDy,
            avgMagnitude = totalMagnitude / activeVectorCount.toDouble(),
            confidence = (activeRatio * 100.0).coerceIn(0.0, 100.0)
        )
    }

    private fun drawHeatmap(data: FloatArray, shape: FlowShape, frame: Mat) {
        val magnitudeData = FloatArray(shape.width * shape.height)
        val xScale = frame.cols().toDouble() / shape.width.toDouble()
        val yScale = frame.rows().toDouble() / shape.height.toDouble()
        for (y in 0 until shape.height) {
            for (x in 0 until shape.width) {
                val fx = shape.u(data, x, y) * xScale
                val fy = shape.v(data, x, y) * yScale
                magnitudeData[(y * shape.width) + x] = sqrt((fx * fx) + (fy * fy)).toFloat()
            }
        }

        val magnitude = Mat(shape.height, shape.width, CvType.CV_32F)
        val normalized = Mat()
        val heatmap8u = Mat()
        val heatmapBgr = Mat()
        val heatmapScaledBgr = Mat()
        val heatmap = Mat()
        val maskSmall = Mat()
        val mask = Mat()
        val blended = Mat()

        try {
            magnitude.put(0, 0, magnitudeData)
            Imgproc.GaussianBlur(magnitude, magnitude, Size(9.0, 9.0), 0.0)

            val maxMagnitude = Core.minMaxLoc(magnitude).maxVal
            Log.d(TAG, "drawHeatmap: maxMagnitude=$maxMagnitude threshold=${minMotionMagnitude * HEATMAP_INPUT_THRESHOLD_MULTIPLIER}")
            if (maxMagnitude <= minMotionMagnitude * HEATMAP_INPUT_THRESHOLD_MULTIPLIER) return

            val normalizeMax = maxMagnitude.coerceAtLeast(minMotionMagnitude * HEATMAP_NORMALIZE_MULTIPLIER)
            magnitude.convertTo(normalized, CvType.CV_32F, 1.0 / normalizeMax)
            normalized.convertTo(heatmap8u, CvType.CV_8U, 255.0)
            Imgproc.GaussianBlur(heatmap8u, heatmap8u, Size(15.0, 15.0), 0.0)
            Imgproc.applyColorMap(heatmap8u, heatmapBgr, Imgproc.COLORMAP_TURBO)
            Imgproc.resize(
                heatmapBgr,
                heatmapScaledBgr,
                Size(frame.cols().toDouble(), frame.rows().toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC
            )
            if (frame.channels() == 4) {
                Imgproc.cvtColor(heatmapScaledBgr, heatmap, Imgproc.COLOR_BGR2RGBA)
            } else {
                heatmapScaledBgr.copyTo(heatmap)
            }

            Imgproc.threshold(
                magnitude,
                maskSmall,
                minMotionMagnitude * HEATMAP_MASK_THRESHOLD_MULTIPLIER,
                255.0,
                Imgproc.THRESH_BINARY
            )
            maskSmall.convertTo(maskSmall, CvType.CV_8U)
            Imgproc.resize(
                maskSmall,
                mask,
                Size(frame.cols().toDouble(), frame.rows().toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC
            )
            Imgproc.GaussianBlur(mask, mask, Size(31.0, 31.0), 0.0)
            Imgproc.threshold(mask, mask, 1.0, 255.0, Imgproc.THRESH_BINARY)

            Core.addWeighted(frame, HEATMAP_FRAME_WEIGHT, heatmap, HEATMAP_COLOR_WEIGHT, 0.0, blended)
            blended.copyTo(frame, mask)
        } finally {
            magnitude.release()
            normalized.release()
            heatmap8u.release()
            heatmapBgr.release()
            heatmapScaledBgr.release()
            heatmap.release()
            maskSmall.release()
            mask.release()
            blended.release()
        }
    }

    private fun computeCenteredGridStart(size: Int, step: Int): Int {
        if (size <= step) return size / 2

        val halfStep = step / 2
        val sampleCount = (((size - 1) - halfStep) / step) + 1
        val occupiedSpan = (sampleCount - 1) * step
        return ((size - 1 - occupiedSpan) / 2.0).roundToInt()
    }

    private fun emptyStats(): FlowStats {
        return FlowStats(
            avgMotion = null,
            sampleCount = 0,
            activeVectorCount = 0,
            avgDx = 0.0,
            avgDy = 0.0,
            avgMagnitude = 0.0,
            confidence = 0.0
        )
    }

    private fun buildOutput(
        frame: Mat,
        position: Point?,
        startNanos: Long,
        stats: FlowStats
    ): OFOutput {
        val processTimeMs = ((System.nanoTime() - startNanos) / 1_000_000.0).coerceAtLeast(0.001)
        ofOutput.ofFrame = frame
        ofOutput.position = position
        ofOutput.metrics = OpticalFlowMetrics(
            algorithm = "AI RAFT",
            frameIndex = frameIndex++,
            processTimeMs = processTimeMs,
            instantFps = 1000.0 / processTimeMs,
            featureCount = stats.sampleCount,
            activeVectorCount = stats.activeVectorCount,
            avgDx = stats.avgDx,
            avgDy = stats.avgDy,
            avgMagnitude = stats.avgMagnitude,
            confidence = stats.confidence,
            threshold = minMotionMagnitude,
            sensitivity = currentSensitivity
        )
        Log.d(
            TAG,
            "buildOutput: frame=${progressLabel()} internal=${frameIndex - 1} processTimeMs=${"%.2f".format(processTimeMs)} " +
                "fps=${"%.2f".format(1000.0 / processTimeMs)} position=$position"
        )
        return ofOutput
    }

    private fun progressLabel(): String {
        val total = totalFrameCount.takeIf { it > 0L }?.toString() ?: "?"
        val current = currentFrameNumber.takeIf { it > 0L }?.toString() ?: "?"
        return "$current / $total"
    }

    private fun reportStatus(message: String) {
        Log.d(TAG, "status: $message")
        statusSink(message)
    }

    private fun matShape(mat: Mat): String {
        if (mat.empty()) return "empty"
        val dims = (0 until mat.dims()).joinToString("x") { mat.size(it).toString() }
        return "dims=${mat.dims()} size=$dims channels=${mat.channels()} type=${mat.type()} total=${mat.total()}"
    }

    private enum class FlowLayout {
        NCHW,
        CHW,
        HWC
    }

    private data class FlowShape(
        val width: Int,
        val height: Int,
        val layout: FlowLayout
    ) {
        private val planeSize = width * height

        fun u(data: FloatArray, x: Int, y: Int): Double {
            return when (layout) {
                FlowLayout.NCHW, FlowLayout.CHW -> data[(y * width) + x].toDouble()
                FlowLayout.HWC -> data[((y * width) + x) * 2].toDouble()
            }
        }

        fun v(data: FloatArray, x: Int, y: Int): Double {
            return when (layout) {
                FlowLayout.NCHW, FlowLayout.CHW -> data[planeSize + (y * width) + x].toDouble()
                FlowLayout.HWC -> data[(((y * width) + x) * 2) + 1].toDouble()
            }
        }
    }

    companion object {
        private const val TAG = "AI-RAFT"
        private const val MODEL_CACHE_DIR = "models"
        private const val MODEL_INPUT_WIDTH = 480
        private const val MODEL_INPUT_HEIGHT = 360
        private const val FIRST_INPUT_NAME = "0"
        private const val SECOND_INPUT_NAME = "1"
        private const val HEATMAP_FRAME_WEIGHT = 0.58
        private const val HEATMAP_COLOR_WEIGHT = 0.70
        private const val HEATMAP_NORMALIZE_MULTIPLIER = 9.0
        private const val HEATMAP_INPUT_THRESHOLD_MULTIPLIER = 0.40
        private const val HEATMAP_MASK_THRESHOLD_MULTIPLIER = 0.32
        private val MODEL_ASSET_CANDIDATES = listOf(
            "models/optical_flow_estimation_raft_2023aug_int8bq.onnx",
            "models/optical_flow_estimation_raft_2023aug.onnx",
            "optical_flow_estimation_raft_2023aug_int8bq.onnx",
            "optical_flow_estimation_raft_2023aug.onnx"
        )

        fun isModelAvailable(context: Context): Boolean {
            val modelAsset = findBundledModelAsset(context.applicationContext)
            Log.d(TAG, "isModelAvailable: asset=$modelAsset")
            return modelAsset != null
        }

        private fun findBundledModelAsset(context: Context): String? {
            for (assetPath in MODEL_ASSET_CANDIDATES) {
                try {
                    context.assets.open(assetPath).use {
                        Log.d(TAG, "findBundledModelAsset: found $assetPath")
                        return assetPath
                    }
                } catch (_: FileNotFoundException) {
                    Log.d(TAG, "findBundledModelAsset: missing $assetPath")
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to probe model asset $assetPath: ${e.message}")
                }
            }
            return null
        }
    }
}
