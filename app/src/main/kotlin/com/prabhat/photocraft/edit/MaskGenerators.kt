package com.prabhat.photocraft.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MaskGenerators {
    fun linearGradient(
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        feather: Float = 0.5f
    ): MaskSelection {
        require(width > 0 && height > 0)
        val coverage = ByteArray(width * height)
        val dx = endX - startX
        val dy = endY - startY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1e-6f) {
            return MaskSelection(width, height, coverage)
        }

        val softness = feather.coerceIn(0.01f, 1f)
        val invWidth = 1f / (width - 1).coerceAtLeast(1)
        val invHeight = 1f / (height - 1).coerceAtLeast(1)

        for (y in 0 until height) {
            val py = y * invHeight
            for (x in 0 until width) {
                val px = x * invWidth
                val t = ((px - startX) * dx + (py - startY) * dy) / lengthSquared
                val ramp = smoothStep(0.5f - softness / 2f, 0.5f + softness / 2f, t)
                coverage[y * width + x] = (ramp * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return MaskSelection(width, height, coverage)
    }

    fun radialGradient(
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        edgeX: Float,
        edgeY: Float,
        feather: Float = 0.5f
    ): MaskSelection {
        require(width > 0 && height > 0)
        val coverage = ByteArray(width * height)
        val radiusX = (edgeX - centerX) * width
        val radiusY = (edgeY - centerY) * height
        val radius = sqrt(radiusX * radiusX + radiusY * radiusY)
        if (radius < 1f) {
            return MaskSelection(width, height, coverage)
        }

        val softness = feather.coerceIn(0.01f, 1f)
        val inner = radius * (1f - softness)
        val centerPx = centerX * width
        val centerPy = centerY * height

        for (y in 0 until height) {
            val dy = y - centerPy
            for (x in 0 until width) {
                val dx = x - centerPx
                val distance = sqrt(dx * dx + dy * dy)
                val ramp = 1f - smoothStep(inner, radius, distance)
                coverage[y * width + x] = (ramp * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return MaskSelection(width, height, coverage)
    }

    fun colorRange(
        width: Int,
        height: Int,
        pixels: IntArray,
        targetColor: Int,
        tolerance: Float = 0.22f,
        feather: Float = 0.35f
    ): MaskSelection {
        require(width > 0 && height > 0)
        require(pixels.size >= width * height)
        val coverage = ByteArray(width * height)
        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF
        val limit = tolerance.coerceIn(0.02f, 1f)
        val softness = feather.coerceIn(0.01f, 1f)
        val innerLimit = limit * (1f - softness)
        val maxDistance = 441.673f

        for (i in 0 until width * height) {
            val pixel = pixels[i]
            if ((pixel ushr 24) == 0) continue
            val dr = (((pixel shr 16) and 0xFF) - targetR).toFloat()
            val dg = (((pixel shr 8) and 0xFF) - targetG).toFloat()
            val db = ((pixel and 0xFF) - targetB).toFloat()
            val distance = sqrt(dr * dr + dg * dg + db * db) / maxDistance
            val ramp = 1f - smoothStep(innerLimit, limit, distance)
            coverage[i] = (ramp * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        return MaskSelection(width, height, coverage)
    }

    fun luminanceRange(
        width: Int,
        height: Int,
        pixels: IntArray,
        targetLuminance: Float,
        tolerance: Float = 0.22f,
        feather: Float = 0.35f
    ): MaskSelection {
        require(width > 0 && height > 0)
        require(pixels.size >= width * height)
        val coverage = ByteArray(width * height)
        val limit = tolerance.coerceIn(0.02f, 1f)
        val softness = feather.coerceIn(0.01f, 1f)
        val innerLimit = limit * (1f - softness)

        for (i in 0 until width * height) {
            val pixel = pixels[i]
            if ((pixel ushr 24) == 0) continue
            val luminance = luminanceOf(pixel)
            val distance = abs(luminance - targetLuminance)
            val ramp = 1f - smoothStep(innerLimit, limit, distance)
            coverage[i] = (ramp * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        return MaskSelection(width, height, coverage)
    }

    fun luminanceOf(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    fun sampleColor(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
        val safeX = x.coerceIn(0, width - 1)
        val safeY = y.coerceIn(0, height - 1)
        return pixels[safeY * width + safeX]
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value < edge0) 0f else 1f
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
