package com.indoorpulse.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.presentation.ui.components.EmergencyFab
import com.indoorpulse.presentation.ui.components.NavigationBanner
import com.indoorpulse.presentation.ui.components.SearchBottomSheetContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Explore
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import com.indoorpulse.presentation.viewmodel.MapViewModel
import com.indoorpulse.presentation.viewmodel.TrackingState
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.routeError.collect { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    val isSurveyorMode by viewModel.isSurveyorMode.collectAsState()
    val isSosActive by viewModel.isSosActive.collectAsState()
    val activeDestination by viewModel.activeDestination.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val estimatedPosition by viewModel.estimatedPosition.collectAsState()
    val activeNodes by viewModel.activeNodes.collectAsState()
    val surveyorEdges by viewModel.edges.collectAsState()
    val lastDroppedNodeId by viewModel.lastDroppedNodeId.collectAsState()
    val snapTargetNodeId by viewModel.snapTargetNodeId.collectAsState()
    val currentFloor by viewModel.currentFloor.collectAsState()
    val is3DMode by viewModel.is3DMode.collectAsState()
    val availableFloors by viewModel.availableFloors.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val recenterTrigger by viewModel.recenterTrigger.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val transitInfo by viewModel.transitState.collectAsState()
    val approachingBuilding by viewModel.approachingBuilding.collectAsState()
    val nearExitNode by viewModel.nearExitNode.collectAsState()
    val trackingState by viewModel.trackingState.collectAsState()
    val stagedNode by viewModel.stagedNode.collectAsState()
    var showManualOverrideDialog by remember { mutableStateOf(false) }

    var targetSyncX by remember { mutableStateOf(0f) }
    var targetSyncY by remember { mutableStateOf(0f) }

    var selectedNodeType by remember { mutableStateOf("Junction") }
    var showNamingDialog by remember { mutableStateOf(false) }
    var nodeNameInput by remember { mutableStateOf("") }

    var entryPoints by remember { mutableStateOf<List<NodeEntity>>(emptyList()) }
    var isLoadingEntryPoints by remember { mutableStateOf(false) }

    LaunchedEffect(approachingBuilding) {
        val building = approachingBuilding
        if (building != null) {
            isLoadingEntryPoints = true
            entryPoints = viewModel.getBuildingEntryPoints(building.buildingId)
            isLoadingEntryPoints = false
        } else {
            entryPoints = emptyList()
        }
    }

    if (approachingBuilding != null) {
        val building = approachingBuilding!!
        AlertDialog(
            onDismissRequest = { viewModel.approachingBuilding.value = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.LocationCity,
                    contentDescription = "Building Detected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Approaching ${building.name}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Select your entry door to calibrate indoor positioning:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isLoadingEntryPoints) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    } else if (entryPoints.isEmpty()) {
                        Text(
                            text = "No dedicated entrance doors mapped for this building.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Button(
                            onClick = {
                                val defaultEntry = NodeEntity(
                                    nodeId = "entry_default_${building.buildingId}",
                                    floorId = "f1",
                                    x = 0f,
                                    y = 0f,
                                    nodeType = "Building Entry",
                                    name = "Main Entrance",
                                    floorLevel = 0,
                                    buildingId = building.buildingId
                                )
                                viewModel.confirmBuildingEntry(building.buildingId, defaultEntry)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Enter Building (Ground Floor)")
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            entryPoints.forEach { entryNode ->
                                OutlinedButton(
                                    onClick = {
                                        viewModel.confirmBuildingEntry(building.buildingId, entryNode)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = entryNode.label,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Floor L${entryNode.floorLevel}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.approachingBuilding.value = null }) {
                    Text("Dismiss / Not Now")
                }
            }
        )
    }

    if (showNamingDialog) {
        AlertDialog(
            onDismissRequest = { showNamingDialog = false },
            title = { Text("Name Location") },
            text = {
                OutlinedTextField(
                    value = nodeNameInput,
                    onValueChange = { nodeNameInput = it },
                    label = { Text("Location Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.forceDropNewNode(nodeNameInput, selectedNodeType)
                    nodeNameInput = ""
                    showNamingDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNamingDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text(
                    "IndoorPulse Admin",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Surveyor Mode") },
                    selected = isSurveyorMode,
                    onClick = {
                        viewModel.toggleSurveyorMode(!isSurveyorMode)
                        scope.launch { drawerState.close() }
                    },
                    badge = { Switch(checked = isSurveyorMode, onCheckedChange = null) }
                )
            }
        }
    ) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            // CRITICAL FIX: Hide sheet if OUTDOORS OR if in Surveyor Mode
            sheetPeekHeight = if (trackingState == TrackingState.OUTDOORS || isSurveyorMode) 0.dp else 80.dp,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                // Only show search if we are NOT currently routing and NOT in surveyor mode
                if (activeDestination == null && !isSurveyorMode) {
                    SearchBottomSheetContent(
                        allNodes = activeNodes,
                        onNodeSelected = { selectedNode ->
                            // Dismiss keyboard/sheet and calculate Dijkstra path
                            viewModel.requestRoute(selectedNode.id)
                        }
                    )
                } else {
                    Spacer(modifier = Modifier.height(1.dp)) // Empty dummy for Scaffold
                }
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (isSosActive) "EMERGENCY MODE" else "IndoorPulse",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    actions = {
                        val isAccessible by viewModel.accessibleMode.collectAsState()
                        IconButton(
                            onClick = { viewModel.toggleAccessibleMode() },
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .background(
                                    color = if (isAccessible) Color(0xFF00C853) else Color(0x33FFFFFF),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(Icons.Default.Accessible, contentDescription = "Accessible", tint = Color.White)
                        }
                        if (trackingState == TrackingState.OUTDOORS) {
                            IconButton(onClick = { showManualOverrideDialog = true }) {
                                Icon(
                                    Icons.Default.LocationCity,
                                    contentDescription = "Select Building",
                                    tint = Color.White
                                )
                            }
                        }
                        if (trackingState == TrackingState.INDOORS) {
                            IconButton(onClick = { viewModel.confirmBuildingExit() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = "Exit Building",
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1A1A2E),
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        ) { innerPadding ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color(0xFF1E1E1E)) // Dark Map Background
            ) {
                if (trackingState == TrackingState.INDOORS) {
                    // 1. RENDER MAP CANVAS (using activeNodes instead of raw DB nodes)
                MapCanvas(
                    uiState = uiState,
                    estimatedPosition = estimatedPosition,
                    isSurveyorMode = isSurveyorMode,
                    surveyorNodes = activeNodes,
                    surveyorEdges = surveyorEdges,
                    lastDroppedNodeId = lastDroppedNodeId,
                    snapTargetNodeId = snapTargetNodeId,
                    selectedNode = selectedNode,
                    onNodeSelected = { viewModel.selectNode(it) },
                    recenterTrigger = recenterTrigger,
                    viewModel = viewModel,
                    onNodeLongClick = { clickedNode ->
                        viewModel.stageNodeForDeletion(clickedNode)
                    },
                    onCrosshairCoordinateChanged = { x, y ->
                        targetSyncX = x
                        targetSyncY = y
                    },
                    allNodes = activeNodes,
                    currentFloor = currentFloor,
                    is3DMode = is3DMode,
                    modifier = Modifier.fillMaxSize()
                )

                // 2. THE GLASS CROSSHAIR (Only visible in Surveyor Mode)
                if (isSurveyorMode) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Draw a subtle, professional crosshair
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Center Crosshair",
                            tint = Color.Red.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // 3. CONTEXTUAL OVERLAYS
                if (isSurveyorMode) {
                    // Show raw telemetry ONLY to admins
                    Text(
                        text = "Pos: (%.2f, %.2f) | Heading: %.1f°".format(
                            estimatedPosition.first,
                            estimatedPosition.second,
                            uiState.headingDegrees
                        ),
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .align(Alignment.TopStart)
                    )

                    // RENDER SURVEYOR HUD AT ABSOLUTE BOTTOM
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .imePadding() // Pushes up if keyboard opens
                    ) {
                        SurveyorHUD(
                            viewModel = viewModel,
                            selectedNodeType = selectedNodeType,
                            onNodeTypeSelected = { selectedNodeType = it },
                            onDropNode = {
                                if (selectedNodeType == "Waypoint") {
                                    viewModel.forceDropNewNode("", selectedNodeType)
                                } else {
                                    showNamingDialog = true
                                }
                            },
                            onUndo = { viewModel.performUndo() },
                            onRedo = { viewModel.performRedo() },
                            onSyncPosition = {
                                viewModel.syncPositionTo(targetSyncX, targetSyncY)
                            },
                            canUndo = canUndo,
                            canRedo = canRedo
                        )
                    }
                } else {
                    // 3. RENDER SOS FAB (Top Left) - Only if NOT currently navigating
                    if (activeDestination == null) {
                        Box(modifier = Modifier.align(Alignment.TopStart)) {
                            EmergencyFab(
                                isActive = isSosActive,
                                onToggle = { viewModel.toggleSosMode(it) }
                            )
                        }
                    }
                }

                // 2. RENDER NAVIGATION BANNER (Top Center)
                if (activeDestination != null) {
                    Box(modifier = Modifier.align(Alignment.TopCenter)) {
                        NavigationBanner(
                            destinationName = activeDestination!!.label,
                            isEmergency = isSosActive,
                            onCancel = {
                                viewModel.clearRoute()
                                if (isSosActive) viewModel.toggleSosMode(false)
                            }
                        )
                    }
                }

                // RIGHT-SIDE FLOATING CONTROLS (Floor Selector, 3D Button, Recenter Button)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = if (isSurveyorMode) 180.dp else 24.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Filter out floors if standard user, or allow extended range for surveyor
                    val displayFloors = if (isSurveyorMode) {
                        val min = (availableFloors.minOrNull() ?: 0)
                        val max = (availableFloors.maxOrNull() ?: 0)
                        ((min - 1)..(max + 1)).toList().sortedDescending() // HIGHEST TO LOWEST
                    } else {
                        availableFloors.sortedDescending() // HIGHEST TO LOWEST
                    }

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 4.dp
                    ) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            displayFloors.forEach { floor ->
                                val isSelected = currentFloor == floor
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, 
                                            CircleShape
                                        )
                                        .clickable { viewModel.setFloor(floor) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("L$floor", color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }

                    // 3D CAD Toggle
                    FloatingActionButton(onClick = { viewModel.toggle3DMode() }) { 
                        Icon(if (is3DMode) Icons.Default.ViewInAr else Icons.Default.Map, contentDescription = "Toggle 3D") 
                    }

                    // Recenter Camera
                    FloatingActionButton(onClick = { viewModel.recenterCamera() }) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Recenter")
                    }
                }

                // TRANSIT UI OVERLAY
                if (transitInfo != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.85f)) // Dim the map heavily
                            .clickable(enabled = false) {} // Consume clicks so map underneath isn't touched
                            .pointerInput(Unit) { detectTapGestures { } }
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (transitInfo!!.portalType == "Elevator") Icons.Default.Elevator else Icons.Default.Stairs,
                                contentDescription = "Transit",
                                tint = Color.White,
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Text(
                                text = "Take ${transitInfo!!.portalType}",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "to Floor L${transitInfo!!.targetFloor}",
                                color = Color.Cyan,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black
                            )
                            
                            Spacer(modifier = Modifier.height(64.dp))
                            
                            Button(
                                onClick = { viewModel.completeTransit() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("I've Arrived at L${transitInfo!!.targetFloor}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                } else {
                    OutdoorDashboard(
                        viewModel = viewModel,
                        onManualOverrideClick = { showManualOverrideDialog = true }
                    )
                }
            }
        }
    }

    if (nearExitNode != null) {
        ExitBuildingDialog(
            nearExitNode = nearExitNode!!,
            viewModel = viewModel,
            onConfirmExit = { viewModel.confirmBuildingExit() },
            onDismiss = { viewModel.resetExitDebounce() }
        )
    }

    if (showManualOverrideDialog) {
        ManualOverrideDialog(
            viewModel = viewModel,
            onDismiss = { showManualOverrideDialog = false }
        )
    }

    if (stagedNode != null) {
        NodeEditDialog(
            node = stagedNode!!,
            viewModel = viewModel,
            onDismiss = { viewModel.clearStagedNode() }
        )
    }
}

@Composable
fun ExitBuildingDialog(
    nearExitNode: NodeEntity,
    viewModel: MapViewModel,
    onConfirmExit: () -> Unit,
    onDismiss: () -> Unit = { viewModel.resetExitDebounce() }
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Leaving the building?",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = "You are near ${nearExitNode.label}. Do you want to end indoor navigation and switch to outdoor mode?",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmExit,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Yes, Exit")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.resetExitDebounce() }) {
                Text("Stay Indoors")
            }
        }
    )
}

@Composable
fun ManualOverrideDialog(
    viewModel: MapViewModel,
    onDismiss: () -> Unit
) {
    var buildings by remember { mutableStateOf<List<BuildingEntity>>(emptyList()) }
    var selectedBuilding by remember { mutableStateOf<BuildingEntity?>(null) }
    var entryPoints by remember { mutableStateOf<List<NodeEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isAddingBuilding by remember { mutableStateOf(false) }
    var newBuildingName by remember { mutableStateOf("") }
    var newBuildingRadius by remember { mutableStateOf("100") }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        isLoading = true
        buildings = viewModel.getAllBuildings()
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (selectedBuilding == null) "Select Building" else "Select Entry: ${selectedBuilding!!.name}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else if (selectedBuilding == null) {
                    OutlinedButton(
                        onClick = { isAddingBuilding = !isAddingBuilding },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isAddingBuilding) "Cancel" else "➕ Add New Building")
                    }

                    if (isAddingBuilding) {
                        OutlinedTextField(
                            value = newBuildingName,
                            onValueChange = { newBuildingName = it },
                            label = { Text("Building Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newBuildingRadius,
                            onValueChange = { newBuildingRadius = it },
                            label = { Text("Geofence Radius (meters)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Button(
                            onClick = {
                                if (newBuildingName.isNotBlank()) {
                                    viewModel.createNewBuilding(
                                        newBuildingName,
                                        newBuildingRadius.toFloatOrNull() ?: 100f
                                    )
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save & Enter Building")
                        }
                    }

                    Text(
                        text = "Choose a building to enter manually:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (buildings.isEmpty()) {
                        Text(
                            text = "No buildings found in database.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            buildings.forEach { building ->
                                OutlinedButton(
                                    onClick = {
                                        selectedBuilding = building
                                        coroutineScope.launch {
                                            isLoading = true
                                            entryPoints = viewModel.getBuildingEntryPoints(building.buildingId)
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(building.name, fontWeight = FontWeight.SemiBold)
                                        Text("${building.geofenceRadius.toInt()}m", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val building = selectedBuilding!!
                    Text(
                        text = "Select your entry door:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entryPoints.isEmpty()) {
                        Text(
                            text = "No dedicated doors mapped. Enter at ground level.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                val fallbackNode = NodeEntity(
                                    nodeId = "entry_manual_${building.buildingId}",
                                    floorId = "f1",
                                    x = 0f,
                                    y = 0f,
                                    nodeType = "Building Entry",
                                    name = "Main Entrance",
                                    floorLevel = 0,
                                    buildingId = building.buildingId
                                )
                                viewModel.manualOverride(building.buildingId, fallbackNode)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Enter Building (Ground Floor)")
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            entryPoints.forEach { entryNode ->
                                Button(
                                    onClick = {
                                        viewModel.manualOverride(building.buildingId, entryNode)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(entryNode.label, fontWeight = FontWeight.SemiBold)
                                        Text("Floor L${entryNode.floorLevel}", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedBuilding != null) {
                    TextButton(onClick = { selectedBuilding = null }) {
                        Text("Back")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun SurveyorHUD(
    viewModel: MapViewModel,
    selectedNodeType: String,
    onNodeTypeSelected: (String) -> Unit,
    onDropNode: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSyncPosition: () -> Unit = {},
    modifier: Modifier = Modifier,
    canUndo: Boolean = true,
    canRedo: Boolean = true
) {
    val selectedNode by viewModel.selectedNode.collectAsState()
    val isPortal = selectedNode != null && (
        selectedNode!!.nodeType.equals("Stairs", ignoreCase = true) ||
        selectedNode!!.nodeType.equals("Elevator", ignoreCase = true)
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF2C2C2C),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ROW 1: Scrollable Node Types
            val types = listOf("Waypoint", "Junction", "Room Door", "Washroom", "Stairs", "Elevator", "Emergency Exit", "Building Entry")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.forEach { type ->
                    FilterChip(
                        selected = selectedNodeType == type,
                        onClick = { onNodeTypeSelected(type) },
                        label = { Text(type, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (isPortal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(onClick = { viewModel.extrudePortal(1) }) { Text("Push Up (L+1)") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.extrudePortal(-1) }) { Text("Push Down (L-1)") }
                }
            }

            // ROW 2: Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onSyncPosition,
                        modifier = Modifier.background(Color.Red.copy(alpha = 0.2f), CircleShape).size(40.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Sync", tint = Color.Red)
                    }
                    IconButton(
                        onClick = { viewModel.undoLastNode() },
                        enabled = canUndo,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (canUndo) Color.White else Color.Gray)
                    }
                    IconButton(
                        onClick = { viewModel.stageNodeForDeletion(selectedNode!!) },
                        enabled = selectedNode != null,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit Selected",
                            tint = if (selectedNode != null) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.snapToNearestNode() },
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = "Snap", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Snap", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = onDropNode,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Drop")
                    }
                }
            }
        }
    }
}

@Composable
fun OutdoorDashboard(
    viewModel: MapViewModel,
    onManualOverrideClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Explore,
            contentDescription = "Outdoor Mode",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Outdoor Mode Active: Scanning for Geofences...",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Walk towards a registered entrance or select a building manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onManualOverrideClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationCity,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("View Known Buildings / Manual Entry", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NodeEditDialog(
    node: NodeEntity,
    viewModel: MapViewModel,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Node ID & Floor
                Column {
                    Text(
                        text = "Edit Node: ${node.label}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "ID: ${node.nodeId} | Floor: L${node.floorLevel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                // Type selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Node Type",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val types = listOf("Waypoint", "Junction", "Room Door", "Washroom", "Stairs", "Elevator", "Emergency Exit", "Building Entry")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        types.forEach { type ->
                            FilterChip(
                                selected = node.nodeType.equals(type, ignoreCase = true),
                                onClick = { viewModel.updateNodeType(node, type) },
                                label = { Text(type, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Retroactive Portal Link (if Stairs or Elevator)
                if (node.nodeType.equals("Stairs", ignoreCase = true) || node.nodeType.equals("Elevator", ignoreCase = true)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(
                            text = "Retroactive Portal Link",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.linkPortalToFloor(node, node.floorLevel + 1)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Link to L${node.floorLevel + 1}")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.linkPortalToFloor(node, node.floorLevel - 1)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Link to L${node.floorLevel - 1}")
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Actions: Delete Node (Red OutlinedButton), Resume Path & Close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteNode(node)
                            onDismiss()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete Node")
                    }

                    OutlinedButton(onClick = { viewModel.resumePathFromNode(node); onDismiss() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Resume Path Here")
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}


