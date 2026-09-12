package com.indoorpulse.presentation.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Undo
import androidx.compose.ui.Alignment
import com.indoorpulse.presentation.viewmodel.MapUiState
import com.indoorpulse.presentation.viewmodel.MapViewModel
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.EdgeEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val estimatedPosition by viewModel.estimatedPosition.collectAsState()
    val isSurveyorMode by viewModel.isSurveyorMode.collectAsState()
    val surveyorNodes by viewModel.nodes.collectAsState()
    val surveyorEdges by viewModel.edges.collectAsState()
    val lastDroppedNodeId by viewModel.lastDroppedNodeId.collectAsState()
    val snapTargetNodeId by viewModel.snapTargetNodeId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IndoorPulse") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Surveyor", style = MaterialTheme.typography.labelSmall)
                        Switch(
                            checked = isSurveyorMode,
                            onCheckedChange = { viewModel.isSurveyorMode.value = it },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        bottomBar = {
            BottomControlBar(
                uiState = uiState,
                surveyorNodes = surveyorNodes,
                onDestinationSelected = { viewModel.selectDestination(it) },
                onResetClicked = { viewModel.resetPosition() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = "Status: ${uiState.statusMessage}",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Pos: (%.2f, %.2f) | Heading: %.1f°".format(
                    uiState.userPosition.x,
                    uiState.userPosition.y,
                    uiState.headingDegrees
                ),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MapCanvas(
                    uiState = uiState,
                    estimatedPosition = estimatedPosition,
                    isSurveyorMode = isSurveyorMode,
                    surveyorNodes = surveyorNodes,
                    surveyorEdges = surveyorEdges,
                    lastDroppedNodeId = lastDroppedNodeId,
                    snapTargetNodeId = snapTargetNodeId,
                    modifier = Modifier.fillMaxSize()
                )
                
                if (isSurveyorMode) {
                    SurveyorHUD(
                        onDropNode = { name, type -> viewModel.forceDropNewNode(name, type) },
                        onSnapNode = { viewModel.snapToTargetNode() },
                        onUndo = { viewModel.undoLastNode() },
                        snapTargetNodeId = snapTargetNodeId,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyorHUD(
    onDropNode: (String, String) -> Unit,
    onSnapNode: () -> Unit,
    onUndo: () -> Unit,
    snapTargetNodeId: String?,
    modifier: Modifier = Modifier
) {
    var selectedChip by remember { mutableStateOf("Junction") }
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
                    onDropNode(nodeNameInput, selectedChip)
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
    
    Card(
        modifier = modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf("Junction", "Room Door", "Stairs", "Waypoint").forEach { type ->
                    FilterChip(
                        selected = selectedChip == type,
                        onClick = { selectedChip = type },
                        label = { Text(type) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallFloatingActionButton(onClick = onUndo) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                
                Button(
                    onClick = {
                        if (selectedChip == "Waypoint") {
                            onDropNode("", selectedChip)
                        } else {
                            showNamingDialog = true
                        }
                    }
                ) {
                    Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Drop Node")
                }

                FilledTonalButton(
                    onClick = onSnapNode,
                    enabled = snapTargetNodeId != null
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Snap to Node")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomControlBar(
    uiState: MapUiState,
    surveyorNodes: List<NodeEntity>,
    onDestinationSelected: (String) -> Unit,
    onResetClicked: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val namedNodes = surveyorNodes.filter { it.name.isNotBlank() && it.nodeType != "Waypoint" }
        val selectedNode = namedNodes.find { it.nodeId == uiState.selectedDestinationNodeId }
        val selectedName = selectedNode?.name ?: "Select Destination"

        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.weight(1f).padding(end = 16.dp)
        ) {
            Text(selectedName)
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Select Destination") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        namedNodes.forEach { node ->
                            TextButton(
                                onClick = {
                                    onDestinationSelected(node.nodeId)
                                    showDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(node.name)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Button(onClick = onResetClicked) {
            Text("Reset")
        }
    }
}
