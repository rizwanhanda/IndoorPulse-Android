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
import com.indoorpulse.domain.usecase.RoutingEngine
import com.indoorpulse.data.local.dao.MapDao
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.FloorEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.sensor.GpsLeashManager
import com.indoorpulse.domain.navigation.ParticleFilter
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type
import com.indoorpulse.data.local.entity.startNodeId
import com.indoorpulse.data.local.entity.endNodeId
import com.indoorpulse.data.local.entity.weight
import com.indoorpulse.domain.model.floorLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.location.Location
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class ViewMode { MAP_2D, AR_CAMERA }

enum class TrackingState { OUTDOORS, INDOORS }

data class TransitInfo(
    val targetFloor: Int,
    val portalType: String,
    val targetX: Float,
    val targetY: Float,
    val nextNodeIndex: Int // How many nodes to pop off the route upon arrival
)

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
    private val mapDao: MapDao,
    private val gpsLeashManager: GpsLeashManager? = null
) : ViewModel() {

    val trackingState = MutableStateFlow(TrackingState.OUTDOORS)
    val nearExitNode = MutableStateFlow<NodeEntity?>(null)
    private var entryAnchorNode: NodeEntity? = null
    private var latestGpsLocation: Location? = null
    private var lastEntryTimeMillis: Long = 0

    private val _activeBuildingId = MutableStateFlow<String?>(null)
    val activeBuildingId: StateFlow<String?> = _activeBuildingId.asStateFlow()
    val approachingBuilding = MutableStateFlow<BuildingEntity?>(null)

    val currentViewMode = MutableStateFlow(ViewMode.MAP_2D)
    val isAutoArEnabled = MutableStateFlow(true)
    val estimatedPosition = MutableStateFlow(Pair(0f, 0f))
    
    private val _isSurveyorMode = MutableStateFlow(false)
    val isSurveyorMode: StateFlow<Boolean> = _isSurveyorMode.asStateFlow()
    val lastDroppedNodeId = MutableStateFlow<String?>(null)
    val snapTargetNodeId = MutableStateFlow<String?>(null)
    private val _selectedNode = MutableStateFlow<NodeEntity?>(null)
    val selectedNode: StateFlow<NodeEntity?> = _selectedNode.asStateFlow()
    private val _stagedNode = MutableStateFlow<NodeEntity?>(null)
    val stagedNode: StateFlow<NodeEntity?> = _stagedNode.asStateFlow()

    private val _transitState = MutableStateFlow<TransitInfo?>(null)
    val transitState: StateFlow<TransitInfo?> = _transitState.asStateFlow()

    fun selectNode(node: NodeEntity?) {
        _selectedNode.value = node
    }

    private val _currentFloor = MutableStateFlow(0)
    val currentFloor: StateFlow<Int> = _currentFloor.asStateFlow()

    private val _is3DMode = MutableStateFlow(false)
    val is3DMode: StateFlow<Boolean> = _is3DMode.asStateFlow()

    fun clearSelectedNode() {
        lastDroppedNodeId.value = null
        snapTargetNodeId.value = null
        _selectedNode.value = null
    }

    fun setFloor(floor: Int) {
        _currentFloor.value = floor
        clearSelectedNode() // CRITICAL: Break temporal continuity when changing floors
        _uiState.update { it.copy(statusMessage = "Switched to floor L$floor") }
    }

    fun toggle3DMode() {
        _is3DMode.value = !_is3DMode.value
    }
    val recenterTrigger = MutableStateFlow(0L)
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()
    val activeDestination = MutableStateFlow<NodeEntity?>(null)
    
    private val _nodes = MutableStateFlow<List<NodeEntity>>(emptyList())
    private val _undoStack = MutableStateFlow<List<SurveyorAction>>(emptyList())
    private val _redoStack = MutableStateFlow<List<SurveyorAction>>(emptyList())

    val canUndo: StateFlow<Boolean> = _undoStack.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val canRedo: StateFlow<Boolean> = _redoStack.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _routeError = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)
    val routeError: SharedFlow<String> = _routeError.asSharedFlow()

    private val _activeRoute = MutableStateFlow<List<Coordinate>>(emptyList())
    val activeRoute: StateFlow<List<Coordinate>> = _activeRoute.asStateFlow()

    val routingEngine = RoutingEngine()

    inner class Repository {
        fun getAllNodesFlow(): Flow<List<NodeEntity>> = _nodes
        fun getAllEdgesFlow(): Flow<List<EdgeEntity>> = _edges
        suspend fun deleteNodeAndEdges(nodeId: String) {
            mapDao.deleteEdgesForNode(nodeId)
            mapDao.deleteNode(nodeId)
        }
        suspend fun updateNode(node: NodeEntity) {
            mapDao.insertNodes(listOf(node))
        }
        suspend fun insertEdge(edge: EdgeEntity) {
            mapDao.insertEdges(listOf(edge))
        }
    }
    val repository = Repository()

    fun stageNodeForDeletion(node: NodeEntity) {
        _stagedNode.value = node
    }

    fun clearStagedNode() {
        _stagedNode.value = null
    }

    fun deleteNode(node: NodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            mapDao.deleteEdgesForNode(node.nodeId)
            mapDao.deleteNode(node.nodeId)
            droppedNodesChain.remove(node.nodeId)
            if (lastDroppedNodeId.value == node.nodeId) {
                lastDroppedNodeId.value = droppedNodesChain.lastOrNull()
            }
            if (snapTargetNodeId.value == node.nodeId) {
                snapTargetNodeId.value = null
            }
            if (_selectedNode.value?.nodeId == node.nodeId) {
                _selectedNode.value = null
            }
            clearStagedNode()
            refreshCache(_activeBuildingId.value ?: "default_building")
            _uiState.update { it.copy(statusMessage = "Deleted node: ${node.label}") }
        }
    }

    fun updateNodeType(node: NodeEntity, newType: String) {
        val updatedNode = node.copy(nodeType = newType)
        viewModelScope.launch(Dispatchers.IO) {
            mapDao.insertNodes(listOf(updatedNode))
            refreshCache(_activeBuildingId.value ?: "default_building")
            if (_stagedNode.value?.nodeId == node.nodeId) {
                _stagedNode.value = updatedNode
            }
            _uiState.update { it.copy(statusMessage = "Updated ${node.label} to $newType") }
        }
    }

    fun linkPortalToFloor(sourceNode: NodeEntity, targetFloor: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = cachedNodes.values.filter {
                it.floorLevel == targetFloor && (
                    it.nodeType.equals("Stairs", ignoreCase = true) ||
                    it.nodeType.equals("Elevator", ignoreCase = true)
                )
            }
            val nearest = candidates.minByOrNull {
                Math.hypot((it.x - sourceNode.x).toDouble(), (it.y - sourceNode.y).toDouble())
            }
            if (nearest != null) {
                val edge = EdgeEntity(
                    edgeId = java.util.UUID.randomUUID().toString(),
                    fromNodeId = sourceNode.nodeId,
                    toNodeId = nearest.nodeId,
                    lengthMeters = 30.0f,
                    edgeType = "vertical_portal",
                    buildingId = sourceNode.buildingId
                )
                mapDao.insertEdges(listOf(edge))
                refreshCache(_activeBuildingId.value ?: sourceNode.buildingId)
                _uiState.update { it.copy(statusMessage = "Linked ${sourceNode.label} to ${nearest.label} on Floor L$targetFloor") }
            } else {
                _routeError.tryEmit("No portal found on Floor L$targetFloor")
            }
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

    val availableFloors: StateFlow<List<Int>> = repository.getAllNodesFlow()
        .map { nodes -> 
            val floors = nodes.map { it.floorLevel }.distinct().sorted()
            if (floors.isEmpty()) listOf(0) else floors
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, listOf(0))

    fun cycleFloor(isSurveyorMode: Boolean) {
        val floors = availableFloors.value
        val newFloor = if (isSurveyorMode) {
            // Surveyors can always map one floor above the highest existing floor, or go below 0.
            val max = floors.maxOrNull() ?: 0
            val min = floors.minOrNull() ?: 0
            if (_currentFloor.value >= max + 1) min - 1 else _currentFloor.value + 1
        } else {
            // Standard users can only cycle through floors that physically exist in the DB
            val currentIndex = floors.indexOf(_currentFloor.value)
            val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % floors.size else 0
            floors[nextIndex]
        }
        setFloor(newFloor)
    }
    
    private val _edges = MutableStateFlow<List<EdgeEntity>>(emptyList())
    val edges: StateFlow<List<EdgeEntity>> = _edges.asStateFlow()
    
    private val droppedNodesChain = mutableListOf<String>()
    private val actionHistory = java.util.Stack<String>()
    private val particleFilter = ParticleFilter()
    
    private var cachedEdges: List<EdgeEntity> = emptyList()
    private var cachedNodes: Map<String, NodeEntity> = emptyMap()

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val currentPoseNodeId: String? = null
    val currentPoseState: StateFlow<Coordinate?> = _uiState.map { it.userPosition }.stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.userPosition)

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

            var buildings = mapDao.getAllBuildings()
            if (buildings.isEmpty()) {
                val defaultBuilding = BuildingEntity(
                    buildingId = "default_building",
                    name = "Main Campus",
                    centerLat = 0.0,
                    centerLng = 0.0,
                    geofenceRadius = 150f
                )
                mapDao.insertBuildings(listOf(defaultBuilding))
                buildings = listOf(defaultBuilding)
            }

            val currentBuildingId = _activeBuildingId.value ?: buildings.firstOrNull()?.buildingId
            if (currentBuildingId != null) {
                refreshCache(currentBuildingId)
            }

            gpsLeashManager?.let { manager ->
                var gpsJob: kotlinx.coroutines.Job? = null
                _isSurveyorMode.collect { isSurveyor ->
                    gpsJob?.cancel()
                    gpsJob = viewModelScope.launch {
                        val interval = if (isSurveyor) 5000L else 10000L
                        manager.observeGeofences(buildings, interval).collect { event ->
                            val detectedBuilding = event.matchedBuilding
                            val location = event.location
                            latestGpsLocation = location

                            if (trackingState.value == TrackingState.OUTDOORS) {
                                if (detectedBuilding != null && detectedBuilding.buildingId != _activeBuildingId.value) {
                                    approachingBuilding.value = detectedBuilding
                                } else {
                                    approachingBuilding.value = null
                                }
                            } else {
                                // INDOORS: Check opportunistic drift correction
                                approachingBuilding.value = null
                                val accuracyThreshold = if (_isSurveyorMode.value) 15.0f else 8.0f
                                if (location.hasAccuracy() && location.accuracy < accuracyThreshold) {
                                    val anchor = entryAnchorNode ?: cachedNodes.values.firstOrNull {
                                        it.nodeType == "Building Entry" || it.nodeType == "Emergency Exit"
                                    }

                                    val anchorLat = anchor?.lat?.takeIf { it != 0.0 }
                                        ?: detectedBuilding?.centerLat
                                        ?: buildings.find { it.buildingId == _activeBuildingId.value }?.centerLat
                                    val anchorLng = anchor?.lng?.takeIf { it != 0.0 }
                                        ?: detectedBuilding?.centerLng
                                        ?: buildings.find { it.buildingId == _activeBuildingId.value }?.centerLng

                                    if (anchorLat != null && anchorLng != null && anchorLat != 0.0 && anchorLng != 0.0) {
                                        val anchorX = anchor?.x ?: 0f
                                        val anchorY = anchor?.y ?: 0f

                                        val results = FloatArray(2)
                                        Location.distanceBetween(
                                            anchorLat,
                                            anchorLng,
                                            location.latitude,
                                            location.longitude,
                                            results
                                        )
                                        val distance = results[0]
                                        val bearingDeg = results[1]
                                        val bearingRad = Math.toRadians(bearingDeg.toDouble())

                                        val deltaX = (distance * kotlin.math.sin(bearingRad)).toFloat()
                                        val deltaY = (distance * kotlin.math.cos(bearingRad)).toFloat()

                                        val correctedX = anchorX + deltaX
                                        val correctedY = anchorY + deltaY

                                        syncPositionTo(correctedX, correctedY)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            currentPoseState.collect { pose ->
                if (pose != null) {
                    // CRITICAL FIX: The proximity check must be actively driven by the PDR state
                    onLocationUpdated(pose.x, pose.y)
                }
            }
        }

        observeSensors()
        val gravity = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
        val linearAccel = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val rotVector = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (gravity != null) sensorManager.registerListener(kinematicPDR, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        if (linearAccel != null) sensorManager.registerListener(kinematicPDR, linearAccel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        if (rotVector != null) sensorManager.registerListener(kinematicPDR, rotVector, android.hardware.SensorManager.SENSOR_DELAY_GAME)

        kinematicPDR.onStepDetected = onStepDetected@{ stepLength, heading ->
            if (_transitState.value != null) return@onStepDetected // CRITICAL: Pause PDR during transit

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
            onLocationUpdated(pos.first, pos.second)
            
            if (isSurveyorMode.value) {
                val lastId = lastDroppedNodeId.value
                val lastNode = lastId?.let { cachedNodes[it] }
                val closestSnappable = cachedNodes.values
                    .filter { isSnappableCandidate(it, lastNode) }
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

    private suspend fun refreshCache(buildingId: String = _activeBuildingId.value ?: "default_building") {
        val nodesList = mapDao.getNodesByBuilding(buildingId)
        val edgesList = mapDao.getEdgesByBuilding(buildingId)
        
        cachedEdges = edgesList
        cachedNodes = nodesList.associateBy { it.nodeId }
        
        _nodes.value = nodesList
        _edges.value = cachedEdges
        routingEngine.buildGraph(nodesList, cachedEdges)
    }

    fun confirmBuildingEntry(buildingId: String, entryNode: NodeEntity) {
        viewModelScope.launch {
            _activeBuildingId.value = buildingId
            entryAnchorNode = entryNode
            refreshCache(buildingId)
            setFloor(entryNode.floorLevel)
            syncPositionTo(entryNode.x, entryNode.y)
            approachingBuilding.value = null
            nearExitNode.value = null
            lastEntryTimeMillis = System.currentTimeMillis()
            trackingState.value = TrackingState.INDOORS
        }
    }

    fun confirmBuildingExit() {
        _activeBuildingId.value = null
        clearRoute()
        nearExitNode.value = null
        entryAnchorNode = null
        trackingState.value = TrackingState.OUTDOORS
        _uiState.update { it.copy(statusMessage = "Exited building. Switched to OUTDOORS.") }
    }

    fun manualOverride(buildingId: String, entryNode: NodeEntity) {
        viewModelScope.launch {
            _activeBuildingId.value = buildingId
            entryAnchorNode = entryNode
            refreshCache(buildingId)
            setFloor(entryNode.floorLevel)
            syncPositionTo(entryNode.x, entryNode.y)
            approachingBuilding.value = null
            nearExitNode.value = null
            lastEntryTimeMillis = System.currentTimeMillis()
            trackingState.value = TrackingState.INDOORS
            _uiState.update { it.copy(statusMessage = "Manual override: Indoors at ${entryNode.label}") }
        }
    }

    fun createNewBuilding(name: String, radius: Float = 100f) {
        val newBuildingId = java.util.UUID.randomUUID().toString()
        val lat = latestGpsLocation?.latitude 
            ?: _uiState.value.userPosition.lat.takeIf { it != 0.0 } 
            ?: 0.0
        val lng = latestGpsLocation?.longitude 
            ?: _uiState.value.userPosition.lng.takeIf { it != 0.0 } 
            ?: 0.0

        val newBuilding = BuildingEntity(
            buildingId = newBuildingId,
            name = name.ifBlank { "New Building" },
            centerLat = lat,
            centerLng = lng,
            geofenceRadius = radius
        )

        viewModelScope.launch {
            mapDao.insertBuildings(listOf(newBuilding))
            _activeBuildingId.value = newBuildingId
            lastEntryTimeMillis = System.currentTimeMillis()
            trackingState.value = TrackingState.INDOORS
            approachingBuilding.value = null
            nearExitNode.value = null
            refreshCache(newBuildingId)
            setFloor(0)
            resetPosition()
            _uiState.update { 
                it.copy(statusMessage = "Created & switched to: ${newBuilding.name}") 
            }
        }
    }

    suspend fun getBuildingEntryPoints(buildingId: String): List<NodeEntity> {
        return mapDao.getEntryPoints(buildingId)
    }

    fun dismissApproachingBuilding() {
        approachingBuilding.value = null
    }

    val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")

    fun canLinkNodes(nodeA: NodeEntity, nodeB: NodeEntity): Boolean {
        // 1. Cross-Floor Validation
        if (nodeA.floorLevel != nodeB.floorLevel) {
            val isNodeAValid = nodeA.type in validPortals || validPortals.any { it.equals(nodeA.type, ignoreCase = true) }
            val isNodeBValid = nodeB.type in validPortals || validPortals.any { it.equals(nodeB.type, ignoreCase = true) }
            if (!isNodeAValid || !isNodeBValid) {
                // Reject the edge: You cannot connect standard nodes across floors
                return false
            }
        }
        return true
    }

    fun createEdgeEntity(nodeA: NodeEntity, nodeB: NodeEntity): EdgeEntity? {
        if (!canLinkNodes(nodeA, nodeB)) {
            return null
        }
        val dx = nodeB.x - nodeA.x
        val dy = nodeB.y - nodeA.y
        val length = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.1f)
        val edgeType = if (nodeA.floorLevel != nodeB.floorLevel) {
            if (nodeA.type.equals("Elevator", ignoreCase = true) && nodeB.type.equals("Elevator", ignoreCase = true)) {
                "elevator"
            } else {
                "stairs"
            }
        } else {
            "corridor"
        }
        return EdgeEntity(
            edgeId = java.util.UUID.randomUUID().toString(),
            fromNodeId = nodeA.nodeId,
            toNodeId = nodeB.nodeId,
            lengthMeters = length,
            edgeType = edgeType,
            buildingId = _activeBuildingId.value ?: "default_building"
        )
    }

    // Locate your function that creates edges (e.g., linkNodes or addEdge)
    fun linkNodes(nodeA: NodeEntity, nodeB: NodeEntity): EdgeEntity? {
        // 1. Cross-Floor Validation
        if (nodeA.floorLevel != nodeB.floorLevel) {
            val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")
            if (nodeA.type !in validPortals || nodeB.type !in validPortals) {
                // Reject the edge: You cannot connect standard nodes across floors
                return null
            }
        }
        
        val newEdge = createEdgeEntity(nodeA, nodeB) ?: return null
        viewModelScope.launch(Dispatchers.IO) {
            mapDao.insertEdges(listOf(newEdge))
            refreshCache()
        }
        return newEdge
    }

    private fun isSnappableCandidate(candidate: NodeEntity, lastNode: NodeEntity?): Boolean {
        if (candidate.nodeId == lastNode?.nodeId) return false
        return if (candidate.floorLevel == _currentFloor.value) {
            true
        } else if (lastNode != null) {
            canLinkNodes(lastNode, candidate)
        } else {
            false
        }
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

                var computedLat = 0.0
                var computedLng = 0.0

                if (_isSurveyorMode.value) {
                    val anchor = entryAnchorNode ?: cachedNodes.values.firstOrNull {
                        it.nodeType == "Building Entry" || it.nodeType == "Emergency Exit"
                    }
                    val anchorLat = anchor?.lat?.takeIf { it != 0.0 }
                    val anchorLng = anchor?.lng?.takeIf { it != 0.0 }

                    if (anchorLat != null && anchorLng != null) {
                        val anchorX = anchor.x
                        val anchorY = anchor.y
                        val deltaX = (pos.first - anchorX).toDouble()
                        val deltaY = (pos.second - anchorY).toDouble()

                        val deltaLat = deltaY / 111139.0
                        val deltaLng = deltaX / (111139.0 * kotlin.math.cos(Math.toRadians(anchorLat)))

                        computedLat = anchorLat + deltaLat
                        computedLng = anchorLng + deltaLng
                    }
                }

                val newNode = NodeEntity(
                    nodeId = newNodeId,
                    floorId = "f1",
                    x = pos.first,
                    y = pos.second,
                    nodeType = nodeType,
                    name = finalLabel,
                    floorLevel = _currentFloor.value,
                    buildingId = _activeBuildingId.value ?: "default_building",
                    lat = computedLat,
                    lng = computedLng
                )
                mapDao.insertNodes(listOf(newNode))
                var createdEdge: EdgeEntity? = null
                if (lastId != null) {
                    val lastNode = cachedNodes[lastId]
                    if (lastNode != null) {
                        val newEdge = createEdgeEntity(lastNode, newNode)
                        if (newEdge != null) {
                            mapDao.insertEdges(listOf(newEdge))
                            createdEdge = newEdge
                        }
                    }
                }
                
                droppedNodesChain.add(newNodeId)
                lastDroppedNodeId.value = newNodeId
                _selectedNode.value = newNode
                _undoStack.value = _undoStack.value + SurveyorAction.DropNode(newNode, createdEdge)
                _redoStack.value = emptyList()
                refreshCache()
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Error saving: ${e.message}") }
            }
        }
    }

    fun extrudePortal(direction: Int) {
        val selected = _selectedNode.value ?: return
        if (!selected.nodeType.equals("Stairs", ignoreCase = true) &&
            !selected.nodeType.equals("Elevator", ignoreCase = true)) {
            return
        }

        viewModelScope.launch {
            val targetFloorLevel = _currentFloor.value + direction
            val targetFloorId = "f$targetFloorLevel"
            val newNodeId = java.util.UUID.randomUUID().toString()

            mapDao.insertFloors(
                listOf(
                    FloorEntity(
                        floorId = targetFloorId,
                        level = targetFloorLevel,
                        name = if (targetFloorLevel == 0) "Ground Floor" else "Floor $targetFloorLevel",
                        altitudeOffsetMeters = targetFloorLevel * 3.5f
                    )
                )
            )

            val newNode = NodeEntity(
                nodeId = newNodeId,
                floorId = targetFloorId,
                x = selected.x,
                y = selected.y,
                nodeType = selected.nodeType,
                name = selected.name,
                floorLevel = targetFloorLevel,
                buildingId = selected.buildingId,
                lat = selected.lat,
                lng = selected.lng
            )
            mapDao.insertNodes(listOf(newNode))

            val newEdge = EdgeEntity(
                edgeId = java.util.UUID.randomUUID().toString(),
                fromNodeId = selected.nodeId,
                toNodeId = newNodeId,
                lengthMeters = 0f,
                edgeType = "vertical_portal",
                buildingId = selected.buildingId
            )
            mapDao.insertEdges(listOf(newEdge))
            _undoStack.value = _undoStack.value + SurveyorAction.DropNode(newNode, newEdge)

            refreshCache(_activeBuildingId.value ?: "default_building")
            setFloor(targetFloorLevel)
            _selectedNode.value = newNode
            _uiState.update { it.copy(statusMessage = "Extruded portal to floor L$targetFloorLevel") }
        }
    }

    fun snapToGhostPortal(currentNode: NodeEntity, targetGhostNode: NodeEntity) {
        val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")
        
        // Ensure both are valid portals and on adjacent floors
        if ((currentNode.type in validPortals || validPortals.any { it.equals(currentNode.type, ignoreCase = true) }) &&
            (targetGhostNode.type in validPortals || validPortals.any { it.equals(targetGhostNode.type, ignoreCase = true) })) {
            if (kotlin.math.abs(currentNode.floorLevel - targetGhostNode.floorLevel) == 1) {
                
                // 1. Align the X/Y coordinates perfectly
                val alignedNode = currentNode.copy(x = targetGhostNode.x, y = targetGhostNode.y)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.updateNode(alignedNode)
                    
                    // 2. Automatically generate the vertical Edge
                    val verticalEdge = EdgeEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        startNodeId = alignedNode.id,
                        endNodeId = targetGhostNode.id,
                        weight = 0f // Zero-cost vertical jump
                    )
                    repository.insertEdge(verticalEdge)
                    refreshCache()
                }
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
                    
                    if (lastNode != null && targetNode != null) {
                        val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")
                        val isLastPortal = lastNode.type in validPortals || validPortals.any { it.equals(lastNode.type, ignoreCase = true) }
                        val isTargetPortal = targetNode.type in validPortals || validPortals.any { it.equals(targetNode.type, ignoreCase = true) }

                        if (isLastPortal && isTargetPortal && kotlin.math.abs(lastNode.floorLevel - targetNode.floorLevel) == 1) {
                            // 1. Align the X/Y coordinates perfectly
                            val alignedNode = lastNode.copy(x = targetNode.x, y = targetNode.y)
                            repository.updateNode(alignedNode)
                            
                            // 2. Automatically generate the vertical Edge
                            val verticalEdge = EdgeEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                startNodeId = alignedNode.id,
                                endNodeId = targetNode.id,
                                weight = 0f // Zero-cost vertical jump
                            )
                            repository.insertEdge(verticalEdge)
                            
                            droppedNodesChain.add(targetId)
                            lastDroppedNodeId.value = targetId
                            _undoStack.value = _undoStack.value + SurveyorAction.SnapEdge(verticalEdge)
                            _redoStack.value = emptyList()
                            refreshCache()
                        } else {
                            val newEdge = createEdgeEntity(lastNode, targetNode)
                            if (newEdge == null) {
                                _uiState.update { it.copy(statusMessage = "Cannot link across floors: only Stairs, Elevator, or Emergency Exit permitted") }
                                return@launch
                            }
                            
                            mapDao.insertEdges(listOf(newEdge))
                            
                            droppedNodesChain.add(targetId)
                            lastDroppedNodeId.value = targetId
                            _undoStack.value = _undoStack.value + SurveyorAction.SnapEdge(newEdge)
                            _redoStack.value = emptyList()
                            refreshCache()
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Error snapping: ${e.message}") }
            }
        }
    }

    fun snapToNearestNode() {
        val lastId = lastDroppedNodeId.value ?: _selectedNode.value?.nodeId
        if (lastId == null) {
            _uiState.update { it.copy(statusMessage = "Snap failed: Drop or select a node first.") }
            return
        }

        val (curX, curY) = estimatedPosition.value
        val floor = _currentFloor.value

        val candidates = cachedNodes.values.filter {
            it.floorLevel == floor && it.nodeId != lastId
        }

        val nearestNode = candidates.minByOrNull {
            Math.hypot((it.x - curX).toDouble(), (it.y - curY).toDouble())
        }

        if (nearestNode == null) {
            _uiState.update { it.copy(statusMessage = "Snap failed: No nodes on floor L$floor.") }
            return
        }

        val dist = Math.hypot((nearestNode.x - curX).toDouble(), (nearestNode.y - curY).toDouble())
        if (dist > 10.0) {
            _uiState.update { it.copy(statusMessage = "Snap failed: Nearest node is too far (%.1fm).".format(dist)) }
            return
        }

        val lastNode = cachedNodes[lastId] ?: return

        val dx = nearestNode.x - lastNode.x
        val dy = nearestNode.y - lastNode.y
        val weight = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(0.1f)

        val newEdge = EdgeEntity(
            edgeId = java.util.UUID.randomUUID().toString(),
            fromNodeId = lastNode.nodeId,
            toNodeId = nearestNode.nodeId,
            lengthMeters = weight,
            edgeType = "corridor",
            buildingId = lastNode.buildingId
        )

        viewModelScope.launch(Dispatchers.IO) {
            mapDao.insertEdges(listOf(newEdge))
            _undoStack.value = _undoStack.value + SurveyorAction.SnapEdge(newEdge)
            _redoStack.value = emptyList()
            lastDroppedNodeId.value = nearestNode.nodeId
            _selectedNode.value = nearestNode
            refreshCache(_activeBuildingId.value ?: "default_building")
            _uiState.update { it.copy(statusMessage = "Snapped to ${nearestNode.label}") }
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
                val loc = Location("GPS").apply {
                    latitude = gps.latitude
                    longitude = gps.longitude
                }
                latestGpsLocation = loc
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
            nodes = cachedNodes.values.associate { it.nodeId to Node(it.nodeId, Coordinate(it.x, it.y, floor = it.floorLevel), it.name, try { NodeType.valueOf(it.nodeType.uppercase()) } catch(e: Exception) { NodeType.CORRIDOR_JUNCTION }) },
            edges = cachedEdges.flatMap { 
                listOf(
                    Edge(fromNodeId = it.fromNodeId, toNodeId = it.toNodeId, distanceMeters = it.lengthMeters, isAccessible = true),
                    Edge(fromNodeId = it.toNodeId, toNodeId = it.fromNodeId, distanceMeters = it.lengthMeters, isAccessible = true)
                )
            }
        )
    }

    fun onLocationUpdated(newX: Float, newY: Float) {
        if (_transitState.value != null) return // CRITICAL: Pause PDR during transit

        // Exit proximity detection when INDOORS
        if (trackingState.value == TrackingState.INDOORS) {
            val exitNodes = cachedNodes.values.filter {
                it.floorLevel == _currentFloor.value &&
                (it.nodeType == "Building Entry" || it.nodeType == "Emergency Exit")
            }
            val closestExit = exitNodes.minByOrNull { exit ->
                Math.hypot((newX - exit.x).toDouble(), (newY - exit.y).toDouble())
            }
            if (closestExit != null && (System.currentTimeMillis() - lastEntryTimeMillis > 15000)) {
                val dist = Math.hypot((newX - closestExit.x).toDouble(), (newY - closestExit.y).toDouble())
                nearExitNode.value = if (dist < 3.0) closestExit else null
            } else {
                nearExitNode.value = null
            }
        } else {
            nearExitNode.value = null
        }

        val route = _activeRoute.value
        if (route.isNotEmpty()) {
            val nextWaypoint = route.first() // Assuming we drop nodes as we reach them
            
            // 1. Check if we reached a waypoint
            val distance = Math.hypot((newX - nextWaypoint.x).toDouble(), (newY - nextWaypoint.y).toDouble())
            if (distance < 2.5) { // 2.5 meter arrival radius
                
                // 2. Is this a floor transition?
                if (route.size > 1 && route[1].floorLevel != _currentFloor.value) {
                    val targetPortal = route[1]
                    // Trigger Transit UX
                    _transitState.value = TransitInfo(
                        targetFloor = targetPortal.floorLevel,
                        portalType = targetPortal.type,
                        targetX = targetPortal.x,
                        targetY = targetPortal.y,
                        nextNodeIndex = 1 
                    )
                } else {
                    // Normal waypoint reached, pop it off the route so UI points to the next one
                    _activeRoute.value = route.drop(1)
                }
            }
        }
    }

    fun completeTransit() {
        val info = _transitState.value ?: return
        
        // 1. Switch Floor
        _currentFloor.value = info.targetFloor
        
        // 2. Teleport Blue Dot to the new portal to zero-out drift
        syncPositionTo(info.targetX, info.targetY)
        
        // 3. Advance the route past the transition nodes
        _activeRoute.value = _activeRoute.value.drop(info.nextNodeIndex + 1)
        
        // 4. Clear state to unpause PDR
        _transitState.value = null
    }

    private fun updateActiveRouteBasedOnPosition() {
        if (_transitState.value != null) return
        val destId = _uiState.value.selectedDestinationNodeId ?: return
        
        val pos = estimatedPosition.value
        val closestNode = cachedNodes.values
            .filter { it.floorLevel == _currentFloor.value }
            .minByOrNull { node ->
                val dx = node.x - pos.first
                val dy = node.y - pos.second
                (dx * dx) + (dy * dy)
            }
        
        if (closestNode != null) {
            val pathNodes = routingEngine.findShortestPath(closestNode.nodeId, destId)
            if (!pathNodes.isNullOrEmpty()) {
                val route = pathNodes.map { Coordinate(it.x, it.y, floor = it.floorLevel) }
                _activeRoute.value = route
                _uiState.update { it.copy(activeRoute = route) }
            }
        }
    }

    fun requestRoute(destinationId: String) {
        if (_isSurveyorMode.value) {
            toggleSurveyorMode(false)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val allNodes = repository.getAllNodesFlow().first()
            val currentFloorNodes = allNodes.filter { it.floorLevel == _currentFloor.value }
            
            // Find nearest node if currentPoseNodeId is null or missing
            val startNodeId = currentPoseNodeId ?: currentPoseState.value?.let { pose ->
                currentFloorNodes
                    .filter { it.floorLevel == _currentFloor.value } // CRITICAL: Must be on current floor
                    .minByOrNull { node ->
                        Math.hypot((node.x - pose.x).toDouble(), (node.y - pose.y).toDouble())
                    }?.id
            }

            if (startNodeId == null) {
                _routeError.emit("Cannot locate starting position on this floor.")
                return@launch
            }

            val destinationNode = cachedNodes[destinationId] ?: allNodes.find { it.nodeId == destinationId }
            activeDestination.value = destinationNode

            val pathNodes = routingEngine.findShortestPath(startNodeId, destinationId)
            
            if (pathNodes.isNullOrEmpty()) {
                _routeError.emit("Destination Unreachable. Are the stairs connected?")
                clearRoute()
            } else {
                val path = pathNodes.map { Coordinate(it.x, it.y, floor = it.floorLevel, type = it.type) }
                _activeRoute.value = path
                _uiState.update { 
                    it.copy(
                        selectedDestinationNodeId = destinationId,
                        activeRoute = path,
                        statusMessage = "Navigating to ${destinationNode?.name ?: destinationId}"
                    )
                }
            }
        }
    }

    fun selectDestination(nodeId: String) {
        requestRoute(nodeId)
    }

    fun resetPosition() {
        lastResetTimestamp = android.os.SystemClock.elapsedRealtime()
        _transitState.value = null
        
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
        _activeRoute.value = emptyList()
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
            val lastNode = lastId?.let { cachedNodes[it] }
            val closestSnappable = cachedNodes.values
                .filter { isSnappableCandidate(it, lastNode) }
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
            clearSelectedNode()
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
        cycleFloor(isSurveyorMode.value)
    }

    fun recenterCamera() {
        recenterTrigger.value = System.currentTimeMillis()
    }

    fun triggerEmergencySOS() {
        viewModelScope.launch(Dispatchers.IO) {
            val allNodes = repository.getAllNodesFlow().first().ifEmpty { cachedNodes.values.toList() }
            
            // 1. STRICT FILTER: Only true exits. No stairs.
            val validExits = allNodes.filter { it.type == "Emergency Exit" || it.type == "Building Entry" }
            
            if (validExits.isEmpty()) {
                _routeError.emit("No Emergency Exits mapped in this building.")
                return@launch
            }

            // 2. Find nearest start node on current floor
            val startNodeId = currentPoseNodeId ?: currentPoseState.value?.let { pose ->
                allNodes.filter { it.floorLevel == _currentFloor.value }
                    .minByOrNull { Math.hypot((it.x - pose.x).toDouble(), (it.y - pose.y).toDouble()) }?.id
            } ?: run {
                _routeError.emit("Cannot locate starting position on this floor.")
                return@launch
            }

            // 3. Find the TRUE shortest path to an exit
            var bestPath: List<NodeEntity>? = null
            var bestExit: NodeEntity? = null
            var shortestDistance = Float.MAX_VALUE

            validExits.forEach { exit ->
                val path = routingEngine.findShortestPath(startNodeId, exit.id)
                if (path != null) {
                    // Calculate physical path length
                    var pathLength = 0f
                    for (i in 0 until path.size - 1) {
                        val n1 = path[i]
                        val n2 = path[i+1]
                        pathLength += Math.hypot((n1.x - n2.x).toDouble(), (n1.y - n2.y).toDouble()).toFloat()
                    }
                    if (pathLength < shortestDistance) {
                        shortestDistance = pathLength
                        bestPath = path
                        bestExit = exit
                    }
                }
            }

            if (bestPath != null) {
                val pathCoordinates = bestPath!!.map { Coordinate(it.x, it.y, floor = it.floorLevel, type = it.type) }
                _activeRoute.value = pathCoordinates
                activeDestination.value = bestExit
                _isSosActive.value = true
                _uiState.update {
                    it.copy(
                        selectedDestinationNodeId = bestExit?.id,
                        activeRoute = pathCoordinates,
                        statusMessage = "EMERGENCY: Evacuation route to ${bestExit?.label ?: bestExit?.name ?: "Exit"}"
                    )
                }
            } else {
                _routeError.emit("No viable path to an exit found.")
            }
        }
    }

    fun triggerEgressRouting() {
        triggerEmergencySOS()
    }

    fun toggleSosMode(isActive: Boolean) {
        _isSosActive.value = isActive
        if (isActive) {
            if (_isSurveyorMode.value) {
                toggleSurveyorMode(false)
            }
            triggerEmergencySOS()
        } else {
            clearDestination()
            _uiState.update { it.copy(statusMessage = "SOS deactivated") }
        }
    }

    fun clearRoute() {
        _transitState.value = null
        clearDestination()
    }

    fun clearDestination() {
        _isSosActive.value = false
        activeDestination.value = null
        _activeRoute.value = emptyList()
        _uiState.update {
            it.copy(
                selectedDestinationNodeId = null,
                activeRoute = emptyList(),
                statusMessage = "Navigation stopped"
            )
        }
    }

    suspend fun getAllBuildings(): List<BuildingEntity> = mapDao.getAllBuildings()
}
