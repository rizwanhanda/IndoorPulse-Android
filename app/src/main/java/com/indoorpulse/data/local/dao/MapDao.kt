package com.indoorpulse.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.FloorEntity
import com.indoorpulse.data.local.entity.NodeEntity

@Dao
@JvmSuppressWildcards
interface MapDao {
    @Query("SELECT * FROM floors")
    suspend fun getFloors(): List<FloorEntity>

    @Query("SELECT * FROM nodes WHERE floorId = :floorId")
    suspend fun getNodesByFloor(floorId: String): List<NodeEntity>

    @Query("SELECT * FROM edges")
    suspend fun getAllEdges(): List<EdgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFloors(floors: List<FloorEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<NodeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<EdgeEntity>): List<Long>

    @Query("DELETE FROM nodes WHERE nodeId = :nodeId")
    suspend fun deleteNode(nodeId: String): Int

    @Query("DELETE FROM edges WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun deleteEdgesForNode(nodeId: String): Int

    @Query("DELETE FROM edges WHERE edgeId = :edgeId")
    suspend fun deleteEdge(edgeId: String): Int
}
