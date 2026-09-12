package com.indoorpulse.presentation.viewmodel

import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.IndoorMap
import com.indoorpulse.data.mock.SampleHostelMap

data class MapUiState(
    val indoorMap: IndoorMap = SampleHostelMap,
    val userPosition: Coordinate = SampleHostelMap.nodes["entrance"]?.coordinate ?: Coordinate(0f, 0f),
    val headingDegrees: Float = 0f,
    val selectedDestinationNodeId: String? = null,
    val activeRoute: List<Coordinate> = emptyList(),
    val statusMessage: String = "Ready"
)
