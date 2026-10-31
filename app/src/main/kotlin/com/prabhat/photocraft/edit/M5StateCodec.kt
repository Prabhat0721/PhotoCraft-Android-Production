package com.prabhat.photocraft.edit

import kotlin.math.roundToInt

object M5StateCodec {
    const val LEGACY_VALUE_COUNT = 18
    const val PREVIOUS_VALUE_COUNT = 53
    const val VALUE_COUNT = 193
    private const val ADJUSTMENT_COUNT = 11
    private const val MAX_CURVE_POINTS = 16
    private const val BASIS_POINT_SCALE = 10000f

    fun encode(state: EditState): IntArray {
        val values = IntArray(VALUE_COUNT)
        state.adjustments.toIntArray().copyInto(values)
        values[11] = toBasisPoints(state.crop.left)
        values[12] = toBasisPoints(state.crop.top)
        values[13] = toBasisPoints(state.crop.right)
        values[14] = toBasisPoints(state.crop.bottom)
        values[15] = state.cropRatio.ordinal
        values[16] = state.filter.filter.ordinal
        values[17] = state.filter.intensity

        var i = LEGACY_VALUE_COUNT
        for (color in HslColor.entries) {
            val a = state.colorGrading.hsl.adjustment(color)
            values[i++] = a.hue
            values[i++] = a.saturation
            values[i++] = a.luminance
        }
        for (grade in listOf(
            state.colorGrading.shadows,
            state.colorGrading.midtones,
            state.colorGrading.highlights
        )) {
            values[i++] = grade.hue
            values[i++] = grade.amount
            values[i++] = grade.luminance
        }
        values[i++] = state.colorGrading.balance
        values[i] = state.colorGrading.blending

        i = PREVIOUS_VALUE_COUNT
        val effects = state.effects
        values[i++] = effects.sharpness
        values[i++] = effects.texture
        values[i++] = effects.details
        values[i++] = effects.noiseReduction
        values[i++] = effects.vignette
        values[i++] = effects.dehaze
        values[i++] = effects.fade
        values[i++] = effects.grain

        val curves = state.curves
        for (curve in listOf(curves.rgb, curves.red, curves.green, curves.blue)) {
            val points = curve.points
                .sortedBy { it.x }
                .take(MAX_CURVE_POINTS)
            values[i++] = points.size
            for (pointIndex in 0 until MAX_CURVE_POINTS) {
                if (pointIndex < points.size) {
                    values[i++] = toBasisPoints(points[pointIndex].x)
                    values[i++] = toBasisPoints(points[pointIndex].y)
                } else {
                    values[i++] = 0
                    values[i++] = 0
                }
            }
        }
        return values
    }

    fun decode(values: IntArray?): EditState? {
        if (values == null) return null
        val size = values.size
        if (size != LEGACY_VALUE_COUNT &&
            size != PREVIOUS_VALUE_COUNT &&
            size != VALUE_COUNT
        ) {
            return null
        }

        return try {
            val adjustments = Adjustments.fromIntArray(
                values.copyOfRange(0, ADJUSTMENT_COUNT)
            )
            val crop = CropSelection.fromBasisPoints(
                left = values[11],
                top = values[12],
                right = values[13],
                bottom = values[14]
            )
            val ratio = CropRatio.entries.getOrNull(values[15]) ?: return null
            val filter = Filter.entries.getOrNull(values[16]) ?: return null
            val filterSettings = FilterSettings(
                filter = filter,
                intensity = values[17]
            )

            var grading = ColorGradingSettings.NEUTRAL
            if (size >= PREVIOUS_VALUE_COUNT) {
                var i = LEGACY_VALUE_COUNT
                val adjustmentsByColor = HslColor.entries.associateWith {
                    HslAdjustment(
                        hue = values[i++],
                        saturation = values[i++],
                        luminance = values[i++]
                    )
                }
                val hsl = HslSettings(
                    red = adjustmentsByColor.getValue(HslColor.RED),
                    orange = adjustmentsByColor.getValue(HslColor.ORANGE),
                    yellow = adjustmentsByColor.getValue(HslColor.YELLOW),
                    green = adjustmentsByColor.getValue(HslColor.GREEN),
                    cyan = adjustmentsByColor.getValue(HslColor.CYAN),
                    blue = adjustmentsByColor.getValue(HslColor.BLUE),
                    purple = adjustmentsByColor.getValue(HslColor.PURPLE),
                    magenta = adjustmentsByColor.getValue(HslColor.MAGENTA)
                )

                fun readGrade() = ToneGrade(
                    hue = values[i++],
                    amount = values[i++],
                    luminance = values[i++]
                )

                val shadows = readGrade()
                val midtones = readGrade()
                val highlights = readGrade()
                grading = ColorGradingSettings(
                    hsl = hsl,
                    shadows = shadows,
                    midtones = midtones,
                    highlights = highlights,
                    balance = values[i++],
                    blending = values[i]
                )
            }

            var effects = PhotoEffects.NEUTRAL
            var curves = CurvesSettings.NEUTRAL
            if (size >= VALUE_COUNT) {
                var i = PREVIOUS_VALUE_COUNT
                effects = PhotoEffects(
                    sharpness = values[i++].coerceIn(-100, 100),
                    texture = values[i++].coerceIn(-100, 100),
                    details = values[i++].coerceIn(-100, 100),
                    noiseReduction = values[i++].coerceIn(-100, 100),
                    vignette = values[i++].coerceIn(-100, 100),
                    dehaze = values[i++].coerceIn(-100, 100),
                    fade = values[i++].coerceIn(-100, 100),
                    grain = values[i++].coerceIn(0, 100)
                )

                fun readCurve(): CurveSettings {
                    val count = values[i++].coerceIn(0, MAX_CURVE_POINTS)
                    val points = ArrayList<CurvePoint>(count)
                    for (pointIndex in 0 until MAX_CURVE_POINTS) {
                        val x = values[i++]
                        val y = values[i++]
                        if (pointIndex < count) {
                            points.add(
                                CurvePoint(
                                    x = (x / BASIS_POINT_SCALE).coerceIn(0f, 1f),
                                    y = (y / BASIS_POINT_SCALE).coerceIn(0f, 1f)
                                )
                            )
                        }
                    }
                    return if (points.size >= 2) {
                        CurveSettings(points)
                    } else {
                        CurveSettings.NEUTRAL
                    }
                }

                curves = CurvesSettings(
                    rgb = readCurve(),
                    red = readCurve(),
                    green = readCurve(),
                    blue = readCurve()
                )
            }

            EditState(
                adjustments = adjustments,
                crop = crop,
                cropRatio = ratio,
                filter = filterSettings,
                effects = effects,
                colorGrading = grading,
                curves = curves
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun encodeStack(states: List<EditState>): IntArray {
        val values = IntArray(states.size * VALUE_COUNT)
        states.forEachIndexed { index, state ->
            encode(state).copyInto(values, index * VALUE_COUNT)
        }
        return values
    }

    fun decodeStack(values: IntArray?): List<EditState> {
        if (values == null || values.isEmpty()) return emptyList()

        val recordSize = when {
            values.size % VALUE_COUNT == 0 -> VALUE_COUNT
            values.size % PREVIOUS_VALUE_COUNT == 0 -> PREVIOUS_VALUE_COUNT
            values.size % LEGACY_VALUE_COUNT == 0 -> LEGACY_VALUE_COUNT
            else -> return emptyList()
        }

        val states = ArrayList<EditState>(values.size / recordSize)
        var offset = 0
        while (offset < values.size) {
            val state = decode(
                values.copyOfRange(offset, offset + recordSize)
            ) ?: return emptyList()
            states.add(state)
            offset += recordSize
        }
        return states
    }

    private fun toBasisPoints(value: Float): Int =
        (value * BASIS_POINT_SCALE).roundToInt().coerceIn(0, 10000)
}
