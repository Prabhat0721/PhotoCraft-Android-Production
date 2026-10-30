package com.prabhat.photocraft.edit

data class HealingSpot(val x: Float, val y: Float, val radius: Float)

data class HealingSettings(
    val spots: List<HealingSpot> = emptyList()
) {
    fun isNeutral(): Boolean = spots.isEmpty()

    companion object {
        val NEUTRAL = HealingSettings()
    }
}
