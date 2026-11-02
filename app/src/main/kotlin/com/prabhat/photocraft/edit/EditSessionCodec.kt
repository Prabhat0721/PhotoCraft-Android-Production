package com.prabhat.photocraft.edit

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object EditSessionCodec {
    const val VERSION = 2
    const val MASK_MAX_EDGE = 192
    const val MAX_METADATA_BYTES = 2_000_000

    fun encode(snapshot: EditingState.Snapshot): String? =
        encode(snapshot, MAX_METADATA_BYTES)

    fun encodeFull(snapshot: EditingState.Snapshot): String? =
        encode(snapshot, Int.MAX_VALUE)

    private fun encode(
        snapshot: EditingState.Snapshot,
        maxBytes: Int
    ): String? {
        return try {
            val root = JSONObject()
            root.put("v", VERSION)
            root.put("current", currentToJson(snapshot.state))
            root.put("label", snapshot.currentLabel)

            var undoStates = snapshot.undo
            var undoLabels = snapshot.undoLabels
            while (true) {
                root.put("undo", historyToJson(undoStates, undoLabels))
                root.put("redo", historyToJson(snapshot.redo, snapshot.redoLabels))
                val compressed = gzip(root.toString().toByteArray(Charsets.UTF_8))
                if (compressed.size <= maxBytes || undoStates.isEmpty()) {
                    return Base64.encodeToString(compressed, Base64.NO_WRAP)
                }
                undoStates = undoStates.drop(1)
                undoLabels = undoLabels.drop(1)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Exception) {
            null
        }
    }

    fun decode(payload: String?): EditingState.Snapshot? {
        if (payload.isNullOrBlank()) return null
        return try {
            val compressed = Base64.decode(payload, Base64.NO_WRAP)
            val json = GZIPInputStream(ByteArrayInputStream(compressed)).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val root = JSONObject(json)
            if (root.optInt("v", 0) !in 1..VERSION) return null
            val current = currentFromJson(root.optJSONObject("current") ?: return null)
                ?: return null
            val undo = historyFromJson(root.optJSONArray("undo"))
            val redo = historyFromJson(root.optJSONArray("redo"))
            EditingState.Snapshot(
                state = current,
                undo = undo.first,
                redo = redo.first,
                undoLabels = undo.second,
                redoLabels = redo.second,
                currentLabel = root.optString("label", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun currentToJson(state: EditState): JSONObject = JSONObject().apply {
        put("m5", ints(M5StateCodec.encode(state)))
        put("healing", healingToJson(state.healing))
        put("masking", maskingToJson(state.masking))
    }

    private fun currentFromJson(json: JSONObject): EditState? {
        val base = M5StateCodec.decode(ints(json.optJSONArray("m5"))) ?: return null
        return base.copy(
            healing = healingFromJson(json.optJSONArray("healing")),
            masking = maskingFromJson(json.optJSONObject("masking"))
        )
    }

    private fun historyToJson(
        states: List<EditState>,
        labels: List<String>
    ): JSONArray = JSONArray().apply {
        states.forEachIndexed { index, state ->
            put(JSONObject().apply {
                put("m5", ints(M5StateCodec.encode(state)))
                put("healing", healingToJson(state.healing))
                put("masking", maskingToJson(state.masking))
                put("label", labels.getOrNull(index).orEmpty())
            })
        }
    }

    private fun historyFromJson(json: JSONArray?): Pair<List<EditState>, List<String>> {
        if (json == null) return emptyList<EditState>() to emptyList()
        val states = ArrayList<EditState>(json.length())
        val labels = ArrayList<String>(json.length())
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val base = M5StateCodec.decode(ints(item.optJSONArray("m5"))) ?: continue
            states.add(
                base.copy(
                    healing = healingFromJson(item.optJSONArray("healing")),
                    masking = maskingFromJson(item.optJSONObject("masking"))
                )
            )
            labels.add(item.optString("label", ""))
        }
        return states to labels
    }

    private fun healingToJson(healing: HealingSettings): JSONArray = JSONArray().apply {
        for (spot in healing.spots) {
            put(spot.x.toDouble())
            put(spot.y.toDouble())
            put(spot.radius.toDouble())
        }
    }

    private fun healingFromJson(json: JSONArray?): HealingSettings {
        if (json == null || json.length() % 3 != 0) return HealingSettings.NEUTRAL
        val spots = ArrayList<HealingSpot>(json.length() / 3)
        var index = 0
        while (index + 2 < json.length()) {
            spots.add(
                HealingSpot(
                    x = json.optDouble(index++, 0.0).toFloat().coerceIn(0f, 1f),
                    y = json.optDouble(index++, 0.0).toFloat().coerceIn(0f, 1f),
                    radius = json.optDouble(index++, 0.05).toFloat()
                        .coerceIn(0.005f, 0.5f)
                )
            )
        }
        return HealingSettings(spots)
    }

    private fun maskingToJson(masking: MaskingState): JSONObject = JSONObject().apply {
        put("selected", masking.selectedMask.ordinal)
        put("brushSize", masking.brushSize.toDouble())
        put("brushFeather", masking.brushFeather.toDouble())
        put("brushFlow", masking.brushFlow.toDouble())
        put("colorTarget", masking.colorTarget ?: -1)
        put("colorTolerance", masking.colorTolerance.toDouble())
        put("colorFeather", masking.colorFeather.toDouble())
        put("luminanceTarget", masking.luminanceTarget?.toDouble() ?: -1.0)
        put("luminanceTolerance", masking.luminanceTolerance.toDouble())
        put("luminanceFeather", masking.luminanceFeather.toDouble())
        put("layers", JSONArray().apply {
            for (layer in masking.layers) {
                put(layerToJson(layer))
            }
        })
    }

    private fun maskingFromJson(json: JSONObject?): MaskingState {
        if (json == null) return MaskingState()
        val layersJson = json.optJSONArray("layers") ?: return MaskingState()
        val layers = ArrayList<MaskLayer>(layersJson.length())
        for (index in 0 until layersJson.length()) {
            val layer = layerFromJson(layersJson.optJSONObject(index)) ?: continue
            layers.add(layer)
        }
        if (layers.isEmpty()) return MaskingState()
        return MaskingState(
            layers = layers,
            selectedMask = MaskType.entries.getOrNull(
                json.optInt("selected", MaskType.SUBJECT.ordinal)
            ) ?: MaskType.SUBJECT,
            brushSize = json.optDouble("brushSize", 40.0).toFloat().coerceIn(1f, 200f),
            brushFeather = json.optDouble("brushFeather", 50.0).toFloat()
                .coerceIn(0f, 100f),
            brushFlow = json.optDouble("brushFlow", 100.0).toFloat().coerceIn(0f, 100f),
            colorTarget = json.optInt("colorTarget", -1).takeIf { it >= 0 },
            colorTolerance = json.optDouble("colorTolerance", 0.22).toFloat()
                .coerceIn(0.02f, 1f),
            colorFeather = json.optDouble("colorFeather", 0.35).toFloat()
                .coerceIn(0.01f, 1f),
            luminanceTarget = json.optDouble("luminanceTarget", -1.0).toFloat()
                .takeIf { it >= 0f },
            luminanceTolerance = json.optDouble("luminanceTolerance", 0.22).toFloat()
                .coerceIn(0.02f, 1f),
            luminanceFeather = json.optDouble("luminanceFeather", 0.35).toFloat()
                .coerceIn(0.01f, 1f)
        )
    }

    private fun layerToJson(layer: MaskLayer): JSONObject = JSONObject().apply {
        put("type", layer.type.ordinal)
        put("enabled", layer.enabled)
        put("inverted", layer.inverted)
        put("local", localToJson(layer.adjustments))
        val selection = layer.selection
        if (selection != null) {
            val reduced = downsample(selection, MASK_MAX_EDGE)
            put("mask", JSONObject().apply {
                put("w", reduced.width)
                put("h", reduced.height)
                put("data", Base64.encodeToString(reduced.coverage, Base64.NO_WRAP))
            })
        }
    }

    private fun layerFromJson(json: JSONObject?): MaskLayer? {
        if (json == null) return null
        val type = MaskType.entries.getOrNull(json.optInt("type", -1)) ?: return null
        val maskJson = json.optJSONObject("mask")
        val selection = if (maskJson != null) {
            val width = maskJson.optInt("w", 0)
            val height = maskJson.optInt("h", 0)
            val coverage = try {
                Base64.decode(maskJson.optString("data", ""), Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (width > 0 && height > 0 && coverage != null &&
                coverage.size == width * height
            ) {
                MaskSelection(width, height, coverage)
            } else {
                null
            }
        } else {
            null
        }
        return MaskLayer(
            type = type,
            selection = selection,
            enabled = json.optBoolean("enabled", true),
            inverted = json.optBoolean("inverted", false),
            adjustments = localFromJson(json.optJSONObject("local"))
        )
    }

    private fun localToJson(local: LocalMaskAdjustments): JSONObject = JSONObject().apply {
        put("adj", ints(local.adjustments.toIntArray()))
        put("filter", local.filter.filter.ordinal)
        put("filterIntensity", local.filter.intensity)
        put("effects", ints(effectsToArray(local.effects)))
        put("grading", ints(colorGradingToArray(local.colorGrading)))
        put("curves", curvesToJson(local.curves))
        put("healing", healingToJson(local.healing))
    }

    private fun localFromJson(json: JSONObject?): LocalMaskAdjustments {
        if (json == null) return LocalMaskAdjustments()
        val adjustments = try {
            ints(json.optJSONArray("adj"))?.let(Adjustments::fromIntArray)
        } catch (_: IllegalArgumentException) {
            null
        } ?: Adjustments.NEUTRAL
        val filter = Filter.entries.getOrNull(json.optInt("filter", 0))
            ?: Filter.NATURAL
        return LocalMaskAdjustments(
            adjustments = adjustments,
            filter = FilterSettings(
                filter = filter,
                intensity = json.optInt("filterIntensity", 100).coerceIn(0, 100)
            ),
            effects = effectsFromArray(ints(json.optJSONArray("effects"))),
            colorGrading = colorGradingFromArray(ints(json.optJSONArray("grading"))),
            curves = curvesFromJson(json.optJSONArray("curves")),
            healing = healingFromJson(json.optJSONArray("healing"))
        )
    }

    private fun curvesToJson(curves: CurvesSettings): JSONArray = JSONArray().apply {
        for (curve in listOf(curves.rgb, curves.red, curves.green, curves.blue)) {
            put(JSONArray().apply {
                for (point in curve.points.sortedBy { it.x }) {
                    put(point.x.toDouble())
                    put(point.y.toDouble())
                }
            })
        }
    }

    private fun curvesFromJson(json: JSONArray?): CurvesSettings {
        if (json == null) return CurvesSettings.NEUTRAL

        fun readCurve(index: Int): CurveSettings {
            val values = json.optJSONArray(index) ?: return CurveSettings.NEUTRAL
            if (values.length() < 4 || values.length() % 2 != 0) {
                return CurveSettings.NEUTRAL
            }
            val points = ArrayList<CurvePoint>(values.length() / 2)
            var i = 0
            while (i + 1 < values.length()) {
                points.add(
                    CurvePoint(
                        x = values.optDouble(i++, 0.0).toFloat().coerceIn(0f, 1f),
                        y = values.optDouble(i++, 0.0).toFloat().coerceIn(0f, 1f)
                    )
                )
            }
            return if (points.size >= 2) CurveSettings(points) else CurveSettings.NEUTRAL
        }

        return CurvesSettings(
            rgb = readCurve(0),
            red = readCurve(1),
            green = readCurve(2),
            blue = readCurve(3)
        )
    }

    private fun effectsToArray(effects: PhotoEffects): IntArray = intArrayOf(
        effects.sharpness, effects.texture, effects.details, effects.noiseReduction,
        effects.vignette, effects.dehaze, effects.fade, effects.grain
    )

    private fun effectsFromArray(values: IntArray?): PhotoEffects {
        if (values == null || values.size != 8) return PhotoEffects.NEUTRAL
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
        val values = IntArray(35)
        var i = 0
        for (color in HslColor.entries) {
            val adjustment = settings.hsl.adjustment(color)
            values[i++] = adjustment.hue
            values[i++] = adjustment.saturation
            values[i++] = adjustment.luminance
        }
        for (grade in listOf(settings.shadows, settings.midtones, settings.highlights)) {
            values[i++] = grade.hue
            values[i++] = grade.amount
            values[i++] = grade.luminance
        }
        values[i++] = settings.balance
        values[i] = settings.blending
        return values
    }

    private fun colorGradingFromArray(values: IntArray?): ColorGradingSettings {
        if (values == null || values.size != 35) return ColorGradingSettings.NEUTRAL
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

    private fun ints(values: IntArray): JSONArray = JSONArray().apply {
        for (value in values) put(value)
    }

    private fun ints(json: JSONArray?): IntArray? {
        if (json == null) return null
        val values = IntArray(json.length())
        for (index in 0 until json.length()) {
            values[index] = json.optInt(index, 0)
        }
        return values
    }

    private fun downsample(selection: MaskSelection, maxEdge: Int): MaskSelection {
        val longest = maxOf(selection.width, selection.height)
        if (longest <= maxEdge) return selection
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

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
