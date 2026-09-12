package com.indoorpulse.domain.model

data class Node(
    val id: String,
    val coordinate: Coordinate,
    val label: String,
    val type: NodeType
)

enum class NodeType {
    ROOM, CORRIDOR_JUNCTION, STAIRS, ELEVATOR, ENTRANCE, WAYPOINT
}
