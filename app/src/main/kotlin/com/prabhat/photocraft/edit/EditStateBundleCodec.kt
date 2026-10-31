package com.prabhat.photocraft.edit

import android.os.Bundle

object EditStateBundleCodec {
    private const val VERSION = 1
    private const val MASK_MAX_EDGE = 192
    private const val HSL_VALUE_COUNT = 24
    private const val GRADE_VALUE_COUNT = 9
    private const val COLOR_GRADING_VALUE_COUNT =
        HSL_VALUE_COUNT + GRADE_VALUE_COUNT + 2

    fun encode(state: EditState): Bundle = Bundle().apply {
        putInt("version", VERSION)
        putIntArray("adjustments", state.adjustments.toIntArray())
        putFloat("cropLeft", state.crop.left)
        putFloat("cropTop", state.crop.top)
        putFloat("cropRight", state.crop.right)
        putFloat("cropBottom", state.crop.bottom)
        putInt("cropRatio", state.cropRatio.ordinal)
        putInt("filter", state.filter.filter.ordinal)
        putInt("filterIntensity", state.filter.intensity)
        putIntArray("effects", effectsToArray(state.effects))
        putIntArray("colorGrading", colorGradingToArray(state.colorGrading))
        encodeCurves(this, state.curves)
        encodeHealing(this, state.healing)
        encodeMasking(this, state.masking)
    }

    fun decode(bundle: Bundle?): EditState? {
        if (bundle == null || bundle.getInt("version", 0) != VERSION) {
            return null
        }
        return try {
            val adjustments = Adjustments.fromIntArray(
                bundle.getIntArray("adjustments") ?: return null
            )
            val crop = CropSelection(
                left = bundle.getFloat("cropLeft"),
                top = bundle.getFloat("cropTop"),
                right = bundle.getFloat("cropRight"),
                bottom = bundle.getFloat("cropBottom")
            )
            val cropRatio = CropRatio.entries.getOrNull(bundle.getInt("cropRatio"))
                ?: return null
            val filter = Filter.entries.getOrNull(bundle.getInt("filter"))
                ?: return null
            val filterSettings = FilterSettings(
                filter = filter,
                intensity = bundle.getInt("filterIntensity")
            )
            val effects = effectsFromArray(bundle.getIntArray("effects"))
                ?: PhotoEffects.NEUTRAL
            val colorGrading = colorGradingFromArray(
                bundle.getIntArray("colorGrading")
            ) ?: ColorGradingSettings.NEUTRAL
            val curves = decodeCurves(bundle)
            val healing = decodeHealing(bundle)
            val masking = decodeMasking(bundle)
            EditState(
                adjustments = adjustments,
                crop = crop,
                cropRatio = cropRatio,
                filter = filterSettings,
                effects = effects,
                colorGrading = colorGrading,
                curves = curves,
                healing = healing,
                masking = masking
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private fun effectsToArray(effects: PhotoEffects): IntArray = intArrayOf(
        effects.sharpness,
        effects.texture,
        effects.details,
        effects.noiseReduction,
        effects.vignette,
        effects.dehaze,
        effects.fade,
        effects.grain
    )

    private fun effectsFromArray(values: IntArray?): PhotoEffects? {
        if (values == null || values.size != 8) return null
        return PhotoEffects(
            sharpness = values[0].coerceIn(-100, 100),
            texture = values[1].coerceIn(-100, 100),
            details = values[2].coerceIn(-100, 100),
            noiseReduction = values[3].coerceIn(-100, 100),
            vignette = values[4].coerceIn(-100, 100),
            dehaze = values[5].coerceIn(-100, 100),
            fade = values[6].coerceIn(-100, 100),
            grain = values[7].coerceIn(0, 100)
        )
    }

    private fun colorGradingToArray(settings: ColorGradingSettings): IntArray {
        val values = IntArray(COLOR_GRADING_VALUE_COUNT)
        var i = 0
        for (color in HslColor.entries) {
            val adjustment = settings.hsl.adjustment(color)
            values[i++] = adjustment.hue
            values[i++] = adjustment.saturation
            values[i++] = adjustment.luminance
        }
        for (grade in listOf(
            settings.shadows,
            settings.midtones,
            settings.highlights
        )) {
            values[i++] = grade.hue
            values[i++] = grade.amount
            values[i++] = grade.luminance
        }
        values[i++] = settings.balance
        values[i] = settings.blending
        return values
    }

    private fun colorGradingFromArray(values: IntArray?): ColorGradingSettings? {
        if (values == null || values.size != COLOR_GRADING_VALUE_COUNT) {
            return null
        }
        var i = 0
        val byColor = HslColor.entries.associateWith {
            HslAdjustment(
                hue = values[i++],
                saturation = values[i++],
                luminance = values[i++]
            )
        }
        fun readGrade() = ToneGrade(
            hue = values[i++],
            amount = values[i++],
            luminance = values[i++]
        )
        val shadows = readGrade()
        val midtones = readGrade()
        val highlights = readGrade()
        return ColorGradingSettings(
            hsl = HslSettings(
                red = byColor.getValue(HslColor.RED),
                orange = byColor.getValue(HslColor.ORANGE),
                yellow = byColor.getValue(HslColor.YELLOW),
                green = byColor.getValue(HslColor.GREEN),
                cyan = byColor.getValue(HslColor.CYAN),
                blue = byColor.getValue(HslColor.BLUE),
                purple = byColor.getValue(HslColor.PURPLE),
                magenta = byColor.getValue(HslColor.MAGENTA)
            ),
            shadows = shadows,
            midtones = midtones,
            highlights = highlights,
            balance = values[i++],
            blending = values[i]
        )
    }

    private fun encodeCurves(bundle: Bundle, curves: CurvesSettings) {
        val channels = listOf(curves.rgb, curves.red, curves.green, curves.blue)
        for ((index, curve) in channels.withIndex()) {
            val points = curve.points.sortedBy { it.x }
            val values = FloatArray(points.size * 2)
            for ((pointIndex, point) in points.withIndex()) {
                values[pointIndex * 2] = point.x
                values[pointIndex * 2 + 1] = point.y
            }
            bundle.putInt("curveCount$index", points.size)
            bundle.putFloatArray("curvePoints$index", values)
        }
    }

    private fun decodeCurves(bundle: Bundle): CurvesSettings {
        fun readCurve(index: Int): CurveSettings {
            val count = bundle.getInt("curveCount$index", 0)
                .coerceIn(0, 64)
            val values = bundle.getFloatArray("curvePoints$index")
                ?: return CurveSettings.NEUTRAL
            if (count < 2 || values.size < count * 2) {
                return CurveSettings.NEUTRAL
            }
            val points = ArrayList<CurvePoint>(count)
            for (pointIndex in 0 until count) {
                points.add(
                    CurvePoint(
                        x = values[pointIndex * 2].coerceIn(0f, 1f),
                        y = values[pointIndex * 2 + 1].coerceIn(0f, 1f)
                    )
                )
            }
            return CurveSettings(points)
        }
        return CurvesSettings(
            rgb = readCurve(0),
            red = readCurve(1),
            green = readCurve(2),
            blue = readCurve(3)
        )
    }

    private fun encodeHealing(bundle: Bundle, healing: HealingSettings) {
        val values = FloatArray(healing.spots.size * 3)
        for ((index, spot) in healing.spots.withIndex()) {
            values[index * 3] = spot.x
            values[index * 3 + 1] = spot.y
            values[index * 3 + 2] = spot.radius
        }
        bundle.putFloatArray("healingSpots", values)
    }

    private fun decodeHealing(bundle: Bundle): HealingSettings {
        val values = bundle.getFloatArray("healingSpots")
            ?: return HealingSettings.NEUTRAL
        if (values.size % 3 != 0) return HealingSettings.NEUTRAL
        val spots = ArrayList<HealingSpot>(values.size / 3)
        var i = 0
        while (i + 2 < values.size) {
            spots.add(
                HealingSpot(
                    x = values[i++].coerceIn(0f, 1f),
                    y = values[i++].coerceIn(0f, 1f),
                    radius = values[i++].coerceIn(0.005f, 0.5f)
                )
            )
        }
        return HealingSettings(spots)
    }

    private fun encodeMasking(bundle: Bundle, masking: MaskingState) {
        bundle.putInt("maskSelected", masking.selectedMask.ordinal)
        bundle.putFloat("maskBrushSize", masking.brushSize)
        bundle.putFloat("maskBrushFeather", masking.brushFeather)
        bundle.putFloat("maskBrushFlow", masking.brushFlow)
        bundle.putInt("maskLayerCount", masking.layers.size)
        for ((index, layer) in masking.layers.withIndex()) {
            val layerBundle = Bundle()
            layerBundle.putInt("type", layer.type.ordinal)
            layerBundle.putBoolean("enabled", layer.enabled)
            layerBundle.putBoolean("inverted", layer.inverted)
            encodeLocalAdjustments(layerBundle, layer.adjustments)
            val selection = layer.selection
            if (selection != null) {
                val reduced = downsample(selection, MASK_MAX_EDGE)
                layerBundle.putInt("maskWidth", reduced.width)
                layerBundle.putInt("maskHeight", reduced.height)
                layerBundle.putByteArray("maskCoverage", reduced.coverage)
            }
            bundle.putBundle("maskLayer$index", layerBundle)
        }
    }

    private fun decodeMasking(bundle: Bundle): MaskingState {
        val layerCount = bundle.getInt("maskLayerCount", 0)
            .coerceIn(0, MaskType.entries.size)
        if (layerCount <= 0) {
            return MaskingState()
        }
        val layers = ArrayList<MaskLayer>(layerCount)
        for (index in 0 until layerCount) {
            val layerBundle = bundle.getBundle("maskLayer$index")
                ?: continue
            val type = MaskType.entries.getOrNull(layerBundle.getInt("type"))
                ?: continue
            val width = layerBundle.getInt("maskWidth", 0)
            val height = layerBundle.getInt("maskHeight", 0)
            val coverage = layerBundle.getByteArray("maskCoverage")
            val selection = if (
                width > 0 && height > 0 &&
                coverage != null && coverage.size == width * height
            ) {
                MaskSelection(width, height, coverage)
            } else {
                null
            }
            layers.add(
                MaskLayer(
                    type = type,
                    selection = selection,
                    enabled = layerBundle.getBoolean("enabled", true),
                    inverted = layerBundle.getBoolean("inverted", false),
                    adjustments = decodeLocalAdjustments(layerBundle)
                )
            )
        }
        if (layers.isEmpty()) {
            return MaskingState()
        }
        return MaskingState(
            layers = layers,
            selectedMask = MaskType.entries.getOrNull(
                bundle.getInt("maskSelected")
            ) ?: MaskType.SUBJECT,
            brushSize = bundle.getFloat("maskBrushSize", 40f),
            brushFeather = bundle.getFloat("maskBrushFeather", 50f),
            brushFlow = bundle.getFloat("maskBrushFlow", 100f)
        )
    }

    private fun encodeLocalAdjustments(
        bundle: Bundle,
        local: LocalMaskAdjustments
    ) {
        bundle.putIntArray("localAdjustments", local.adjustments.toIntArray())
        bundle.putInt("localFilter", local.filter.filter.ordinal)
        bundle.putInt("localFilterIntensity", local.filter.intensity)
        bundle.putIntArray("localEffects", effectsToArray(local.effects))
        bundle.putIntArray(
            "localColorGrading",
            colorGradingToArray(local.colorGrading)
        )
        encodeCurves(bundle, local.curves)
        encodeHealing(bundle, local.healing)
    }

    private fun decodeLocalAdjustments(bundle: Bundle): LocalMaskAdjustments {
        val adjustments = try {
            bundle.getIntArray("localAdjustments")?.let {
                Adjustments.fromIntArray(it)
            }
        } catch (_: IllegalArgumentException) {
            null
        } ?: Adjustments.NEUTRAL
        val filter = Filter.entries.getOrNull(bundle.getInt("localFilter"))
            ?: Filter.NATURAL
        return LocalMaskAdjustments(
            adjustments = adjustments,
            filter = FilterSettings(
                filter = filter,
                intensity = bundle.getInt("localFilterIntensity", 100)
            ),
            effects = effectsFromArray(bundle.getIntArray("localEffects"))
                ?: PhotoEffects.NEUTRAL,
            colorGrading = colorGradingFromArray(
                bundle.getIntArray("localColorGrading")
            ) ?: ColorGradingSettings.NEUTRAL,
            curves = decodeCurves(bundle),
            healing = decodeHealing(bundle)
        )
    }

    private fun downsample(selection: MaskSelection, maxEdge: Int): MaskSelection {
        val longest = maxOf(selection.width, selection.height)
        if (longest <= maxEdge) {
            return selection
        }
        val scale = maxEdge.toFloat() / longest
        val width = (selection.width * scale).toInt().coerceAtLeast(1)
        val height = (selection.height * scale).toInt().coerceAtLeast(1)
        val coverage = ByteArray(width * height)
        for (y in 0 until height) {
            val sourceY = (y.toFloat() / height * selection.height).toInt()
                .coerceIn(0, selection.height - 1)
            for (x in 0 until width) {
                val sourceX = (x.toFloat() / width * selection.width).toInt()
                    .coerceIn(0, selection.width - 1)
                coverage[y * width + x] = selection.coverageAt(sourceX, sourceY).toByte()
            }
        }
        return MaskSelection(width, height, coverage)
    }
}
