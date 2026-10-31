package com.prabhat.photocraft.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.prabhat.photocraft.edit.CurvePoint
import com.prabhat.photocraft.edit.CurveSettings
import com.prabhat.photocraft.edit.CurvesMath
import kotlin.math.abs
import kotlin.math.hypot

class CurveEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var curve = CurveSettings.NEUTRAL
    private var onCurveChanged: ((CurveSettings) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val padding = 16f * density
    private val pointRadius = 5f * density
    private val touchRadius = 20f * density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 22, 26)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 58, 66)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 72, 82)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private var activeIndex = -1
    private var lastTapIndex = -1
    private var lastTapTime = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Tone curve editor"
    }

    fun setCurve(value: CurveSettings) {
        curve = value
        activeIndex = -1
        invalidate()
    }

    fun setCurveColor(color: Int) {
        curvePaint.color = color
        pointPaint.color = color
        invalidate()
    }

    fun setOnCurveChangedListener(listener: ((CurveSettings) -> Unit)?) {
        onCurveChanged = listener
    }

    private val plotLeft: Float get() = padding
    private val plotTop: Float get() = padding
    private val plotRight: Float get() = width - padding
    private val plotBottom: Float get() = height - padding

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((280f * density).toInt(), widthMeasureSpec)
        val height = resolveSize((200f * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(), 10f * density, 10f * density,
            backgroundPaint
        )

        for (i in 1..3) {
            val fx = plotLeft + (plotRight - plotLeft) * i / 4f
            val fy = plotTop + (plotBottom - plotTop) * i / 4f
            canvas.drawLine(fx, plotTop, fx, plotBottom, gridPaint)
            canvas.drawLine(plotLeft, fy, plotRight, fy, gridPaint)
        }
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotTop, diagonalPaint)

        val table = CurvesMath.createLookupTable(curve)
        val path = Path()
        val steps = 128
        for (i in 0..steps) {
            val x = i / steps.toFloat()
            val lutIndex = (x * 255f).toInt().coerceIn(0, 255)
            val y = table[lutIndex] / 255f
            val px = plotLeft + x * (plotRight - plotLeft)
            val py = plotBottom - y * (plotBottom - plotTop)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, curvePaint)

        for (point in curve.points) {
            val px = plotLeft + point.x.coerceIn(0f, 1f) * (plotRight - plotLeft)
            val py = plotBottom - point.y.coerceIn(0f, 1f) * (plotBottom - plotTop)
            canvas.drawCircle(px, py, pointRadius, pointPaint)
            canvas.drawCircle(px, py, pointRadius, pointStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val nx = ((event.x - plotLeft) / (plotRight - plotLeft)).coerceIn(0f, 1f)
        val ny = (1f - (event.y - plotTop) / (plotBottom - plotTop)).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = findNearestPoint(event.x, event.y)
                val now = System.currentTimeMillis()
                if (index >= 0) {
                    if (index in 1 until curve.points.lastIndex &&
                        now - lastTapTime < 320 &&
                        lastTapIndex == index
                    ) {
                        val points = curve.points.toMutableList()
                        points.removeAt(index)
                        curve = curve.copy(points = points)
                        activeIndex = -1
                        lastTapIndex = -1
                        notifyChanged()
                        invalidate()
                        return true
                    }
                    activeIndex = index
                    lastTapIndex = index
                    lastTapTime = now
                } else {
                    val points = curve.points.toMutableList()
                    points.add(CurvePoint(nx, ny))
                    points.sortBy { it.x }
                    curve = curve.copy(points = points)
                    activeIndex = points.indexOfFirst {
                        abs(it.x - nx) < 1e-4f && abs(it.y - ny) < 1e-4f
                    }
                    lastTapIndex = activeIndex
                    lastTapTime = now
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeIndex < 0 || activeIndex >= curve.points.size) return false
                val points = curve.points.toMutableList()
                val isFirst = activeIndex == 0
                val isLast = activeIndex == points.lastIndex
                val minX = if (isFirst) 0f else points[activeIndex - 1].x + 0.01f
                val maxX = if (isLast) 1f else points[activeIndex + 1].x - 0.01f
                val x = if (isFirst) 0f else if (isLast) 1f else nx.coerceIn(minX, maxX)
                points[activeIndex] = CurvePoint(x, ny)
                curve = curve.copy(points = points)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeIndex >= 0) {
                    notifyChanged()
                    activeIndex = -1
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestPoint(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = touchRadius
        for ((index, point) in curve.points.withIndex()) {
            val px = plotLeft + point.x.coerceIn(0f, 1f) * (plotRight - plotLeft)
            val py = plotBottom - point.y.coerceIn(0f, 1f) * (plotBottom - plotTop)
            val distance = hypot(x - px, y - py)
            if (distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    private fun notifyChanged() {
        onCurveChanged?.invoke(curve)
    }
}
