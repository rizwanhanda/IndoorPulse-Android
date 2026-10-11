package com.indoorpulse.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indoorpulse.data.sensor.SensorDataSource
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.IndoorMap
import com.indoorpulse.domain.model.Node
import com.indoorpulse.domain.model.Edge
import com.indoorpulse.domain.model.NodeType
import com.indoorpulse.domain.usecase.ApplyGpsLeashUseCase
import com.indoorpulse.domain.usecase.FindShortestPathUseCase
import com.indoorpulse.domain.usecase.GpsLeashResult
import com.indoorpulse.data.local.dao.MapDao
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.FloorEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.domain.navigation.ParticleFilter
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class ViewMode { MAP_2D, AR_CAMERA }

sealed interface SurveyorAction {
    data class DeleteNode(val node: NodeEntity) : SurveyorAction
    data class DropNode(val node: NodeEntity, val edge: EdgeEntity?) : SurveyorAction
    data class SnapEdge(val edge: EdgeEntity) : SurveyorAction
}

class MapViewModel(
    private val sensorManager: android.hardware.SensorManager,
    private val sensorDataSource: SensorDataSource,
    private val findShortestPathUseCase: FindShortestPathUseCase,
    private val applyGpsLeashUseCase: ApplyGpsLeashUseCase,
    private val mapDao: MapDao
) : ViewModel() {

    val currentViewMode = MutableStateFlow(ViewMode.MAP_2D)
    val isAutoArEnabled = MutableStateFlow(true)
    val estimatedPosition = MutableStateFlow(Pair(0f, 0f))
    
    private val _isSurveyorMode = MutableStateFlow(false)
    val isSurveyorMode: StateFlow<Boolean> = _isSurveyorMode.asStateFlow()
    val lastDroppedNodeId = MutableStateFlow<String?>(null)
    val snapTargetNodeId = MutableStateFlow<String?>(null)
    val currentFloor = MutableStateFlow("L1")
    val recenterTrigger = MutableStateFlow(0L)
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()
    val activeDestination = MutableStateFlow<NodeEntity?>(null)
    
    private val _nodes = MutableStateFlow<List<NodeEntity>>(emptyList())
    private val _undoStack = MutableStateFlow<List<SurveyorAction>>(emptyList())
    private val _redoStack = MutableStateFlow<List<SurveyorAction>>(emptyList())

    val canUndo: StateFlow<Boolean> = _undoStack.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val canRedo: StateFlow<Boolean> = _redoStack.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    inner class Repository {
        fun getAllNodesFlow(): Flow<List<NodeEntity>> = _nodes
        suspend fun deleteNodeAndEdges(nodeId: String) {
            mapDao.deleteEdgesForNode(nodeId)
            mapDao.deleteNode(nodeId)
        }
    }
    val repository = Repository()

    fun stageNodeForDeletion(node: NodeEntity) {
        // Pushing a new deletion clears the redo future
        _undoStack.value = _undoStack.value + SurveyorAction.DeleteNode(node)
        _redoStack.value = emptyList()
        if (lastDroppedNodeId.value == node.nodeId) {
            lastDroppedNodeId.value = null
        }
        if (snapTargetNodeId.value == node.nodeId) {
            snapTargetNodeId.value = null
        }
    }

    fun performUndo() {
        val stack = _undoStack.value
        if (stack.isEmpty()) return
        val action = stack.last()
        _undoStack.value = stack.dropLast(1)
        _redoStack.value = _redoStack.value + action

        viewModelScope.launch(Dispatchers.IO) {
            when (action) {
                is SurveyorAction.DeleteNode -> {
                    // Restored to activeNodes automatically because it's no longer in DeleteNode actions
                }
                is SurveyorAction.DropNode -> {
                    try {
                        mapDao.deleteNode(action.node.nodeId)
                        if (action.edge != null) {
                            mapDao.deleteEdge(action.edge.edgeId)
                        }
                        droppedNodesChain.remove(action.node.nodeId)
                        lastDroppedNodeId.value = droppedNodesChain.lastOrNull()
                        refreshCache()
                    } catch (e: Exception) {
                        _uiState.update { it.copy(statusMessage = "Undo drop failed: ${e.message}") }
                    }
                }
                is SurveyorAction.SnapEdge -> {
                    try {
                        mapDao.deleteEdge(action.edge.edgeId)
                        droppedNodesChain.remove(action.edge.toNodeId)
                        lastDroppedNodeId.value = action.edge.fromNodeId
                        refreshCache()
                    } catch (e: Exception) {
                        _uiState.update { it.copy(statusMessage = "Undo snap failed: ${e.message}") }
                    }
                }
            }
        }
    }

    fun performRedo() {
        val stack = _redoStack.value
        if (stack.isEmpty()) return
        val action = stack.last()
        _redoStack.value = stack.dropLast(1)
        _undoStack.value = _undoStack.value + action

        viewModelScope.launch(Dispatchers.IO) {
            when (action) {
                is SurveyorAction.DeleteNode -> {
                    // Staged deletion reapplied automatically because it's back in _undoStack
                }
                is SurveyorAction.DropNode -> {
                    try {
                        mapDao.insertNodes(listOf(action.node))
                        if (action.edge != null) {
                            mapDao.insertEdges(listOf(action.edge))
                        }
                        droppedNodesChain.add(action.node.nodeId)
                        lastDroppedNodeId.value = action.node.nodeId
                        refreshCache()
                    } catch (e: Exception) {
                        _uiState.update { it.copy(statusMessage = "Redo drop failed: ${e.message}") }
                    }
                }
                is SurveyorAction.SnapEdge -> {
                    try {
                        mapDao.insertEdges(listOf(action.edge))
                        droppedNodesChain.add(action.edge.toNodeId)
                        lastDroppedNodeId.value = action.edge.toNodeId
                        refreshCache()
                    } catch (e: Exception) {
                        _uiState.update { it.copy(statusMessage = "Redo snap failed: ${e.message}") }
                    }
                }
            }
        }
    }

    fun undoDeletion(node: NodeEntity) {
        performUndo()
    }

    fun undoLastNode() {
        performUndo()
    }

    // Ensure activeNodes filters out anything currently deleted in the undoStack
    val activeNodes: StateFlow<List<NodeEntity>> = repository.getAllNodesFlow().combine(_undoStack) { dbNodes, actions ->
        val stagedDeletedNodes = actions.filterIsInstance<SurveyorAction.DeleteNode>().map { it.node }.toSet()
        dbNodes.filterNot { it in stagedDeletedNodes }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val nodes: StateFlow<List<NodeEntity>> = activeNodes
    val allNodes: StateFlow<List<NodeEntity>> = activeNodes
    
    private val _edges = MutableStateFlow<List<EdgeEntity>>(emptyList())
    val edges: StateFlow<List<EdgeEntity>> = _edges.asStateFlow()
    
    private val droppedNodesChain = mutableListOf<String>()
    private val actionHistory = java.util.Stack<String>()
    private val particleFilter = ParticleFilter()
    
    private var cachedEdges: List<EdgeEntity> = emptyList()
    private var cachedNodes: Map<String, NodeEntity> = emptyMap()

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val stepLengthMeters = 0.7f

    private var smoothAzimuth = 0f

    private val kinematicPDR = com.indoorpulse.data.sensor.KinematicPDR()

    // History for step debouncing and spike rejection
    private var lastStepTimestampMillis = 0L
    private val orientationHistory = mutableListOf<Pair<Long, Float>>()
    private var lastResetTimestamp = 0L

    init {
        viewModelScope.launch {
            val floors = mapDao.getFloors()
            if (floors.isEmpty()) {
                mapDao.insertFloors(listOf(
                    FloorEntity(
                        floorId = "f1",
                        level = 0,
                        name = "Ground Floor",
                        altitudeOffsetMeters = 0f
                    )
                ))
            }
            refreshCache()
        }
        observeSensors()
        val gravity = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
        val linearAccel = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val rotVector = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (gravity != null) sensorManager.registerListener(kinematicPDR, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        if (linearAccel != null) sensorManager.registerListener(kinematicPDR, linearAccel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        if (rotVector != null) sensorManager.registerListener(kinematicPDR, rotVector, android.hardware.SensorManager.SENSOR_DELAY_GAME)

        kinematicPDR.onStepDetected = { stepLength, heading ->
            _uiState.update { it.copy(headingDegrees = heading) }

            val currentPos = _uiState.value.userPosition
            
            val rad = Math.toRadians(heading.toDouble())
            val dx = (stepLength * kotlin.math.sin(rad)).toFloat()
            val dy = (stepLength * kotlin.math.cos(rad)).toFloat()

            val candidatePos = currentPos.copy(
                x = currentPos.x + dx,
                y = currentPos.y + dy
            )

            val snappedPos = snapToNearestCorridor(candidatePos, heading)

            particleFilter.update(
                stepLength = stepLength,
                heading = heading,
                edges = cachedEdges,
                nodesMap = cachedNodes,
                constrainToGraph = !isSurveyorMode.value
            )
            val pos = particleFilter.getEstimatedPosition()
            estimatedPosition.value = pos
            
            if (isSurveyorMode.value) {
                val lastId = lastDroppedNodeId.value
                val closestSnappable = cachedNodes.values
                    .filter { it.nodeId != lastId }
                    .minByOrNull { node ->
                        val dX = node.x - pos.first
                        val dY = node.y - pos.second
                        (dX * dX) + (dY * dY)
                    }
                val snapDist = if (closestSnappable != null) {
                    val dX = closestSnappable.x - pos.first
                    val dY = closestSnappable.y - pos.second
                    kotlin.math.sqrt((dX * dX + dY * dY).toDouble()).toFloat()
                } else Float.MAX_VALUE
                
                if (closestSnappable != null && snapDist <= 3.5f) {
                    snapTargetNodeId.value = closestSnappable.nodeId
                } else {
                    snapTargetNodeId.value = null
                }
            } else {
                snapTargetNodeId.value = null
            }

            updateActiveRouteBasedOnPosition()

            _uiState.update { it.copy(userPosition = snappedPos) }
        }
    }

    private suspend fun refreshCache() {
        cachedEdges = mapDao.getAllEdges()
        val nodesList = mapDao.getNodesByFloor("f1")
        
        cachedNodes = nodesList.associateBy { it.nodeId }
        
        _nodes.value = nodesList
        _edges.value = cachedEdges
    }

    private fun getUniqueNodeName(requestedName: String, existingNames: List<String>): String {
        if (!existingNames.contains(requestedName)) {
            return requestedName
        }
        
        var counter = 2
        var newName = "$requestedName $counter"
        
        // Keep incrementing until we find a clean name
        while (existingNames.contains(newName)) {
            counter++
            newName = "$requestedName $counter"
        }
        
        return newName
    }

    fun forceDropNewNode(nodeName: String, nodeType: String) {
        viewModelScope.launch {
            try {
                val pos = estimatedPosition.value
                val lastId = lastDroppedNodeId.value
                
                mapDao.insertFloors(listOf(FloorEntity(floorId = "f1", level = 0, name = "Ground Floor", altitudeOffsetMeters = 0f)))
                val newNodeId = java.util.UUID.randomUUID().toString()
                
                val currentNames = cachedNodes.values.map { it.name }
                val finalLabel = if (nodeName.isNotBlank()) {
                    getUniqueNodeName(nodeName.trim(), currentNames)
                } else {
                    val currentNodes = if (activeNodes.value.isNotEmpty()) activeNodes.value else cachedNodes.values.toList()
                    val count = currentNodes.count { it.type.equals(nodeType, ignoreCase = true) }
                    "$nodeType ${count + 1}"
                }

                val newNode = NodeEntity(
                    nodeId = newNodeId,
                    floorId = "f1",
                    x = pos.first,
                    y = pos.second,
                    nodeType = nodeType,
                    name = finalLabel
                )
                mapDao.insertNodes(listOf(newNode))
                var createdEdge: EdgeEntity? = null
                if (lastId != null) {
                    val lastNode = cachedNodes[lastId]
                    val length = if (lastNode != null) {
                        val dx = pos.first - lastNode.x
                        val dy = pos.second - lastNode.y
                        kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    } else {
                        1.0f
                    }
                        
                    val newEdge = EdgeEntity(
                        edgeId = java.util.UUID.randomUUID().toString(),
                        fromNodeId = lastId,
                        toNodeId = newNodeId,
                        lengthMeters = length,
                        edgeType = "corridor"
                    )
                    mapDao.insertEdges(listOf(newEdge))
                    createdEdge = newEdge
                }
                
                droppedNodesChain.add(newNodeId)
                lastDroppedNodeId.value = newNodeId
                _undoStack.value = _undoStack.value + SurveyorAction.DropNode(newNode, createdEdge)
                _redoStack.value = emptyList()
                refreshCache()
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Error saving: ${e.message}") }
            }
        }
    }

    fun snapToTargetNode() {
        viewModelScope.launch {
            try {
                val lastId = lastDroppedNodeId.value
                val targetId = snapTargetNodeId.value
                
                if (lastId != null && targetId != null && lastId != targetId) {
                    val lastNode = cachedNodes[lastId]
                    val targetNode = cachedNodes[targetId]
                    
                    val length = if (lastNode != null && targetNode != null) {
                        val dx = targetNode.x - lastNode.x
                        val dy = targetNode.y - lastNode.y
                        kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    } else {
                        1.0f
                    }
                    
                    val newEdge = EdgeEntity(
                        edgeId = java.util.UUID.randomUUID().toString(),
                        fromNodeId = lastId,
                        toNodeId = targetId,
                        lengthMeters = length,
                        edgeType = "corridor"
                    )
                    mapDao.insertEdges(listOf(newEdge))
                    
                    droppedNodesChain.add(targetId)
                    lastDroppedNodeId.value = targetId
                    _undoStack.value = _undoStack.value + SurveyorAction.SnapEdge(newEdge)
                    _redoStack.value = emptyList()
                    refreshCache()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Error snapping: ${e.message}") }
            }
        }
    }

    private fun shortestAngleDiff(target: Float, current: Float): Float {
        var diff = (target - current) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun observeSensors() {
        viewModelScope.launch {
            sensorDataSource.gravityEvents.collect { gravity ->
                val pitchDegrees = Math.toDegrees(kotlin.math.atan2(gravity.gy.toDouble(), gravity.gz.toDouble()))
                if (isAutoArEnabled.value) {
                    if (pitchDegrees > 65) {
                        currentViewMode.value = ViewMode.AR_CAMERA
                    } else if (pitchDegrees < 45) {
                        currentViewMode.value = ViewMode.MAP_2D
                    }
                }
            }
        }

        viewModelScope.launch {
            sensorDataSource.gpsLocations.collect { gps ->
                val updatedPos = _uiState.value.userPosition.copy(lat = gps.latitude, lng = gps.longitude)
                
                val leashResult = applyGpsLeashUseCase(
                    userCoordinate = updatedPos,
                    originLat = 0.0,
                    originLng = 0.0,
                    boundingRadiusMeters = 500f
                )

                when (leashResult) {
                    is GpsLeashResult.WithinBounds -> {
                        _uiState.update { 
                            it.copy(
                                userPosition = leashResult.coordinate
                            )
                        }
                    }
                    is GpsLeashResult.DriftDetected -> {
                        // Suppress legacy drift message
                    }
                }
            }
        }
    }

    private fun snapToNearestCorridor(
        candidatePos: Coordinate, 
        currentHeading: Float
    ): Coordinate {
        var closestDistSq = Float.MAX_VALUE
        var bestProjX = candidatePos.x
        var bestProjY = candidatePos.y
        var bestEdgeBearing = 0f

        for (edge in cachedEdges) {
            val n1 = cachedNodes[edge.fromNodeId] ?: continue
            val n2 = cachedNodes[edge.toNodeId] ?: continue
            val p1 = Coordinate(n1.x, n1.y)
            val p2 = Coordinate(n2.x, n2.y)

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

    private fun buildDynamicMap(): IndoorMap {
        return IndoorMap(
            buildingId = "db_map",
            buildingName = "Surveyed Map",
            originLat = 0.0,
            originLng = 0.0,
            boundingRadiusMeters = 500f,
            nodes = cachedNodes.values.associate { it.nodeId to Node(it.nodeId, Coordinate(it.x, it.y), it.name, try { NodeType.valueOf(it.nodeType.uppercase()) } catch(e: Exception) { NodeType.CORRIDOR_JUNCTION }) },
            edges = cachedEdges.flatMap { 
                listOf(
                    Edge(fromNodeId = it.fromNodeId, toNodeId = it.toNodeId, distanceMeters = it.lengthMeters, isAccessible = true),
                    Edge(fromNodeId = it.toNodeId, toNodeId = it.fromNodeId, distanceMeters = it.lengthMeters, isAccessible = true)
                )
            }
        )
    }

    private fun updateActiveRouteBasedOnPosition() {
        val destId = _uiState.value.selectedDestinationNodeId ?: return
        
        val pos = estimatedPosition.value
        val closestNode = cachedNodes.values.minByOrNull { node ->
            val dx = node.x - pos.first
            val dy = node.y - pos.second
            (dx * dx) + (dy * dy)
        }
        
        if (closestNode != null) {
            val route = findShortestPathUseCase(buildDynamicMap(), closestNode.nodeId, destId)
            _uiState.update { it.copy(activeRoute = route) }
        }
    }

    fun selectDestination(nodeId: String) {
        if (_isSurveyorMode.value) {
            toggleSurveyorMode(false)
        }
        val startNode = cachedNodes.values.minByOrNull { node ->
            val dx = node.x - _uiState.value.userPosition.x
            val dy = node.y - _uiState.value.userPosition.y
            (dx * dx) + (dy * dy)
        }

        val destinationNode = cachedNodes[nodeId] ?: _nodes.value.find { it.nodeId == nodeId }
        activeDestination.value = destinationNode

        if (startNode != null) {
            val dynamicMap = buildDynamicMap()
            
            val route = findShortestPathUseCase(dynamicMap, startNode.nodeId, nodeId)
            _uiState.update { 
                it.copy(
                    selectedDestinationNodeId = nodeId,
                    activeRoute = route,
                    statusMessage = if (route.isNotEmpty()) "Navigating to ${cachedNodes[nodeId]?.name}" else "No route found"
                )
            }
        }
    }

    fun requestRoute(nodeId: String) {
        selectDestination(nodeId)
    }

    fun resetPosition() {
        lastResetTimestamp = android.os.SystemClock.elapsedRealtime()
        
        // Atomic wipe
        particleFilter.initialize(0f, 0f)
        estimatedPosition.value = Pair(0f, 0f)
        smoothAzimuth = 0f
        
        if (isSurveyorMode.value) {
            lastDroppedNodeId.value = null
        }
        
        _isSosActive.value = false
        activeDestination.value = null
        
        _uiState.update {
            it.copy(
                userPosition = Coordinate(0f, 0f),
                headingDegrees = 0f,
                statusMessage = "Reset position to origin",
                activeRoute = emptyList(),
                selectedDestinationNodeId = null
            )
        }
    }

    fun syncPositionTo(x: Float, y: Float) {
        particleFilter.forcePosition(x, y)
        val pos = Pair(x, y)
        estimatedPosition.value = pos
        _uiState.update { 
            it.copy(
                userPosition = Coordinate(x, y),
                statusMessage = "Position synced to (%.1f, %.1f)".format(x, y)
            ) 
        }
        if (isSurveyorMode.value) {
            val lastId = lastDroppedNodeId.value
            val closestSnappable = cachedNodes.values
                .filter { it.nodeId != lastId }
                .minByOrNull { node ->
                    val dX = node.x - pos.first
                    val dY = node.y - pos.second
                    (dX * dX) + (dY * dY)
                }
            val snapDist = if (closestSnappable != null) {
                val dX = closestSnappable.x - pos.first
                val dY = closestSnappable.y - pos.second
                kotlin.math.sqrt((dX * dX + dY * dY).toDouble()).toFloat()
            } else Float.MAX_VALUE
            
            if (closestSnappable != null && snapDist <= 3.5f) {
                snapTargetNodeId.value = closestSnappable.nodeId
            } else {
                snapTargetNodeId.value = null
            }
        }
    }

    fun toggleSurveyorMode(isActive: Boolean = !_isSurveyorMode.value) {
        _isSurveyorMode.value = isActive
        if (isActive) {
            // Entering Surveyor: Interlock - kill active routes
            clearRoute()
            _isSosActive.value = false
        } else {
            // Exiting Surveyor: Commit staged deletions permanently to Room DB
            viewModelScope.launch(Dispatchers.IO) {
                val stagedDeletions = _undoStack.value.filterIsInstance<SurveyorAction.DeleteNode>().map { it.node }
                stagedDeletions.forEach { node ->
                    repository.deleteNodeAndEdges(node.id)
                }
                _undoStack.value = emptyList()
                _redoStack.value = emptyList()
                refreshCache()
            }
        }
    }

    fun switchFloor(floor: String? = null) {
        if (floor != null) {
            currentFloor.value = floor
        } else {
            currentFloor.value = if (currentFloor.value == "L1") "L2" else "L1"
        }
        _uiState.update { it.copy(statusMessage = "Switched to floor ${currentFloor.value}") }
    }

    fun recenterCamera() {
        recenterTrigger.value = System.currentTimeMillis()
    }

    fun triggerEgressRouting() {
        val exitNode = cachedNodes.values.find {
            it.nodeType == "Emergency Exit" ||
            it.name.contains("exit", ignoreCase = true) ||
            it.name.contains("egress", ignoreCase = true) ||
            it.nodeType.equals("stairs", ignoreCase = true) ||
            it.name.contains("stairs", ignoreCase = true)
        } ?: cachedNodes.values.firstOrNull()

        if (exitNode != null) {
            _isSosActive.value = true
            selectDestination(exitNode.nodeId)
            _uiState.update {
                it.copy(statusMessage = "EMERGENCY: Evacuation route to ${exitNode.name}")
            }
        } else {
            _uiState.update {
                it.copy(statusMessage = "EMERGENCY: No egress destination found")
            }
        }
    }

    fun toggleSosMode(isActive: Boolean) {
        _isSosActive.value = isActive
        if (isActive) {
            if (_isSurveyorMode.value) {
                toggleSurveyorMode(false)
            }
            triggerEgressRouting()
        } else {
            clearDestination()
            _uiState.update { it.copy(statusMessage = "SOS deactivated") }
        }
    }

    fun clearRoute() {
        clearDestination()
    }

    fun clearDestination() {
        _isSosActive.value = false
        activeDestination.value = null
        _uiState.update {
            it.copy(
                selectedDestinationNodeId = null,
                activeRoute = emptyList(),
                statusMessage = "Navigation stopped"
            )
        }
    }
}
