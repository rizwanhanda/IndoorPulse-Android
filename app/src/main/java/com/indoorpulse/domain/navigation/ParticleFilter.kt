package com.indoorpulse.domain.navigation

import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Particle(var x: Float, var y: Float, var weight: Float)

class ParticleFilter(private val numParticles: Int = 1000) {

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
            
            // 1. THE ROOT FIX: Add 180 degrees to perfectly flip the compass to match your drawn map
            val alignedHeading = (heading + 180f + (random.nextGaussian().toFloat() * 5.0f)) % 360f
            val headingRadians = Math.toRadians(alignedHeading.toDouble())
            
            // 2. PURE MATH: No hacked negative signs. 
            // In Canvas: 0 degrees (Up) means X is 0, and Y subtracts (goes Up the screen).
            particle.x += (noisyStep * Math.sin(headingRadians)).toFloat()
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
}
