package com.prabhat.photocraft.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PhotoEffects(
    val sharpness: Int = 0,
    val texture: Int = 0,
    val details: Int = 0,
    val noiseReduction: Int = 0,
    val vignette: Int = 0,
    val dehaze: Int = 0
) {
    init {
        require(listOf(sharpness, texture, details, noiseReduction, vignette, dehaze)
            .all { it in -100..100 }) {
            "Effect values must be between -100 and 100."
        }
    }

    fun isNeutral(): Boolean =
        sharpness == 0 && texture == 0 && details == 0 &&
            noiseReduction == 0 && vignette == 0 && dehaze == 0

    companion object {
        val NEUTRAL = PhotoEffects()
    }
}

object PhotoEffectsMath {
    fun applyInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        effects: PhotoEffects
    ) {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) {
            "Pixel dimensions must match the buffer."
        }
        if (effects.isNeutral()) return

        if (effects.noiseReduction > 0) {
            smooth(pixels, width, height, effects.noiseReduction)
        }
        if (effects.sharpness != 0) {
            localContrast(pixels, width, height, effects.sharpness, 1)
        }
        if (effects.texture != 0) {
            localContrast(pixels, width, height, effects.texture, 2)
        }
        if (effects.details != 0) {
            localContrast(pixels, width, height, effects.details, 1, 0.45f)
        }
        if (effects.dehaze != 0) {
            applyDehaze(pixels, width, height, effects.dehaze)
        }
        if (effects.vignette != 0) {
            applyVignette(pixels, width, height, effects.vignette)
        }
    }

    private fun applyDehaze(p: IntArray, w: Int, h: Int, amount: Int) {
        val strength = (amount / 100f).coerceIn(-1f, 1f)
        val src = p.copyOf()
        val dark = IntArray(src.size)
        val radius = 2

        for (y in 0 until h) {
            for (x in 0 until w) {
                var minimum = 255
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val c = src[yy * w + xx]
                        if ((c ushr 24) == 0) continue
                        val channelMin = min(
                            ((c shr 16) and 255),
                            min(((c shr 8) and 255), (c and 255))
                        )
                        if (channelMin < minimum) minimum = channelMin
                    }
                }
                dark[y * w + x] = minimum
            }
        }

        val airlight = 0.82f
        val omega = 0.85f
        for (i in src.indices) {
            val c = src[i]
            val alpha = c ushr 24
            if (alpha == 0) continue
            val darkNormalized = dark[i] / 255f
            val transmission = (1f - omega * darkNormalized / airlight)
                .coerceIn(0.28f, 1f)
            val r = ((c shr 16) and 255) / 255f
            val g = ((c shr 8) and 255) / 255f
            val b = (c and 255) / 255f

            val dehazedR = ((r - airlight) / transmission + airlight)
            val dehazedG = ((g - airlight) / transmission + airlight)
            val dehazedB = ((b - airlight) / transmission + airlight)

            val mixedR = r + (dehazedR - r) * strength
            val mixedG = g + (dehazedG - g) * strength
            val mixedB = b + (dehazedB - b) * strength

            p[i] = (alpha shl 24) or
                (clamp(mixedR * 255f) shl 16) or
                (clamp(mixedG * 255f) shl 8) or
                clamp(mixedB * 255f)
        }
    }

    private fun smooth(p: IntArray, w: Int, h: Int, amount: Int) {
        val src = p.copyOf()
        val strength = amount / 100f * 0.72f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = src[i]
            if ((c ushr 24) == 0) continue
            var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val n = src[yy * w + xx]
                val l1 = lum(c); val l2 = lum(n)
                val weight = if (abs(l1 - l2) < 36) 1f else 0.18f
                sr += ((n shr 16) and 255) * weight
                sg += ((n shr 8) and 255) * weight
                sb += (n and 255) * weight
                count += 1
            }
            val r = ((c shr 16) and 255).toFloat()
            val g = ((c shr 8) and 255).toFloat()
            val b = (c and 255).toFloat()
            val total = max(1f, count.toFloat())
            p[i] = (c and -0x1000000) or
                (mix(r, sr / total, strength) shl 16) or
                (mix(g, sg / total, strength) shl 8) or
                mix(b, sb / total, strength)
        }
    }

    private fun localContrast(
        p: IntArray, w: Int, h: Int, amount: Int,
        radius: Int, scale: Float = 1f
    ) {
        val src = p.copyOf()
        val strength = amount / 100f * 1.15f * scale
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = src[i]
            if ((c ushr 24) == 0) continue
            var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val n = src[yy * w + xx]
                sr += ((n shr 16) and 255).toFloat()
                sg += ((n shr 8) and 255).toFloat()
                sb += (n and 255).toFloat()
                count++
            }
            if (count == 0) continue
            val r = ((c shr 16) and 255).toFloat()
            val g = ((c shr 8) and 255).toFloat()
            val b = (c and 255).toFloat()
            p[i] = (c and -0x1000000) or
                (channel(r, sr / count, strength) shl 16) or
                (channel(g, sg / count, strength) shl 8) or
                channel(b, sb / count, strength)
        }
    }

    private fun applyVignette(p: IntArray, w: Int, h: Int, amount: Int) {
        val strength = amount / 100f * 0.72f
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxDist = max(1f, sqrt(cx * cx + cy * cy))
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = p[i]
            if ((c ushr 24) == 0) continue
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / maxDist
            val edge = (d * d).coerceIn(0f, 1f)
            val factor = (1f - strength * edge).coerceIn(0.15f, 1.85f)
            p[i] = (c and -0x1000000) or
                (clamp(((c shr 16) and 255) * factor) shl 16) or
                (clamp(((c shr 8) and 255) * factor) shl 8) or
                clamp((c and 255) * factor)
        }
    }

    private fun lum(c: Int): Int =
        ((((c shr 16) and 255) * 30 +
            ((c shr 8) and 255) * 59 +
            (c and 255) * 11) / 100)

    private fun mix(a: Float, b: Float, t: Float): Int =
        clamp(a + (b - a) * t)

    private fun channel(value: Float, average: Float, strength: Float): Int =
        clamp(value + (value - average) * strength)

    private fun clamp(v: Float): Int =
        v.toInt().coerceIn(0, 255)
}
