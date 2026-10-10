package com.indoorpulse.presentation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type

@Composable
fun SearchBottomSheetContent(
    allNodes: List<NodeEntity>,
    onNodeSelected: (NodeEntity) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    // Filter the nodes dynamically as the user types
    val filteredNodes = allNodes.filter { 
        it.label.contains(searchQuery, ignoreCase = true) 
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .imePadding(), // Pushes up gracefully with the keyboard
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // We REMOVED the custom Box() drag handle because BottomSheetScaffold provides one natively.
        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search for a room or POI...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Render the actual real-time database results
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false) // Expands to fit content, up to the screen height
        ) {
            items(filteredNodes) { node ->
                ListItem(
                    headlineContent = { Text(node.label, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(if (node.type == "EXIT") "Emergency Exit" else "Standard POI") },
                    leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = "Node") },
                    modifier = Modifier.clickable { onNodeSelected(node) }
                )
                HorizontalDivider()
            }
        }
    }
}
