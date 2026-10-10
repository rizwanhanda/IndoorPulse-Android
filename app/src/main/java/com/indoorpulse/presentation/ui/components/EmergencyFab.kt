package com.indoorpulse.presentation.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun EmergencyFab(isActive: Boolean, onToggle: (Boolean) -> Unit) {
    FloatingActionButton(
        onClick = { onToggle(!isActive) },
        containerColor = if (isActive) Color.White else Color.Red,
        contentColor = if (isActive) Color.Red else Color.White,
        modifier = Modifier
            .padding(16.dp)
            // Add a thick glowing border if active to make it obvious
            .border(
                width = if (isActive) 4.dp else 0.dp, 
                color = if (isActive) Color.Red else Color.Transparent, 
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Icon(Icons.Default.Warning, contentDescription = "SOS Toggle")
    }
}
