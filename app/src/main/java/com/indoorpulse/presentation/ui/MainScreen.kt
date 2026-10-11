package com.indoorpulse.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.presentation.ui.components.EmergencyFab
import com.indoorpulse.presentation.ui.components.NavigationBanner
import com.indoorpulse.presentation.ui.components.SearchBottomSheetContent
import com.indoorpulse.presentation.ui.components.SurveyorHUD
import com.indoorpulse.presentation.viewmodel.MapViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel) {
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
    val recenterTrigger by viewModel.recenterTrigger.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()

    var targetSyncX by remember { mutableStateOf(0f) }
    var targetSyncY by remember { mutableStateOf(0f) }

    var selectedNodeType by remember { mutableStateOf("Junction") }
    var showNamingDialog by remember { mutableStateOf(false) }
    var nodeNameInput by remember { mutableStateOf("") }

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
            // CRITICAL FIX: Hide sheet if routing OR if in Surveyor Mode
            sheetPeekHeight = if (activeDestination != null || isSurveyorMode) 0.dp else 80.dp,
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        // DYNAMIC SOS COLOR SHIFT
                        containerColor = if (isSosActive) Color.Red else MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White
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

                // 1. RENDER MAP CANVAS (using activeNodes instead of raw DB nodes)
                MapCanvas(
                    uiState = uiState,
                    estimatedPosition = estimatedPosition,
                    isSurveyorMode = isSurveyorMode,
                    surveyorNodes = activeNodes,
                    surveyorEdges = surveyorEdges,
                    lastDroppedNodeId = lastDroppedNodeId,
                    snapTargetNodeId = snapTargetNodeId,
                    recenterTrigger = recenterTrigger,
                    viewModel = viewModel,
                    onNodeLongClick = { clickedNode ->
                        viewModel.stageNodeForDeletion(clickedNode)
                    },
                    onCrosshairCoordinateChanged = { x, y ->
                        targetSyncX = x
                        targetSyncY = y
                    },
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
                            selectedNodeType = selectedNodeType,
                            onNodeTypeSelected = { selectedNodeType = it },
                            onDropNode = {
                                if (selectedNodeType == "Waypoint") {
                                    viewModel.forceDropNewNode("", selectedNodeType)
                                } else {
                                    showNamingDialog = true
                                }
                            },
                            onSnapToNode = { viewModel.snapToTargetNode() },
                            onUndo = { viewModel.performUndo() },
                            onRedo = { viewModel.performRedo() },
                            onSyncPosition = {
                                viewModel.syncPositionTo(targetSyncX, targetSyncY)
                            },
                            canUndo = canUndo,
                            canRedo = canRedo,
                            isSnapEnabled = snapTargetNodeId != null
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

                // RIGHT-SIDE FAB STACK (L1, Crosshair)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // CRITICAL FIX: Increased Surveyor Mode padding from 140.dp to 170.dp to prevent overlap
                        .padding(end = 16.dp, bottom = if (isSurveyorMode) 170.dp else 100.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Floor Switcher
                    FloatingActionButton(onClick = { viewModel.switchFloor() }) {
                        Text(currentFloor, fontWeight = FontWeight.Bold)
                    }

                    // Recenter Camera
                    FloatingActionButton(onClick = { viewModel.recenterCamera() }) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Recenter")
                    }
                }
            }
        }
    }
}
