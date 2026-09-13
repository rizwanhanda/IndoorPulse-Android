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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.indoorpulse.presentation.viewmodel.MapUiState
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.EdgeEntity

private fun getProjectedPoint(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
    val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    if (l2 == 0f) return Pair(x1, y1)
    val t = Math.max(0f, Math.min(1f, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2))
    return Pair(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
}

private fun distanceTo(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    return Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
}

@Composable
fun MapCanvas(
    uiState: MapUiState,
    estimatedPosition: Pair<Float, Float>,
    isSurveyorMode: Boolean = false,
    surveyorNodes: List<NodeEntity> = emptyList(),
    surveyorEdges: List<EdgeEntity> = emptyList(),
    lastDroppedNodeId: String? = null,
    snapTargetNodeId: String? = null,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(20f) }
    var offset by remember { mutableStateOf(Offset(500f, 500f)) }

    val animatedX by animateFloatAsState(
        targetValue = estimatedPosition.first,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "userX"
    )
    val animatedY by animateFloatAsState(
        targetValue = estimatedPosition.second,
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
        fun projY(y: Float) = offset.y - (y * scale)

        val userCanvasX = projX(estimatedPosition.first)
        val userCanvasY = projY(estimatedPosition.second)

            // 1. Draw all database edges
        surveyorEdges.forEach { edge ->
            val fromNode = surveyorNodes.find { it.nodeId == edge.fromNodeId }
            val toNode = surveyorNodes.find { it.nodeId == edge.toNodeId }
            if (fromNode != null && toNode != null) {
                drawLine(
                    color = Color(0xFF708090), // Slate Gray
                    start = Offset(projX(fromNode.x), projY(fromNode.y)),
                    end = Offset(projX(toNode.x), projY(toNode.y)),
                    strokeWidth = 4f * scale * 0.1f // relative to scale or fixed? instructions: stroke width: 4.dp (just use 8f absolute to keep it simple, or `8f * density`)
                ) // Let's just use fixed pixel size for lines since the instructions just said 4.dp. 
                // We'll use 8f to roughly match.
            }
        }
        
        surveyorEdges.forEach { edge ->
            val fromNode = surveyorNodes.find { it.nodeId == edge.fromNodeId }
            val toNode = surveyorNodes.find { it.nodeId == edge.toNodeId }
            if (fromNode != null && toNode != null) {
                drawLine(
                    color = Color(0xFF708090), // Slate Gray
                    start = Offset(projX(fromNode.x), projY(fromNode.y)),
                    end = Offset(projX(toNode.x), projY(toNode.y)),
                    strokeWidth = 8f
                )
            }
        }



        // 2. Draw Active Route over edges
        if (!isSurveyorMode && uiState.activeRoute.isNotEmpty()) {
            val px = estimatedPosition.first
            val py = estimatedPosition.second

            if (uiState.activeRoute.size >= 2) {
                var closestSegmentIndex = 0
                var bestProj = Pair(uiState.activeRoute[0].x, uiState.activeRoute[0].y)
                var minDistToRoute = Float.MAX_VALUE
        
                // 1. Find the closest physical segment on the active route
                for (i in 0 until uiState.activeRoute.size - 1) {
                    val n1 = uiState.activeRoute[i]
                    val n2 = uiState.activeRoute[i + 1]
                    val proj = getProjectedPoint(px, py, n1.x, n1.y, n2.x, n2.y)
                    val dist = distanceTo(px, py, proj.first, proj.second)
                    if (dist < minDistToRoute) {
                        minDistToRoute = dist
                        closestSegmentIndex = i
                        bestProj = proj
                    }
                }
        
                // 2. THE LIFELINE: Dashed line from user to the anchor point
                drawLine(
                    color = Color.Cyan.copy(alpha = 0.6f),
                    start = Offset(projX(px), projY(py)),
                    end = Offset(projX(bestProj.first), projY(bestProj.second)),
                    strokeWidth = 6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )
        
                // 3. THE SHRINKING SEGMENT: Solid line from anchor point to the next node
                val nextNode = uiState.activeRoute[closestSegmentIndex + 1]
                drawLine(
                    color = Color.Cyan,
                    start = Offset(projX(bestProj.first), projY(bestProj.second)),
                    end = Offset(projX(nextNode.x), projY(nextNode.y)),
                    strokeWidth = 12f
                )
        
                // 4. REMAINING ROUTE: Solid lines for the rest of the path
                for (i in closestSegmentIndex + 1 until uiState.activeRoute.size - 1) {
                    val n1 = uiState.activeRoute[i]
                    val n2 = uiState.activeRoute[i + 1]
                    drawLine(
                        color = Color.Cyan,
                        start = Offset(projX(n1.x), projY(n1.y)),
                        end = Offset(projX(n2.x), projY(n2.y)),
                        strokeWidth = 12f
                    )
                }
            } else {
                // Final destination stretch
                val dest = uiState.activeRoute[0]
                drawLine(
                    color = Color.Cyan.copy(alpha = 0.6f),
                    start = Offset(projX(px), projY(py)),
                    end = Offset(projX(dest.x), projY(dest.y)),
                    strokeWidth = 6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )
            }
        }

        // 3. Draw Nodes
        surveyorNodes.forEach { node ->
            if (node.nodeType == "Waypoint" || node.name.isBlank()) {
                drawCircle(
                    color = Color.Gray,
                    radius = 6f, // ~ 3.dp depending on scale preference
                    center = Offset(projX(node.x), projY(node.y))
                )
            } else {
                val color = when (node.nodeType) {
                    "Junction" -> Color.Red
                    "Room Door" -> Color.Blue
                    "Stairs" -> Color.Green
                    else -> Color.Yellow
                }
                drawCircle(
                    color = color,
                    radius = 12f, // 6.dp ~ 12px
                    center = Offset(projX(node.x), projY(node.y))
                )
                
                if (node.name.isNotBlank()) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = node.name,
                        topLeft = Offset(projX(node.x) - 20f, projY(node.y) - 40f), // Label above
                        style = TextStyle(color = Color.White)
                    )
                }
            }
            
            if (isSurveyorMode && snapTargetNodeId != null && node.nodeId == snapTargetNodeId) {
                drawCircle(
                    color = Color.Yellow,
                    radius = 24f, // 12.dp hollow circle
                    center = Offset(projX(node.x), projY(node.y)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f) // 3.dp thickness
                )
            }
        }

        // 4. Draw Rubber-Band & Snapping (Surveyor Mode Only)
        if (isSurveyorMode) {
            if (snapTargetNodeId != null) {
                val snapNode = surveyorNodes.find { it.nodeId == snapTargetNodeId }
                if (snapNode != null) {
                    drawLine(
                        color = Color.Yellow,
                        start = Offset(userCanvasX, userCanvasY),
                        end = Offset(projX(snapNode.x), projY(snapNode.y)),
                        strokeWidth = 6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                    )
                }
            } else if (lastDroppedNodeId != null) {
                val lastNode = surveyorNodes.find { it.nodeId == lastDroppedNodeId }
                if (lastNode != null) {
                    drawLine(
                        color = Color.Yellow,
                        start = Offset(projX(lastNode.x), projY(lastNode.y)),
                        end = Offset(userCanvasX, userCanvasY),
                        strokeWidth = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }
            }
        }

        // 1. Draw the Blue Puck Base
        drawCircle(color = Color.Blue, radius = 12.dp.toPx(), center = Offset(userCanvasX, userCanvasY))

        // Standard compass and Canvas both rotate clockwise. 
        // We simply offset by 180 to match the map's orientation without mirroring the turns.
        val visualHeading = (animatedHeading + 180f) % 360f

        withTransform({
            // CRITICAL FIX: Adding 180f here visually flips the drawing so the sharp tip points Forward
            rotate(degrees = (visualHeading + 180f) % 360f, pivot = Offset(userCanvasX, userCanvasY))
        }) {
            val arrowPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(userCanvasX, userCanvasY - 35f)             
                lineTo(userCanvasX + 25f, userCanvasY + 15f)       
                lineTo(userCanvasX, userCanvasY + 5f)              
                lineTo(userCanvasX - 25f, userCanvasY + 15f)       
                close()
            }
            drawPath(path = arrowPath, color = Color.White)
            drawPath(path = arrowPath, color = Color.DarkGray, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}
