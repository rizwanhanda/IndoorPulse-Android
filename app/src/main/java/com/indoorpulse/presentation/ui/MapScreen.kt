package com.indoorpulse.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indoorpulse.presentation.viewmodel.MapUiState
import com.indoorpulse.presentation.viewmodel.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IndoorPulse") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        bottomBar = {
            BottomControlBar(
                uiState = uiState,
                onDestinationSelected = { viewModel.selectDestination(it) },
                onResetClicked = { viewModel.resetToNode("entrance") }
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

            MapCanvas(
                uiState = uiState,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomControlBar(
    uiState: MapUiState,
    onDestinationSelected: (String) -> Unit,
    onResetClicked: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            val selectedName = uiState.selectedDestinationNodeId?.let { 
                uiState.indoorMap.nodes[it]?.label 
            } ?: "Select Destination"

            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                uiState.indoorMap.nodes.values.forEach { node ->
                    DropdownMenuItem(
                        text = { Text(node.label) },
                        onClick = {
                            onDestinationSelected(node.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Button(onClick = onResetClicked) {
            Text("Reset")
        }
    }
}
