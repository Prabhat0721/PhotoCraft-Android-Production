package com.prabhat.photocraft.edit

data class EditState(
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val crop: CropSelection = CropSelection.FULL,
    val cropRatio: CropRatio = CropRatio.FREE,
    val filter: FilterSettings = FilterSettings.NEUTRAL,
    val effects: PhotoEffects = PhotoEffects.NEUTRAL,
    val colorGrading: ColorGradingSettings = ColorGradingSettings.NEUTRAL,
    val curves: CurvesSettings = CurvesSettings.NEUTRAL,
    val healing: HealingSettings = HealingSettings.NEUTRAL,
    val masking: MaskingState = MaskingState()
) {
    fun isNeutral(): Boolean =
        adjustments.isNeutral() &&
            crop.isFull() &&
            cropRatio == CropRatio.FREE &&
            filter.isNeutral() &&
            effects.isNeutral() &&
            colorGrading.isNeutral() &&
            curves.isNeutral() &&
            healing.isNeutral() &&
            masking.isNeutral()
}
