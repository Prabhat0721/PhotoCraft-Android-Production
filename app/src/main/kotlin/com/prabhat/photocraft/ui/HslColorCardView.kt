package com.prabhat.photocraft.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.prabhat.photocraft.R
import com.prabhat.photocraft.edit.HslAdjustment
import com.prabhat.photocraft.edit.HslColor
import kotlin.math.roundToInt

class HslColorCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onHslGestureStarted(card: HslColorCardView)
        fun onHslChanged(card: HslColorCardView, adjustment: HslAdjustment)
        fun onHslGestureStopped(card: HslColorCardView)
    }

    private val density = resources.displayMetrics.density
    private val chipView: ColorChipView
    private val nameView: TextView
    private val hueSlider: GradientSliderView
    private val saturationSlider: GradientSliderView
    private val luminanceSlider: GradientSliderView
    private val cardBackground: GradientDrawable
    private val sliderListener: GradientSliderView.Listener

    private var channel = HslColor.RED
    private var currentAdjustment = HslAdjustment()
    private var hslListener: Listener? = null

    val color: HslColor
        get() = channel

    val adjustment: HslAdjustment
        get() = currentAdjustment

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        elevation = 1f * density

        cardBackground = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                context.getColor(R.color.surface_alt),
                context.getColor(R.color.surface)
            )
        ).apply {
            cornerRadius = 14f * density
            setStroke(dp(1), context.getColor(R.color.stroke))
        }
        background = cardBackground

        chipView = ColorChipView(context)
        nameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.text_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        header.addView(chipView, LayoutParams(dp(22), dp(22)))
        header.addView(
            nameView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        )
        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        hueSlider = buildSlider(context.getString(R.string.control_hue), -180, 180)
        saturationSlider = buildSlider(context.getString(R.string.control_saturation), -100, 100)
        luminanceSlider = buildSlider(context.getString(R.string.control_luminance), -100, 100)

        addView(hueSlider, sliderLayoutParams())
        addView(saturationSlider, sliderLayoutParams())
        addView(luminanceSlider, sliderLayoutParams())

        sliderListener = object : GradientSliderView.Listener {
            override fun onSliderStarted(view: GradientSliderView) {
                hslListener?.onHslGestureStarted(this@HslColorCardView)
            }

            override fun onSliderChanged(view: GradientSliderView, value: Int) {
                currentAdjustment = when (view) {
                    hueSlider -> currentAdjustment.copy(hue = value)
                    saturationSlider -> currentAdjustment.copy(saturation = value)
                    luminanceSlider -> currentAdjustment.copy(luminance = value)
                    else -> currentAdjustment
                }
                hslListener?.onHslChanged(this@HslColorCardView, currentAdjustment)
            }

            override fun onSliderStopped(view: GradientSliderView) {
                hslListener?.onHslGestureStopped(this@HslColorCardView)
            }
        }
        hueSlider.setOnSliderListener(sliderListener)
        saturationSlider.setOnSliderListener(sliderListener)
        luminanceSlider.setOnSliderListener(sliderListener)

        bind(HslColor.RED, HslAdjustment(), null)
    }

    fun bind(color: HslColor, adjustment: HslAdjustment, listener: Listener?) {
        channel = color
        hslListener = listener
        nameView.text = channelLabel(color)
        chipView.setChannelColor(ColorChipView.colorForHue(color.centerHue, 0.9f, 0.98f))
        chipView.contentDescription = channelLabel(color)
        hueSlider.setGradient(hueSpectrum())
        saturationSlider.setGradient(saturationGradient(color.centerHue))
        luminanceSlider.setGradient(luminanceGradient(color.centerHue))
        setAdjustment(adjustment)
    }

    fun setOnHslListener(listener: Listener?) {
        hslListener = listener
    }

    fun setAdjustment(adjustment: HslAdjustment) {
        currentAdjustment = adjustment
        hueSlider.setValue(adjustment.hue)
        saturationSlider.setValue(adjustment.saturation)
        luminanceSlider.setValue(adjustment.luminance)
    }

    fun setCardEnabled(enabled: Boolean) {
        hueSlider.setSliderEnabled(enabled)
        saturationSlider.setSliderEnabled(enabled)
        luminanceSlider.setSliderEnabled(enabled)
        chipView.alpha = if (enabled) 1f else 0.45f
        nameView.alpha = if (enabled) 1f else 0.45f
    }

    override fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        super.setSelected(selected)
        chipView.isSelected = selected
        cardBackground.setStroke(
            dp(if (selected) 2 else 1),
            context.getColor(if (selected) R.color.accent else R.color.stroke)
        )
    }

    private fun buildSlider(title: String, minimum: Int, maximum: Int): GradientSliderView =
        GradientSliderView(context).apply {
            configure(
                title = title,
                minimum = minimum,
                maximum = maximum,
                initialValue = 0,
                neutralValue = 0
            )
            setCompact(true)
            setCenterMarkerVisible(true)
        }

    private fun sliderLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun channelLabel(color: HslColor): String =
        color.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun hueSpectrum(): IntArray {
        val colors = IntArray(7)
        for (index in colors.indices) {
            colors[index] = ColorChipView.colorForHue(index * 60f, 1f, 1f)
        }
        return colors
    }

    private fun saturationGradient(hue: Float): IntArray = intArrayOf(
        Color.HSVToColor(floatArrayOf(normalizeHue(hue), 0f, 0.78f)),
        Color.HSVToColor(floatArrayOf(normalizeHue(hue), 1f, 1f))
    )

    private fun luminanceGradient(hue: Float): IntArray = intArrayOf(
        Color.BLACK,
        Color.HSVToColor(floatArrayOf(normalizeHue(hue), 1f, 0.75f)),
        Color.WHITE
    )

    private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
