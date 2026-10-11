package com.indoorpulse.domain.model

data class Coordinate(
    val x: Float,
    val y: Float,
    val floor: Int = 1,
    val lat: Double? = null,
    val lng: Double? = null,
    val type: String = "Stairs"
)

val Coordinate.floorLevel: Int
    get() = floor
