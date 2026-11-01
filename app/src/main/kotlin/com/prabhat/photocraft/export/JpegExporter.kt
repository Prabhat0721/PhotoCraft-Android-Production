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
import com.prabhat.photocraft.edit.HealingSpot
import com.prabhat.photocraft.edit.PhotoEffects
import com.prabhat.photocraft.edit.PhotoEffectsMath
import com.prabhat.photocraft.edit.CropSelection
import com.prabhat.photocraft.edit.MaskLayer
import com.prabhat.photocraft.edit.MaskingState
import com.prabhat.photocraft.util.RequestSequence
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class JpegExporter(
    private val resolver: ContentResolver,
    private val cacheDir: File? = null
) : Closeable {
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
            val decodedWidth = decoded.width
            val decodedHeight = decoded.height

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
                    sourceWidth = decodedWidth,
                    sourceHeight = decodedHeight,
                    crop = crop,
                    requestId = requestId,
                    onProgress = onProgress
                )
                if (!completed) {
                    return Result.Failure(FailureReason.CANCELLED)
                }
            }

            if (!request.healing.isNeutral()) {
                val mappedHealing = mapHealingToCrop(
                    request.healing, request.crop,
                    decodedWidth, decodedHeight
                )
                if (!mappedHealing.isNeutral()) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(
                        pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height
                    )
                    HealingMath.applyInPlace(
                        pixels, bitmap.width, bitmap.height, mappedHealing
                    )
                    bitmap.setPixels(
                        pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height
                    )
                }
            }

            if (!applyLocalMaskAdjustments(
                    bitmap, request.masking, request.crop,
                    decodedWidth, decodedHeight
                )
            ) {
                return Result.Failure(FailureReason.CANCELLED)
            }

            if (request.colorSpace == ExportColorSpace.ADOBE_RGB) {
                if (!applyColorSpaceInTiles(bitmap, requestId, onProgress)) {
                    return Result.Failure(FailureReason.CANCELLED)
                }
            }

            if (isCancelled()) {
                return Result.Failure(FailureReason.CANCELLED)
            }

            reportProgress(requestId, Stage.WRITING, 95, onProgress)
            val result = writeJpeg(
                bitmap = bitmap,
                destinationUri = destinationUri,
                quality = request.quality.jpegQuality,
                editSessionPayload = request.editSessionPayload,
                iccProfile = request.iccProfile
            )
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
        sourceWidth: Int,
        sourceHeight: Int,
        crop: PixelCrop,
        requestId: Int,
        onProgress: (Stage, Int) -> Unit
    ): Boolean {
        val tiles = ExportTiling.plan(bitmap.width, bitmap.height)
        var buffer = IntArray(0)
        var inner = IntArray(0)
        val halo = 3
        val sourceScaleX = crop.width.toFloat() / bitmap.width
        val sourceScaleY = crop.height.toFloat() / bitmap.height
        for ((index, tile) in tiles.withIndex()) {
            if (isCancelled()) {
                return false
            }
            val left = (tile.left - halo).coerceAtLeast(0)
            val top = (tile.top - halo).coerceAtLeast(0)
            val right = (tile.left + tile.width + halo).coerceAtMost(bitmap.width)
            val bottom = (tile.top + tile.height + halo).coerceAtMost(bitmap.height)
            val expandedWidth = right - left
            val expandedHeight = bottom - top
            val expandedCount = expandedWidth * expandedHeight
            if (buffer.size < expandedCount) {
                buffer = IntArray(expandedCount)
            }
            bitmap.getPixels(
                buffer,
                0,
                expandedWidth,
                left,
                top,
                expandedWidth,
                expandedHeight
            )
            AdjustmentMath.applyInPlace(buffer, adjustments, expandedCount)
            FilterMath.applyInPlace(buffer, filter, expandedCount)
            PhotoEffectsMath.applyInPlace(
                buffer, expandedWidth, expandedHeight, effects,
                left, top, sourceWidth, sourceHeight,
                crop.left.toFloat(), crop.top.toFloat(),
                sourceScaleX, sourceScaleY
            )
            ColorGradingMath.applyInPlace(buffer, colorGrading, expandedCount)
            CurvesMath.applyInPlace(buffer, curves, expandedCount)

            if (inner.size < tile.pixelCount) {
                inner = IntArray(tile.pixelCount)
            }
            for (row in 0 until tile.height) {
                val sourceOffset =
                    (tile.top - top + row) * expandedWidth + (tile.left - left)
                System.arraycopy(
                    buffer, sourceOffset, inner, row * tile.width, tile.width
                )
            }
            bitmap.setPixels(
                inner,
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
        crop: CropSelection,
        sourceWidth: Int,
        sourceHeight: Int
    ): Boolean {
        val layers = masking.layers
            .filter {
                it.type == masking.selectedMask &&
                    it.enabled && it.selection != null && !it.adjustments.isNeutral()
            }
        if (layers.isEmpty()) return true

        val tiles = ExportTiling.plan(bitmap.width, bitmap.height)
        val halo = 3
        val pixelCrop = ExportPlanner.pixelCrop(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            crop = crop
        )
        val sourceScaleX = pixelCrop.width.toFloat() / bitmap.width
        val sourceScaleY = pixelCrop.height.toFloat() / bitmap.height
        var pixels = IntArray(0)
        var adjusted = IntArray(0)
        var inner = IntArray(0)

        for (layer in layers) {
            if (isCancelled()) return false
            val selection = layer.selection ?: continue

            for (tile in tiles) {
                if (isCancelled()) return false

                val left = (tile.left - halo).coerceAtLeast(0)
                val top = (tile.top - halo).coerceAtLeast(0)
                val right = (tile.left + tile.width + halo).coerceAtMost(bitmap.width)
                val bottom = (tile.top + tile.height + halo).coerceAtMost(bitmap.height)
                val expandedWidth = right - left
                val expandedHeight = bottom - top
                val expandedCount = expandedWidth * expandedHeight

                if (pixels.size < expandedCount) {
                    pixels = IntArray(expandedCount)
                }
                bitmap.getPixels(
                    pixels, 0, expandedWidth,
                    left, top, expandedWidth, expandedHeight
                )

                if (adjusted.size < expandedCount) {
                    adjusted = IntArray(expandedCount)
                }
                System.arraycopy(pixels, 0, adjusted, 0, expandedCount)
                AdjustmentMath.applyInPlace(
                    adjusted, layer.adjustments.adjustments, expandedCount
                )
                FilterMath.applyInPlace(
                    adjusted, layer.adjustments.filter, expandedCount
                )
                PhotoEffectsMath.applyInPlace(
                    adjusted, expandedWidth, expandedHeight, layer.adjustments.effects,
                    left, top, sourceWidth, sourceHeight,
                    pixelCrop.left.toFloat(), pixelCrop.top.toFloat(),
                    sourceScaleX, sourceScaleY
                )
                ColorGradingMath.applyInPlace(
                    adjusted, layer.adjustments.colorGrading, expandedCount
                )
                CurvesMath.applyInPlace(
                    adjusted, layer.adjustments.curves, expandedCount
                )
                if (!layer.adjustments.healing.isNeutral()) {
                    val tileHealing = mapHealingToTile(
                        layer.adjustments.healing, crop,
                        sourceWidth, sourceHeight,
                        bitmap.width, bitmap.height,
                        left, top, expandedWidth, expandedHeight
                    )
                    if (!tileHealing.isNeutral()) {
                        HealingMath.applyInPlace(
                            adjusted, expandedWidth, expandedHeight, tileHealing
                        )
                    }
                }

                for (localY in 0 until tile.height) {
                    val outputY = tile.top + localY
                    val sourceY = pixelCrop.top +
                        ((outputY + 0.5f) / bitmap.height) * pixelCrop.height
                    val maskY = sourceY * selection.height

                    for (localX in 0 until tile.width) {
                        val outputX = tile.left + localX
                        val sourceX = pixelCrop.left +
                            ((outputX + 0.5f) / bitmap.width) * pixelCrop.width
                        val maskX = sourceX * selection.width

                        var coverage = selection.coverageAtBilinear(maskX, maskY)
                        if (layer.inverted) coverage = 255 - coverage
                        if (coverage == 0) continue

                        val bufferIndex = (outputY - top) * expandedWidth +
                            (outputX - left)
                        val base = pixels[bufferIndex]
                        val edited = adjusted[bufferIndex]
                        val amount = coverage / 255f

                        fun blend(shift: Int): Int {
                            val a = (base shr shift) and 0xFF
                            val b = (edited shr shift) and 0xFF
                            return (a + (b - a) * amount)
                                .toInt().coerceIn(0, 255)
                        }

                        pixels[bufferIndex] = (base and -0x1000000) or
                            (blend(16) shl 16) or
                            (blend(8) shl 8) or
                            blend(0)
                    }
                }

                if (inner.size < tile.pixelCount) {
                    inner = IntArray(tile.pixelCount)
                }
                for (row in 0 until tile.height) {
                    val sourceOffset =
                        (tile.top - top + row) * expandedWidth + (tile.left - left)
                    System.arraycopy(
                        pixels, sourceOffset, inner, row * tile.width, tile.width
                    )
                }
                bitmap.setPixels(
                    inner, 0, tile.width,
                    tile.left, tile.top, tile.width, tile.height
                )
            }
        }
        return true
    }

    private fun applyColorSpaceInTiles(
        bitmap: Bitmap,
        requestId: Int,
        onProgress: (Stage, Int) -> Unit
    ): Boolean {
        val tiles = ExportTiling.plan(bitmap.width, bitmap.height)
        var buffer = IntArray(0)
        for ((index, tile) in tiles.withIndex()) {
            if (isCancelled()) return false
            if (buffer.size < tile.pixelCount) {
                buffer = IntArray(tile.pixelCount)
            }
            bitmap.getPixels(
                buffer, 0, tile.width,
                tile.left, tile.top, tile.width, tile.height
            )
            ColorSpaceConverter.srgbToAdobeRgbInPlace(buffer, tile.pixelCount)
            bitmap.setPixels(
                buffer, 0, tile.width,
                tile.left, tile.top, tile.width, tile.height
            )
            reportProgress(
                requestId,
                Stage.PROCESSING,
                90 + ((index + 1) * 5 / tiles.size),
                onProgress
            )
        }
        return true
    }

    private fun mapHealingToCrop(
        healing: HealingSettings,
        crop: CropSelection,
        sourceWidth: Int,
        sourceHeight: Int
    ): HealingSettings {
        val cropWidth = crop.width
        val cropHeight = crop.height
        if (cropWidth <= 0f || cropHeight <= 0f) return HealingSettings.NEUTRAL
        val sourceShort = minOf(sourceWidth, sourceHeight).toFloat()
        val cropShort = minOf(
            cropWidth * sourceWidth,
            cropHeight * sourceHeight
        ).coerceAtLeast(1f)
        val spots = healing.spots.mapNotNull { spot ->
            val x = (spot.x - crop.left) / cropWidth
            val y = (spot.y - crop.top) / cropHeight
            if (x < 0f || x > 1f || y < 0f || y > 1f) {
                null
            } else {
                HealingSpot(
                    x = x,
                    y = y,
                    radius = spot.radius * sourceShort / cropShort
                )
            }
        }
        return if (spots.isEmpty()) HealingSettings.NEUTRAL else HealingSettings(spots)
    }

    private fun mapHealingToTile(
        healing: HealingSettings,
        crop: CropSelection,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        tileLeft: Int,
        tileTop: Int,
        tileWidth: Int,
        tileHeight: Int
    ): HealingSettings {
        val cropWidth = crop.width
        val cropHeight = crop.height
        if (cropWidth <= 0f || cropHeight <= 0f) return HealingSettings.NEUTRAL
        val scaleX = outputWidth / cropWidth
        val scaleY = outputHeight / cropHeight
        val sourceShort = minOf(sourceWidth, sourceHeight).toFloat()
        val tileShort = minOf(tileWidth, tileHeight).toFloat().coerceAtLeast(1f)
        val spots = healing.spots.mapNotNull { spot ->
            val outputX = (spot.x - crop.left) * scaleX
            val outputY = (spot.y - crop.top) * scaleY
            val tileX = (outputX - tileLeft) / tileWidth
            val tileY = (outputY - tileTop) / tileHeight
            val outputRadius = spot.radius * sourceShort *
                (outputWidth / (cropWidth * sourceWidth))
            val tileRadius = outputRadius / tileShort
            if (tileX < 0f || tileX > 1f || tileY < 0f || tileY > 1f) {
                null
            } else {
                HealingSpot(tileX, tileY, tileRadius.coerceIn(0.005f, 0.5f))
            }
        }
        return if (spots.isEmpty()) HealingSettings.NEUTRAL else HealingSettings(spots)
    }

    private fun writeJpeg(
        bitmap: Bitmap,
        destinationUri: Uri,
        quality: Int,
        editSessionPayload: String?,
        iccProfile: ByteArray?
    ): Result {
        return try {
            if (isCancelled()) {
                return Result.Failure(FailureReason.CANCELLED)
            }
            val needsMetadata = !editSessionPayload.isNullOrBlank() ||
                (iccProfile != null && iccProfile.isNotEmpty())
            if (!needsMetadata || cacheDir == null) {
                return writeDirectJpeg(bitmap, destinationUri, quality)
            }

            val temp = File.createTempFile("photocraft-export", ".jpg", cacheDir)
            try {
                val compressed = FileOutputStream(temp).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                }
                if (!compressed) {
                    return Result.Failure(FailureReason.WRITE_FAILED)
                }
                if (isCancelled()) {
                    return Result.Failure(FailureReason.CANCELLED)
                }
                val output = resolver.openOutputStream(destinationUri, "wt")
                    ?: return Result.Failure(FailureReason.WRITE_FAILED)
                val outcome = output.use { stream ->
                    ExportWriteGuard.writeIfActive({ isCancelled() }) {
                        temp.inputStream().use { input ->
                            JpegMetadata.write(
                                input = input,
                                output = stream,
                                sessionPayload = editSessionPayload,
                                iccProfile = iccProfile,
                                isCancelled = { isCancelled() }
                            )
                        }
                    }
                }
                when (outcome) {
                    ExportWriteGuard.Outcome.CANCELLED ->
                        return Result.Failure(FailureReason.CANCELLED)
                    ExportWriteGuard.Outcome.WRITE_FAILED ->
                        return Result.Failure(
                            if (isCancelled()) {
                                FailureReason.CANCELLED
                            } else {
                                FailureReason.WRITE_FAILED
                            }
                        )
                    ExportWriteGuard.Outcome.SUCCESS -> Unit
                }
            } finally {
                temp.delete()
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

    private fun writeDirectJpeg(
        bitmap: Bitmap,
        destinationUri: Uri,
        quality: Int
    ): Result {
        return try {
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
