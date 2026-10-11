package com.indoorpulse.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "floors")
data class FloorEntity(
    @PrimaryKey val floorId: String,
    val level: Int,
    val name: String,
    val altitudeOffsetMeters: Float
)

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val nodeId: String,
    val floorId: String,
    val x: Float,
    val y: Float,
    val nodeType: String,
    val name: String = "",
    @ColumnInfo(defaultValue = "0") val floorLevel: Int = 0
)

val NodeEntity.id: String
    get() = nodeId

val NodeEntity.label: String
    get() = if (name.isNotBlank()) name else nodeId

val NodeEntity.type: String
    get() = nodeType

@Entity(tableName = "edges")
data class EdgeEntity(
    @PrimaryKey val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val lengthMeters: Float,
    val edgeType: String
) {
    @androidx.room.Ignore
    constructor(
        id: String,
        startNodeId: String,
        endNodeId: String,
        weight: Float = 0f
    ) : this(
        edgeId = id,
        fromNodeId = startNodeId,
        toNodeId = endNodeId,
        lengthMeters = weight,
        edgeType = "vertical_portal"
    )
}

val EdgeEntity.id: String
    get() = edgeId

val EdgeEntity.startNodeId: String
    get() = fromNodeId

val EdgeEntity.endNodeId: String
    get() = toNodeId

val EdgeEntity.weight: Float
    get() = lengthMeters
