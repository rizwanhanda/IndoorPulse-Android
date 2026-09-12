package com.indoorpulse.domain.usecase

import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.IndoorMap
import java.util.PriorityQueue
import kotlin.math.sqrt

class FindShortestPathUseCase {
    
    private data class NodeWrapper(
        val id: String,
        val gScore: Float,
        val fScore: Float,
        val previousNodeId: String?
    ) : Comparable<NodeWrapper> {
        override fun compareTo(other: NodeWrapper): Int {
            return fScore.compareTo(other.fScore)
        }
    }

    operator fun invoke(map: IndoorMap, startNodeId: String, targetNodeId: String): List<Coordinate> {
        val startNode = map.nodes[startNodeId] ?: return emptyList()
        val targetNode = map.nodes[targetNodeId] ?: return emptyList()

        val adjacencyList = mutableMapOf<String, MutableList<Pair<String, Float>>>()
        for (edge in map.edges) {
            if (!edge.isAccessible) continue
            adjacencyList.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId to edge.distanceMeters)
            adjacencyList.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge.fromNodeId to edge.distanceMeters)
        }

        val openSet = PriorityQueue<NodeWrapper>()
        val gScores = mutableMapOf<String, Float>()
        val cameFrom = mutableMapOf<String, String>()

        gScores[startNodeId] = 0f
        openSet.add(NodeWrapper(startNodeId, 0f, heuristic(startNode.coordinate, targetNode.coordinate), null))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll() ?: break

            if (current.id == targetNodeId) {
                return reconstructPath(cameFrom, current.id, map)
            }

            val neighbors = adjacencyList[current.id] ?: emptyList()
            for ((neighborId, weight) in neighbors) {
                val tentativeGScore = gScores.getOrDefault(current.id, Float.MAX_VALUE) + weight
                if (tentativeGScore < gScores.getOrDefault(neighborId, Float.MAX_VALUE)) {
                    cameFrom[neighborId] = current.id
                    gScores[neighborId] = tentativeGScore
                    val neighborNode = map.nodes[neighborId] ?: continue
                    val fScore = tentativeGScore + heuristic(neighborNode.coordinate, targetNode.coordinate)
                    
                    openSet.add(NodeWrapper(neighborId, tentativeGScore, fScore, current.id))
                }
            }
        }

        return emptyList()
    }

    private fun heuristic(a: Coordinate, b: Coordinate): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt((dx * dx) + (dy * dy))
    }

    private fun reconstructPath(cameFrom: Map<String, String>, currentId: String, map: IndoorMap): List<Coordinate> {
        val path = mutableListOf<Coordinate>()
        var curr = currentId
        path.add(map.nodes[curr]?.coordinate ?: return emptyList())
        while (cameFrom.containsKey(curr)) {
            curr = cameFrom[curr]!!
            path.add(map.nodes[curr]?.coordinate ?: return emptyList())
        }
        return path.reversed()
    }
}
