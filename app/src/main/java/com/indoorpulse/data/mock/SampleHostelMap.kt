package com.indoorpulse.data.mock

import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.Edge
import com.indoorpulse.domain.model.IndoorMap
import com.indoorpulse.domain.model.Node
import com.indoorpulse.domain.model.NodeType

val SampleHostelMap = IndoorMap(
    buildingId = "hostel_a",
    buildingName = "Hostel A",
    nodes = mapOf(
        "entrance" to Node("entrance", Coordinate(0f, 0f), "Entrance", NodeType.ENTRANCE),
        "corridor_1" to Node("corridor_1", Coordinate(0f, 10f), "Corridor 1", NodeType.CORRIDOR_JUNCTION),
        "corridor_2" to Node("corridor_2", Coordinate(10f, 10f), "Corridor 2", NodeType.CORRIDOR_JUNCTION),
        "room_101" to Node("room_101", Coordinate(-5f, 10f), "Room 101", NodeType.ROOM),
        "room_102" to Node("room_102", Coordinate(15f, 10f), "Room 102", NodeType.ROOM),
        "washroom" to Node("washroom", Coordinate(10f, 15f), "Washroom", NodeType.ROOM),
        "stairs" to Node("stairs", Coordinate(0f, 20f), "Stairs", NodeType.STAIRS)
    ),
    edges = listOf(
        // Entrance to Corridor 1
        Edge("entrance", "corridor_1", 10f),
        Edge("corridor_1", "entrance", 10f),
        // Corridor 1 to Room 101
        Edge("corridor_1", "room_101", 5f),
        Edge("room_101", "corridor_1", 5f),
        // Corridor 1 to Corridor 2
        Edge("corridor_1", "corridor_2", 10f),
        Edge("corridor_2", "corridor_1", 10f),
        // Corridor 1 to Stairs
        Edge("corridor_1", "stairs", 10f),
        Edge("stairs", "corridor_1", 10f),
        // Corridor 2 to Room 102
        Edge("corridor_2", "room_102", 5f),
        Edge("room_102", "corridor_2", 5f),
        // Corridor 2 to Washroom
        Edge("corridor_2", "washroom", 5f),
        Edge("washroom", "corridor_2", 5f)
    ),
    originLat = 28.7041,
    originLng = 77.1025,
    boundingRadiusMeters = 50f
)
