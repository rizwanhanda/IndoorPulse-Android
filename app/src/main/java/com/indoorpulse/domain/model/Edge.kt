package com.indoorpulse.domain.model

data class Edge(
    val fromNodeId: String,
    val toNodeId: String,
    val distanceMeters: Float,
    val isAccessible: Boolean = true
)
