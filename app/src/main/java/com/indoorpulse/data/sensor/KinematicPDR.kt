package com.indoorpulse.data.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class KinematicPDR : SensorEventListener {
    // ---- PDR STATE ----
    private val gravity = FloatArray(3)
    private var hasGravity = false
    
    // EMA & Adaptive Envelope State
    private var smoothedAccel = 0f
    private var wasAboveZero = true
    private var currentMax = 0f
    private var currentMin = 0f
    private var lastStepTime = 0L
    
    // Scientifically Tuned Constants
    private val EMA_ALPHA = 0.15f // Strips out hand shaking, preserves body bounce
    private val WEINBERG_K = 0.55f 
    private val MIN_STEP_TIME_MS = 280 
    private val BOUNCE_THRESHOLD = 0.5f // Tuned down slightly to capture natural walking
    
    var currentHeading = 0f
    var onStepDetected: ((stepLength: Float, heading: Float) -> Unit)? = null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                hasGravity = true
            }
            
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                
                // Flat-phone azimuth, normalized to 0-360
                var headingDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                headingDeg = (headingDeg + 360f) % 360f
                currentHeading = headingDeg
            }
            
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!hasGravity) return
                
                val gMag = Math.sqrt((gravity[0]*gravity[0] + gravity[1]*gravity[1] + gravity[2]*gravity[2]).toDouble()).toFloat()
                if (gMag == 0f) return
                
                // 1. Isolate raw vertical acceleration (gravity projection)
                val rawVertical = (event.values[0]*gravity[0] + event.values[1]*gravity[1] + event.values[2]*gravity[2]) / gMag

                // 2. EMA Low-Pass Filter (Isolates pure sinusoidal walking wave)
                smoothedAccel = EMA_ALPHA * rawVertical + (1.0f - EMA_ALPHA) * smoothedAccel

                // 3. Track the smooth wave's envelope
                if (smoothedAccel > currentMax) currentMax = smoothedAccel
                if (smoothedAccel < currentMin) currentMin = smoothedAccel

                // 4. Zero-Crossing Detector (Heel Strike)
                if (wasAboveZero && smoothedAccel < 0f) {
                    val bounce = currentMax - currentMin
                    
                    if (bounce > BOUNCE_THRESHOLD) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastStepTime > MIN_STEP_TIME_MS) {
                            lastStepTime = currentTime
                            
                            // Highly stable Weinberg Stride based on filtered wave envelope
                            val dynamicStride = WEINBERG_K * Math.pow(bounce.toDouble(), 0.25).toFloat()
                            val finalStride = Math.max(0.4f, Math.min(1.1f, dynamicStride))
                            
                            onStepDetected?.invoke(finalStride, currentHeading)
                        }
                    }
                    // Reset envelope for the next step wave
                    currentMax = 0f
                    currentMin = 0f
                }
                
                wasAboveZero = smoothedAccel > 0f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
