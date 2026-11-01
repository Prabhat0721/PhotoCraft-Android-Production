package com.prabhat.photocraft.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.prabhat.photocraft.edit.CropSelection
import com.prabhat.photocraft.edit.HealingSpot
import com.prabhat.photocraft.edit.MaskSelection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CropPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var bitmap: Bitmap? = null
    private var crop = CropSelection.FULL
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var maskSelection: MaskSelection? = null
    private var overlayBitmap: Bitmap? = null
    private var maskTouchListener: ((Float, Float, Int) -> Boolean)? = null
    private var healingSpots: List<HealingSpot> = emptyList()
    private var showHealingOverlay = false
    private var brushCursorVisible = false
    private var brushCursorX = 0f
    private var brushCursorY = 0f
    private var brushCursorRadius = 0f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var zoomEnabled = true
    private var multiTouchActive = false
    private var maskGestureCanceled = false
    private var lastTouchSourceX = 0f
    private var lastTouchSourceY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled) return false
                multiTouchActive = true
                if (!maskGestureCanceled) {
                    maskTouchListener?.invoke(
                        lastTouchSourceX,
                        lastTouchSourceY,
                        MotionEvent.ACTION_CANCEL
                    )
                    maskGestureCanceled = true
                }
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previousZoom = zoom
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                if (nextZoom == previousZoom) return true
                val focusX = detector.focusX
                val focusY = detector.focusY
                val centerX = width / 2f
                val centerY = height / 2f
                val ratio = nextZoom / previousZoom
                panX = focusX - centerX - (lastFocusX - centerX - panX) * ratio
                panY = focusY - centerY - (lastFocusY - centerY - panY) * ratio
                zoom = nextZoom
                lastFocusX = focusX
                lastFocusY = focusY
                if (zoom <= MIN_ZOOM + 0.001f) {
                    zoom = MIN_ZOOM
                    panX = 0f
                    panY = 0f
                }
                clampPan()
                invalidate()
                return true
            }
        }
    )
    private val healingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(230, 255, 210, 80)
    }
    private val healingFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(55, 255, 210, 80)
    }
    private val brushCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }
    private val brushCursorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(150, 0, 0, 0)
    }

    fun setHealingSpots(spots: List<HealingSpot>, show: Boolean) {
        healingSpots = spots
        showHealingOverlay = show
        invalidate()
    }

    fun setBrushCursor(x: Float, y: Float, radius: Float, visible: Boolean) {
        brushCursorX = x
        brushCursorY = y
        brushCursorRadius = radius
        brushCursorVisible = visible
        invalidate()
    }

    fun setBrushCursorAtSource(
        sourceX: Float,
        sourceY: Float,
        radiusSource: Float,
        sourceWidth: Int,
        visible: Boolean
    ) {
        if (!visible || sourceWidth <= 0 || sourceRect.width() <= 0) {
            setBrushCursor(0f, 0f, 0f, false)
            return
        }
        val scale = destinationRect.width() / sourceRect.width()
        val viewX = destinationRect.left + (sourceX - sourceRect.left) * scale
        val viewY = destinationRect.top + (sourceY - sourceRect.top) * scale
        setBrushCursor(viewX, viewY, radiusSource * scale, true)
    }

    fun setZoomEnabled(enabled: Boolean) {
        zoomEnabled = enabled
        if (!enabled) {
            resetZoom()
        }
    }

    fun resetZoom() {
        zoom = MIN_ZOOM
        panX = 0f
        panY = 0f
        multiTouchActive = false
        maskGestureCanceled = false
        invalidate()
    }

    fun getZoomScale(): Float = zoom

    fun setMaskTouchListener(listener: ((Float, Float, Int) -> Boolean)?) {
        maskTouchListener = listener
    }

    fun setMaskSelection(selection: MaskSelection?) {
        maskSelection = selection
        overlayBitmap?.recycle()
        overlayBitmap = null

        if (selection != null) {
            val scale = min(1f, 512f / maxOf(selection.width, selection.height).toFloat())
            val ow = (selection.width * scale).roundToInt().coerceAtLeast(1)
            val oh = (selection.height * scale).roundToInt().coerceAtLeast(1)
            val pixels = IntArray(ow * oh)

            for (y in 0 until oh) {
                val sy = (y.toFloat() / oh * selection.height).toInt()
                    .coerceIn(0, selection.height - 1)
                for (x in 0 until ow) {
                    val sx = (x.toFloat() / ow * selection.width).toInt()
                        .coerceIn(0, selection.width - 1)
                    val alpha = selection.coverageAt(sx, sy) * 110 / 255
                    pixels[y * ow + x] = Color.argb(alpha, 255, 45, 45)
                }
            }
            overlayBitmap = Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
        }
        invalidate()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleDetector.isQuickScaleEnabled = false
        scaleDetector.isStylusScaleEnabled = false
    }

    fun setBitmap(bitmap: Bitmap?) {
        this.bitmap = bitmap
        invalidate()
    }

    fun setCrop(selection: CropSelection) {
        crop = selection
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zoomEnabled) {
            scaleDetector.onTouchEvent(event)
        }

        if (event.pointerCount > 1) {
            multiTouchActive = true
        }
        if (multiTouchActive) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                multiTouchActive = false
                maskGestureCanceled = false
            }
            return true
        }

        val listener = maskTouchListener ?: return zoomEnabled || super.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            !destinationRect.contains(event.x, event.y)
        ) return zoomEnabled

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
            else -> return zoomEnabled || super.onTouchEvent(event)
        }

        if (destinationRect.width() <= 0f || destinationRect.height() <= 0f) {
            return zoomEnabled
        }

        val sx = (sourceRect.left +
            (event.x - destinationRect.left) / destinationRect.width() * sourceRect.width())
            .coerceIn(sourceRect.left.toFloat(), sourceRect.right.toFloat() - 1f)
        val sy = (sourceRect.top +
            (event.y - destinationRect.top) / destinationRect.height() * sourceRect.height())
            .coerceIn(sourceRect.top.toFloat(), sourceRect.bottom.toFloat() - 1f)
        lastTouchSourceX = sx
        lastTouchSourceY = sy

        return listener(sx, sy, event.actionMasked) || zoomEnabled
    }

    private fun clampPan() {
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0 ||
            width <= 0 || height <= 0
        ) {
            panX = 0f
            panY = 0f
            return
        }
        val baseScale = min(
            width.toFloat() / sourceRect.width(),
            height.toFloat() / sourceRect.height()
        )
        val maxPanX = max(
            0f,
            (sourceRect.width() * baseScale * zoom - width) / 2f
        )
        val maxPanY = max(
            0f,
            (sourceRect.height() * baseScale * zoom - height) / 2f
        )
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (image.width <= 0 || image.height <= 0 || width <= 0 || height <= 0) {
            return
        }

        val sourceLeft = floor(crop.left * image.width).toInt().coerceIn(0, image.width - 1)
        val sourceTop = floor(crop.top * image.height).toInt().coerceIn(0, image.height - 1)
        val sourceRight = ceil(crop.right * image.width).toInt()
            .coerceIn(sourceLeft + 1, image.width)
        val sourceBottom = ceil(crop.bottom * image.height).toInt()
            .coerceIn(sourceTop + 1, image.height)
        sourceRect.set(sourceLeft, sourceTop, sourceRight, sourceBottom)

        clampPan()
        val scale = min(
            width.toFloat() / sourceRect.width(),
            height.toFloat() / sourceRect.height()
        ) * zoom
        val destinationWidth = sourceRect.width() * scale
        val destinationHeight = sourceRect.height() * scale
        val left = (width - destinationWidth) / 2f + panX
        val top = (height - destinationHeight) / 2f + panY
        destinationRect.set(left, top, left + destinationWidth, top + destinationHeight)
        canvas.drawBitmap(image, sourceRect, destinationRect, paint)

        val overlay = overlayBitmap
        if (overlay != null && maskSelection != null) {
            val ol = floor(crop.left * overlay.width).toInt().coerceIn(0, overlay.width - 1)
            val ot = floor(crop.top * overlay.height).toInt().coerceIn(0, overlay.height - 1)
            val oright = ceil(crop.right * overlay.width).toInt().coerceIn(ol + 1, overlay.width)
            val obottom = ceil(crop.bottom * overlay.height).toInt().coerceIn(ot + 1, overlay.height)
            canvas.drawBitmap(
                overlay, Rect(ol, ot, oright, obottom), destinationRect, overlayPaint
            )
        }

        if (showHealingOverlay && healingSpots.isNotEmpty()) {
            val minDimension = min(destinationRect.width(), destinationRect.height())
            for (spot in healingSpots) {
                val cx = destinationRect.left + spot.x * destinationRect.width()
                val cy = destinationRect.top + spot.y * destinationRect.height()
                val radius = spot.radius * minDimension
                canvas.drawCircle(cx, cy, radius, healingFillPaint)
                canvas.drawCircle(cx, cy, radius, healingPaint)
            }
        }

        if (brushCursorVisible && brushCursorRadius > 0f) {
            canvas.drawCircle(
                brushCursorX, brushCursorY, brushCursorRadius, brushCursorShadowPaint
            )
            canvas.drawCircle(brushCursorX, brushCursorY, brushCursorRadius, brushCursorPaint)
        }
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
