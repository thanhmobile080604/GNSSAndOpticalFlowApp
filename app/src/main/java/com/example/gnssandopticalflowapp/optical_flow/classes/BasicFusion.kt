package com.example.gnssandopticalflowapp.optical_flow.classes

import com.example.gnssandopticalflowapp.optical_flow.inter.SensorFusion
import org.opencv.core.Point

class BasicFusion : SensorFusion {
    override fun getPosition(
        imu_velocity: FloatArray,
        imu_position: FloatArray,
        of_position: Point
    ): FloatArray {
        return FloatArray(3)
    }
}
