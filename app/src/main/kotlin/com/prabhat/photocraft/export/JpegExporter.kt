package com.prabhat.photocraft.export

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.prabhat.photocraft.edit.AdjustmentMath
import com.prabhat.photocraft.edit.ColorGradingMath
import com.prabhat.photocraft.edit.ColorGradingSettings
import com.prabhat.photocraft.edit.Adjustments
import com.prabhat.photocraft.edit.CurvesMath
import com.prabhat.photocraft.edit.CurvesSettings
import com.prabhat.photocraft.edit.FilterMath
import com.prabhat.photocraft.edit.FilterSettings
import com.prabhat.photocraft.edit.HealingMath
import com.prabhat.photocraft.edit.HealingSettings
import com.prabhat.photocraft.edit.PhotoEffects
import com.prabhat.photocraft.edit.PhotoEffectsMath
import com.prabhat.photocraft.edit.CropSelection
import com.prabhat.photocraft.edit.MaskLayer
import com.prabhat.photocraft.edit.MaskingState
import com.prabhat.photocraft.util.RequestSequence
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class JpegExporter(private val resolver: ContentResolver) : Closeable {
    sealed class Result {
        data class Success(val width: Int, val height: Int) : Result()
        data class Failure(val reason: FailureReason) : Result()
    }

    enum class FailureReason {
        INVALID_URI,
        ACCESS_DENIED,
        DECODE_FAILED,
        UNSUPPORTED_OR_CORRUPT,
        TOO_LARGE,
        SOURCE_CONFLICT,
        WRITE_FAILED,
        CANCELLED
    }

    enum class Stage {
        PREPARING,
        DECODING,
        PROCESSING,
        WRITING
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = RequestSequence()
    private val closed = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    fun export(
        sourceUri: Uri,
        destinationUri: Uri,
        request: ExportRequest,
        callback: (Result) -> Unit
    ) {
        export(
            sourceUri = sourceUri,
            destinationUri = destinationUri,
            request = request,
            attestation = null,
            onProgress = { _, _ -> },
            callback = callback
        )
    }

    fun export(
        sourceUri: Uri,
        destinationUri: Uri,
        request: ExportRequest,
        attestation: DestinationAttestation?,
        onProgress: (Stage, Int) -> Unit,
        callback: (Result) -> Unit
    ) {
        if (closed.get()) {
            return
        }

        cancelRequested.set(false)
        val requestId = requests.next()
        executor.execute {
            val result = exportBlocking(
                requestId = requestId,
                sourceUri = sourceUri,
                destinationUri = destinationUri,
                request = request,
                attestation = attestation,
                onProgress = onProgress
            )
            if (closed.get() || !requests.isCurrent(requestId)) {
                return@execute
            }

            mainHandler.post {
                if (closed.get() || !requests.isCurrent(requestId)) {
                    return@post
                }
                callback(result)
            }
        }
    }

    fun cancel() {
        cancelRequested.set(true)
    }

    fun discardDestination(
        sourceUri: Uri?,
        destinationUri: Uri,
        attestation: DestinationAttestation?
    ) {
        cleanupDestination(sourceUri, destinationUri, attestation)
    }

    private fun exportBlocking(
        requestId: Int,
        sourceUri: Uri,
        destinationUri: Uri,
        request: ExportRequest,
        attestation: DestinationAttestation?,
        onProgress: (Stage, Int) -> Unit
    ): Result {
        var succeeded = false
        var decoded: Bitmap? = null
        var working: Bitmap? = null
        return try {
            if (!hasScheme(sourceUri, SUPPORTED_SOURCE_SCHEMES)) {
                return Result.Failure(FailureReason.INVALID_URI)
            }
            if (!hasScheme(destinationUri, SUPPORTED_DESTINATION_SCHEMES)) {
                return Result.Failure(FailureReason.WRITE_FAILED)
            }
            if (sourceUri == destinationUri) {
                return Result.Failure(FailureReason.SOURCE_CONFLICT)
            }

            reportProgress(requestId, Stage.PREPARING, 0, onProgress)
            val decodePlan = if (request.sourceWidth > 0 && request.sourceHeight > 0) {
                ExportMemoryPolicy.plan(
                    sourceWidth = request.sourceWidth,
                    sourceHeight = request.sourceHeight,
                    crop = request.crop,
                    size = request.outputSize
                ) ?: return Result.Failure(FailureReason.DECODE_FAILED)
            } else {
                null
            }

            if (isCancelled()) {
                return Result.Failure(FailureReason.CANCELLED)
            }
            reportProgress(requestId, Stage.DECODING, 5, onProgress)
            decoded = if (decodePlan != null) {
                decode(sourceUri, decodePlan.decodeWidth, decodePlan.decodeHeight)
            } else {
                decode(sourceUri, 0, 0)
            }
            if (isCancelled()) {
                return Result.Failure(FailureReason.CANCELLED)
            }
            if (decoded.width <= 0 || decoded.height <= 0) {
                return Result.Failure(FailureReason.UNSUPPORTED_OR_CORRUPT)
            }

            val output = decodePlan?.output ?: ExportPlanner.plan(
                sourceWidth = decoded.width,
                sourceHeight = decoded.height,
                crop = request.crop,
                size = request.outputSize
            ).output
            val crop = ExportPlanner.pixelCrop(
                sourceWidth = decoded.width,
                sourceHeight = decoded.height,
                crop = request.crop
            )
            val usesFullBitmap =
                crop.left == 0 &&
                    crop.top == 0 &&
                    crop.right == decoded.width &&
                    crop.bottom == decoded.height &&
                    output.width == decoded.width &&
                    output.height == decoded.height

            if (usesFullBitmap) {
                working = decoded
                decoded = null
            } else {
                if (isCancelled()) {
                    return Result.Failure(FailureReason.CANCELLED)
                }
                val target = Bitmap.createBitmap(
                    output.width,
                    output.height,
                    Bitmap.Config.ARGB_8888
                )
                working = target
                val canvas = Canvas(target)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(
                    decoded,
                    Rect(crop.left, crop.top, crop.right, crop.bottom),
                    Rect(0, 0, output.width, output.height),
                    paint
                )
                decoded.recycle()
                decoded = null
            }

            val bitmap = working
            if (!request.adjustments.isNeutral() || !request.filter.isNeutral() ||
                !request.effects.isNeutral() || !request.colorGrading.isNeutral() ||
                !request.curves.isNeutral()) {
                val completed = applyEditsInTiles(
                    bitmap = bitmap,
                    adjustments = request.adjustments,
                    filter = request.filter,
                    effects = request.effects,
                    colorGrading = request.colorGrading,
                    curves = request.curves,
                    requestId = requestId,
                    onProgress = onProgress
                )
                if (!completed) {
                    return Result.Failure(FailureReason.CANCELLED)
                }
            }

            if (!request.healing.isNeutral()) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                HealingMath.applyInPlace(pixels, bitmap.width, bitmap.height, request.healing)
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }

            if (!applyLocalMaskAdjustments(bitmap, request.masking, request.crop)) {
                return Result.Failure(FailureReason.CANCELLED)
            }

            if (isCancelled()) {
                return Result.Failure(FailureReason.CANCELLED)
            }

            reportProgress(requestId, Stage.WRITING, 95, onProgress)
            val result = writeJpeg(bitmap, destinationUri, request.quality.jpegQuality)
            if (result is Result.Success) {
                succeeded = true
            }
            result
        } catch (_: ImageDecoder.DecodeException) {
            Result.Failure(FailureReason.UNSUPPORTED_OR_CORRUPT)
        } catch (_: SecurityException) {
            Result.Failure(FailureReason.ACCESS_DENIED)
        } catch (_: IOException) {
            Result.Failure(FailureReason.DECODE_FAILED)
        } catch (_: IllegalArgumentException) {
            Result.Failure(FailureReason.INVALID_URI)
        } catch (_: RuntimeException) {
            Result.Failure(FailureReason.DECODE_FAILED)
        } catch (_: OutOfMemoryError) {
            Result.Failure(FailureReason.TOO_LARGE)
        } finally {
            if (!succeeded) {
                cleanupDestination(sourceUri, destinationUri, attestation)
            }
            working?.recycle()
            decoded?.recycle()
        }
    }

    private fun decode(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            decoder.setMutableRequired(true)
            if (targetWidth > 0 && targetHeight > 0) {
                val sourceSize = info.size
                if (
                    sourceSize.width > 0 &&
                    sourceSize.height > 0 &&
                    (targetWidth != sourceSize.width || targetHeight != sourceSize.height)
                ) {
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        }
    }

    private fun applyEditsInTiles(
        bitmap: Bitmap,
        adjustments: Adjustments,
        filter: FilterSettings,
        effects: PhotoEffects,
        colorGrading: ColorGradingSettings,
        curves: CurvesSettings,
        requestId: Int,
        onProgress: (Stage, Int) -> Unit
    ): Boolean {
        val tiles = ExportTiling.plan(bitmap.width, bitmap.height)
        var buffer = IntArray(0)
        for ((index, tile) in tiles.withIndex()) {
            if (isCancelled()) {
                return false
            }
            if (buffer.size < tile.pixelCount) {
                buffer = IntArray(tile.pixelCount)
            }
            bitmap.getPixels(
                buffer,
                0,
                tile.width,
                tile.left,
                tile.top,
                tile.width,
                tile.height
            )
            AdjustmentMath.applyInPlace(buffer, adjustments, tile.pixelCount)
            FilterMath.applyInPlace(buffer, filter, tile.pixelCount)
            PhotoEffectsMath.applyInPlace(
                buffer, tile.width, tile.height, effects,
                tile.left, tile.top, bitmap.width, bitmap.height
            )
            ColorGradingMath.applyInPlace(buffer, colorGrading, tile.pixelCount)
            CurvesMath.applyInPlace(buffer, curves, tile.pixelCount)
            bitmap.setPixels(
                buffer,
                0,
                tile.width,
                tile.left,
                tile.top,
                tile.width,
                tile.height
            )
            val percent = 20 + ((index + 1) * 70 / tiles.size)
            reportProgress(requestId, Stage.PROCESSING, percent, onProgress)
        }
        return true
    }

    private fun applyLocalMaskAdjustments(
        bitmap: Bitmap,
        masking: MaskingState,
        crop: CropSelection
    ): Boolean {
        val layers = masking.layers
            .filter { it.enabled && it.selection != null && !it.adjustments.isNeutral() }

        for (layer in layers) {
            if (isCancelled()) return false
            val selection = layer.selection ?: continue
            val tiles = ExportTiling.plan(bitmap.width, bitmap.height)

            for (tile in tiles) {
                if (isCancelled()) return false

                val pixels = IntArray(tile.pixelCount)
                bitmap.getPixels(
                    pixels, 0, tile.width,
                    tile.left, tile.top, tile.width, tile.height
                )

                val adjusted = pixels.copyOf()
                AdjustmentMath.applyInPlace(
                    adjusted, layer.adjustments.adjustments, tile.pixelCount
                )
                FilterMath.applyInPlace(adjusted, layer.adjustments.filter, tile.pixelCount)
                PhotoEffectsMath.applyInPlace(
                    adjusted, tile.width, tile.height, layer.adjustments.effects,
                    tile.left, tile.top, bitmap.width, bitmap.height
                )
                ColorGradingMath.applyInPlace(
                    adjusted, layer.adjustments.colorGrading, tile.pixelCount
                )
                CurvesMath.applyInPlace(adjusted, layer.adjustments.curves, tile.pixelCount)
                HealingMath.applyInPlace(
                    adjusted, tile.width, tile.height, layer.adjustments.healing
                )

                for (localY in 0 until tile.height) {
                    val outputY = tile.top + localY
                    val sourceY = crop.top +
                        ((outputY + 0.5f) / bitmap.height) * crop.height
                    val maskY = (sourceY * selection.height)
                        .toInt().coerceIn(0, selection.height - 1)

                    for (localX in 0 until tile.width) {
                        val outputX = tile.left + localX
                        val sourceX = crop.left +
                            ((outputX + 0.5f) / bitmap.width) * crop.width
                        val maskX = (sourceX * selection.width)
                            .toInt().coerceIn(0, selection.width - 1)

                        var coverage = selection.coverageAt(maskX, maskY)
                        if (layer.inverted) coverage = 255 - coverage
                        if (coverage == 0) continue

                        val i = localY * tile.width + localX
                        val base = pixels[i]
                        val edited = adjusted[i]
                        val amount = coverage / 255f

                        fun blend(shift: Int): Int {
                            val a = (base shr shift) and 0xFF
                            val b = (edited shr shift) and 0xFF
                            return (a + (b - a) * amount)
                                .toInt().coerceIn(0, 255)
                        }

                        pixels[i] = (base and -0x1000000) or
                            (blend(16) shl 16) or
                            (blend(8) shl 8) or
                            blend(0)
                    }
                }

                bitmap.setPixels(
                    pixels, 0, tile.width,
                    tile.left, tile.top, tile.width, tile.height
                )
            }
        }
        return true
    }

    private fun writeJpeg(bitmap: Bitmap, destinationUri: Uri, quality: Int): Result {
        return try {
            if (isCancelled()) {
                return Result.Failure(FailureReason.CANCELLED)
            }
            val output = resolver.openOutputStream(destinationUri, "wt")
                ?: return Result.Failure(FailureReason.WRITE_FAILED)
            output.use { stream ->
                when (
                    ExportWriteGuard.writeIfActive({ isCancelled() }) {
                        val compressed = bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            quality,
                            stream
                        )
                        if (compressed) {
                            stream.flush()
                        }
                        compressed
                    }
                ) {
                    ExportWriteGuard.Outcome.CANCELLED ->
                        return Result.Failure(FailureReason.CANCELLED)
                    ExportWriteGuard.Outcome.WRITE_FAILED ->
                        return Result.Failure(FailureReason.WRITE_FAILED)
                    ExportWriteGuard.Outcome.SUCCESS -> Unit
                }
            }
            if (
                ExportWriteGuard.afterClose({ isCancelled() }) ==
                ExportWriteGuard.Outcome.CANCELLED
            ) {
                return Result.Failure(FailureReason.CANCELLED)
            }
            Result.Success(bitmap.width, bitmap.height)
        } catch (_: SecurityException) {
            Result.Failure(FailureReason.WRITE_FAILED)
        } catch (_: IOException) {
            Result.Failure(FailureReason.WRITE_FAILED)
        } catch (_: IllegalArgumentException) {
            Result.Failure(FailureReason.WRITE_FAILED)
        } catch (_: RuntimeException) {
            Result.Failure(FailureReason.WRITE_FAILED)
        }
    }

    private fun cleanupDestination(
        sourceUri: Uri?,
        destinationUri: Uri,
        attestation: DestinationAttestation?
    ) {
        if (attestation == null || !attestation.established) {
            return
        }
        try {
            val documentId = try {
                DocumentsContract.getDocumentId(destinationUri)
            } catch (_: Exception) {
                null
            }
            val currentSize = queryDestinationSize(destinationUri)
            val decision = DestinationCleanup.plan(
                attestation = attestation,
                destinationIsContent = destinationUri.scheme == "content",
                sourceMatchesDestination = sourceUri == destinationUri,
                currentDocumentId = documentId,
                currentSize = currentSize
            )
            DestinationCleanup.attempt(decision) {
                resolver.delete(destinationUri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun queryDestinationSize(uri: Uri): Long? = try {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0)
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun reportProgress(
        requestId: Int,
        stage: Stage,
        percent: Int,
        onProgress: (Stage, Int) -> Unit
    ) {
        if (closed.get() || !requests.isCurrent(requestId)) {
            return
        }
        val bounded = percent.coerceIn(0, 100)
        mainHandler.post {
            if (!closed.get() && requests.isCurrent(requestId)) {
                onProgress(stage, bounded)
            }
        }
    }

    private fun isCancelled(): Boolean = cancelRequested.get() || closed.get()

    private fun hasScheme(uri: Uri, schemes: Set<String>): Boolean =
        uri.scheme?.let(schemes::contains) == true

    override fun close() {
        closed.set(true)
        requests.invalidate()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private companion object {
        val SUPPORTED_SOURCE_SCHEMES = setOf("content", "file", "android.resource")
        val SUPPORTED_DESTINATION_SCHEMES = setOf("content", "file")
    }
}
