package com.indoorpulse.data.sensor

data class StepEvent(
    val timestampNano: Long,
    val stepCountIncrement: Int = 1
)

data class OrientationEvent(
    val azimuthDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float
)

data class GpsLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

data class GravityEvent(
    val gx: Float,
    val gy: Float,
    val gz: Float
)
