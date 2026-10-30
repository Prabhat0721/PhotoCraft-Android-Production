package com.prabhat.photocraft.edit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object HealingMath {
    private const val CANDIDATE_DIRECTIONS = 16
    private const val RING_SAMPLES = 48

    fun applyInPlace(pixels: IntArray, width: Int, height: Int, settings: HealingSettings) {
        if (settings.isNeutral() || width <= 0 || height <= 0) return
        require(pixels.size >= width * height)

        val source = pixels.copyOf()
        val shortEdge = min(width, height).toFloat()

        for (spot in settings.spots) {
            healSpot(source, pixels, width, height, shortEdge, spot)
        }
    }

    private fun healSpot(
        source: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        shortEdge: Float,
        spot: HealingSpot
    ) {
        val centerX = spot.x * width
        val centerY = spot.y * height
        val radius = (spot.radius * shortEdge).coerceIn(2f, shortEdge / 2f)
        val feather = (radius * 0.35f).coerceAtLeast(1f)

        val left = (centerX - radius).toInt().coerceAtLeast(0)
        val right = (centerX + radius).toInt().coerceAtMost(width - 1)
        val top = (centerY - radius).toInt().coerceAtLeast(0)
        val bottom = (centerY + radius).toInt().coerceAtMost(height - 1)
        if (left > right || top > bottom) return

        val ring = sampleRing(source, width, height, centerX, centerY, radius)
        if (ring == null) return

        val offset = findBestOffset(source, width, height, centerX, centerY, radius, ring)
            ?: return

        val donorRing = sampleRing(
            source,
            width,
            height,
            centerX + offset.first,
            centerY + offset.second,
            radius
        ) ?: return

        val colorShiftR = ring[0] - donorRing[0]
        val colorShiftG = ring[1] - donorRing[1]
        val colorShiftB = ring[2] - donorRing[2]

        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue

                val donorX = (x + offset.first).toInt()
                val donorY = (y + offset.second).toInt()
                if (donorX !in 0 until width || donorY !in 0 until height) continue

                val amount = smoothEdge(distance, radius, feather)
                if (amount <= 0f) continue

                val index = y * width + x
                val base = output[index]
                val donor = source[donorY * width + donorX]

                val baseAlpha = base ushr 24
                val donorAlpha = donor ushr 24
                val alpha = (baseAlpha + (donorAlpha - baseAlpha) * amount).roundToInt()
                    .coerceIn(0, 255)

                val red = blendChannel(base, donor, 16, colorShiftR, amount)
                val green = blendChannel(base, donor, 8, colorShiftG, amount)
                val blue = blendChannel(base, donor, 0, colorShiftB, amount)

                output[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    private fun blendChannel(
        base: Int,
        donor: Int,
        shift: Int,
        colorShift: Float,
        amount: Float
    ): Int {
        val baseValue = (base shr shift) and 0xFF
        val donorValue = (((donor shr shift) and 0xFF) + colorShift)
            .coerceIn(0f, 255f)
        return (baseValue + (donorValue - baseValue) * amount)
            .roundToInt()
            .coerceIn(0, 255)
    }

    private fun smoothEdge(distance: Float, radius: Float, feather: Float): Float {
        val inner = (radius - feather).coerceAtLeast(0f)
        if (distance <= inner) return 1f
        if (distance >= radius) return 0f
        val t = (radius - distance) / feather
        return t * t * (3f - 2f * t)
    }

    private fun findBestOffset(
        source: IntArray,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        ring: FloatArray
    ): Pair<Float, Float>? {
        var bestOffset: Pair<Float, Float>? = null
        var bestScore = Float.MAX_VALUE
        val distance = radius * 2.4f

        for (i in 0 until CANDIDATE_DIRECTIONS) {
            val angle = (2.0 * PI * i / CANDIDATE_DIRECTIONS).toFloat()
            val offsetX = cos(angle) * distance
            val offsetY = sin(angle) * distance

            val donorCenterX = centerX + offsetX
            val donorCenterY = centerY + offsetY
            if (donorCenterX - radius < 0f || donorCenterX + radius >= width ||
                donorCenterY - radius < 0f || donorCenterY + radius >= height
            ) {
                continue
            }

            val donorRing = sampleRing(
                source,
                width,
                height,
                donorCenterX,
                donorCenterY,
                radius
            ) ?: continue

            val dr = ring[0] - donorRing[0]
            val dg = ring[1] - donorRing[1]
            val db = ring[2] - donorRing[2]
            val score = dr * dr + dg * dg + db * db
            if (score < bestScore) {
                bestScore = score
                bestOffset = offsetX to offsetY
            }
        }
        return bestOffset
    }

    private fun sampleRing(
        source: IntArray,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radius: Float
    ): FloatArray? {
        val ringRadius = radius * 1.25f
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0

        for (i in 0 until RING_SAMPLES) {
            val angle = (2.0 * PI * i / RING_SAMPLES).toFloat()
            val x = (centerX + cos(angle) * ringRadius).roundToInt()
            val y = (centerY + sin(angle) * ringRadius).roundToInt()
            if (x !in 0 until width || y !in 0 until height) continue
            val pixel = source[y * width + x]
            sumR += (pixel shr 16) and 0xFF
            sumG += (pixel shr 8) and 0xFF
            sumB += pixel and 0xFF
            count++
        }

        if (count == 0) return null
        return floatArrayOf(
            (sumR / count).toFloat(),
            (sumG / count).toFloat(),
            (sumB / count).toFloat()
        )
    }
}
