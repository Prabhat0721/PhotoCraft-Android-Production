package com.prabhat.photocraft.edit

import kotlin.math.abs

object HslMath {
    fun applyInPlace(pixels: IntArray, settings: HslSettings) {
        if (settings.isNeutral()) return

        for (i in pixels.indices) {
            val argb = pixels[i]
            val alpha = argb ushr 24
            if (alpha == 0) continue

            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f

            val hsv = rgbToHsv(r, g, b)
            var h = hsv[0]
            var s = hsv[1]
            var v = hsv[2]

            // Find applicable color band
            val color = getHslColor(h)
            val adj = settings.adjustments[color] ?: HslAdjustment.NEUTRAL

            // Apply adjustments
            if (adj.hue != 0) {
                h = (h + (adj.hue / 100f * 180f)) % 360f
                if (h < 0) h += 360f
            }
            if (adj.saturation != 0) {
                s = (s + (adj.saturation / 100f)).coerceIn(0f, 1f)
            }
            if (adj.luminance != 0) {
                v = (v + (adj.luminance / 100f)).coerceIn(0f, 1f)
            }

            val rgb = hsvToRgb(h, s, v)
            pixels[i] = (alpha shl 24) or
                    ((rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private fun getHslColor(h: Float): HslColor {
        return when {
            h < 20 || h >= 340 -> HslColor.RED
            h < 40 -> HslColor.ORANGE
            h < 70 -> HslColor.YELLOW
            h < 160 -> HslColor.GREEN
            h < 200 -> HslColor.CYAN
            h < 260 -> HslColor.BLUE
            h < 300 -> HslColor.PURPLE
            else -> HslColor.MAGENTA
        }
    }

    private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        var h = 0f
        if (delta != 0f) {
            h = when (max) {
                r -> ((g - b) / delta) % 6
                g -> ((b - r) / delta) + 2
                else -> ((r - g) / delta) + 4
            } * 60f
            if (h < 0) h += 360f
        }
        val s = if (max == 0f) 0f else delta / max
        return floatArrayOf(h, s, max)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r1 + m, g1 + m, b1 + m)
    }
}
