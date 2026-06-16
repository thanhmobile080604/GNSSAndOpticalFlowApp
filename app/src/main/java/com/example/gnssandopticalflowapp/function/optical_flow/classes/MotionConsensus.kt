package com.example.gnssandopticalflowapp.function.optical_flow.classes

import kotlin.math.hypot
import kotlin.math.max

/**
 * Robust dominant-motion estimation used to reject independently-moving objects (e.g. other
 * vehicles, most often the car ahead in the centre of the frame) from an optical-flow field.
 *
 * We take the per-axis median as a robust centre, measure each vector's residual distance to that
 * centre, and keep only the vectors within a robust gate (a multiple of the median residual
 * magnitude — a MAD-like dispersion measure, not the textbook MAD taken about the residuals' own
 * median). The surviving inliers are treated as the background; their median is the motion we
 * trust, and the inlier ratio is a confidence signal (few inliers => a lot of independent motion).
 *
 * ASSUMPTION & LIMITATION: this only works when the static world is the MAJORITY of the vectors.
 * If one large object (a bus/truck right ahead) fills most of the analysed band, the median locks
 * onto IT, the real background becomes the rejected minority, and the object's motion is reported
 * as the trusted speed/heading WITH a high inlier ratio — so confidence is NOT lowered. Monocular
 * flow alone cannot tell which cluster is the world; the inertial/route fusion in the caller is the
 * backstop for that case. Treat this as clean-up for minority moving objects, not a guarantee in
 * heavy stop-and-go following traffic.
 */
object MotionConsensus {
    class Result(
        val centerDx: Double,
        val centerDy: Double,
        val inlierRatio: Double,
        val inlierCount: Int,
        val inliers: BooleanArray
    )

    /**
     * @param gateMultiplier how many robust-scale units of residual still count as an inlier (~2.5).
     * @param absoluteGatePx floor on the gate so very coherent flow (near-zero spread) does not
     *   reject tiny, harmless deviations.
     */
    fun dominantMotion(
        dx: DoubleArray,
        dy: DoubleArray,
        gateMultiplier: Double,
        absoluteGatePx: Double
    ): Result {
        val n = dx.size
        if (n == 0) return Result(0.0, 0.0, 0.0, 0, BooleanArray(0))

        val centerDx0 = medianOf(dx)
        val centerDy0 = medianOf(dy)
        val residuals = DoubleArray(n) { index ->
            hypot(dx[index] - centerDx0, dy[index] - centerDy0)
        }
        val gate = max(absoluteGatePx, gateMultiplier * medianOf(residuals))

        val inliers = BooleanArray(n) { index -> residuals[index] <= gate }
        var inlierCount = 0
        for (isInlier in inliers) {
            if (isInlier) inlierCount++
        }

        // Degenerate guard: if the gate rejected everything keep all points and the raw median.
        if (inlierCount == 0) {
            inliers.fill(true)
            return Result(centerDx0, centerDy0, 1.0, n, inliers)
        }

        val inlierDx = DoubleArray(inlierCount)
        val inlierDy = DoubleArray(inlierCount)
        var cursor = 0
        for (index in 0 until n) {
            if (inliers[index]) {
                inlierDx[cursor] = dx[index]
                inlierDy[cursor] = dy[index]
                cursor++
            }
        }

        return Result(
            centerDx = medianOf(inlierDx),
            centerDy = medianOf(inlierDy),
            inlierRatio = inlierCount.toDouble() / n.toDouble(),
            inlierCount = inlierCount,
            inliers = inliers
        )
    }

    private fun medianOf(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
