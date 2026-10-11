package com.indoorpulse.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SurveyorHUD(
    selectedNodeType: String,
    onNodeTypeSelected: (String) -> Unit,
    onDropNode: () -> Unit,
    onSnapToNode: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSyncPosition: () -> Unit = {},
    modifier: Modifier = Modifier,
    canUndo: Boolean = true,
    canRedo: Boolean = true,
    isSnapEnabled: Boolean = true
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ROW 1: Scrollable Node Types
            val types = listOf("Waypoint", "Junction", "Room Door", "Washroom", "Stairs", "Emergency Exit", "Building Entry")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(types) { type ->
                    FilterChip(
                        selected = selectedNodeType == type,
                        onClick = { onNodeTypeSelected(type) },
                        label = { Text(type) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // ROW 2: Actions & Unified Undo/Redo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Unified Undo/Redo Group
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background, RoundedCornerShape(50)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    VerticalDivider(modifier = Modifier.height(20.dp).width(1.dp))
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                }

                // NEW: Sync Position Button
                IconButton(
                    onClick = onSyncPosition,
                    modifier = Modifier.background(Color.Red.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Sync Position", tint = Color.Red)
                }

                Button(onClick = onDropNode) {
                    Icon(Icons.Default.AddLocation, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Drop")
                }

                OutlinedButton(onClick = onSnapToNode, enabled = isSnapEnabled) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Snap")
                }
            }
        }
    }
}
