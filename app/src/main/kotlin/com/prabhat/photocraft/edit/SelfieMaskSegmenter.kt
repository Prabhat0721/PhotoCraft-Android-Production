package com.prabhat.photocraft.edit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SelfieMaskSegmenter {

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    suspend fun createMask(bitmap: Bitmap): MaskSelection =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)

            segmenter.process(image)
                .addOnSuccessListener { result ->
                    val mask = result.buffer
                    val width = result.width
                    val height = result.height

                    val coverage = ByteArray(width * height)
                    mask.rewind()

                    for (i in coverage.indices) {
                        val probability = mask.getFloat()
                        coverage[i] = (probability * 255f)
                            .toInt()
                            .coerceIn(0, 255)
                            .toByte()
                    }

                    if (continuation.isActive) {
                        continuation.resume(
                            MaskSelection(width, height, coverage).smoothed(1)
                        )
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
        }

    fun close() {
        segmenter.close()
    }
}
