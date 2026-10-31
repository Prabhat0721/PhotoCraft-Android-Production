package com.prabhat.photocraft.edit

class EditingState(initial: EditState = EditState()) {
    var state: EditState = initial
        private set

    var revision: Int = 0
        private set

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var currentLabel: String = INITIAL_LABEL
    private var gestureBase: HistoryEntry? = null

    val adjustments: Adjustments
        get() = state.adjustments

    val crop: CropSelection
        get() = state.crop

    val cropRatio: CropRatio
        get() = state.cropRatio

    val filter: FilterSettings
        get() = state.filter

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    val isModified: Boolean
        get() = !state.isNeutral()

    fun beginGesture() {
        if (gestureBase == null) {
            gestureBase = HistoryEntry(state, currentLabel)
        }
    }

    fun update(
        adjustment: Adjustment,
        value: Int,
        label: String = adjustment.name
    ): Boolean {
        val nextAdjustments = state.adjustments.with(adjustment, value)
        if (nextAdjustments == state.adjustments) {
            return false
        }
        return commit(state.copy(adjustments = nextAdjustments), label)
    }

    fun updateCrop(
        crop: CropSelection,
        ratio: CropRatio,
        label: String = DEFAULT_CROP_LABEL
    ): Boolean {
        val next = state.copy(crop = crop, cropRatio = ratio)
        if (next == state) {
            return false
        }
        return commit(next, label)
    }

    fun updateFilter(
        filter: FilterSettings,
        label: String = DEFAULT_FILTER_LABEL
    ): Boolean {
        val next = state.copy(filter = filter)
        if (next == state) {
            return false
        }
        return commit(next, label)
    }

    fun updateFilter(
        filter: Filter,
        intensity: Int,
        label: String = DEFAULT_FILTER_LABEL
    ): Boolean = updateFilter(FilterSettings(filter, intensity), label)

    fun updateEffects(
        effects: PhotoEffects,
        label: String = DEFAULT_EFFECTS_LABEL
    ): Boolean {
        val next = state.copy(effects = effects)
        if (next == state) {
            return false
        }
        return commit(next, label)
    }

    fun updateColorGrading(
        settings: ColorGradingSettings,
        label: String = DEFAULT_COLOR_GRADING_LABEL
    ): Boolean {
        val next = state.copy(colorGrading = settings)
        if (next == state) return false
        return commit(next, label)
    }

    fun updateCurves(
        curves: CurvesSettings,
        label: String = "Curves"
    ): Boolean {
        val next = state.copy(curves = curves)
        if (next == state) return false
        return commit(next, label)
    }

    fun updateHealing(
        healing: HealingSettings,
        label: String = "Healing"
    ): Boolean {
        val next = state.copy(healing = healing)
        if (next == state) return false
        return commit(next, label)
    }

    fun updateMasking(
        masking: MaskingState,
        label: String = "Masking"
    ): Boolean {
        val next = state.copy(masking = masking)
        if (next == state) return false
        return commit(next, label)
    }

    fun applyPreset(next: EditState, label: String): Boolean {
        if (next == state) {
            return false
        }
        return commit(next, label)
    }

    fun endGesture() {
        val base = gestureBase ?: return
        if (state != base.state) {
            pushUndo(base)
            redoStack.clear()
        } else {
            currentLabel = base.label
        }
        gestureBase = null
        revision += 1
    }

    fun cancelGesture() {
        val base = gestureBase ?: return
        state = base.state
        currentLabel = base.label
        gestureBase = null
        revision += 1
    }

    fun undo(): Boolean {
        if (gestureBase != null) {
            endGesture()
        }
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(HistoryEntry(state, currentLabel))
        state = previous.state
        currentLabel = previous.label
        revision += 1
        return true
    }

    fun redo(): Boolean {
        if (gestureBase != null) {
            endGesture()
        }
        val next = redoStack.removeLastOrNull() ?: return false
        pushUndo(HistoryEntry(state, currentLabel))
        state = next.state
        currentLabel = next.label
        revision += 1
        return true
    }

    fun reset(label: String = DEFAULT_RESET_LABEL): Boolean {
        if (gestureBase != null) {
            endGesture()
        }
        if (state.isNeutral()) {
            return false
        }
        pushUndo(HistoryEntry(state, currentLabel))
        state = EditState()
        currentLabel = label
        redoStack.clear()
        revision += 1
        return true
    }

    fun restoreTo(stepsBack: Int): Boolean {
        if (stepsBack < 0 || stepsBack > undoStack.size) {
            return false
        }
        repeat(stepsBack) {
            if (!undo()) {
                return false
            }
        }
        return true
    }

    fun history(limit: Int = DEFAULT_HISTORY_VISIBLE): List<HistoryPoint> {
        if (limit <= 0) {
            return emptyList()
        }
        val points = ArrayList<HistoryPoint>(limit)
        points.add(HistoryPoint(label = currentLabel, stepsBack = 0, isCurrent = true))
        var steps = 1
        for (entry in undoStack.reversed()) {
            if (points.size >= limit) {
                break
            }
            points.add(HistoryPoint(label = entry.label, stepsBack = steps, isCurrent = false))
            steps += 1
        }
        return points
    }

    fun snapshot(): Snapshot = Snapshot(
        state = state,
        undo = undoStack.map { it.state },
        redo = redoStack.map { it.state },
        undoLabels = undoStack.map { it.label },
        redoLabels = redoStack.map { it.label },
        currentLabel = currentLabel
    )

    private fun commit(next: EditState, label: String): Boolean {
        val outsideGesture = gestureBase == null
        if (outsideGesture) {
            pushUndo(HistoryEntry(state, currentLabel))
            redoStack.clear()
        }
        state = next
        currentLabel = label
        revision += 1
        return true
    }

    private fun pushUndo(value: HistoryEntry) {
        undoStack.addLast(value)
        while (undoStack.size > HISTORY_LIMIT) {
            undoStack.removeFirst()
        }
    }

    data class HistoryPoint(
        val label: String,
        val stepsBack: Int,
        val isCurrent: Boolean
    )

    private data class HistoryEntry(
        val state: EditState,
        val label: String
    )

    data class Snapshot(
        val state: EditState,
        val undo: List<EditState>,
        val redo: List<EditState>,
        val undoLabels: List<String> = emptyList(),
        val redoLabels: List<String> = emptyList(),
        val currentLabel: String = ""
    ) {
        val adjustments: Adjustments
            get() = state.adjustments

        val crop: CropSelection
            get() = state.crop

        val cropRatio: CropRatio
            get() = state.cropRatio

        val filter: FilterSettings
            get() = state.filter
    }

    companion object {
        const val HISTORY_LIMIT = 100
        const val DEFAULT_HISTORY_VISIBLE = 8
        const val INITIAL_LABEL = ""
        const val DEFAULT_CROP_LABEL = "Crop"
        const val DEFAULT_FILTER_LABEL = "Filter"
        const val DEFAULT_EFFECTS_LABEL = "Effects"
        const val DEFAULT_COLOR_GRADING_LABEL = "Color Grading"
        const val DEFAULT_RESET_LABEL = "Reset"

        fun restored(snapshot: Snapshot): EditingState {
            val state = EditingState(snapshot.state)
            state.currentLabel = snapshot.currentLabel
            val undoStart = (snapshot.undo.size - HISTORY_LIMIT).coerceAtLeast(0)
            val undoStates = snapshot.undo.drop(undoStart)
            val undoLabels = snapshot.undoLabels.drop(undoStart)
            undoStates.forEachIndexed { index, undoState ->
                state.undoStack.addLast(
                    HistoryEntry(
                        state = undoState,
                        label = undoLabels.getOrNull(index) ?: DEFAULT_EDIT_LABEL
                    )
                )
            }
            val redoStart = (snapshot.redo.size - HISTORY_LIMIT).coerceAtLeast(0)
            val redoStates = snapshot.redo.drop(redoStart)
            val redoLabels = snapshot.redoLabels.drop(redoStart)
            redoStates.forEachIndexed { index, redoState ->
                state.redoStack.addLast(
                    HistoryEntry(
                        state = redoState,
                        label = redoLabels.getOrNull(index) ?: DEFAULT_EDIT_LABEL
                    )
                )
            }
            return state
        }

        private const val DEFAULT_EDIT_LABEL = "Edit"
    }
}
