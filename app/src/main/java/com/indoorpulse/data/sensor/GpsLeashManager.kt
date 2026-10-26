package com.indoorpulse.data.sensor

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.indoorpulse.data.local.entity.BuildingEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class LocationEvent(
    val matchedBuilding: BuildingEntity?,
    val location: Location
)

class GpsLeashManager(
    private val fusedLocationClient: FusedLocationProviderClient
) {
    @SuppressLint("MissingPermission")
    fun observeGeofences(
        buildings: List<BuildingEntity>,
        intervalMillis: Long = 10000L
    ): Flow<LocationEvent> = callbackFlow {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setIntervalMillis(intervalMillis)
            .setMinUpdateDistanceMeters(5f)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val lastLocation = result.lastLocation ?: return
                val results = FloatArray(1)
                var matchedBuilding: BuildingEntity? = null

                for (building in buildings) {
                    Location.distanceBetween(
                        lastLocation.latitude,
                        lastLocation.longitude,
                        building.centerLat,
                        building.centerLng,
                        results
                    )
                    val distanceMeters = results[0]
                    if (distanceMeters < building.geofenceRadius) {
                        matchedBuilding = building
                        break
                    }
                }

                trySend(LocationEvent(matchedBuilding, lastLocation))
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
}
