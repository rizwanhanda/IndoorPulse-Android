package com.indoorpulse.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.presentation.ui.components.EmergencyFab
import com.indoorpulse.presentation.ui.components.NavigationBanner
import com.indoorpulse.presentation.ui.components.SearchBottomSheetContent
import com.indoorpulse.presentation.viewmodel.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel) {
    val isSurveyorMode by viewModel.isSurveyorMode.collectAsState()
    val isSosActive by viewModel.isSosActive.collectAsState()
    val activeDestination by viewModel.activeDestination.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val estimatedPosition by viewModel.estimatedPosition.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState(initial = emptyList())
    val surveyorNodes by viewModel.nodes.collectAsState()
    val surveyorEdges by viewModel.edges.collectAsState()
    val lastDroppedNodeId by viewModel.lastDroppedNodeId.collectAsState()
    val snapTargetNodeId by viewModel.snapTargetNodeId.collectAsState()
    val currentFloor by viewModel.currentFloor.collectAsState()
    val recenterTrigger by viewModel.recenterTrigger.collectAsState()

    val scaffoldState = rememberBottomSheetScaffoldState()

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (activeDestination != null) 0.dp else 80.dp, // Hide sheet if routing
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            // Only show search if we are NOT currently routing
            if (activeDestination == null) {
                SearchBottomSheetContent(
                    allNodes = allNodes,
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
                colors = TopAppBarDefaults.topAppBarColors(
                    // DYNAMIC SOS COLOR SHIFT
                    containerColor = if (isSosActive) Color.Red else MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isSurveyorMode) "Surveyor" else "Standard", color = Color.White)
                        Switch(
                            checked = isSurveyorMode,
                            onCheckedChange = { viewModel.toggleSurveyorMode(it) },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF1E1E1E)) // Dark Map Background
        ) {

            // 1. RENDER MAP CANVAS
            MapCanvas(
                uiState = uiState,
                estimatedPosition = estimatedPosition,
                isSurveyorMode = isSurveyorMode,
                surveyorNodes = surveyorNodes,
                surveyorEdges = surveyorEdges,
                lastDroppedNodeId = lastDroppedNodeId,
                snapTargetNodeId = snapTargetNodeId,
                recenterTrigger = recenterTrigger,
                modifier = Modifier.fillMaxSize()
            )

            // 2. CONTEXTUAL OVERLAYS
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

                SurveyorHUD(
                    onDropNode = { name, type -> viewModel.forceDropNewNode(name, type) },
                    onSnapNode = { viewModel.snapToTargetNode() },
                    onUndo = { viewModel.undoLastNode() },
                    snapTargetNodeId = snapTargetNodeId,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp)
                )
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

            // 3. RIGHT-SIDE ACTION STACK
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(16.dp),
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
