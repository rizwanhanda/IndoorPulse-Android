package com.indoorpulse.presentation.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.indoorpulse.presentation.viewmodel.MapUiState

@Composable
fun MapCanvas(
    uiState: MapUiState,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(20f) }
    var offset by remember { mutableStateOf(Offset(500f, 500f)) }

    val animatedX by animateFloatAsState(
        targetValue = uiState.userPosition.x,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "userX"
    )
    val animatedY by animateFloatAsState(
        targetValue = uiState.userPosition.y,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "userY"
    )

    var previousHeading by remember { mutableStateOf(uiState.headingDegrees) }
    var currentHeadingTarget by remember { mutableStateOf(uiState.headingDegrees) }

    val diff = (uiState.headingDegrees - previousHeading) % 360f
    val shortestDiff = if (diff > 180f) diff - 360f else if (diff < -180f) diff + 360f else diff
    currentHeadingTarget += shortestDiff
    previousHeading = uiState.headingDegrees

    val animatedHeading by animateFloatAsState(
        targetValue = currentHeadingTarget,
        animationSpec = tween(durationMillis = 150),
        label = "heading"
    )

    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(5f, 100f)
                    val newOffset = (offset + pan - centroid) * zoom + centroid
                    offset = newOffset
                }
            }
    ) {
        fun projX(x: Float) = offset.x + (x * scale)
        fun projY(y: Float) = offset.y - (y * scale) // Invert Y here because Canvas +Y is Downwards

        uiState.indoorMap.edges.forEach { edge ->
            val fromNode = uiState.indoorMap.nodes[edge.fromNodeId]
            val toNode = uiState.indoorMap.nodes[edge.toNodeId]
            if (fromNode != null && toNode != null) {
                drawLine(
                    color = Color.DarkGray,
                    start = Offset(projX(fromNode.coordinate.x), projY(fromNode.coordinate.y)),
                    end = Offset(projX(toNode.coordinate.x), projY(toNode.coordinate.y)),
                    strokeWidth = 5f
                )
            }
        }

        uiState.indoorMap.nodes.values.forEach { node ->
            drawCircle(
                color = Color.LightGray,
                radius = 0.5f * scale,
                center = Offset(projX(node.coordinate.x), projY(node.coordinate.y))
            )
        }

        if (uiState.activeRoute.size >= 2) {
            for (i in 0 until uiState.activeRoute.size - 1) {
                val p1 = uiState.activeRoute[i]
                val p2 = uiState.activeRoute[i + 1]
                drawLine(
                    color = Color.Cyan,
                    start = Offset(projX(p1.x), projY(p1.y)),
                    end = Offset(projX(p2.x), projY(p2.y)),
                    strokeWidth = 10f
                )
            }
        }

        val userCanvasX = projX(animatedX)
        val userCanvasY = projY(animatedY)

        drawCircle(
            color = Color.Red,
            radius = 0.8f * scale,
            center = Offset(userCanvasX, userCanvasY)
        )

        val coneLength = 3f * scale

        withTransform({
            translate(userCanvasX, userCanvasY)
            rotate(animatedHeading, Offset.Zero)
        }) {
            val conePath = Path().apply {
                moveTo(0f, -coneLength)
                lineTo(-coneLength * 0.5f, -coneLength * 0.2f)
                lineTo(coneLength * 0.5f, -coneLength * 0.2f)
                close()
            }
            drawPath(path = conePath, color = Color(0x88FF0000))
        }

        uiState.indoorMap.nodes.values.forEach { node ->
            drawText(
                textMeasurer = textMeasurer,
                text = node.label,
                topLeft = Offset(projX(node.coordinate.x) + 10f, projY(node.coordinate.y) - 10f),
                style = TextStyle(color = Color.White)
            )
        }
    }
}
