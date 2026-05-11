package com.example.gnssandopticalflowapp.model

import kotlin.math.cos
import kotlin.math.sin

data class SphereMesh(
    val vertices: FloatArray,
    val indices: IntArray
)

fun createSphere(radius: Float, stacks: Int, slices: Int): SphereMesh {
    val vertices = mutableListOf<Float>()
    val indices = mutableListOf<Int>()

    for (i in 0..stacks) {
        val v = i.toFloat() / stacks
        val radLat = Math.PI / 2.0 - i * Math.PI / stacks
        val cosLat = cos(radLat)
        val sinLat = sin(radLat)

        for (j in 0..slices) {
            val u = j.toFloat() / slices
            val radLon = u * 2.0 * Math.PI - Math.PI

            val x = radius * cosLat * sin(radLon)
            val y = radius * sinLat
            val z = radius * cosLat * cos(radLon)

            vertices.add(x.toFloat())
            vertices.add(y.toFloat())
            vertices.add(z.toFloat())

            vertices.add(u)
            vertices.add(v)

            // Normalized normal vector
            val nx = x / radius
            val ny = y / radius
            val nz = z / radius
            vertices.add(nx.toFloat())
            vertices.add(ny.toFloat())
            vertices.add(nz.toFloat())
        }
    }

    val verticesPerRow = slices + 1
    for (i in 0 until stacks) {
        for (j in 0 until slices) {
            val first = i * verticesPerRow + j
            val second = first + verticesPerRow

            indices.add(first)
            indices.add(second)
            indices.add(first + 1)

            indices.add(second)
            indices.add(second + 1)
            indices.add(first + 1)
        }
    }

    return SphereMesh(vertices.toFloatArray(), indices.toIntArray())
}

