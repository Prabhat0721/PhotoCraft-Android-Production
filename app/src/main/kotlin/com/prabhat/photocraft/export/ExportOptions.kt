package com.prabhat.photocraft.export

import com.prabhat.photocraft.edit.Adjustments
import com.prabhat.photocraft.edit.CropSelection
import com.prabhat.photocraft.edit.CurvesSettings
import com.prabhat.photocraft.edit.FilterSettings
import com.prabhat.photocraft.edit.HealingSettings
import com.prabhat.photocraft.edit.MaskingState
import com.prabhat.photocraft.edit.PhotoEffects

enum class ExportQuality(val jpegQuality: Int) {
    HIGH(95),
    STANDARD(85),
    SMALLER_FILE(70)
}

enum class ExportColorSpace {
    SRGB,
    ADOBE_RGB
}

enum class ExportSize(val percent: Int?, val longEdgePx: Int?) {
    ORIGINAL(null, null),
    PERCENT_75(75, null),
    PERCENT_50(50, null),
    PERCENT_25(25, null),
    LONG_EDGE_2048(null, 2048),
    LONG_EDGE_1080(null, 1080)
}

data class OutputDimensions(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Output dimensions must be positive." }
    }
}

data class PixelCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(left >= 0 && top >= 0) { "Crop origin must be non-negative." }
        require(width > 0 && height > 0) { "Crop dimensions must be positive." }
    }

    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height
}

data class ExportRequest(
    val adjustments: Adjustments,
    val crop: CropSelection,
    val quality: ExportQuality,
    val outputSize: ExportSize,
    val filter: FilterSettings = FilterSettings.NEUTRAL,
    val effects: PhotoEffects = PhotoEffects.NEUTRAL,
    val colorGrading: com.prabhat.photocraft.edit.ColorGradingSettings = com.prabhat.photocraft.edit.ColorGradingSettings.NEUTRAL,
    val curves: CurvesSettings = CurvesSettings.NEUTRAL,
    val healing: HealingSettings = HealingSettings.NEUTRAL,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val masking: MaskingState = MaskingState(),
    val colorSpace: ExportColorSpace = ExportColorSpace.SRGB,
    val iccProfile: ByteArray? = null,
    val editSessionPayload: String? = null
)
