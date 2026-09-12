package com.indoorpulse.domain.usecase

import com.indoorpulse.domain.model.Coordinate
import kotlin.math.*

sealed class GpsLeashResult {
    data class WithinBounds(val coordinate: Coordinate) : GpsLeashResult()
    data class DriftDetected(val clampedCoordinate: Coordinate) : GpsLeashResult()
}

class ApplyGpsLeashUseCase {

    operator fun invoke(
        userCoordinate: Coordinate,
        originLat: Double,
        originLng: Double,
        boundingRadiusMeters: Float
    ): GpsLeashResult {
        
        val distance = if (userCoordinate.lat != null && userCoordinate.lng != null) {
            haversineDistance(originLat, originLng, userCoordinate.lat, userCoordinate.lng)
        } else {
            sqrt((userCoordinate.x * userCoordinate.x) + (userCoordinate.y * userCoordinate.y))
        }

        return if (distance <= boundingRadiusMeters) {
            GpsLeashResult.WithinBounds(userCoordinate)
        } else {
            val clamped = if (userCoordinate.lat != null && userCoordinate.lng != null) {
                userCoordinate
            } else {
                val ratio = boundingRadiusMeters / distance
                userCoordinate.copy(x = userCoordinate.x * ratio, y = userCoordinate.y * ratio)
            }
            GpsLeashResult.DriftDetected(clamped)
        }
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = 6371e3 // Earth's radius in meters
        val phi1 = lat1 * PI / 180
        val phi2 = lat2 * PI / 180
        val deltaPhi = (lat2 - lat1) * PI / 180
        val deltaLambda = (lon2 - lon1) * PI / 180

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (r * c).toFloat()
    }
}
