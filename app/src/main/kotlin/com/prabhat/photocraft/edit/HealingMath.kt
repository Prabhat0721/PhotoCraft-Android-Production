package com.prabhat.photocraft.edit

object HealingMath {
    fun applyInPlace(pixels: IntArray, width: Int, height: Int, settings: HealingSettings) {
        if (settings.isNeutral()) return

        // Simple patch-based approach: For each spot, sample from nearby pixels
        for (spot in settings.spots) {
            val radius = spot.radius.toInt()
            val centerX = (spot.x * width).toInt()
            val centerY = (spot.y * height).toInt()
            
            // Basic "heal" - average surrounding area (very simplified for demo)
            val left = (centerX - radius).coerceIn(0, width - 1)
            val right = (centerX + radius).coerceIn(0, width - 1)
            val top = (centerY - radius).coerceIn(0, height - 1)
            val bottom = (centerY + radius).coerceIn(0, height - 1)
            
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var count = 0
            
            for (y in top..bottom) {
                for (x in left..right) {
                    val idx = y * width + x
                    val argb = pixels[idx]
                    sumR += (argb shr 16) and 0xFF
                    sumG += (argb shr 8) and 0xFF
                    sumB += argb and 0xFF
                    count++
                }
            }
            
            if (count > 0) {
                val avgR = (sumR / count).toInt()
                val avgG = (sumG / count).toInt()
                val avgB = (sumB / count).toInt()
                
                // Set the center to average
                pixels[centerY * width + centerX] = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
            }
        }
    }
}
