package com.prabhat.photocraft.edit

import kotlin.math.abs
import kotlin.math.sqrt

object CurvesMath {
    fun applyInPlace(pixels: IntArray, settings: CurvesSettings) {
        applyInPlace(pixels, settings, pixels.size)
    }

    fun applyInPlace(pixels: IntArray, settings: CurvesSettings, pixelCount: Int) {
        require(pixelCount in 0..pixels.size)
        if (settings.isNeutral() || pixelCount == 0) return

        val rgbMap = createLookupTable(settings.rgb)
        val redMap = createLookupTable(settings.red)
        val greenMap = createLookupTable(settings.green)
        val blueMap = createLookupTable(settings.blue)

        for (i in 0 until pixelCount) {
            val argb = pixels[i]
            val alpha = argb ushr 24
            if (alpha == 0) continue

            var r = redMap[(argb shr 16) and 0xFF]
            var g = greenMap[(argb shr 8) and 0xFF]
            var b = blueMap[argb and 0xFF]

            r = rgbMap[r]
            g = rgbMap[g]
            b = rgbMap[b]

            pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    internal fun createLookupTable(settings: CurveSettings): IntArray {
        val table = IntArray(256)
        if (settings.isNeutral()) {
            for (i in 0..255) table[i] = i
            return table
        }

        val points = sanitize(settings.points)
        if (points.size < 2) {
            for (i in 0..255) table[i] = i
            return table
        }

        val xs = DoubleArray(points.size) { points[it].x.toDouble() }
        val ys = DoubleArray(points.size) { points[it].y.toDouble() }
        val slopes = monotoneSlopes(xs, ys)

        for (i in 0..255) {
            val x = i / 255.0
            table[i] = (evaluate(xs, ys, slopes, x) * 255.0 + 0.5)
                .toInt()
                .coerceIn(0, 255)
        }
        return table
    }

    private fun sanitize(raw: List<CurvePoint>): List<CurvePoint> {
        val sorted = raw
            .map {
                CurvePoint(
                    x = it.x.coerceIn(0f, 1f),
                    y = it.y.coerceIn(0f, 1f)
                )
            }
            .sortedBy { it.x }

        val deduped = ArrayList<CurvePoint>(sorted.size)
        for (point in sorted) {
            if (deduped.isNotEmpty() && abs(deduped.last().x - point.x) < 1e-4f) {
                deduped[deduped.size - 1] = point
            } else {
                deduped.add(point)
            }
        }
        if (deduped.isEmpty()) {
            return listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        }
        if (deduped.first().x > 0f) {
            deduped.add(0, CurvePoint(0f, deduped.first().y))
        }
        if (deduped.last().x < 1f) {
            deduped.add(CurvePoint(1f, deduped.last().y))
        }
        return deduped
    }

    private fun monotoneSlopes(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        val slopes = DoubleArray(n)
        if (n < 2) return slopes

        val deltas = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            deltas[i] = if (dx == 0.0) 0.0 else (ys[i + 1] - ys[i]) / dx
        }

        slopes[0] = deltas[0]
        slopes[n - 1] = deltas[n - 2]
        for (i in 1 until n - 1) {
            if (deltas[i - 1] * deltas[i] <= 0.0) {
                slopes[i] = 0.0
            } else {
                slopes[i] = (deltas[i - 1] + deltas[i]) / 2.0
            }
        }

        for (i in 0 until n - 1) {
            val delta = deltas[i]
            if (delta == 0.0) {
                slopes[i] = 0.0
                slopes[i + 1] = 0.0
                continue
            }
            val alpha = slopes[i] / delta
            val beta = slopes[i + 1] / delta
            val magnitude = alpha * alpha + beta * beta
            if (magnitude > 9.0) {
                val tau = 3.0 / sqrt(magnitude)
                slopes[i] = tau * alpha * delta
                slopes[i + 1] = tau * beta * delta
            }
        }
        return slopes
    }

    private fun evaluate(
        xs: DoubleArray,
        ys: DoubleArray,
        slopes: DoubleArray,
        x: Double
    ): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        var low = 0
        var high = xs.size - 1
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (xs[mid] <= x) low = mid else high = mid
        }

        val h = xs[high] - xs[low]
        if (h <= 0.0) return ys[high]
        val t = (x - xs[low]) / h
        val t2 = t * t
        val t3 = t2 * t

        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
        val h10 = t3 - 2.0 * t2 + t
        val h01 = -2.0 * t3 + 3.0 * t2
        val h11 = t3 - t2

        return h00 * ys[low] +
            h10 * h * slopes[low] +
            h01 * ys[high] +
            h11 * h * slopes[high]
    }
}
