package com.prabhat.photocraft.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.prabhat.photocraft.R
import com.prabhat.photocraft.edit.CropHandle
import com.prabhat.photocraft.edit.CropHandleMath
import com.prabhat.photocraft.edit.CropRatio
import com.prabhat.photocraft.edit.CropSelection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    interface Listener {
        fun onCropStarted()
        fun onCropChanged(crop: CropSelection)
        fun onCropStopped()
        fun onCropCancelled()
    }

    private var imageWidth = 0
    private var imageHeight = 0
    private var crop = CropSelection.FULL
    private var ratio = CropRatio.FREE
    private var listener: Listener? = null
    private var activeHandle: CropHandle? = null
    private var lastX = 0f
    private var lastY = 0f

    private val imageRect = RectF()
    private val cropRect = RectF()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.crop_scrim)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setImageAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        imageWidth = width
        imageHeight = height
        invalidate()
    }

    fun setCrop(selection: CropSelection) {
        crop = selection
        invalidate()
    }

    fun setCropRatio(cropRatio: CropRatio) {
        ratio = cropRatio
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!updateRects()) {
            return
        }

        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, scrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, scrimPaint)

        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(
            cropRect.left + inset,
            cropRect.top + inset,
            cropRect.right - inset,
            cropRect.bottom - inset,
            borderPaint
        )

        val radius = dp(4f)
        drawHandle(canvas, cropRect.left, cropRect.top, radius)
        drawHandle(canvas, cropRect.right, cropRect.top, radius)
        drawHandle(canvas, cropRect.left, cropRect.bottom, radius)
        drawHandle(canvas, cropRect.right, cropRect.bottom, radius)
        if (!ratio.isFixed) {
            drawHandle(canvas, cropRect.centerX(), cropRect.top, radius)
            drawHandle(canvas, cropRect.centerX(), cropRect.bottom, radius)
            drawHandle(canvas, cropRect.left, cropRect.centerY(), radius)
            drawHandle(canvas, cropRect.right, cropRect.centerY(), radius)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || imageWidth <= 0 || imageHeight <= 0 || !updateRects()) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val normalizedX = (event.x - imageRect.left) / imageRect.width()
                val normalizedY = (event.y - imageRect.top) / imageRect.height()
                val toleranceX = max(dp(24f) / imageRect.width(), 0.015f)
                val toleranceY = max(dp(24f) / imageRect.height(), 0.015f)
                val handle = CropHandleMath.hitTest(
                    crop = crop,
                    x = normalizedX,
                    y = normalizedY,
                    toleranceX = toleranceX,
                    toleranceY = toleranceY,
                    allowEdges = !ratio.isFixed
                ) ?: return false
                activeHandle = handle
                lastX = normalizedX
                lastY = normalizedY
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onCropStarted()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val handle = activeHandle ?: return false
                val normalizedX = ((event.x - imageRect.left) / imageRect.width())
                    .coerceIn(0f, 1f)
                val normalizedY = ((event.y - imageRect.top) / imageRect.height())
                    .coerceIn(0f, 1f)
                val deltaX = normalizedX - lastX
                val deltaY = normalizedY - lastY
                if (deltaX != 0f || deltaY != 0f) {
                    crop = CropHandleMath.drag(
                        crop = crop,
                        handle = handle,
                        deltaX = deltaX,
                        deltaY = deltaY,
                        ratio = ratio,
                        sourceWidth = imageWidth,
                        sourceHeight = imageHeight
                    )
                    lastX = normalizedX
                    lastY = normalizedY
                    invalidate()
                    listener?.onCropChanged(crop)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeHandle == null) {
                    return false
                }
                finishCrop(cancelled = false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activeHandle == null) {
                    return false
                }
                finishCrop(cancelled = true)
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun finishCrop(cancelled: Boolean) {
        activeHandle = null
        parent?.requestDisallowInterceptTouchEvent(false)
        if (cancelled) {
            listener?.onCropCancelled()
        } else {
            listener?.onCropStopped()
            performClick()
        }
    }

    private fun updateRects(): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0) {
            return false
        }
        val scale = min(
            width.toFloat() / imageWidth,
            height.toFloat() / imageHeight
        )
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val imageLeft = (width - displayedWidth) / 2f
        val imageTop = (height - displayedHeight) / 2f
        imageRect.set(
            imageLeft,
            imageTop,
            imageLeft + displayedWidth,
            imageTop + displayedHeight
        )
        cropRect.set(
            imageRect.left + crop.left * imageRect.width(),
            imageRect.top + crop.top * imageRect.height(),
            imageRect.left + crop.right * imageRect.width(),
            imageRect.top + crop.bottom * imageRect.height()
        )
        return true
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, handlePaint)
    }

    private fun dp(value: Float): Float =
        (value * resources.displayMetrics.density).roundToInt().toFloat()
}
