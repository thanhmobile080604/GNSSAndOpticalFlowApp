package com.example.opticalflowapp.velocity_estimator.classes

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.Semaphore

class IMUEstimator(context: Context) : SensorEventListener {
    private val sensorManager: SensorManager
    private val accelerometer: Sensor?
    private val gyroscope: Sensor?
    private val magnetometer: Sensor?


    private var gravity = FloatArray(3)
    private var magnitude = FloatArray(3)
    private val linearAcceleration = FloatArray(3)
    private var rotationVector = FloatArray(3)
    private var angularVelocity = FloatArray(3)
    private val velocity = FloatArray(3)
    private val position = FloatArray(3)
    private var lastUpdateTime: Long
    private val semaphore: Semaphore

    init {
        // Get a reference to the SensorManager
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Get references to the accelerometer and gyroscope sensors
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // init binary Semaphore
        semaphore = Semaphore(1)

        // Initialize the last update time
        lastUpdateTime = System.currentTimeMillis()
    }

    fun register() {
        // Register this class as a listener for the sensors
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

        // Handle the sensor data
        when (event.sensor.getType()) {
            Sensor.TYPE_ACCELEROMETER ->                 // Save the gravity vector
                gravity = event.values.clone()

            Sensor.TYPE_GYROSCOPE -> {
                // Save the rotation vector and angular velocity
                rotationVector = event.values.clone()
                angularVelocity = event.values.clone()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> magnitude = event.values.clone()
        }
        // Calculate the linear acceleration by subtracting the gravity vector
        // from the accelerometer readings
        linearAcceleration[0] = event.values[0] - gravity[0]
        linearAcceleration[1] = event.values[1] - gravity[1]
        linearAcceleration[2] = event.values[2] - gravity[2]

        // Integrate the linear acceleration to estimate the velocity
        velocity[0] += linearAcceleration[0] * deltaTime
        velocity[1] += linearAcceleration[1] * deltaTime
        velocity[2] += linearAcceleration[2] * deltaTime

        // Apply a low-pass filter to the velocity estimate to reduce noise
        velocity[0] = 0.8f * velocity[0] + 0.2f * angularVelocity[0]
        velocity[1] = 0.8f * velocity[1] + 0.2f * angularVelocity[1]
        velocity[2] = 0.8f * velocity[2] + 0.2f * angularVelocity[2]

        // orientation
        val RotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrix(RotationMatrix, null, gravity, magnitude)
        // Express the updated rotation matrix as three orientation angles.
        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(RotationMatrix, orientationAngles)
        convertToDegrees(orientationAngles)
        Log.d(
            "ORIENTATION",
            orientationAngles[0].toString() + ", " + orientationAngles[1] + ", " + orientationAngles[2]
        )


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
            vector[i] = Math.round(Math.toDegrees(vector[i].toDouble())).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }

    fun getVelocity(): FloatArray {
        // Return the current velocity estimate
        return velocity.clone()
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
}
