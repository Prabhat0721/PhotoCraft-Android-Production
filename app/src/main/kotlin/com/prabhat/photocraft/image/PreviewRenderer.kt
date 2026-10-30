package com.prabhat.photocraft.image

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.prabhat.photocraft.edit.AdjustmentMath
import com.prabhat.photocraft.edit.ColorGradingMath
import com.prabhat.photocraft.edit.ColorGradingSettings
import com.prabhat.photocraft.edit.Adjustments
import com.prabhat.photocraft.edit.CurvesMath
import com.prabhat.photocraft.edit.CurvesSettings
import com.prabhat.photocraft.edit.HealingMath
import com.prabhat.photocraft.edit.HealingSettings
import com.prabhat.photocraft.edit.HslMath
import com.prabhat.photocraft.edit.HslSettings
import com.prabhat.photocraft.edit.PhotoEffects
import com.prabhat.photocraft.edit.PhotoEffectsMath
import com.prabhat.photocraft.edit.MaskingState
import com.prabhat.photocraft.util.RequestSequence
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class PreviewRenderer : Closeable {
    sealed class Result {
        data class Success(val bitmap: Bitmap) : Result()
        data class Failure(val reason: FailureReason) : Result()
    }

    enum class FailureReason {
        OUT_OF_MEMORY,
        RENDER_FAILED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread(
        "PhotoCraftPreview",
        Process.THREAD_PRIORITY_BACKGROUND
    ).apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val requests = RequestSequence()
    private val closed = AtomicBoolean(false)
    private var pixelBuffer: IntArray? = null

    fun request(
        source: Bitmap,
        adjustments: Adjustments,
        filter: FilterSettings,
        effects: PhotoEffects,
        colorGrading: ColorGradingSettings = ColorGradingSettings.NEUTRAL,
        hsl: HslSettings = HslSettings.NEUTRAL,
        curves: CurvesSettings = CurvesSettings.NEUTRAL,
        healing: HealingSettings = HealingSettings.NEUTRAL,
        masking: MaskingState = MaskingState(),
        callback: (Result) -> Unit
    ) {
        if (closed.get()) {
            return
        }

        val requestId = requests.next()
        renderHandler.post {
            if (closed.get() || !requests.isCurrent(requestId)) {
                return@post
            }

            val result = render(source, adjustments, filter, effects, colorGrading, hsl, curves, healing, masking)

            if (closed.get() || !requests.isCurrent(requestId)) {
                recycleDiscarded(result, source)
                return@post
            }

            mainHandler.post {
                if (closed.get() || !requests.isCurrent(requestId)) {
                    recycleDiscarded(result, source)
                    return@post
                }
                callback(result)
            }
        }
    }

    private fun render(
        source: Bitmap,
        adjustments: Adjustments,
        filter: FilterSettings,
        effects: PhotoEffects,
        colorGrading: ColorGradingSettings,
        hsl: HslSettings,
        curves: CurvesSettings,
        healing: HealingSettings,
        masking: MaskingState
    ): Result {
        if (adjustments.isNeutral() && filter.isNeutral() && effects.isNeutral() &&
            colorGrading.isNeutral() && hsl.isNeutral() && curves.isNeutral() && healing.isNeutral() && masking.isNeutral()) {
            return Result.Success(source)
        }

        var output: Bitmap? = null
        return try {
            val width = source.width
            val height = source.height
            val size = width * height
            val pixels = pixelBuffer
                ?.takeIf { it.size == size }
                ?: IntArray(size).also { pixelBuffer = it }

            source.getPixels(pixels, 0, width, 0, 0, width, height)
            AdjustmentMath.applyInPlace(pixels, adjustments)
            FilterMath.applyInPlace(pixels, filter)
            PhotoEffectsMath.applyInPlace(pixels, width, height, effects)
            ColorGradingMath.applyInPlace(pixels, colorGrading)
            HslMath.applyInPlace(pixels, hsl)
            CurvesMath.applyInPlace(pixels, curves)
            HealingMath.applyInPlace(pixels, width, height, healing)

            // Apply each enabled local mask and blend by its coverage.
            for (layer in listOf(masking.subject, masking.background)) {
                if (!layer.enabled) continue
                val selection = layer.selection ?: continue
                val local = layer.adjustments

                if (local.adjustments.isNeutral() && local.filter.isNeutral() &&
                    local.effects.isNeutral() && local.colorGrading.isNeutral() && local.hsl.isNeutral() && 
                    local.curves.isNeutral() && local.healing.isNeutral()) {
                    continue
                }

                val edited = pixels.copyOf()
                AdjustmentMath.applyInPlace(edited, local.adjustments)
                FilterMath.applyInPlace(edited, local.filter)
                PhotoEffectsMath.applyInPlace(edited, width, height, local.effects)
                ColorGradingMath.applyInPlace(edited, local.colorGrading)
                HslMath.applyInPlace(edited, local.hsl)
                CurvesMath.applyInPlace(edited, local.curves)
                HealingMath.applyInPlace(edited, width, height, local.healing)

                for (y in 0 until height) {
                    val maskY = (y.toFloat() * selection.height / height)
                        .toInt().coerceIn(0, selection.height - 1)
                    for (x in 0 until width) {
                        val maskX = (x.toFloat() * selection.width / width)
                            .toInt().coerceIn(0, selection.width - 1)
                        var coverage = selection.coverageAt(maskX, maskY)
                        if (layer.inverted) coverage = 255 - coverage
                        if (coverage == 0) continue

                        val i = y * width + x
                        val base = pixels[i]
                        val changed = edited[i]
                        val amount = coverage / 255f

                        fun blend(shift: Int): Int {
                            val before = (base shr shift) and 0xFF
                            val after = (changed shr shift) and 0xFF
                            return (before + (after - before) * amount)
                                .toInt().coerceIn(0, 255)
                        }

                        pixels[i] = (base and -0x1000000) or
                            (blend(16) shl 16) or
                            (blend(8) shl 8) or blend(0)
                    }
                }
            }

            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                density = source.density
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
            Result.Success(output)
        } catch (_: OutOfMemoryError) {
            output?.recycle()
            Result.Failure(FailureReason.OUT_OF_MEMORY)
        } catch (_: IllegalArgumentException) {
            output?.recycle()
            Result.Failure(FailureReason.RENDER_FAILED)
        } catch (_: IllegalStateException) {
            output?.recycle()
            Result.Failure(FailureReason.RENDER_FAILED)
        }
    }

    private fun recycleDiscarded(result: Result, source: Bitmap) {
        if (result is Result.Success && result.bitmap !== source) {
            result.bitmap.recycle()
        }
    }

    override fun close() {
        closed.set(true)
        requests.invalidate()
        mainHandler.removeCallbacksAndMessages(null)
        renderHandler.removeCallbacksAndMessages(null)
        renderThread.quitSafely()
        pixelBuffer = null
    }
}
