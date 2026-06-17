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
    // Get a reference to the SensorManager
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Get references to the accelerometer and gyroscope sensors
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    // Fused gravity (gyro + accelerometer). Unlike a raw-accelerometer low-pass, its vertical axis is
    // NOT tilted by braking / centripetal force, so the yaw projected onto it stays accurate while the
    // bike leans through a corner. Falls back to the low-pass below when the device lacks this sensor.
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)


    private var gravity = FloatArray(3)
    private var magnitude = FloatArray(3)
    private val linearAcceleration = FloatArray(3)
    private var rotationVector = FloatArray(3)
    private var angularVelocity = FloatArray(3)
    // Learned gyro zero-rate bias (rad/s, device frame). Subtracted in getYawRate so a constant
    // offset does not integrate into heading drift; (re)learned only while stationary.
    private val gyroBias = FloatArray(3)
    private val velocity = FloatArray(3)
    private val position = FloatArray(3)
    private var gravityInitialized = false
    private var hasHardwareGravity = false
    private var lastUpdateTime: Long

    // init binary Semaphore
    private val semaphore: Semaphore = Semaphore(1)

    init {

        // Initialize the last update time
        lastUpdateTime = System.currentTimeMillis()
    }

    fun register() {
        // Register this class as a listener for the sensors. Prefer the fused gravity sensor; the
        // accelerometer low-pass is only used as a fallback when TYPE_GRAVITY is unavailable.
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun unregister() {
        // Unregister this class as a listener for the sensors
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Calculate the time elapsed since the last update
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastUpdateTime) / 1000.0f

        // Update the last update time
        lastUpdateTime = currentTime

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                // Lean-compensated vertical axis straight from the fused sensor.
                gravity = event.values.clone()
                gravityInitialized = true
                hasHardwareGravity = true
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasHardwareGravity) {
                    // Fallback gravity estimate: low-pass the raw accelerometer (tilts under
                    // sustained acceleration, but better than nothing on devices without TYPE_GRAVITY).
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
                // Save the rotation vector and angular velocity
                rotationVector = event.values.clone()
                angularVelocity = event.values.clone()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> magnitude = event.values.clone()
        }

        // Apply a low-pass filter to the velocity estimate to reduce noise
        velocity[0] = 0.8f * velocity[0] + 0.2f * angularVelocity[0]
        velocity[1] = 0.8f * velocity[1] + 0.2f * angularVelocity[1]
        velocity[2] = 0.8f * velocity[2] + 0.2f * angularVelocity[2]

        // orientation
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnitude)
        // Express the updated rotation matrix as three orientation angles.
        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        convertToDegrees(orientationAngles)

        // Use the velocity estimate to update the position
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
        // Do nothing
    }

    fun getVelocity(): FloatArray {
        // Return the current velocity estimate
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

    /**
     * Gravity-removed linear acceleration projected onto the horizontal (ground) plane,
     * expressed in the device coordinate frame (m/s^2).
     *
     * Only gravity (which is estimated reliably from the accelerometer low-pass) is used,
     * so this is robust even when the magnetometer is disturbed inside a vehicle. The result
     * still lives in the device frame: the caller is responsible for resolving which horizontal
     * direction is "vehicle forward" (e.g. by learning it online against GNSS).
     */
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

    /**
     * Folds the latest raw gyro sample into the zero-rate bias estimate (slow EWMA). Call ONLY when
     * the vehicle is known to be stationary: the true rotation is then zero, so whatever the gyro
     * reports is its bias. getYawRate() subtracts this, removing the main source of heading drift
     * during a GNSS outage — every stop (e.g. a red light) becomes a free re-calibration.
     */
    fun learnGyroBias() {
        gyroBias[0] = (1f - GYRO_BIAS_EWMA_ALPHA) * gyroBias[0] + GYRO_BIAS_EWMA_ALPHA * angularVelocity[0]
        gyroBias[1] = (1f - GYRO_BIAS_EWMA_ALPHA) * gyroBias[1] + GYRO_BIAS_EWMA_ALPHA * angularVelocity[1]
        gyroBias[2] = (1f - GYRO_BIAS_EWMA_ALPHA) * gyroBias[2] + GYRO_BIAS_EWMA_ALPHA * angularVelocity[2]
    }

    fun getYawRate(): Float {
        // Project angular velocity onto gravity vector to get yaw rate around Earth's Z axis (UP/DOWN)
        val gMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()).toFloat()
        if (gMag < 0.1f) return 0f

        // Remove the learned zero-rate bias before projecting, so a constant gyro offset does not
        // integrate into heading drift while GNSS is lost.
        val wx = angularVelocity[0] - gyroBias[0]
        val wy = angularVelocity[1] - gyroBias[1]
        val wz = angularVelocity[2] - gyroBias[2]
        // angularVelocity dot gravity.
        // Tùy thuộc vào hệ tọa độ của Canvas (thường Y hướng xuống dưới),
        // dấu của góc xoay cần đảo ngược để xoay phải vẽ sang phải.
        val yawRateRadSec = -(wx * gravity[0] + wy * gravity[1] + wz * gravity[2]) / gMag
        return Math.toDegrees(yawRateRadSec.toDouble()).toFloat()
    }

    fun getPosition(): FloatArray {
        // Return the current position estimate
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
        // Unregister this class as a listener for the sensors
        unregister()
    }

    private companion object {
        // Slow EWMA so a few stationary samples nudge the bias without a single noisy frame dominating.
        const val GYRO_BIAS_EWMA_ALPHA = 0.05f
    }
}
