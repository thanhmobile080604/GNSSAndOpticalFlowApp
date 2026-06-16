package com.example.gnssandopticalflowapp.function.optical_flow.interfaces

import com.example.gnssandopticalflowapp.model.OFOutput
import org.opencv.core.Mat

interface OpticalFlow {
    fun run(newFrame: Mat): OFOutput?
    fun resetMotionVector()
    fun updateFeatures()
    fun setSensitivity(value: Int)
    fun setMovingMode(isMoving: Boolean)

    /**
     * Restricts which vertical band of the frame feeds the motion metrics (and the drawn vectors),
     * expressed as fractions of the frame height. Live routing uses this to drop the sky / distant
     * vanishing-point region near the top, where flow is near-zero or misleading and would only
     * dilute the speed estimate. [topFraction]/[bottomFraction] are clamped to [0,1]; the default
     * full-frame band is (0.0, 1.0). An invalid band (bottom <= top) resets to full frame.
     */
    fun setMetricsRegion(topFraction: Double, bottomFraction: Double)

    /**
     * When enabled, rejects independently-moving objects (e.g. other vehicles, typically the car
     * ahead in the centre) before measuring motion: only vectors that agree with the dominant
     * background motion feed the speed/heading metrics, and frames with a lot of disagreeing
     * motion report lower confidence. Default disabled so non-routing callers are unchanged.
     */
    fun setRejectMovingObjects(enabled: Boolean)
}
