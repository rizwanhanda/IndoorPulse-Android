package com.indoorpulse.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

val nodeTypes = listOf(
    "Waypoint",       // Unnamed point for drawing curved paths
    "Junction",       // Hallway intersections
    "Room Door",      // Standard destinations
    "Washroom",       // Common POI
    "Stairs",         // Multi-floor transitions
    "Emergency Exit", // SOS Routing Targets
    "Building Entry"  // GPS Handshake targets
)

@Composable
fun SurveyorBottomSheet(
    selectedNodeType: String,
    onNodeTypeSelected: (String) -> Unit,
    onDropNode: () -> Unit,
    onSnapToNode: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
    onSyncPosition: () -> Unit = {}
) {
    SurveyorHUD(
        selectedNodeType = selectedNodeType,
        onNodeTypeSelected = onNodeTypeSelected,
        onDropNode = onDropNode,
        onSnapToNode = onSnapToNode,
        onUndo = onUndo,
        onRedo = onRedo,
        onSyncPosition = onSyncPosition,
        modifier = modifier
    )
}
