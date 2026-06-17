package com.example.gnssandopticalflowapp.function.optical_flow.classes

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt
import kotlin.math.sqrt

class IMUEstimator(context: Context) : SensorEventListener {
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)


    private var gravity = FloatArray(3)
    private var magnitude = FloatArray(3)
    private val linearAcceleration = FloatArray(3)
    private var rotationVector = FloatArray(3)
    private var angularVelocity = FloatArray(3)
    private val gyroBias = FloatArray(3)
    private val velocity = FloatArray(3)
    private val position = FloatArray(3)
    private var gravityInitialized = false
    private var hasHardwareGravity = false
    private var lastUpdateTime: Long
    private val semaphore: Semaphore = Semaphore(1)

    init {
        lastUpdateTime = System.currentTimeMillis()
    }

    fun register() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastUpdateTime) / 1000.0f

        lastUpdateTime = currentTime

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity = event.values.clone()
                gravityInitialized = true
                hasHardwareGravity = true
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasHardwareGravity) {
                    if (!gravityInitialized) {
                        gravity = event.values.clone()
                        gravityInitialized = true
                    } else {
                        val alpha = 0.8f
                        gravity[0] = alpha * gravity[0] + (1.0f - alpha) * event.values[0]
                        gravity[1] = alpha * gravity[1] + (1.0f - alpha) * event.values[1]
                        gravity[2] = alpha * gravity[2] + (1.0f - alpha) * event.values[2]
                    }
                }

                linearAcceleration[0] = event.values[0] - gravity[0]
                linearAcceleration[1] = event.values[1] - gravity[1]
                linearAcceleration[2] = event.values[2] - gravity[2]

                velocity[0] += linearAcceleration[0] * deltaTime
                velocity[1] += linearAcceleration[1] * deltaTime
                velocity[2] += linearAcceleration[2] * deltaTime
            }

            Sensor.TYPE_GYROSCOPE -> {
                rotationVector = event.values.clone()
                angularVelocity = event.values.clone()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> magnitude = event.values.clone()
        }

        velocity[0] = 0.8f * velocity[0] + 0.2f * angularVelocity[0]
        velocity[1] = 0.8f * velocity[1] + 0.2f * angularVelocity[1]
        velocity[2] = 0.8f * velocity[2] + 0.2f * angularVelocity[2]

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnitude)
        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        convertToDegrees(orientationAngles)

        try {
            semaphore.acquire()
            position[0] += velocity[0] * deltaTime
            position[1] += velocity[1] * deltaTime
            position[2] += velocity[2] * deltaTime
            semaphore.release()
        } catch (e: Exception) {
            Log.e("IMU", "Failed to acquire semaphore")
        }
    }

    private fun convertToDegrees(vector: FloatArray) {
        for (i in vector.indices) {
            vector[i] = Math.toDegrees(vector[i].toDouble()).roundToInt().toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    fun getVelocity(): FloatArray {
        return velocity.clone()
    }

    fun getLinearAcceleration(): FloatArray {
        var output = FloatArray(3)
        try {
            semaphore.acquire()
            output = linearAcceleration.clone()
            semaphore.release()
        } catch (e: Exception) {
            Log.e("IMU", "Failed to acquire semaphore")
        }
        return output
    }

    fun getHorizontalLinearAcceleration(): FloatArray {
        val a = getLinearAcceleration()
        val g = gravity
        val gMagSq = g[0] * g[0] + g[1] * g[1] + g[2] * g[2]
        if (gMagSq < 0.1f) return a
        val projection = (a[0] * g[0] + a[1] * g[1] + a[2] * g[2]) / gMagSq
        return floatArrayOf(
            a[0] - projection * g[0],
            a[1] - projection * g[1],
            a[2] - projection * g[2]
        )
    }

    fun learnGyroBias() {
        gyroBias[0] = (1f - GYRO_BIAS_EWMA_ALPHA) * gyroBias[0] + GYRO_BIAS_EWMA_ALPHA * angularVelocity[0]
        gyroBias[1] = (1f - GYRO_BIAS_EWMA_ALPHA) * gyroBias[1] + GYRO_BIAS_EWMA_ALPHA * angularVelocity[1]
        gyroBias[2] = (1f - GYRO_BIAS_EWMA_ALPHA) * gyroBias[2] + GYRO_BIAS_EWMA_ALPHA * angularVelocity[2]
    }

    fun getYawRate(): Float {
        val gMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()).toFloat()
        if (gMag < 0.1f) return 0f
        val wx = angularVelocity[0] - gyroBias[0]
        val wy = angularVelocity[1] - gyroBias[1]
        val wz = angularVelocity[2] - gyroBias[2]
        val yawRateRadSec = -(wx * gravity[0] + wy * gravity[1] + wz * gravity[2]) / gMag
        return Math.toDegrees(yawRateRadSec.toDouble()).toFloat()
    }

    fun getPosition(): FloatArray {
        var output = FloatArray(3)
        try {
            semaphore.acquire()
            output = position.clone()
            semaphore.release()
        } catch (e: Exception) {
            Log.e("IMU", "Failed to acquire semaphore")
        }
        return output
    }

    fun stop() {
        unregister()
    }

    private companion object {
        const val GYRO_BIAS_EWMA_ALPHA = 0.05f
    }
}
