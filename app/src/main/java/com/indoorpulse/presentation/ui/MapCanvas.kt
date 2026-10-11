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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type
import com.indoorpulse.presentation.viewmodel.MapViewModel
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.floorLevel

private fun getProjectedPoint(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
    val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    if (l2 == 0f) return Pair(x1, y1)
    val t = Math.max(0f, Math.min(1f, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2))
    return Pair(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
}

private fun distanceTo(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    return Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
}

fun findNodeAt(
    clickOffset: Offset,
    nodes: List<NodeEntity>,
    canvasOffset: Offset,
    scale: Float,
    hitRadiusPx: Float = 48f
): NodeEntity? {
    return nodes.minByOrNull { node ->
        val nodeScreenX = canvasOffset.x + (node.x * scale)
        val nodeScreenY = canvasOffset.y - (node.y * scale)
        val dx = clickOffset.x - nodeScreenX
        val dy = clickOffset.y - nodeScreenY
        dx * dx + dy * dy
    }?.takeIf { node ->
        val nodeScreenX = canvasOffset.x + (node.x * scale)
        val nodeScreenY = canvasOffset.y - (node.y * scale)
        val dx = clickOffset.x - nodeScreenX
        val dy = clickOffset.y - nodeScreenY
        Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() <= hitRadiusPx
    }
}

@Composable
fun MapCanvas(
    uiState: MapUiState = MapUiState(),
    estimatedPosition: Pair<Float, Float> = Pair(0f, 0f),
    isSurveyorMode: Boolean = false,
    surveyorNodes: List<NodeEntity> = emptyList(),
    surveyorEdges: List<EdgeEntity> = emptyList(),
    lastDroppedNodeId: String? = null,
    snapTargetNodeId: String? = null,
    selectedNode: NodeEntity? = null,
    onNodeSelected: ((NodeEntity?) -> Unit)? = null,
    recenterTrigger: Long = 0L,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel? = null,
    snackbarHostState: SnackbarHostState? = null,
    onNodeLongClick: ((NodeEntity) -> Unit)? = null,
    scale: Float = 20f,
    offset: Offset = Offset(500f, 500f),
    onTransform: ((Float, Offset) -> Unit)? = null,
    onSizeChanged: ((androidx.compose.ui.unit.IntSize) -> Unit)? = null,
    onCrosshairCoordinateChanged: ((Float, Float) -> Unit)? = null,
    allNodes: List<NodeEntity> = surveyorNodes,
    currentPose: Pair<Float, Float>? = estimatedPosition,
    currentHeading: Float = uiState.headingDegrees,
    currentFloor: Int = 0,
    is3DMode: Boolean = false,
    activeRoute: List<Coordinate>? = null
) {
    val activeNodes = if (surveyorNodes.isNotEmpty()) surveyorNodes else allNodes
    val currentPosePosition = currentPose ?: estimatedPosition
    val density = LocalDensity.current

    val currentNodes by rememberUpdatedState(activeNodes)
    val currentIsSurveyorMode by rememberUpdatedState(isSurveyorMode)
    val currentOnNodeLongClick by rememberUpdatedState(onNodeLongClick)
    val currentViewModel by rememberUpdatedState(viewModel)
    val currentSelectedNode by rememberUpdatedState(selectedNode)
    val currentOnNodeSelected by rememberUpdatedState(onNodeSelected)

    var currentScale by remember { mutableStateOf(scale) }
    var currentOffset by remember { mutableStateOf(offset) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    fun notifyCrosshair(
        w: Int = canvasSize.width,
        h: Int = canvasSize.height,
        off: Offset = currentOffset,
        sc: Float = currentScale
    ) {
        if (!is3DMode && w > 0 && h > 0 && sc > 0f) {
            val mapX = (w / 2f - off.x) / sc
            // CRITICAL FIX: Y-Up Cartesian inversion
            val mapY = -(h / 2f - off.y) / sc
            onCrosshairCoordinateChanged?.invoke(mapX, mapY)
        }
    }

    LaunchedEffect(currentScale, currentOffset, canvasSize, is3DMode) {
        notifyCrosshair()
    }

    fun project(x: Float, y: Float, floor: Int): Offset {
        val px = x * currentScale
        val py = -y * currentScale // Canvas Y-inversion
        
        return if (is3DMode) {
            // Standard Isometric Math (Rotate 45 deg, tilt 30 deg)
            val isoX = (px - py) * 0.866f // cos(30)
            val isoY = (px + py) * 0.5f   // sin(30)
            val zOffset = floor * 250f    // Visual gap between floors
            Offset(currentOffset.x + isoX, currentOffset.y + isoY - zOffset)
        } else {
            Offset(currentOffset.x + px, currentOffset.y + py)
        }
    }

    fun findNodeAt(clickOffset: Offset, nodes: List<NodeEntity>): NodeEntity? {
        val hitRadius = with(density) { 36.dp.toPx() }
        val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")
        val nodesToSearch = if (is3DMode) {
            nodes
        } else {
            nodes.filter { 
                it.floorLevel == currentFloor || 
                (currentIsSurveyorMode && currentFloor > 0 && it.floorLevel == currentFloor - 1 && it.type in validPortals)
            }
        }
        return nodesToSearch.minByOrNull { node ->
            val pos = project(node.x, node.y, currentFloor)
            val dx = clickOffset.x - pos.x
            val dy = clickOffset.y - pos.y
            dx * dx + dy * dy
        }?.takeIf { node ->
            val pos = project(node.x, node.y, currentFloor)
            val dx = clickOffset.x - pos.x
            val dy = clickOffset.y - pos.y
            Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() <= hitRadius
        }
    }

    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0L) {
            val cx = if (canvasSize.width > 0) canvasSize.width / 2f else 500f
            val cy = if (canvasSize.height > 0) canvasSize.height / 2f else 500f
            val px = currentPosePosition.first * currentScale
            val py = -currentPosePosition.second * currentScale
            val newOffset = if (is3DMode) {
                val isoX = (px - py) * 0.866f
                val isoY = (px + py) * 0.5f - (currentFloor * 250f)
                Offset(cx - isoX, cy - isoY)
            } else {
                Offset(cx - px, cy - py)
            }
            currentOffset = newOffset
            notifyCrosshair(off = newOffset)
            onTransform?.invoke(currentScale, newOffset)
        }
    }

    val animatedX by animateFloatAsState(
        targetValue = currentPosePosition.first,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "userX"
    )
    val animatedY by animateFloatAsState(
        targetValue = currentPosePosition.second,
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
            .onSizeChanged { size ->
                canvasSize = size
                onSizeChanged?.invoke(size)
                notifyCrosshair(w = size.width, h = size.height)
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = currentScale
                    currentScale = (currentScale * zoom).coerceIn(5f, 100f)
                    
                    // BULLETPROOF ZOOM MATH: 
                    // Only apply focal shift if the scale actually changed.
                    val actualZoom = currentScale / oldScale
                    val newOffset = (currentOffset - centroid) * actualZoom + centroid + pan
                    currentOffset = newOffset
                    
                    notifyCrosshair(off = newOffset, sc = currentScale)
                    onTransform?.invoke(currentScale, newOffset)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        // Reverse project tap to map coordinates
                        val mapX = (tapOffset.x - currentOffset.x) / currentScale
                        val mapY = -(tapOffset.y - currentOffset.y) / currentScale
                        
                        // Find nearest node on current floor within a generous radius (3.0 meters / ~60 pixels)
                        val radiusThreshold = 3.0
                        val clickedNode = currentNodes.filter { it.floorLevel == currentFloor }
                            .minByOrNull { node ->
                                minOf(
                                    Math.hypot((node.x - mapX).toDouble(), (node.y - mapY).toDouble()),
                                    Math.hypot((node.x - (mapX / 20f)).toDouble(), (node.y - (mapY / 20f)).toDouble())
                                )
                            }
                            ?.takeIf { node ->
                                Math.hypot((node.x - mapX).toDouble(), (node.y - mapY).toDouble()) < radiusThreshold ||
                                Math.hypot((node.x - (mapX / 20f)).toDouble(), (node.y - (mapY / 20f)).toDouble()) < radiusThreshold
                            }
                            
                        currentOnNodeSelected?.invoke(clickedNode) // Will pass null if tapped empty space
                        currentViewModel?.selectNode(clickedNode)
                        if (currentIsSurveyorMode) {
                            currentViewModel?.snapTargetNodeId?.value = clickedNode?.nodeId
                        }
                    },
                    onLongPress = { clickOffset ->
                        if (currentIsSurveyorMode) {
                            val clickedNode = findNodeAt(clickOffset, currentNodes)
                            if (clickedNode != null) {
                                if (currentOnNodeLongClick != null) {
                                    currentOnNodeLongClick?.invoke(clickedNode)
                                } else {
                                    currentViewModel?.stageNodeForDeletion(clickedNode)
                                }
                            }
                        }
                    }
                )
            }
    ) {
        if (canvasSize == androidx.compose.ui.unit.IntSize.Zero) {
            canvasSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
        }

        // NO GLOBAL ROTATION OR SQUASHING HERE ANYMORE
        val floorsToDraw = if (is3DMode) {
            activeNodes.map { it.floorLevel }.plus(currentFloor).distinct().sorted()
        } else {
            listOf(currentFloor)
        }

        floorsToDraw.forEach { floor ->
            val nodesOnFloor = activeNodes.filter { it.floorLevel == floor }
            val nodeIdsOnFloor = nodesOnFloor.map { it.nodeId }.toSet()

            // Subtle floor plane boundary in 3D mode
            if (is3DMode) {
                val planeHalf = 40f
                val c1 = project(-planeHalf, -planeHalf, floor)
                val c2 = project(planeHalf, -planeHalf, floor)
                val c3 = project(planeHalf, planeHalf, floor)
                val c4 = project(-planeHalf, planeHalf, floor)

                val floorPath = Path().apply {
                    moveTo(c1.x, c1.y)
                    lineTo(c2.x, c2.y)
                    lineTo(c3.x, c3.y)
                    lineTo(c4.x, c4.y)
                    close()
                }
                drawPath(
                    path = floorPath,
                    color = Color.LightGray.copy(alpha = 0.05f)
                )
                drawPath(
                    path = floorPath,
                    color = if (floor == currentFloor) Color.Cyan.copy(alpha = 0.4f) else Color.Gray.copy(alpha = 0.2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = "Floor L$floor",
                    topLeft = Offset(c4.x, c4.y - 25f),
                    style = TextStyle(color = if (floor == currentFloor) Color.Cyan else Color.LightGray)
                )
            }

            // 1. Draw Edges connected to this floor
            surveyorEdges.forEach { edge ->
                if (edge.fromNodeId in nodeIdsOnFloor && edge.toNodeId in nodeIdsOnFloor) {
                    val fromNode = nodesOnFloor.find { it.nodeId == edge.fromNodeId }
                    val toNode = nodesOnFloor.find { it.nodeId == edge.toNodeId }
                    if (fromNode != null && toNode != null) {
                        val startPos = project(fromNode.x, fromNode.y, fromNode.floorLevel)
                        val endPos = project(toNode.x, toNode.y, toNode.floorLevel)
                        drawLine(
                            color = Color(0xFF708090), // Slate Gray
                            start = startPos,
                            end = endPos,
                            strokeWidth = 8f
                        )
                    }
                }
            }

            // 2. Draw Active Route
            val currentActiveRoute = activeRoute ?: uiState.activeRoute
            if (floor == currentFloor && !isSurveyorMode && currentActiveRoute.isNotEmpty()) {
                val px = estimatedPosition.first
                val py = estimatedPosition.second

                // Leash line from user to nearest current-floor node in route
                val currentFloorNodes = currentActiveRoute.filter { it.floorLevel == currentFloor }
                if (currentFloorNodes.isNotEmpty()) {
                    val closest = currentFloorNodes.minByOrNull { n ->
                        val dx = n.x - px
                        val dy = n.y - py
                        dx * dx + dy * dy
                    }
                    if (closest != null) {
                        val userPos = project(px, py, currentFloor)
                        val anchorPos = project(closest.x, closest.y, currentFloor)
                        drawLine(
                            color = Color.Cyan.copy(alpha = 0.6f),
                            start = userPos,
                            end = anchorPos,
                            strokeWidth = 6f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                        )
                    }
                }

                // Group the path into pairs (segments)
                val segments = currentActiveRoute.zipWithNext()
                val scale = 1f

                segments.forEach { (nodeA, nodeB) ->
                    // CRITICAL FIX: Only draw the cyan line if AT LEAST ONE of the nodes is on the current floor 
                    // (This allows drawing the line up to the stairs), OR if we are in 3D mode.
                    if (is3DMode || nodeA.floorLevel == currentFloor || nodeB.floorLevel == currentFloor) {
                        
                        // Do not draw vertical lines between floors in 2D mode
                        if (!is3DMode && nodeA.floorLevel != nodeB.floorLevel) return@forEach
                        
                        val posA = project(nodeA.x, nodeA.y, nodeA.floorLevel)
                        val posB = project(nodeB.x, nodeB.y, nodeB.floorLevel)
                        
                        drawLine(
                            color = Color.Cyan,
                            start = posA,
                            end = posB,
                            strokeWidth = 8f / scale,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // 1. Draw Ghost Portals (Surveyor Mode Only)
            if (!is3DMode && isSurveyorMode && currentFloor > 0) {
                val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")
                val ghostNodes = activeNodes.filter { 
                    it.floorLevel == currentFloor - 1 && it.type in validPortals 
                }
                
                ghostNodes.forEach { ghost ->
                    val pos = project(ghost.x, ghost.y, currentFloor) // Project to current view
                    val scale = 1f
                    
                    // Draw a dashed circle or semi-transparent marker to indicate a portal below
                    drawCircle(
                        color = Color.Gray.copy(alpha = 0.4f),
                        radius = 12f / scale,
                        center = pos,
                        style = Stroke(width = 2f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    )
                    
                    drawContext.canvas.nativeCanvas.drawText(
                        "▼ ${ghost.label}", // Down arrow indicates it's below
                        pos.x + (15f / scale),
                        pos.y,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.GRAY
                            alpha = 100
                            textSize = 24f / scale
                        }
                    )

                    if (snapTargetNodeId != null && ghost.nodeId == snapTargetNodeId) {
                        drawCircle(
                            color = Color.Yellow,
                            radius = 24f / scale,
                            center = pos,
                            style = Stroke(width = 6f / scale)
                        )
                    }
                }
            }

            // 2. Draw Nodes on this floor
            nodesOnFloor.forEach { node ->
                val pos = project(node.x, node.y, node.floorLevel)
                val scale = 1f

                // CRITICAL FIX: Ensure highlight only draws on the correct floor
                if (currentSelectedNode?.id == node.id && currentSelectedNode?.floorLevel == currentFloor) {
                    drawCircle(
                        color = Color.Yellow,
                        radius = 16f / scale,
                        center = pos,
                        style = Stroke(width = 4f / scale)
                    )
                }

                if (node.nodeType == "Waypoint" || node.name.isBlank()) {
                    drawCircle(
                        color = Color.Gray,
                        radius = 6f,
                        center = pos
                    )
                } else {
                    val color = when (node.nodeType) {
                        "Junction" -> Color.Red
                        "Room Door" -> Color.Blue
                        "Stairs" -> Color.Green
                        "Washroom" -> Color(0xFF00BCD4)
                        "Emergency Exit" -> Color.Red
                        "Building Entry" -> Color(0xFF4CAF50)
                        else -> Color.Yellow
                    }
                    val scale = 1f
                    drawCircle(
                        color = color,
                        radius = 8f / scale,
                        center = pos
                    )

                    // CRITICAL FIX: Draw Text Pill Background
                    val textToDraw = node.label
                    val paint = android.graphics.Paint().apply { textSize = 30f / scale; isAntiAlias = true }
                    val textWidth = paint.measureText(textToDraw)

                    drawRect(
                        color = Color.Black.copy(alpha = 0.6f),
                        topLeft = Offset(pos.x + (10f / scale), pos.y - (25f / scale)),
                        size = Size(textWidth + (10f / scale), 35f / scale)
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        textToDraw,
                        pos.x + (15f / scale),
                        pos.y + (4f / scale),
                        paint.apply { this.color = android.graphics.Color.WHITE }
                    )
                }

                if (isSurveyorMode && snapTargetNodeId != null && node.nodeId == snapTargetNodeId) {
                    drawCircle(
                        color = Color.Yellow,
                        radius = 24f,
                        center = pos,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                    )
                }
            }

            // 4. Draw Rubber-Band & Snapping (Surveyor Mode Only)
            if (isSurveyorMode && floor == currentFloor) {
                val userCanvasPos = project(currentPosePosition.first, currentPosePosition.second, currentFloor)
                if (snapTargetNodeId != null) {
                    val snapNode = activeNodes.find { it.nodeId == snapTargetNodeId }
                    if (snapNode != null) {
                        val snapPos = project(snapNode.x, snapNode.y, currentFloor)
                        drawLine(
                            color = Color.Yellow,
                            start = userCanvasPos,
                            end = snapPos,
                            strokeWidth = 6f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                        )
                    }
                } else if (lastDroppedNodeId != null) {
                    val lastNode = nodesOnFloor.find { it.nodeId == lastDroppedNodeId }
                    if (lastNode != null) {
                        val lastPos = project(lastNode.x, lastNode.y, lastNode.floorLevel)
                        drawLine(
                            color = Color.Yellow,
                            start = lastPos,
                            end = userCanvasPos,
                            strokeWidth = 4f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    }
                }
            }

            // 5. Draw Blue Dot (Only on current floor)
            if (floor == currentFloor) {
                val puckPos = project(currentPosePosition.first, currentPosePosition.second, currentFloor)
                drawCircle(color = Color.Blue, radius = 12.dp.toPx(), center = puckPos)

                val visualHeading = (animatedHeading + 180f) % 360f
                withTransform({
                    rotate(degrees = (visualHeading + 180f) % 360f, pivot = puckPos)
                }) {
                    val arrowPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(puckPos.x, puckPos.y - 35f)
                        lineTo(puckPos.x + 25f, puckPos.y + 15f)
                        lineTo(puckPos.x, puckPos.y + 5f)
                        lineTo(puckPos.x - 25f, puckPos.y + 15f)
                        close()
                    }
                    drawPath(path = arrowPath, color = Color.White)
                    drawPath(path = arrowPath, color = Color.DarkGray, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }
            }
        }

        // Draw Inter-floor connector edges in 3D Mode
        if (is3DMode) {
            surveyorEdges.forEach { edge ->
                val fromNode = activeNodes.find { it.nodeId == edge.fromNodeId }
                val toNode = activeNodes.find { it.nodeId == edge.toNodeId }
                if (fromNode != null && toNode != null && fromNode.floorLevel != toNode.floorLevel) {
                    val startPos = project(fromNode.x, fromNode.y, fromNode.floorLevel)
                    val endPos = project(toNode.x, toNode.y, toNode.floorLevel)
                    drawLine(
                        color = Color(0xFF00E5FF),
                        start = startPos,
                        end = endPos,
                        strokeWidth = 6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                }
            }
        }
    }
}
