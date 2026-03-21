package com.example.opticalflowapp.velocity_estimator.classes

import com.example.opticalflowapp.velocity_estimator.inter.SensorFusion
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
