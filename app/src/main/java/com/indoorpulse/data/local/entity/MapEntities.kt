package com.indoorpulse.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "buildings")
data class BuildingEntity(
    @PrimaryKey val buildingId: String,
    val name: String,
    val centerLat: Double,
    val centerLng: Double,
    val geofenceRadius: Float
)

@Entity(tableName = "floors")
data class FloorEntity(
    @PrimaryKey val floorId: String,
    val level: Int,
    val name: String,
    val altitudeOffsetMeters: Float
)

@Entity(
    tableName = "nodes",
    foreignKeys = [
        ForeignKey(
            entity = BuildingEntity::class,
            parentColumns = ["buildingId"],
            childColumns = ["buildingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("buildingId"),
        Index("floorId")
    ]
)
data class NodeEntity(
    @PrimaryKey val nodeId: String,
    val floorId: String,
    val x: Float,
    val y: Float,
    val nodeType: String,
    val name: String = "",
    @ColumnInfo(defaultValue = "0") val floorLevel: Int = 0,
    @ColumnInfo(defaultValue = "default_building") val buildingId: String = "default_building",
    @ColumnInfo(defaultValue = "0.0") val lat: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val lng: Double = 0.0
)

val NodeEntity.id: String
    get() = nodeId

val NodeEntity.label: String
    get() = if (name.isNotBlank()) name else nodeId

val NodeEntity.type: String
    get() = nodeType

@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = BuildingEntity::class,
            parentColumns = ["buildingId"],
            childColumns = ["buildingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("buildingId"),
        Index("fromNodeId"),
        Index("toNodeId")
    ]
)
data class EdgeEntity(
    @PrimaryKey val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val lengthMeters: Float,
    val edgeType: String,
    @ColumnInfo(defaultValue = "default_building") val buildingId: String = "default_building"
) {
    @androidx.room.Ignore
    constructor(
        id: String,
        startNodeId: String,
        endNodeId: String,
        weight: Float = 0f,
        buildingId: String = "default_building"
    ) : this(
        edgeId = id,
        fromNodeId = startNodeId,
        toNodeId = endNodeId,
        lengthMeters = weight,
        edgeType = "vertical_portal",
        buildingId = buildingId
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
