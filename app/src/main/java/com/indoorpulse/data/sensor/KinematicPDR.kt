package com.indoorpulse.data.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class KinematicPDR : SensorEventListener {
    private val gravity = FloatArray(3)
    private var hasGravity = false
    
    // Schmitt Trigger State Machine
    private var smoothedAccel = 0f
    private var isArmedForStep = false
    private var currentPeak = 0f
    private var lastStepTime = 0L
    
    // --- THE GOLDEN CONSTANTS ---
    private val EMA_ALPHA = 0.12f   // Stricter low-pass to kill hand vibration
    private val HIGH_THRES = 0.35f  // MUST bounce UP this hard (heel strike)
    private val LOW_THRES = -0.35f  // MUST drop DOWN this hard (foot lift)
    private val WEINBERG_K = 0.55f 
    
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
                currentHeading = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!hasGravity) return
                val gMag = Math.sqrt((gravity[0]*gravity[0] + gravity[1]*gravity[1] + gravity[2]*gravity[2]).toDouble()).toFloat()
                if (gMag == 0f) return
                
                val rawVertical = (event.values[0]*gravity[0] + event.values[1]*gravity[1] + event.values[2]*gravity[2]) / gMag
                smoothedAccel = EMA_ALPHA * rawVertical + (1.0f - EMA_ALPHA) * smoothedAccel

                // 1. ARM THE TRIGGER (Detect upward heel strike)
                if (smoothedAccel > HIGH_THRES) {
                    isArmedForStep = true
                    if (smoothedAccel > currentPeak) currentPeak = smoothedAccel
                }

                // 2. FIRE THE STEP (Detect downward foot lift completion)
                if (isArmedForStep && smoothedAccel < LOW_THRES) {
                    val currentTime = System.currentTimeMillis()
                    val stepTime = currentTime - lastStepTime
                    
                    // Reject impossible human rhythms (e.g. picking phone off a desk)
                    if (stepTime in 250..1500) {
                        val bounce = currentPeak - LOW_THRES
                        val stepFrequency = 1000f / stepTime.toFloat()
                        
                        // 1. Weinberg Model (Measures the physical height of your bounce)
                        val bounceStride = WEINBERG_K * Math.pow(bounce.toDouble(), 0.25).toFloat()
                        
                        // 2. Frequency Model (Measures your forward momentum/speed)
                        // Average adult takes ~1.5 to 1.8 steps per second.
                        val freqStride = 0.3f + (0.15f * stepFrequency)
                        
                        // 3. Fused Sensor Fusion (60% Bounce, 40% Momentum)
                        // This perfectly balances heavy, slow steps with light, fast steps.
                        val dynamicStride = (bounceStride * 0.6f) + (freqStride * 0.4f)
                        
                        // Hard clamp to strictly enforce physical human limits
                        val finalStride = Math.max(0.4f, Math.min(0.85f, dynamicStride))
                        
                        onStepDetected?.invoke(finalStride, currentHeading)
                    }
                    
                    // Reset machine for the next step
                    lastStepTime = currentTime
                    isArmedForStep = false
                    currentPeak = 0f
                }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
