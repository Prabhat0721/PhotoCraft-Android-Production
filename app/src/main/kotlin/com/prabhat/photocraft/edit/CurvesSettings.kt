package com.prabhat.photocraft.edit

import kotlin.math.pow

data class CurvePoint(val x: Float, val y: Float)

data class CurveSettings(
    val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
) {
    fun isNeutral(): Boolean = points.size == 2 && points[0].x == 0f && points[0].y == 0f && points[1].x == 1f && points[1].y == 1f

    companion object {
        val NEUTRAL = CurveSettings()
    }
}

data class CurvesSettings(
    val rgb: CurveSettings = CurveSettings.NEUTRAL,
    val red: CurveSettings = CurveSettings.NEUTRAL,
    val green: CurveSettings = CurveSettings.NEUTRAL,
    val blue: CurveSettings = CurveSettings.NEUTRAL
) {
    fun isNeutral(): Boolean = rgb.isNeutral() && red.isNeutral() && green.isNeutral() && blue.isNeutral()

    companion object {
        val NEUTRAL = CurvesSettings()
    }
}
