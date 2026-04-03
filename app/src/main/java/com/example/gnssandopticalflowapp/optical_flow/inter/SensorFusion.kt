package com.example.gnssandopticalflowapp.optical_flow.inter

import org.opencv.core.Point

interface SensorFusion {
    /**
     * This method perform Sensor fusion between the IMU and the Optical Flow sensor.
     * Eventually, it outputs an estimated position base on each sensor output.
     * @param imuVelocity
     * @param imuPosition
     * @param ofPosition
     * @return
     */
    fun getPosition(imuVelocity: FloatArray, imuPosition: FloatArray, ofPosition: Point): FloatArray
}
