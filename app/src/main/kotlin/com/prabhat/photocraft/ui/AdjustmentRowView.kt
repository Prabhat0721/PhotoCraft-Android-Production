package com.prabhat.photocraft.ui

import android.content.Context
import android.util.AttributeSet
import com.prabhat.photocraft.edit.Adjustment
import java.util.Locale

class AdjustmentRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : EditorSliderView(context, attrs, defStyleAttr) {
    interface Listener {
        fun onAdjustmentStarted(row: AdjustmentRowView)
        fun onAdjustmentChanged(row: AdjustmentRowView, value: Int)
        fun onAdjustmentStopped(row: AdjustmentRowView)
    }

    private var boundAdjustment: Adjustment? = null
    private var rowListener: Listener? = null
    private val bridge = object : EditorSliderView.Listener {
        override fun onSliderStarted(view: EditorSliderView) {
            rowListener?.onAdjustmentStarted(this@AdjustmentRowView)
        }

        override fun onSliderChanged(view: EditorSliderView, value: Int) {
            rowListener?.onAdjustmentChanged(this@AdjustmentRowView, value)
        }

        override fun onSliderStopped(view: EditorSliderView) {
            rowListener?.onAdjustmentStopped(this@AdjustmentRowView)
        }
    }

    val adjustment: Adjustment?
        get() = boundAdjustment

    fun bind(adjustment: Adjustment, label: String, value: Int, listener: Listener) {
        boundAdjustment = adjustment
        rowListener = listener
        configure(
            title = label,
            minimum = Adjustment.MIN_VALUE,
            maximum = Adjustment.MAX_VALUE,
            initialValue = value,
            neutralValue = 0,
            formatter = { formatValue(adjustment, it) }
        )
        setOnSliderListener(bridge)
    }

    fun setAdjustmentEnabled(enabled: Boolean) {
        setSliderEnabled(enabled)
    }

    companion object {
        fun formatValue(adjustment: Adjustment, value: Int): String = when (adjustment) {
            Adjustment.EXPOSURE -> String.format(Locale.US, "%+.2f EV", value / 50.0)
            else -> String.format(Locale.US, "%+d", value)
        }
    }
}
