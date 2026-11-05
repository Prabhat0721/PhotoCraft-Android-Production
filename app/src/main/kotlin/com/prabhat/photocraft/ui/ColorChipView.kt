package com.prabhat.photocraft.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.prabhat.photocraft.R
import kotlin.math.min

class ColorChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val ringWidth = 2f * density
    private val ringGap = 2f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33FFFFFF
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = context.getColor(R.color.text_primary)
    }

    private var channelColor = Color.WHITE
    private var shader: RadialGradient? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setChannelColor(color: Int, animate: Boolean = false) {
        if (channelColor == color) return
        channelColor = color
        shader = null
        invalidate()
        if (animate) {
            animate().cancel()
            animate().scaleX(1.06f).scaleY(1.06f).setDuration(90L)
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                }
                .start()
        }
    }

    override fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        super.setSelected(selected)
        animate().cancel()
        val target = if (selected) 1.08f else 1f
        animate().scaleX(target).scaleY(target).setDuration(120L).start()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) / 2f - ringWidth - ringGap
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        fillPaint.shader = ensureShader(radius)
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null
        canvas.drawCircle(cx, cy, radius, borderPaint)

        if (isSelected) {
            canvas.drawCircle(cx, cy, radius + ringWidth + ringGap, selectionPaint)
        }
    }

    private fun ensureShader(radius: Float): RadialGradient {
        shader?.let { return it }
        val safeRadius = radius.coerceAtLeast(1f)
        val created = RadialGradient(
            width / 2f - safeRadius * 0.32f,
            height / 2f - safeRadius * 0.35f,
            safeRadius * 1.45f,
            intArrayOf(
                blendTowardWhite(channelColor, 0.35f),
                channelColor,
                darken(channelColor, 0.18f)
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        shader = created
        return created
    }

    private fun blendTowardWhite(color: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * a).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * a).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * a).toInt()
        )
    }

    private fun darken(color: Int, amount: Float): Int {
        val factor = 1f - amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt()
        )
    }

    companion object {
        fun colorForHue(hue: Float, saturation: Float = 0.85f, value: Float = 1f): Int {
            val normalized = ((hue % 360f) + 360f) % 360f
            return Color.HSVToColor(floatArrayOf(normalized, saturation, value))
        }
    }
}
