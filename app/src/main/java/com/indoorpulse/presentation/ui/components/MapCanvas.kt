package com.indoorpulse.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.presentation.viewmodel.MapUiState
import com.indoorpulse.presentation.viewmodel.MapViewModel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

@Composable
fun MapCanvas(
    uiState: MapUiState = MapUiState(),
    estimatedPosition: Pair<Float, Float> = Pair(0f, 0f),
    isSurveyorMode: Boolean = false,
    surveyorNodes: List<NodeEntity> = emptyList(),
    surveyorEdges: List<EdgeEntity> = emptyList(),
    lastDroppedNodeId: String? = null,
    snapTargetNodeId: String? = null,
    recenterTrigger: Long = 0L,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel? = null,
    onNodeLongClick: ((NodeEntity) -> Unit)? = null,
    scale: Float = 20f,
    offset: Offset = Offset(500f, 500f),
    onTransform: ((Float, Offset) -> Unit)? = null,
    onSizeChanged: ((IntSize) -> Unit)? = null,
    onCrosshairCoordinateChanged: ((Float, Float) -> Unit)? = null,
    allNodes: List<NodeEntity> = surveyorNodes,
    currentPose: Pair<Float, Float>? = estimatedPosition
) {
    com.indoorpulse.presentation.ui.MapCanvas(
        uiState = uiState,
        estimatedPosition = estimatedPosition,
        isSurveyorMode = isSurveyorMode,
        surveyorNodes = if (surveyorNodes.isNotEmpty()) surveyorNodes else allNodes,
        surveyorEdges = surveyorEdges,
        lastDroppedNodeId = lastDroppedNodeId,
        snapTargetNodeId = snapTargetNodeId,
        recenterTrigger = recenterTrigger,
        modifier = modifier,
        viewModel = viewModel,
        onNodeLongClick = onNodeLongClick,
        scale = scale,
        offset = offset,
        onTransform = onTransform,
        onSizeChanged = onSizeChanged,
        onCrosshairCoordinateChanged = onCrosshairCoordinateChanged,
        allNodes = allNodes,
        currentPose = currentPose
    )
}
