package com.prabhat.photocraft.export

import kotlin.math.pow
import kotlin.math.roundToInt

object ColorSpaceConverter {
    private const val TABLE_SIZE = 4096
    private const val ADOBE_GAMMA = 2.19921875f

    private val srgbToLinear = FloatArray(256) { value ->
        val channel = value / 255f
        if (channel <= 0.04045f) {
            channel / 12.92f
        } else {
            ((channel + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private val adobeEncode = IntArray(TABLE_SIZE + 1) { index ->
        val linear = index.toFloat() / TABLE_SIZE
        (linear.coerceIn(0f, 1f).pow(1f / ADOBE_GAMMA) * 255f)
            .roundToInt().coerceIn(0, 255)
    }

    fun srgbToAdobeRgbInPlace(pixels: IntArray, count: Int) {
        val limit = minOf(count, pixels.size)
        for (index in 0 until limit) {
            val color = pixels[index]
            val alpha = color and -0x1000000
            val red = srgbToLinear[(color shr 16) and 0xFF]
            val green = srgbToLinear[(color shr 8) and 0xFF]
            val blue = srgbToLinear[color and 0xFF]

            val adobeRed = 0.7151626f * red + 0.2848373f * green
            val adobeGreen = green
            val adobeBlue = 0.0411705f * green + 0.9588295f * blue

            pixels[index] = alpha or
                (encode(adobeRed) shl 16) or
                (encode(adobeGreen) shl 8) or
                encode(adobeBlue)
        }
    }

    private fun encode(linear: Float): Int {
        val index = (linear.coerceIn(0f, 1f) * TABLE_SIZE).roundToInt()
        return adobeEncode[index]
    }
}
