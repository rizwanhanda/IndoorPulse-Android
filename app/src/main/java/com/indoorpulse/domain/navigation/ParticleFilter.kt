package com.indoorpulse.domain.navigation

import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Particle(var x: Float, var y: Float, var weight: Float)

class ParticleFilter(private val numParticles: Int = 1000) {

    // The exact real-world compass degree that corresponds to "UP" on your screen.
    // Set to 180f based on the X/Y inversion you reported.
    private val MAP_NORTH_AZIMUTH = 180f

    private val random = Random()
    var particles = List(numParticles) { Particle(0f, 0f, 1f / numParticles) }
        private set

    fun initialize(startX: Float, startY: Float) {
        particles = List(numParticles) {
            val nx = startX + (random.nextGaussian() * 0.5).toFloat()
            val ny = startY + (random.nextGaussian() * 0.5).toFloat()
            Particle(nx, ny, 1f / numParticles.toFloat())
        }
    }

    fun update(
        stepLength: Float,
        heading: Float,
        edges: List<EdgeEntity>,
        nodesMap: Map<String, NodeEntity>,
        constrainToGraph: Boolean
    ) {
        var sumWeights = 0f

        for (particle in particles) {
            val noisyStep = stepLength * (1.0f + (random.nextGaussian().toFloat() * 0.1f))
            
            // 1. Translate World Space (True North) into Map Space (Screen Up)
            var mapSpaceHeading = (heading - MAP_NORTH_AZIMUTH) % 360f
            if (mapSpaceHeading < 0) mapSpaceHeading += 360f
            
            // 2. Inject Gaussian noise AFTER the frame translation
            mapSpaceHeading += (random.nextGaussian().toFloat() * 5.0f)
            val headingRadians = Math.toRadians(mapSpaceHeading.toDouble())
            
            // 3. PURE CANVAS MATH (+Y is Down). 
            // If mapSpaceHeading is 0 (Up), X adds 0, Y subtracts step (moves Up on screen).
            // CRITICAL FIX: The X-axis operator is now `-=` instead of `+=`.
            // This perfectly mirrors Left and Right movement while keeping Up and Down intact.
            particle.x -= (noisyStep * Math.sin(headingRadians)).toFloat()
            particle.y -= (noisyStep * Math.cos(headingRadians)).toFloat()

            if (constrainToGraph && edges.isNotEmpty() && nodesMap.isNotEmpty()) {
                var minDistance = Float.MAX_VALUE

                for (edge in edges) {
                    val fromNode = nodesMap[edge.fromNodeId]
                    val toNode = nodesMap[edge.toNodeId]

                    if (fromNode != null && toNode != null) {
                        val proj = getProjectedPoint(particle.x, particle.y, fromNode.x, fromNode.y, toNode.x, toNode.y)
                        val dist = distanceTo(particle.x, particle.y, proj.first, proj.second)
                        if (dist < minDistance) {
                            minDistance = dist
                        }
                    }
                }

                if (minDistance < 4.0f) {
                    val sigma = 1.5f
                    val likelihood = Math.exp(-(minDistance * minDistance).toDouble() / (2 * sigma * sigma)).toFloat()
                    particle.weight *= Math.max(likelihood, 0.1f)
                }
            } else {
                particle.weight = 1f / numParticles
            }
            sumWeights += particle.weight
        }

        if (sumWeights > 0f) {
            particles.forEach { p ->
                p.weight /= sumWeights
            }
        }

        resample()
    }

    private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        if (l2 == 0f) return Math.hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
        var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
        t = Math.max(0f, Math.min(1f, t))
        val projX = x1 + t * (x2 - x1)
        val projY = y1 + t * (y2 - y1)
        return Math.hypot((px - projX).toDouble(), (py - projY).toDouble()).toFloat()
    }

    private fun getProjectedPoint(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
        val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        if (l2 == 0f) return Pair(x1, y1)
        var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
        t = Math.max(0f, Math.min(1f, t))
        return Pair(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }

    private fun distanceTo(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
    }

    fun resample() {
        val newParticles = mutableListOf<Particle>()
        var index = random.nextInt(numParticles)
        var beta = 0f
        val maxWeight = particles.maxOfOrNull { it.weight } ?: return

        for (i in 0 until numParticles) {
            beta += random.nextFloat() * 2f * maxWeight
            while (beta > particles[index].weight) {
                beta -= particles[index].weight
                index = (index + 1) % numParticles
            }
            val p = particles[index]
            newParticles.add(Particle(p.x, p.y, p.weight))
        }

        particles = newParticles
    }

    fun getEstimatedPosition(): Pair<Float, Float> {
        var avgX = 0f
        var avgY = 0f
        var sumWeights = 0f
        particles.forEach { p ->
            avgX += p.x * p.weight
            avgY += p.y * p.weight
            sumWeights += p.weight
        }
        return if (sumWeights > 0f) {
            Pair(avgX / sumWeights, avgY / sumWeights)
        } else {
            Pair(0f, 0f)
        }
    }

    fun forcePosition(newX: Float, newY: Float) {
        // Teleport all particles to the exact new position
        particles.forEach { particle ->
            particle.x = newX
            particle.y = newY
            particle.weight = 1.0f / particles.size
        }
    }
}
