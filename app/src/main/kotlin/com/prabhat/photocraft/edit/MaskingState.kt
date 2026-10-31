package com.prabhat.photocraft.edit

enum class MaskType {
    SUBJECT,
    BACKGROUND,
    BRUSH,
    LINEAR_GRADIENT,
    RADIAL_GRADIENT,
    COLOR_RANGE,
    LUMINANCE_RANGE
}

data class LocalMaskAdjustments(
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val filter: FilterSettings = FilterSettings.NEUTRAL,
    val effects: PhotoEffects = PhotoEffects.NEUTRAL,
    val colorGrading: ColorGradingSettings = ColorGradingSettings.NEUTRAL,
    val curves: CurvesSettings = CurvesSettings.NEUTRAL,
    val healing: HealingSettings = HealingSettings.NEUTRAL
) {
    fun isNeutral(): Boolean =
        adjustments.isNeutral() &&
            filter.isNeutral() &&
            effects.isNeutral() &&
            colorGrading.isNeutral() &&
            curves.isNeutral() &&
            healing.isNeutral()
}

data class MaskSelection(
    val width: Int,
    val height: Int,
    val coverage: ByteArray
) {
    init {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
        require(width.toLong() * height.toLong() == coverage.size.toLong()) {
            "Coverage size must match mask dimensions"
        }
    }

    fun coverageAt(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return coverage[y * width + x].toInt() and 0xFF
    }

    fun coverageAtBilinear(x: Float, y: Float): Int {
        if (width <= 0 || height <= 0) return 0
        val clampedX = x.coerceIn(0f, (width - 1).toFloat())
        val clampedY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = clampedX - x0
        val ty = clampedY - y0
        val top = coverageAt(x0, y0) * (1f - tx) + coverageAt(x1, y0) * tx
        val bottom = coverageAt(x0, y1) * (1f - tx) + coverageAt(x1, y1) * tx
        return (top * (1f - ty) + bottom * ty).toInt().coerceIn(0, 255)
    }

    fun smoothed(radius: Int = 1): MaskSelection {
        val safeRadius = radius.coerceIn(0, 3)
        if (safeRadius == 0) return this
        val output = ByteArray(coverage.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -safeRadius..safeRadius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -safeRadius..safeRadius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        sum += coverageAt(xx, yy)
                        count += 1
                    }
                }
                output[y * width + x] = (sum / count).toByte()
            }
        }
        return copy(coverage = output)
    }

    fun copiedCoverage(): ByteArray = coverage.copyOf()

    fun inverted(): MaskSelection {
        val output = ByteArray(coverage.size) { i ->
            (255 - (coverage[i].toInt() and 0xFF)).toByte()
        }
        return copy(coverage = output)
    }

    fun paint(
        sourceWidth: Int,
        sourceHeight: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        flow: Int = 100,
        erase: Boolean = false,
        feather: Float = 0f
    ): MaskSelection {
        val scaleX = width.toFloat() / sourceWidth.coerceAtLeast(1)
        val scaleY = height.toFloat() / sourceHeight.coerceAtLeast(1)

        val mappedX = centerX * scaleX
        val mappedY = centerY * scaleY
        val mappedRadius = radius * maxOf(scaleX, scaleY)

        val output = coverage.copyOf()
        val r = mappedRadius.coerceAtLeast(1f)
        val left = (mappedX - r).toInt().coerceAtLeast(0)
        val right = (mappedX + r).toInt().coerceAtMost(width - 1)
        val top = (mappedY - r).toInt().coerceAtLeast(0)
        val bottom = (mappedY + r).toInt().coerceAtMost(height - 1)
        val amount = flow.coerceIn(0, 100) * 255 / 100
        val featherAmount = feather.coerceIn(0f, 1f)
        val inner = r * (1f - featherAmount)

        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x - mappedX
                val dy = y - mappedY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance <= r) {
                    val falloff = if (distance <= inner) {
                        1f
                    } else {
                        val t = ((r - distance) / (r - inner).coerceAtLeast(0.0001f))
                            .coerceIn(0f, 1f)
                        t * t * (3f - 2f * t)
                    }
                    val index = y * width + x
                    val old = output[index].toInt() and 0xFF
                    val strength = (amount * falloff).toInt()
                    val updated = if (erase) {
                        (old - strength).coerceAtLeast(0)
                    } else {
                        (old + strength).coerceAtMost(255)
                    }
                    output[index] = updated.toByte()
                }
            }
        }
        return copy(coverage = output)
    }
}

data class MaskLayer(
    val type: MaskType,
    val selection: MaskSelection? = null,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    val adjustments: LocalMaskAdjustments = LocalMaskAdjustments()
) {
    fun paint(
        sourceWidth: Int,
        sourceHeight: Int,
        x: Float,
        y: Float,
        radius: Float,
        flow: Int,
        erase: Boolean = false,
        feather: Float = 0f,
        maxEdge: Int = 0
    ): MaskLayer {
        require(sourceWidth > 0 && sourceHeight > 0)
        val current = selection ?: run {
            var maskWidth = sourceWidth
            var maskHeight = sourceHeight
            if (maxEdge > 0) {
                val longest = maxOf(sourceWidth, sourceHeight)
                if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    maskWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
                    maskHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
                }
            }
            MaskSelection(maskWidth, maskHeight, ByteArray(maskWidth * maskHeight))
        }

        return copy(
            selection = current.paint(
                sourceWidth, sourceHeight, x, y, radius, flow, erase, feather
            )
        )
    }
}

data class MaskingState(
    val layers: List<MaskLayer> = MaskType.entries.map { MaskLayer(it) },
    val selectedMask: MaskType = MaskType.SUBJECT,
    val brushSize: Float = 40f,
    val brushFeather: Float = 50f,
    val brushFlow: Float = 100f,
    val colorTarget: Int? = null,
    val colorTolerance: Float = 0.22f,
    val colorFeather: Float = 0.35f,
    val luminanceTarget: Float? = null,
    val luminanceTolerance: Float = 0.22f,
    val luminanceFeather: Float = 0.35f
) {
    fun layer(type: MaskType): MaskLayer =
        layers.firstOrNull { it.type == type } ?: MaskLayer(type)

    fun withLayer(updated: MaskLayer): MaskingState {
        val found = layers.any { it.type == updated.type }
        val next = if (found) {
            layers.map { if (it.type == updated.type) updated else it }
        } else {
            layers + updated
        }
        return copy(layers = next)
    }

    val subject: MaskLayer
        get() = layer(MaskType.SUBJECT)

    val background: MaskLayer
        get() = layer(MaskType.BACKGROUND)

    fun selected(): MaskLayer = layer(selectedMask)

    fun isNeutral(): Boolean =
        layers.all { layer ->
            layer.selection == null && layer.adjustments.isNeutral()
        }

    fun paintSelected(
        sourceWidth: Int,
        sourceHeight: Int,
        x: Float,
        y: Float,
        radius: Float,
        flow: Int = 100,
        erase: Boolean = false,
        feather: Float = 0f,
        maxEdge: Int = 0
    ): MaskingState {
        val updated = selected().paint(
            sourceWidth, sourceHeight, x, y, radius, flow, erase, feather, maxEdge
        )
        return withLayer(updated)
    }

    fun withSelection(
        type: MaskType,
        selection: MaskSelection?,
        inverted: Boolean = false
    ): MaskingState =
        withLayer(layer(type).copy(selection = selection, inverted = inverted))

    fun selectMask(type: MaskType): MaskingState =
        copy(selectedMask = type)
}
