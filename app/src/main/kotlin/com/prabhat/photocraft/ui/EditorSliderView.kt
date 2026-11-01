package com.prabhat.photocraft.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.prabhat.photocraft.R
import java.util.Locale

@SuppressLint("ClickableViewAccessibility")
open class EditorSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    interface Listener {
        fun onSliderStarted(view: EditorSliderView)
        fun onSliderChanged(view: EditorSliderView, value: Int)
        fun onSliderStopped(view: EditorSliderView)
    }

    private val labelView: TextView
    private val valueView: TextView
    private val seekBar: SeekBar
    private var minimumValue = 0
    private var maximumValue = 100
    private var neutralValue: Int? = null
    private var titleText = ""
    private var formatter: ((Int) -> String)? = null
    private var listener: Listener? = null
    private var suppressCallback = false
    private var consumeTouchUntilUp = false

    private val resetDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (!seekBar.isEnabled) return false
                val neutral = neutralValue ?: return false
                consumeTouchUntilUp = true
                if (value == neutral) return true
                seekBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                listener?.onSliderStarted(this@EditorSliderView)
                setValue(neutral)
                listener?.onSliderChanged(this@EditorSliderView, neutral)
                listener?.onSliderStopped(this@EditorSliderView)
                return true
            }
        }
    )

    val value: Int
        get() = seekBar.progress + minimumValue

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_editor_slider, this, true)
        labelView = findViewById(R.id.editorSliderLabel)
        valueView = findViewById(R.id.editorSliderValue)
        seekBar = findViewById(R.id.editorSliderSeekBar)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (suppressCallback) return
                val current = progress + minimumValue
                updateValue(current, animate = fromUser)
                listener?.onSliderChanged(this@EditorSliderView, current)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                listener?.onSliderStarted(this@EditorSliderView)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                listener?.onSliderStopped(this@EditorSliderView)
            }
        })
        seekBar.setOnTouchListener { _, event ->
            resetDetector.onTouchEvent(event)
            if (consumeTouchUntilUp) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    consumeTouchUntilUp = false
                }
                true
            } else {
                false
            }
        }
    }

    fun configure(
        title: String,
        minimum: Int,
        maximum: Int,
        initialValue: Int,
        neutralValue: Int? = null,
        formatter: ((Int) -> String)? = null
    ) {
        require(maximum > minimum) { "Slider maximum must be greater than minimum." }
        titleText = title
        minimumValue = minimum
        maximumValue = maximum
        this.neutralValue = neutralValue?.coerceIn(minimum, maximum)
        this.formatter = formatter
        labelView.text = title
        suppressCallback = true
        seekBar.max = maximum - minimum
        suppressCallback = false
        setValue(initialValue)
    }

    fun setValue(value: Int) {
        val clamped = value.coerceIn(minimumValue, maximumValue)
        suppressCallback = true
        seekBar.progress = clamped - minimumValue
        suppressCallback = false
        updateValue(clamped, animate = false)
    }

    fun setSliderEnabled(enabled: Boolean) {
        seekBar.isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    fun setOnSliderListener(listener: Listener?) {
        this.listener = listener
    }

    private fun updateValue(value: Int, animate: Boolean) {
        val formatted = formatter?.invoke(value) ?: defaultFormat(value)
        if (valueView.text != formatted) {
            valueView.text = formatted
            if (animate) {
                valueView.animate().cancel()
                valueView.scaleX = 1.08f
                valueView.scaleY = 1.08f
                valueView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        seekBar.contentDescription = context.getString(
            R.string.adjustment_control_description,
            titleText,
            formatted
        )
    }

    private fun defaultFormat(value: Int): String = when {
        value == 0 -> "0"
        minimumValue < 0 -> String.format(Locale.US, "%+d", value)
        else -> value.toString()
    }
}
