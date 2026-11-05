package com.prabhat.photocraft.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.prabhat.photocraft.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onWheelStarted(view: ColorWheelView)
        fun onWheelChanged(view: ColorWheelView, hue: Int, saturation: Int)
        fun onWheelStopped(view: ColorWheelView)
    }

    private val density = resources.displayMetrics.density
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.getColor(R.color.stroke)
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.text_primary)
    }
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0x99000000.toInt()
    }

    private var sweepShader: SweepGradient? = null
    private var radialShader: RadialGradient? = null
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private var currentHue = 0
    private var currentSaturation = 0
    private var listener: Listener? = null
    private var tracking = false

    val hue: Int
        get() = currentHue

    val saturation: Int
        get() = currentSaturation

    init {
        isClickable = true
        isFocusable = true
        updateContentDescription()
    }

    fun setOnWheelListener(listener: Listener?) {
        this.listener = listener
    }

    fun setValue(hue: Int, saturation: Int) {
        currentHue = hue.coerceIn(-180, 180)
        currentSaturation = saturation.coerceIn(0, 100)
        updateContentDescription()
        invalidate()
    }

    fun setHue(hue: Int) {
        currentHue = hue.coerceIn(-180, 180)
        updateContentDescription()
        invalidate()
    }

    fun setSaturation(saturation: Int) {
        currentSaturation = saturation.coerceIn(0, 100)
        updateContentDescription()
        invalidate()
    }

    private fun updateContentDescription() {
        contentDescription = context.getString(
            R.string.grade_wheel_description,
            currentHue,
            currentSaturation
        )
    }

    fun setWheelEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(200)
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val size = min(width, height).coerceAtLeast(1)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - 2f * density
        if (radius <= 0f) return

        val hueColors = IntArray(13) { index ->
            Color.HSVToColor(floatArrayOf(index * 30f, 1f, 1f))
        }
        sweepShader = SweepGradient(centerX, centerY, hueColors, null)
        radialShader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        wheelPaint.shader = sweepShader
        canvas.drawCircle(centerX, centerY, radius, wheelPaint)
        wheelPaint.shader = null

        fadePaint.shader = radialShader
        canvas.drawCircle(centerX, centerY, radius, fadePaint)
        fadePaint.shader = null

        canvas.drawCircle(centerX, centerY, radius, borderPaint)
        drawThumb(canvas)
    }

    private fun drawThumb(canvas: Canvas) {
        val angleRad = normalizeHue(currentHue) * PI / 180.0
        val fraction = currentSaturation / 100f
        val thumbX = centerX + cos(angleRad).toFloat() * radius * fraction
        val thumbY = centerY + sin(angleRad).toFloat() * radius * fraction
        val thumbRadius = 8f * density
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbFill)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                listener?.onWheelStarted(this)
                updateFromTouch(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    updateFromTouch(event.x, event.y)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    updateFromTouch(event.x, event.y)
                    tracking = false
                    listener?.onWheelStopped(this)
                }
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    listener?.onWheelStopped(this)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (radius <= 0f) return
        val dx = x - centerX
        val dy = y - centerY
        val distance = hypot(dx, dy)
        val degrees = (atan2(dy, dx) * 180.0 / PI + 360.0) % 360.0
        val engineHue = if (degrees > 180.0) degrees - 360.0 else degrees
        val hue = engineHue.roundToInt().coerceIn(-180, 180)
        val saturation = ((distance / radius).coerceIn(0f, 1f) * 100f).roundToInt()

        if (hue != currentHue || saturation != currentSaturation) {
            currentHue = hue
            currentSaturation = saturation
            updateContentDescription()
            invalidate()
            listener?.onWheelChanged(this, hue, saturation)
        }
    }

    private fun normalizeHue(hue: Int): Float =
        (((hue.toFloat() % 360f) + 360f) % 360f)

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
