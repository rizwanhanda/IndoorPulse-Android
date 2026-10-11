package com.indoorpulse.domain.usecase

import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.endNodeId
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.startNodeId
import com.indoorpulse.data.local.entity.weight

class RoutingEngine {
    private var nodes = mapOf<String, NodeEntity>()
    private var edges = mapOf<String, List<EdgeEntity>>()

    fun buildGraph(nodeList: List<NodeEntity>, edgeList: List<EdgeEntity>) {
        nodes = nodeList.associateBy { it.id }
        // Create bidirectional adjacency list
        val adj = mutableMapOf<String, MutableList<EdgeEntity>>()
        edgeList.forEach { edge ->
            adj.getOrPut(edge.startNodeId) { mutableListOf() }.add(edge)
            adj.getOrPut(edge.endNodeId) { mutableListOf() }.add(
                EdgeEntity(edge.id, edge.endNodeId, edge.startNodeId, edge.weight)
            )
        }
        edges = adj
    }

    // A* Heuristic: 3D Euclidean Distance (Heavy penalty for floor changes to encourage stairs)
    private fun heuristic(nodeId: String, targetId: String): Float {
        val n1 = nodes[nodeId] ?: return Float.MAX_VALUE
        val n2 = nodes[targetId] ?: return Float.MAX_VALUE
        val dx = n1.x - n2.x
        val dy = n1.y - n2.y
        val dz = (n1.floorLevel - n2.floorLevel) * 10f // 10-unit vertical penalty
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    fun findShortestPath(startId: String, targetId: String): List<NodeEntity>? {
        if (!nodes.containsKey(startId) || !nodes.containsKey(targetId)) return null

        val openSet = java.util.PriorityQueue<Pair<String, Float>>(compareBy { it.second })
        val cameFrom = mutableMapOf<String, String>()
        val gScore = mutableMapOf<String, Float>().withDefault { Float.MAX_VALUE }
        
        gScore[startId] = 0f
        openSet.add(startId to heuristic(startId, targetId))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()?.first ?: break

            if (current == targetId) {
                val path = mutableListOf<NodeEntity>()
                var curr = current
                while (cameFrom.containsKey(curr)) {
                    path.add(0, nodes[curr]!!)
                    curr = cameFrom[curr]!!
                }
                path.add(0, nodes[startId]!!)
                return path
            }

            edges[current]?.forEach { edge ->
                val neighbor = edge.endNodeId
                // If it's a vertical portal edge, cost is 0. Otherwise, use physical distance.
                val physicalDistance = if (edge.weight == 0f) 0f else heuristic(current, neighbor)
                val tentativeGScore = gScore.getValue(current) + physicalDistance

                if (tentativeGScore < gScore.getValue(neighbor)) {
                    cameFrom[neighbor] = current
                    gScore[neighbor] = tentativeGScore
                    val fScore = tentativeGScore + heuristic(neighbor, targetId)
                    // Remove old entry if exists, add new
                    openSet.removeAll { it.first == neighbor } 
                    openSet.add(neighbor to fScore)
                }
            }
        }
        return null // No path found
    }
}
