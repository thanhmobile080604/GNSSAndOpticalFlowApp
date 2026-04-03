package com.example.gnssandopticalflowapp.optical_flow.classes

import com.example.gnssandopticalflowapp.optical_flow.inter.SensorFusion
import org.opencv.core.Point

class BasicFusion : SensorFusion {
    override fun getPosition(
        imuVelocity: FloatArray,
        imuPosition: FloatArray,
        ofPosition: Point
    ): FloatArray {
        return FloatArray(3)
    }
}
