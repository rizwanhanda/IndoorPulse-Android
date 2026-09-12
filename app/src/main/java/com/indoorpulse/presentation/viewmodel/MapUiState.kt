package com.indoorpulse.presentation.viewmodel

import com.indoorpulse.domain.model.Coordinate

data class MapUiState(
    val userPosition: Coordinate = Coordinate(0f, 0f),
    val headingDegrees: Float = 0f,
    val selectedDestinationNodeId: String? = null,
    val activeRoute: List<Coordinate> = emptyList(),
    val statusMessage: String = "Ready"
)
