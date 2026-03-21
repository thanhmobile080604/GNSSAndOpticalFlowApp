package com.example.gnssandopticalflowapp.velocity_estimator.inter

import org.opencv.core.Point

interface SensorFusion {
    /**
     * This method perform Sensor fusion between the IMU and the Optical Flow sensor.
     * Eventually, it outputs an estimated position base on each sensor output.
     * @param imu_velocity
     * @param imu_position
     * @param of_position
     * @return
     */
    fun getPosition(imu_velocity: FloatArray, imu_position: FloatArray, of_position: Point): FloatArray
}
