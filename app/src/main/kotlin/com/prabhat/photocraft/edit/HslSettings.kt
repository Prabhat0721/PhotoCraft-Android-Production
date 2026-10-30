package com.prabhat.photocraft.edit

enum class HslColor {
    RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, MAGENTA
}

data class HslAdjustment(
    val hue: Int = 0,
    val saturation: Int = 0,
    val luminance: Int = 0
) {
    companion object {
        val NEUTRAL = HslAdjustment(0, 0, 0)
        const val MIN_VALUE = -100
        const val MAX_VALUE = 100
    }
}

data class HslSettings(
    val adjustments: Map<HslColor, HslAdjustment> = HslColor.values().associateWith { HslAdjustment.NEUTRAL }
) {
    fun with(color: HslColor, adjustment: HslAdjustment): HslSettings {
        val newMap = adjustments.toMutableMap()
        newMap[color] = adjustment
        return copy(adjustments = newMap)
    }

    fun isNeutral(): Boolean = adjustments.values.all { it == HslAdjustment.NEUTRAL }

    companion object {
        val NEUTRAL = HslSettings()
    }
}
