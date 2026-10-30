package com.prabhat.photocraft.edit

object CurvesMath {
    fun applyInPlace(pixels: IntArray, settings: CurvesSettings) {
        if (settings.isNeutral()) return

        val rgbMap = createLookupTable(settings.rgb)
        val redMap = createLookupTable(settings.red)
        val greenMap = createLookupTable(settings.green)
        val blueMap = createLookupTable(settings.blue)

        for (i in pixels.indices) {
            val argb = pixels[i]
            val alpha = argb ushr 24
            if (alpha == 0) continue

            var r = ((argb shr 16) and 0xFF)
            var g = ((argb shr 8) and 0xFF)
            var b = (argb and 0xFF)

            // Apply Channel curves
            r = redMap[r]
            g = greenMap[g]
            b = blueMap[b]

            // Apply RGB composite curve
            r = rgbMap[r]
            g = rgbMap[g]
            b = rgbMap[b]

            pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun createLookupTable(settings: CurveSettings): IntArray {
        val table = IntArray(256)
        if (settings.isNeutral()) {
            for (i in 0..255) table[i] = i
            return table
        }

        // Simplistic linear interpolation for now
        val sorted = settings.points.sortedBy { it.x }
        for (i in 0..255) {
            val x = i / 255f
            var y = x
            for (j in 0 until sorted.size - 1) {
                if (x >= sorted[j].x && x <= sorted[j + 1].x) {
                    val t = (x - sorted[j].x) / (sorted[j + 1].x - sorted[j].x)
                    y = sorted[j].y + t * (sorted[j + 1].y - sorted[j].y)
                    break
                }
            }
            table[i] = (y * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return table
    }
}
