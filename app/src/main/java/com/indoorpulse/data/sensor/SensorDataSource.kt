package com.indoorpulse.data.sensor

import kotlinx.coroutines.flow.Flow

interface SensorDataSource {
    val stepEvents: Flow<StepEvent>
    val orientationEvents: Flow<OrientationEvent>
    val gpsLocations: Flow<GpsLocation>
    val gravityEvents: Flow<GravityEvent>
}
