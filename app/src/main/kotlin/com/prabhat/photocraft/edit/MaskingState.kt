package com.prabhat.photocraft.edit

enum class MaskType {
    SUBJECT,
    BACKGROUND
}

data class LocalMaskAdjustments(
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val filter: FilterSettings = FilterSettings.NEUTRAL,
    val effects: PhotoEffects = PhotoEffects.NEUTRAL,
    val colorGrading: ColorGradingSettings = ColorGradingSettings.NEUTRAL,
    val hsl: HslSettings = HslSettings.NEUTRAL,
    val curves: CurvesSettings = CurvesSettings.NEUTRAL,
    val healing: HealingSettings = HealingSettings.NEUTRAL
)

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

    fun copiedCoverage(): ByteArray = coverage.copyOf()

    fun paint(
        sourceWidth: Int,
        sourceHeight: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        flow: Int = 100,
        erase: Boolean = false
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

        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x - mappedX
                val dy = y - mappedY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance <= r) {
                    val index = y * width + x
                    val old = output[index].toInt() and 0xFF
                    val strength = (amount * (1f - distance / r)).toInt()
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
        erase: Boolean = false
    ): MaskLayer {
        require(sourceWidth > 0 && sourceHeight > 0)
        val current = selection
            ?: MaskSelection(sourceWidth, sourceHeight, ByteArray(sourceWidth * sourceHeight))

        return copy(
            selection = current.paint(sourceWidth, sourceHeight, x, y, radius, flow, erase)
        )
    }
}

data class MaskingState(
    val subject: MaskLayer = MaskLayer(MaskType.SUBJECT),
    val background: MaskLayer = MaskLayer(MaskType.BACKGROUND),
    val selectedMask: MaskType = MaskType.SUBJECT,
    val brushSize: Float = 40f,
    val brushFeather: Float = 50f,
    val brushFlow: Float = 100f
) {
    fun selected(): MaskLayer =
        if (selectedMask == MaskType.SUBJECT) subject else background

    fun isNeutral(): Boolean =
        subject.adjustments.adjustments.isNeutral() && subject.adjustments.filter.isNeutral() && 
        subject.adjustments.effects.isNeutral() && subject.adjustments.colorGrading.isNeutral() && 
        subject.adjustments.hsl.isNeutral() && subject.adjustments.curves.isNeutral() && subject.adjustments.healing.isNeutral() &&
        background.adjustments.adjustments.isNeutral() && background.adjustments.filter.isNeutral() &&
        background.adjustments.effects.isNeutral() && background.adjustments.colorGrading.isNeutral() && 
        background.adjustments.hsl.isNeutral() && background.adjustments.curves.isNeutral() && background.adjustments.healing.isNeutral() &&
        subject.selection == null && background.selection == null

    fun paintSelected(
        sourceWidth: Int,
        sourceHeight: Int,
        x: Float,
        y: Float,
        radius: Float,
        flow: Int = 100,
        erase: Boolean = false
    ): MaskingState {
        val updated = selected().paint(sourceWidth, sourceHeight, x, y, radius, flow, erase)
        return when (selectedMask) {
            MaskType.SUBJECT -> copy(subject = updated)
            MaskType.BACKGROUND -> copy(background = updated)
        }
    }

    fun selectMask(type: MaskType): MaskingState =
        copy(selectedMask = type)
}
