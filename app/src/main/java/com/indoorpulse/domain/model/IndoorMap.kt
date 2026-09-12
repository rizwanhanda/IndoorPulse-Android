package com.indoorpulse.domain.model

data class IndoorMap(
    val buildingId: String,
    val buildingName: String,
    val nodes: Map<String, Node>,
    val edges: List<Edge>,
    val originLat: Double,
    val originLng: Double,
    val boundingRadiusMeters: Float
)
