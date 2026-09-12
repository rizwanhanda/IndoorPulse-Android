package com.indoorpulse.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indoorpulse.data.sensor.SensorDataSource
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.IndoorMap
import com.indoorpulse.domain.usecase.ApplyGpsLeashUseCase
import com.indoorpulse.domain.usecase.FindShortestPathUseCase
import com.indoorpulse.domain.usecase.GpsLeashResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MapViewModel(
    private val sensorDataSource: SensorDataSource,
    private val findShortestPathUseCase: FindShortestPathUseCase,
    private val applyGpsLeashUseCase: ApplyGpsLeashUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val stepLengthMeters = 0.7f

    private var smoothAzimuth = 0f

    // History for step debouncing and spike rejection
    private var lastStepTimestampMillis = 0L
    private val orientationHistory = mutableListOf<Pair<Long, Float>>()
    private var lastResetTimestamp = 0L

    init {
        observeSensors()
    }

    private fun shortestAngleDiff(target: Float, current: Float): Float {
        var diff = (target - current) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun observeSensors() {
        viewModelScope.launch {
            sensorDataSource.orientationEvents.collect { orientation ->
                val newAzimuth = orientation.azimuthDegrees
                val diff = shortestAngleDiff(newAzimuth, smoothAzimuth)
                
                val dynamicAlpha = if (abs(diff) > 25f) 0.55f else 0.2f
                smoothAzimuth = (smoothAzimuth + dynamicAlpha * diff + 360f) % 360f

                val timestamp = android.os.SystemClock.elapsedRealtime()
                orientationHistory.add(timestamp to smoothAzimuth)
                orientationHistory.removeAll { timestamp - it.first > 500 } // Keep last 500ms

                _uiState.update { it.copy(headingDegrees = smoothAzimuth) }
            }
        }

        viewModelScope.launch {
            sensorDataSource.stepEvents.collect { stepEvent ->
                val currentTimeMillis = android.os.SystemClock.elapsedRealtime()

                if (currentTimeMillis - lastResetTimestamp < 300) return@collect

                // Step Debouncing
                if (currentTimeMillis - lastStepTimestampMillis < 260) return@collect

                lastStepTimestampMillis = currentTimeMillis

                val currentHeading = _uiState.value.headingDegrees
                val currentPos = _uiState.value.userPosition
                val map = _uiState.value.indoorMap
                
                val rad = Math.toRadians(currentHeading.toDouble())
                val dx = (stepLengthMeters * stepEvent.stepCountIncrement * sin(rad)).toFloat()
                val dy = (stepLengthMeters * stepEvent.stepCountIncrement * cos(rad)).toFloat()

                val candidatePos = currentPos.copy(
                    x = currentPos.x + dx,
                    y = currentPos.y + dy
                )

                val snappedPos = snapToNearestCorridor(candidatePos, currentHeading, map)

                _uiState.update { it.copy(userPosition = snappedPos) }
            }
        }

        viewModelScope.launch {
            sensorDataSource.gpsLocations.collect { gps ->
                val map = _uiState.value.indoorMap
                val updatedPos = _uiState.value.userPosition.copy(lat = gps.latitude, lng = gps.longitude)
                
                val leashResult = applyGpsLeashUseCase(
                    userCoordinate = updatedPos,
                    originLat = map.originLat,
                    originLng = map.originLng,
                    boundingRadiusMeters = map.boundingRadiusMeters
                )

                when (leashResult) {
                    is GpsLeashResult.WithinBounds -> {
                        _uiState.update { 
                            it.copy(
                                userPosition = leashResult.coordinate,
                                statusMessage = "GPS: Within bounds"
                            )
                        }
                    }
                    is GpsLeashResult.DriftDetected -> {
                        _uiState.update { 
                            it.copy(
                                statusMessage = "GPS Drift Detected! Outside bounding radius."
                            )
                        }
                    }
                }
            }
        }
    }

    private fun snapToNearestCorridor(
        candidatePos: Coordinate, 
        currentHeading: Float, 
        map: IndoorMap
    ): Coordinate {
        var closestDistSq = Float.MAX_VALUE
        var bestProjX = candidatePos.x
        var bestProjY = candidatePos.y
        var bestEdgeBearing = 0f

        for (edge in map.edges) {
            val n1 = map.nodes[edge.fromNodeId] ?: continue
            val n2 = map.nodes[edge.toNodeId] ?: continue
            val p1 = n1.coordinate
            val p2 = n2.coordinate

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val l2 = dx * dx + dy * dy
            if (l2 == 0f) continue

            val t = ((candidatePos.x - p1.x) * dx + (candidatePos.y - p1.y) * dy) / l2
            val tClamped = t.coerceIn(0f, 1f)
            
            val projX = p1.x + tClamped * dx
            val projY = p1.y + tClamped * dy

            val distSq = (candidatePos.x - projX) * (candidatePos.x - projX) + (candidatePos.y - projY) * (candidatePos.y - projY)
            if (distSq < closestDistSq) {
                closestDistSq = distSq
                bestProjX = projX
                bestProjY = projY
                
                bestEdgeBearing = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
            }
        }

        val dist = kotlin.math.sqrt(closestDistSq.toDouble()).toFloat()
        
        val diffForward = abs(shortestAngleDiff(currentHeading, bestEdgeBearing))
        val diffReverse = abs(shortestAngleDiff(currentHeading, bestEdgeBearing + 180f))
        
        val isAligned = diffForward <= 35f || diffReverse <= 35f

        if (dist <= 1.2f && isAligned) {
            val pullFactor = 0.25f
            return candidatePos.copy(
                x = candidatePos.x + (bestProjX - candidatePos.x) * pullFactor,
                y = candidatePos.y + (bestProjY - candidatePos.y) * pullFactor
            )
        }

        return candidatePos
    }

    fun selectDestination(nodeId: String) {
        val map = _uiState.value.indoorMap
        
        val startNode = map.nodes.values.minByOrNull { node ->
            val dx = node.coordinate.x - _uiState.value.userPosition.x
            val dy = node.coordinate.y - _uiState.value.userPosition.y
            (dx * dx) + (dy * dy)
        }

        if (startNode != null) {
            val route = findShortestPathUseCase(map, startNode.id, nodeId)
            _uiState.update { 
                it.copy(
                    selectedDestinationNodeId = nodeId,
                    activeRoute = route,
                    statusMessage = if (route.isNotEmpty()) "Navigating to ${map.nodes[nodeId]?.label}" else "No route found"
                )
            }
        }
    }

    fun resetToNode(nodeId: String) {
        val node = _uiState.value.indoorMap.nodes[nodeId]
        if (node != null) {
            lastResetTimestamp = android.os.SystemClock.elapsedRealtime()
            _uiState.update {
                it.copy(
                    userPosition = node.coordinate,
                    statusMessage = "Reset position to ${node.label}",
                    activeRoute = emptyList(),
                    selectedDestinationNodeId = null
                )
            }
        }
    }
}
