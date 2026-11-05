package com.prabhat.photocraft.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.prabhat.photocraft.R
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class GradientSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSliderStarted(view: GradientSliderView)
        fun onSliderChanged(view: GradientSliderView, value: Int)
        fun onSliderStopped(view: GradientSliderView)
    }

    private val density = resources.displayMetrics.density
    private val labelView: TextView
    private val valueView: TextView
    private val trackView: TrackView

    private var minimumValue = 0
    private var maximumValue = 100
    private var neutralValue: Int? = null
    private var titleText = ""
    private var formatter: ((Int) -> String)? = null
    private var listener: Listener? = null
    private var currentValue = 0
    private var consumeTouchUntilUp = false
    private var compact = false
    private var centerMarkerVisible = false

    private val resetDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (!isEnabled) return false
                val neutral = neutralValue ?: return false
                consumeTouchUntilUp = true
                if (currentValue == neutral) return true
                trackView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                beginGesture()
                setValueInternal(neutral, animate = true)
                listener?.onSliderChanged(this@GradientSliderView, neutral)
                endGesture()
                return true
            }
        }
    )

    val value: Int
        get() = currentValue

    init {
        orientation = VERTICAL
        setPadding(0, dp(5), 0, dp(5))

        labelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.text_secondary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(context.getColor(R.color.accent))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(48)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setBackgroundResource(R.drawable.bg_value_pill)
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        header.addView(
            labelView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        trackView = TrackView(context)
        addView(
            trackView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(1) }
        )
        trackView.setOnTouchListener { _, event -> handleTouch(event) }
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
        setValue(initialValue)
    }

    fun setValue(value: Int) {
        setValueInternal(value, animate = false)
    }

    fun setSliderEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    fun setOnSliderListener(listener: Listener?) {
        this.listener = listener
    }

    fun setGradient(colors: IntArray, positions: FloatArray? = null) {
        trackView.setGradient(colors, positions)
    }

    fun setCompact(compact: Boolean) {
        if (this.compact == compact) return
        this.compact = compact
        val verticalPadding = dp(if (compact) 3 else 5)
        setPadding(0, verticalPadding, 0, verticalPadding)
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 10f else 11f)
        valueView.minWidth = dp(if (compact) 44 else 48)
        val trackHeightPx = dp(if (compact) 32 else 40)
        (trackView.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = trackHeightPx
            trackView.layoutParams = params
        }
        trackView.setCompact(compact)
        requestLayout()
    }

    fun setCenterMarkerVisible(visible: Boolean) {
        if (centerMarkerVisible == visible) return
        centerMarkerVisible = visible
        trackView.setCenterMarkerVisible(visible)
    }

    private fun setValueInternal(value: Int, animate: Boolean) {
        val clamped = value.coerceIn(minimumValue, maximumValue)
        currentValue = clamped
        trackView.invalidate()
        updateValueText(clamped, animate)
    }

    private fun updateValueText(value: Int, animate: Boolean) {
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
                    .setDuration(120L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        trackView.contentDescription = context.getString(
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

    private fun beginGesture() {
        if (!isEnabled) return
        listener?.onSliderStarted(this)
    }

    private fun endGesture() {
        if (!isEnabled) return
        listener?.onSliderStopped(this)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        resetDetector.onTouchEvent(event)
        if (consumeTouchUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                consumeTouchUntilUp = false
            }
            return true
        }
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackView.isPressed = true
                beginGesture()
                updateFromTouch(event.x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (trackView.isPressed) {
                    updateFromTouch(event.x)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (trackView.isPressed) {
                    updateFromTouch(event.x)
                    trackView.isPressed = false
                    endGesture()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (trackView.isPressed) {
                    trackView.isPressed = false
                    endGesture()
                }
            }
        }
        return false
    }

    private fun updateFromTouch(x: Float) {
        val fraction = trackView.fractionForX(x)
        val next = (
            minimumValue + fraction * (maximumValue - minimumValue)
            ).roundToInt().coerceIn(minimumValue, maximumValue)
        if (next != currentValue) {
            setValueInternal(next, animate = true)
            listener?.onSliderChanged(this, next)
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private inner class TrackView(context: Context) : View(context) {
        private var trackHeight = 6f * density
        private var thumbRadius = 9f * density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.text_primary)
        }
        private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = context.getColor(R.color.accent)
        }
        private val trackRect = RectF()
        private val centerMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = context.getColor(R.color.text_primary)
            alpha = 140
        }
        private var centerMarkerVisible = false

        private var colors = intArrayOf(
            context.getColor(R.color.slider_track),
            context.getColor(R.color.accent)
        )
        private var positions: FloatArray? = null
        private var shader: LinearGradient? = null
        private var startX = 0f
        private var endX = 1f

        fun setGradient(colors: IntArray, positions: FloatArray?) {
            require(colors.size >= 2) { "A gradient needs at least two colors." }
            require(positions == null || positions.size == colors.size) {
                "Gradient positions must match the color count."
            }
            this.colors = colors
            this.positions = positions
            shader = null
            invalidate()
        }

        fun fractionForX(x: Float): Float {
            val span = endX - startX
            if (span <= 0f) return 0f
            return ((x - startX) / span).coerceIn(0f, 1f)
        }

        fun setCompact(compact: Boolean) {
            trackHeight = (if (compact) 5f else 6f) * density
            thumbRadius = (if (compact) 8f else 9f) * density
            updateTrackBounds(width)
            shader = null
            invalidate()
        }

        fun setCenterMarkerVisible(visible: Boolean) {
            centerMarkerVisible = visible
            invalidate()
        }

        private fun updateTrackBounds(viewWidth: Int) {
            startX = thumbRadius
            endX = (viewWidth - thumbRadius).coerceAtLeast(startX + 1f)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateTrackBounds(w)
            shader = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerY = height / 2f
            trackPaint.shader = ensureShader()
            trackRect.set(
                startX,
                centerY - trackHeight / 2f,
                endX,
                centerY + trackHeight / 2f
            )
            canvas.drawRoundRect(
                trackRect,
                trackHeight / 2f,
                trackHeight / 2f,
                trackPaint
            )
            trackPaint.shader = null

            val span = (endX - startX).coerceAtLeast(1f)
            val fraction = if (maximumValue > minimumValue) {
                ((currentValue - minimumValue).toFloat() / (maximumValue - minimumValue))
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            if (centerMarkerVisible) {
                val neutral = (neutralValue ?: minimumValue).coerceIn(minimumValue, maximumValue)
                val neutralFraction = if (maximumValue > minimumValue) {
                    ((neutral - minimumValue).toFloat() / (maximumValue - minimumValue))
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                val markerX = startX + span * neutralFraction
                canvas.drawLine(
                    markerX,
                    centerY - 6f * density,
                    markerX,
                    centerY + 6f * density,
                    centerMarkerPaint
                )
            }

            val thumbX = startX + span * fraction
            canvas.drawCircle(thumbX, centerY, thumbRadius, thumbFill)
            canvas.drawCircle(thumbX, centerY, thumbRadius, thumbStroke)
        }

        private fun ensureShader(): LinearGradient {
            shader?.let { return it }
            val created = LinearGradient(
                startX,
                0f,
                endX,
                0f,
                colors,
                positions,
                Shader.TileMode.CLAMP
            )
            shader = created
            return created
        }
    }
}
