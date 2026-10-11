package com.indoorpulse

import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.type
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.domain.model.floorLevel
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    private val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")

    private fun canLinkNodes(nodeA: NodeEntity, nodeB: NodeEntity): Boolean {
        if (nodeA.floorLevel != nodeB.floorLevel) {
            val isNodeAValid = nodeA.type in validPortals || validPortals.any { it.equals(nodeA.type, ignoreCase = true) }
            val isNodeBValid = nodeB.type in validPortals || validPortals.any { it.equals(nodeB.type, ignoreCase = true) }
            if (!isNodeAValid || !isNodeBValid) {
                return false
            }
        }
        return true
    }

    @Test
    fun testSameFloorAllowsAnyNodeType() {
        val nodeA = NodeEntity(nodeId = "1", floorId = "f1", x = 0f, y = 0f, nodeType = "Waypoint", floorLevel = 0)
        val nodeB = NodeEntity(nodeId = "2", floorId = "f1", x = 1f, y = 1f, nodeType = "Room Door", floorLevel = 0)
        assertTrue(canLinkNodes(nodeA, nodeB))
    }

    @Test
    fun testCrossFloorRejectsStandardNodes() {
        val nodeA = NodeEntity(nodeId = "1", floorId = "f1", x = 0f, y = 0f, nodeType = "Waypoint", floorLevel = 0)
        val nodeB = NodeEntity(nodeId = "2", floorId = "f1", x = 0f, y = 0f, nodeType = "Waypoint", floorLevel = 1)
        assertFalse(canLinkNodes(nodeA, nodeB))

        val nodeC = NodeEntity(nodeId = "3", floorId = "f1", x = 0f, y = 0f, nodeType = "Room Door", floorLevel = 0)
        val nodeD = NodeEntity(nodeId = "4", floorId = "f1", x = 0f, y = 0f, nodeType = "Stairs", floorLevel = 1)
        assertFalse(canLinkNodes(nodeC, nodeD))
    }

    @Test
    fun testCrossFloorAllowsValidPortals() {
        val stairs0 = NodeEntity(nodeId = "s0", floorId = "f1", x = 5f, y = 5f, nodeType = "Stairs", floorLevel = 0)
        val stairs1 = NodeEntity(nodeId = "s1", floorId = "f1", x = 5f, y = 5f, nodeType = "Stairs", floorLevel = 1)
        assertTrue(canLinkNodes(stairs0, stairs1))

        val elev0 = NodeEntity(nodeId = "e0", floorId = "f1", x = 10f, y = 10f, nodeType = "Elevator", floorLevel = 0)
        val elev1 = NodeEntity(nodeId = "e1", floorId = "f1", x = 10f, y = 10f, nodeType = "Elevator", floorLevel = 1)
        assertTrue(canLinkNodes(elev0, elev1))

        val exit0 = NodeEntity(nodeId = "x0", floorId = "f1", x = 2f, y = 2f, nodeType = "Emergency Exit", floorLevel = 0)
        val exit1 = NodeEntity(nodeId = "x1", floorId = "f1", x = 2f, y = 2f, nodeType = "Stairs", floorLevel = 1)
        assertTrue(canLinkNodes(exit0, exit1))
    }

    @Test
    fun testAutoVerticalMeshingAlignmentAndEdge() {
        val currentStairs = NodeEntity(nodeId = "s2", floorId = "f1", x = 12.3f, y = 8.7f, nodeType = "Stairs", floorLevel = 1)
        val ghostStairs = NodeEntity(nodeId = "s1", floorId = "f1", x = 10.0f, y = 5.0f, nodeType = "Stairs", floorLevel = 0)

        // Verify adjacent portal condition
        assertTrue(currentStairs.type in validPortals && ghostStairs.type in validPortals)
        assertEquals(1, Math.abs(currentStairs.floorLevel - ghostStairs.floorLevel))

        // Auto-align coordinates
        val alignedNode = currentStairs.copy(x = ghostStairs.x, y = ghostStairs.y)
        assertEquals(10.0f, alignedNode.x)
        assertEquals(5.0f, alignedNode.y)

        // Vertical Edge generation
        val edge = com.indoorpulse.data.local.entity.EdgeEntity(
            id = "vert-1",
            startNodeId = alignedNode.id,
            endNodeId = ghostStairs.id,
            weight = 0f
        )
        assertEquals("vert-1", edge.edgeId)
        assertEquals("s2", edge.fromNodeId)
        assertEquals("s1", edge.toNodeId)
        assertEquals(0f, edge.lengthMeters)
    }

    @Test
    fun testFloorAwarePathSegmentFiltering() {
        val c0 = com.indoorpulse.domain.model.Coordinate(x = 1f, y = 1f, floor = 0)
        val c1 = com.indoorpulse.domain.model.Coordinate(x = 5f, y = 5f, floor = 0)
        val c2 = com.indoorpulse.domain.model.Coordinate(x = 5f, y = 5f, floor = 1)
        val c3 = com.indoorpulse.domain.model.Coordinate(x = 10f, y = 10f, floor = 1)

        val route = listOf(c0, c1, c2, c3)
        val segments = route.zipWithNext()

        // 2D Mode on Floor 0:
        val currentFloor = 0
        val is3DMode = false

        val drawnSegmentsFloor0 = segments.filter { (nodeA, nodeB) ->
            if (is3DMode || nodeA.floor == currentFloor || nodeB.floor == currentFloor) {
                if (!is3DMode && nodeA.floor != nodeB.floor) false else true
            } else {
                false
            }
        }
        // Only segment (c0 -> c1) should be drawn on Floor 0 in 2D
        assertEquals(1, drawnSegmentsFloor0.size)
        assertEquals(c0, drawnSegmentsFloor0[0].first)
        assertEquals(c1, drawnSegmentsFloor0[0].second)

        // 3D Mode: All 3 segments should be drawn including the vertical transition (c1 -> c2)
        val drawnSegments3D = segments.filter { (nodeA, nodeB) ->
            true // in 3D mode all are drawn
        }
        assertEquals(3, drawnSegments3D.size)
    }

    @Test
    fun testNearestNeighborFallbackOnCurrentFloor() {
        val n0 = NodeEntity(nodeId = "n0", floorId = "f1", x = 10f, y = 10f, nodeType = "Waypoint", floorLevel = 0)
        val n1 = NodeEntity(nodeId = "n1", floorId = "f1", x = 1f, y = 1f, nodeType = "Room Door", floorLevel = 1)
        val n2 = NodeEntity(nodeId = "n2", floorId = "f1", x = 20f, y = 20f, nodeType = "Waypoint", floorLevel = 0)
        val allNodes = listOf(n0, n1, n2)

        val currentFloor = 0
        val pose = com.indoorpulse.domain.model.Coordinate(x = 2f, y = 2f, floor = 0)

        val currentFloorNodes = allNodes.filter { it.floorLevel == currentFloor }
        val nearestNode = currentFloorNodes.minByOrNull { node ->
            Math.hypot((node.x - pose.x).toDouble(), (node.y - pose.y).toDouble())
        }

        // Even though n1 is at (1,1) closer to (2,2), it's on Floor 1, so n0 on Floor 0 should be selected
        assertNotNull(nearestNode)
        assertEquals("n0", nearestNode?.id)
    }

    @Test
    fun testSmartPortalSnapWithinRange() {
        val activePortal = NodeEntity(nodeId = "s_l1", floorId = "f1", x = 5.2f, y = 5.1f, nodeType = "Stairs", floorLevel = 1)
        val ghostPortal = NodeEntity(nodeId = "s_l0", floorId = "f1", x = 5.0f, y = 5.0f, nodeType = "Stairs", floorLevel = 0)
        val farGhostPortal = NodeEntity(nodeId = "s_far", floorId = "f1", x = 20.0f, y = 20.0f, nodeType = "Stairs", floorLevel = 0)
        val sameFloorPortal = NodeEntity(nodeId = "s_same", floorId = "f1", x = 5.1f, y = 5.1f, nodeType = "Stairs", floorLevel = 1)
        val nonPortalNode = NodeEntity(nodeId = "d_l0", floorId = "f1", x = 5.0f, y = 5.0f, nodeType = "Room Door", floorLevel = 0)

        val allNodes = listOf(activePortal, ghostPortal, farGhostPortal, sameFloorPortal, nonPortalNode)

        val targetGhost = allNodes.find {
            it.id != activePortal.id &&
            it.type in validPortals &&
            Math.abs(it.floorLevel - activePortal.floorLevel) == 1 &&
            Math.hypot((it.x - activePortal.x).toDouble(), (it.y - activePortal.y).toDouble()) < 5.0
        }

        assertNotNull(targetGhost)
        assertEquals("s_l0", targetGhost?.id)

        // Verify aligned node coordinates
        val alignedNode = activePortal.copy(x = targetGhost!!.x, y = targetGhost.y)
        assertEquals(5.0f, alignedNode.x)
        assertEquals(5.0f, alignedNode.y)
    }

    @Test
    fun testInvertedFloorSelectorDisplayOrder() {
        val availableFloors = listOf(0, 1, 2)
        
        // Standard user: sorted descending
        val standardDisplayFloors = availableFloors.sortedDescending()
        assertEquals(listOf(2, 1, 0), standardDisplayFloors)

        // Surveyor user: extended range sorted descending
        val min = availableFloors.minOrNull() ?: 0
        val max = availableFloors.maxOrNull() ?: 0
        val surveyorDisplayFloors = ((min - 1)..(max + 1)).toList().sortedDescending()
        assertEquals(listOf(3, 2, 1, 0, -1), surveyorDisplayFloors)
    }

    @Test
    fun testNodeTapSelectionRadius() {
        val n0 = NodeEntity(nodeId = "target", floorId = "f1", x = 10f, y = 10f, nodeType = "Waypoint", floorLevel = 1)
        val n1 = NodeEntity(nodeId = "other", floorId = "f1", x = 20f, y = 20f, nodeType = "Waypoint", floorLevel = 1)
        val currentFloorNodes = listOf(n0, n1)

        val radiusThreshold = 3.0 // 3 meters (~60px)

        // Tap within 2 meters of n0
        val tapNearX = 11.0f
        val tapNearY = 10.5f
        val selectedNear = currentFloorNodes
            .minByOrNull { Math.hypot((it.x - tapNearX).toDouble(), (it.y - tapNearY).toDouble()) }
            ?.takeIf { Math.hypot((it.x - tapNearX).toDouble(), (it.y - tapNearY).toDouble()) < radiusThreshold }
        assertNotNull(selectedNear)
        assertEquals("target", selectedNear?.id)

        // Tap far away (empty space)
        val tapFarX = 0f
        val tapFarY = 0f
        val selectedFar = currentFloorNodes
            .minByOrNull { Math.hypot((it.x - tapFarX).toDouble(), (it.y - tapFarY).toDouble()) }
            ?.takeIf { Math.hypot((it.x - tapFarX).toDouble(), (it.y - tapFarY).toDouble()) < radiusThreshold }
        assertNull(selectedFar)
    }

    @Test
    fun testAStarRoutingEngineSameFloorAndCrossFloor() {
        val routing = com.indoorpulse.domain.usecase.RoutingEngine()

        val n0 = NodeEntity(nodeId = "start", floorId = "f1", x = 0f, y = 0f, nodeType = "Waypoint", floorLevel = 0)
        val n1 = NodeEntity(nodeId = "mid", floorId = "f1", x = 5f, y = 0f, nodeType = "Stairs", floorLevel = 0)
        val n2 = NodeEntity(nodeId = "stairs_l1", floorId = "f1", x = 5f, y = 0f, nodeType = "Stairs", floorLevel = 1)
        val n3 = NodeEntity(nodeId = "dest_l1", floorId = "f1", x = 10f, y = 0f, nodeType = "Room Door", floorLevel = 1)
        val isolated = NodeEntity(nodeId = "unreachable", floorId = "f1", x = 100f, y = 100f, nodeType = "Room Door", floorLevel = 1)

        val e1 = com.indoorpulse.data.local.entity.EdgeEntity("e1", "start", "mid", 5f)
        val e2 = com.indoorpulse.data.local.entity.EdgeEntity("e2", "mid", "stairs_l1", 0f) // Vertical portal
        val e3 = com.indoorpulse.data.local.entity.EdgeEntity("e3", "stairs_l1", "dest_l1", 5f)

        routing.buildGraph(listOf(n0, n1, n2, n3, isolated), listOf(e1, e2, e3))

        // Multi-floor path across vertical portal
        val path = routing.findShortestPath("start", "dest_l1")
        assertNotNull(path)
        assertEquals(4, path?.size)
        assertEquals("start", path?.get(0)?.id)
        assertEquals("mid", path?.get(1)?.id)
        assertEquals("stairs_l1", path?.get(2)?.id)
        assertEquals("dest_l1", path?.get(3)?.id)

        // Same floor path
        val sameFloorPath = routing.findShortestPath("start", "mid")
        assertNotNull(sameFloorPath)
        assertEquals(2, sameFloorPath?.size)
        assertEquals("start", sameFloorPath?.get(0)?.id)
        assertEquals("mid", sameFloorPath?.get(1)?.id)

        // Unreachable destination returns null
        val unreachablePath = routing.findShortestPath("start", "unreachable")
        assertNull(unreachablePath)
    }

    @Test
    fun testStrictFloorNearestNeighborFilter() {
        val nFloor0 = NodeEntity(nodeId = "on_floor_0", floorId = "f1", x = 10f, y = 10f, nodeType = "Waypoint", floorLevel = 0)
        val nFloor1Closer = NodeEntity(nodeId = "on_floor_1", floorId = "f1", x = 0.5f, y = 0.5f, nodeType = "Waypoint", floorLevel = 1)

        val allNodes = listOf(nFloor0, nFloor1Closer)
        val currentFloor = 0
        val pose = com.indoorpulse.domain.model.Coordinate(x = 0f, y = 0f, floor = 0)

        // Strict floor filter
        val startNodeId = allNodes
            .filter { it.floorLevel == currentFloor }
            .minByOrNull { Math.hypot((it.x - pose.x).toDouble(), (it.y - pose.y).toDouble()) }?.id

        assertEquals("on_floor_0", startNodeId)
    }

    @Test
    fun testHighlightConditionStrictFloorMatching() {
        val selected = NodeEntity(nodeId = "sel", floorId = "f1", x = 5f, y = 5f, nodeType = "Stairs", floorLevel = 1)
        val nodeOnCurrentFloor = NodeEntity(nodeId = "sel", floorId = "f1", x = 5f, y = 5f, nodeType = "Stairs", floorLevel = 1)

        val currentFloorMatches = 1
        val currentFloorDifferent = 0

        val shouldHighlightOnFloor1 = selected.id == nodeOnCurrentFloor.id && selected.floorLevel == currentFloorMatches
        val shouldHighlightOnFloor0 = selected.id == nodeOnCurrentFloor.id && selected.floorLevel == currentFloorDifferent

        assertTrue(shouldHighlightOnFloor1)
        assertFalse(shouldHighlightOnFloor0)
    }

    @Test
    fun testTransitTriggerOnMultiFloorPortalProximity() {
        var currentFloor = 0
        var transitState: com.indoorpulse.presentation.viewmodel.TransitInfo? = null

        val portal0 = com.indoorpulse.domain.model.Coordinate(x = 5f, y = 5f, floor = 0, type = "Elevator")
        val portal1 = com.indoorpulse.domain.model.Coordinate(x = 5f, y = 5f, floor = 1, type = "Elevator")
        val dest1 = com.indoorpulse.domain.model.Coordinate(x = 10f, y = 10f, floor = 1, type = "Room Door")

        var activeRoute = listOf(portal0, portal1, dest1)

        val onLocationUpdated: (Float, Float) -> Unit = { newX, newY ->
            if (transitState == null && activeRoute.isNotEmpty()) {
                val nextWaypoint = activeRoute.first()
                val distance = Math.hypot((newX - nextWaypoint.x).toDouble(), (newY - nextWaypoint.y).toDouble())
                if (distance < 2.5) {
                    if (activeRoute.size > 1 && activeRoute[1].floorLevel != currentFloor) {
                        val targetPortal = activeRoute[1]
                        transitState = com.indoorpulse.presentation.viewmodel.TransitInfo(
                            targetFloor = targetPortal.floorLevel,
                            portalType = targetPortal.type,
                            targetX = targetPortal.x,
                            targetY = targetPortal.y,
                            nextNodeIndex = 1
                        )
                    } else {
                        activeRoute = activeRoute.drop(1)
                    }
                }
            }
        }

        // 1. Move within 2.5m of portal0 (e.g., at (5.5, 5.5))
        onLocationUpdated(5.5f, 5.5f)

        assertNotNull(transitState)
        assertEquals(1, transitState?.targetFloor)
        assertEquals("Elevator", transitState?.portalType)
        assertEquals(5f, transitState?.targetX)
        assertEquals(5f, transitState?.targetY)
        assertEquals(1, transitState?.nextNodeIndex)

        // 2. While transitState != null, PDR updates are paused / ignored
        val initialTransit = transitState
        onLocationUpdated(99f, 99f)
        assertEquals(initialTransit, transitState)
        assertEquals(3, activeRoute.size) // Route not touched while in transit
    }

    @Test
    fun testCompleteTransitTeleportAndRouteAdvance() {
        var currentFloor = 0
        var userPos = Pair(5.5f, 5.5f)

        val portal0 = com.indoorpulse.domain.model.Coordinate(x = 5f, y = 5f, floor = 0, type = "Stairs")
        val portal1 = com.indoorpulse.domain.model.Coordinate(x = 5f, y = 5f, floor = 1, type = "Stairs")
        val dest1 = com.indoorpulse.domain.model.Coordinate(x = 12f, y = 8f, floor = 1, type = "Room Door")

        var activeRoute = listOf(portal0, portal1, dest1)
        var transitState: com.indoorpulse.presentation.viewmodel.TransitInfo? = com.indoorpulse.presentation.viewmodel.TransitInfo(
            targetFloor = portal1.floorLevel,
            portalType = portal1.type,
            targetX = portal1.x,
            targetY = portal1.y,
            nextNodeIndex = 1
        )

        val completeTransit: () -> Unit = {
            val info = transitState
            if (info != null) {
                // 1. Switch Floor
                currentFloor = info.targetFloor

                // 2. Teleport Blue Dot to the new portal
                userPos = Pair(info.targetX, info.targetY)

                // 3. Advance route past transition nodes
                activeRoute = activeRoute.drop(info.nextNodeIndex + 1)

                // 4. Clear transit state
                transitState = null
            }
        }

        completeTransit()

        // Floor switched to 1
        assertEquals(1, currentFloor)
        // Position teleported to (5f, 5f)
        assertEquals(5f, userPos.first)
        assertEquals(5f, userPos.second)
        // Route advanced to remaining nodes
        assertEquals(1, activeRoute.size)
        assertEquals(dest1, activeRoute.first())
        // Transit state cleared
        assertNull(transitState)
    }

    @Test
    fun testNormalWaypointDropWhenNotFloorTransition() {
        val currentFloor = 0
        var transitState: com.indoorpulse.presentation.viewmodel.TransitInfo? = null

        val wp0 = com.indoorpulse.domain.model.Coordinate(x = 2f, y = 2f, floor = 0, type = "Waypoint")
        val wp1 = com.indoorpulse.domain.model.Coordinate(x = 4f, y = 4f, floor = 0, type = "Waypoint")
        var activeRoute = listOf(wp0, wp1)

        val onLocationUpdated: (Float, Float) -> Unit = { newX, newY ->
            if (transitState == null && activeRoute.isNotEmpty()) {
                val nextWaypoint = activeRoute.first()
                val distance = Math.hypot((newX - nextWaypoint.x).toDouble(), (newY - nextWaypoint.y).toDouble())
                if (distance < 2.5) {
                    if (activeRoute.size > 1 && activeRoute[1].floorLevel != currentFloor) {
                        val targetPortal = activeRoute[1]
                        transitState = com.indoorpulse.presentation.viewmodel.TransitInfo(
                            targetFloor = targetPortal.floorLevel,
                            portalType = targetPortal.type,
                            targetX = targetPortal.x,
                            targetY = targetPortal.y,
                            nextNodeIndex = 1
                        )
                    } else {
                        activeRoute = activeRoute.drop(1)
                    }
                }
            }
        }

        // Arrive at wp0
        onLocationUpdated(2.1f, 2.0f)

        assertNull(transitState)
        assertEquals(1, activeRoute.size)
        assertEquals(wp1, activeRoute.first())
    }

    @Test
    fun testEmergencySOSStrictlyIgnoresPortalsAndSelectsShortestExit() {
        val routing = com.indoorpulse.domain.usecase.RoutingEngine()

        val start = NodeEntity(nodeId = "start", floorId = "f1", x = 0f, y = 0f, nodeType = "Waypoint", floorLevel = 0)
        val stairs = NodeEntity(nodeId = "stairs_node", floorId = "f1", x = 2f, y = 0f, nodeType = "Stairs", floorLevel = 0)
        val elevator = NodeEntity(nodeId = "elev_node", floorId = "f1", x = 1f, y = 0f, nodeType = "Elevator", floorLevel = 0)
        val exitFar = NodeEntity(nodeId = "exit_far", floorId = "f1", x = 15f, y = 0f, nodeType = "Emergency Exit", floorLevel = 0)
        val buildingEntryNear = NodeEntity(nodeId = "entry_near", floorId = "f1", x = 6f, y = 0f, nodeType = "Building Entry", floorLevel = 0)

        val allNodes = listOf(start, stairs, elevator, exitFar, buildingEntryNear)

        val e1 = com.indoorpulse.data.local.entity.EdgeEntity("e1", "start", "stairs_node", 2f)
        val e2 = com.indoorpulse.data.local.entity.EdgeEntity("e2", "start", "elev_node", 1f)
        val e3 = com.indoorpulse.data.local.entity.EdgeEntity("e3", "start", "entry_near", 6f)
        val e4 = com.indoorpulse.data.local.entity.EdgeEntity("e4", "start", "exit_far", 15f)

        routing.buildGraph(allNodes, listOf(e1, e2, e3, e4))

        // 1. STRICT FILTER: Only true exits. No stairs, no elevators.
        val validExits = allNodes.filter { it.type == "Emergency Exit" || it.type == "Building Entry" }

        assertEquals(2, validExits.size)
        assertTrue(validExits.none { it.type == "Stairs" })
        assertTrue(validExits.none { it.type == "Elevator" })
        assertTrue(validExits.any { it.type == "Emergency Exit" })
        assertTrue(validExits.any { it.type == "Building Entry" })

        // 2. Evaluate actual A* path distances
        var bestPath: List<NodeEntity>? = null
        var bestExit: NodeEntity? = null
        var shortestDistance = Float.MAX_VALUE

        validExits.forEach { exit ->
            val path = routing.findShortestPath("start", exit.id)
            if (path != null) {
                var pathLength = 0f
                for (i in 0 until path.size - 1) {
                    val n1 = path[i]
                    val n2 = path[i + 1]
                    pathLength += Math.hypot((n1.x - n2.x).toDouble(), (n1.y - n2.y).toDouble()).toFloat()
                }
                if (pathLength < shortestDistance) {
                    shortestDistance = pathLength
                    bestPath = path
                    bestExit = exit
                }
            }
        }

        assertNotNull(bestPath)
        assertEquals("entry_near", bestExit?.id)
        assertEquals(6f, shortestDistance, 0.01f)
    }

    @Test
    fun testEmergencySOSNoValidExitsReturnsEmpty() {
        val n1 = NodeEntity(nodeId = "stairs", floorId = "f1", x = 0f, y = 0f, nodeType = "Stairs", floorLevel = 0)
        val n2 = NodeEntity(nodeId = "elev", floorId = "f1", x = 1f, y = 1f, nodeType = "Elevator", floorLevel = 0)
        val n3 = NodeEntity(nodeId = "door", floorId = "f1", x = 2f, y = 2f, nodeType = "Room Door", floorLevel = 0)

        val allNodes = listOf(n1, n2, n3)
        val validExits = allNodes.filter { it.type == "Emergency Exit" || it.type == "Building Entry" }

        assertTrue(validExits.isEmpty())
    }

    @Test
    fun testTransitProximityTriggerDrivenByPoseStream() {
        val currentFloor = 0
        var transitTriggered = false
        var targetFloor = -1

        val portal0 = com.indoorpulse.domain.model.Coordinate(x = 10f, y = 10f, floor = 0, type = "Elevator")
        val portal1 = com.indoorpulse.domain.model.Coordinate(x = 10f, y = 10f, floor = 2, type = "Elevator")
        val route = listOf(portal0, portal1)

        val onLocationUpdated: (Float, Float) -> Unit = { x, y ->
            if (route.isNotEmpty()) {
                val nextWaypoint = route.first()
                val dist = Math.hypot((x - nextWaypoint.x).toDouble(), (y - nextWaypoint.y).toDouble())
                if (dist < 2.5) {
                    if (route.size > 1 && route[1].floorLevel != currentFloor) {
                        transitTriggered = true
                        targetFloor = route[1].floorLevel
                    }
                }
            }
        }

        // 1. Pose tick far away: (0, 0)
        val poseFar = com.indoorpulse.domain.model.Coordinate(x = 0f, y = 0f, floor = 0)
        onLocationUpdated(poseFar.x, poseFar.y)
        assertFalse(transitTriggered)

        // 2. Pose tick near portal: (9.5, 10.0) -> dist = 0.5 < 2.5m
        val poseNear = com.indoorpulse.domain.model.Coordinate(x = 9.5f, y = 10.0f, floor = 0)
        onLocationUpdated(poseNear.x, poseNear.y)
        assertTrue(transitTriggered)
        assertEquals(2, targetFloor)
    }
}