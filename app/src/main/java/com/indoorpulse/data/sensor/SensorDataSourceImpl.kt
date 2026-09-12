package com.indoorpulse.data.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class SensorDataSourceImpl(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) : SensorDataSource {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override val stepEvents: Flow<StepEvent> = callbackFlow {
        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        
        var lastStepTime = 0L
        val stepThreshold = 2.5f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    
                    val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    val currentTime = System.currentTimeMillis()
                    
                    if (magnitude > stepThreshold && (currentTime - lastStepTime) > 300) {
                        lastStepTime = currentTime
                        trySend(StepEvent(event.timestamp, 1))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (linearAccel != null) {
            sensorManager.registerListener(listener, linearAccel, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }.conflate()

    override val orientationEvents: Flow<OrientationEvent> = callbackFlow {
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    
                    // 1. Initial pitch/roll detection for posture classification
                    val initialAngles = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, initialAngles)
                    val pitchDeg = Math.toDegrees(initialAngles[1].toDouble()).toFloat()
                    val rollDeg = Math.toDegrees(initialAngles[2].toDouble()).toFloat()

                    val remappedMatrix = FloatArray(9)
                    
                    // 2. Posture-Aware Axis Remapping
                    if (kotlin.math.abs(pitchDeg) < 45f && kotlin.math.abs(rollDeg) < 45f) {
                        // HORIZONTAL_FLAT: Screen facing up (tray holding)
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_X,
                            SensorManager.AXIS_Y,
                            remappedMatrix
                        )
                    } else if (kotlin.math.abs(rollDeg) > 45f && kotlin.math.abs(rollDeg) < 135f) {
                        // LANDSCAPE: Phone held sideways
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_Y,
                            SensorManager.AXIS_MINUS_X,
                            remappedMatrix
                        )
                    } else {
                        // PORTRAIT_UPRIGHT: Screen facing user vertically (e.g. texting/AR)
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_X,
                            SensorManager.AXIS_Z,
                            remappedMatrix
                        )
                    }
                    
                    val orientationAngles = FloatArray(3)
                    SensorManager.getOrientation(remappedMatrix, orientationAngles)

                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

                    trySend(OrientationEvent(azimuth, pitch, roll))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }.conflate()

    @SuppressLint("MissingPermission")
    override val gpsLocations: Flow<GpsLocation> = callbackFlow {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    trySend(GpsLocation(location.latitude, location.longitude, location.accuracy))
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        awaitClose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override val gravityEvents: Flow<GravityEvent> = callbackFlow {
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                    trySend(GravityEvent(event.values[0], event.values[1], event.values[2]))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (gravitySensor != null) {
            sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }.conflate()
}
