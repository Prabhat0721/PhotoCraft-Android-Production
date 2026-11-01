package com.prabhat.photocraft.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.prabhat.photocraft.export.JpegMetadata
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class PhotoLoader(private val resolver: ContentResolver) : Closeable {
    sealed class Result {
        data class Success(
            val bitmap: Bitmap,
            val displayName: String?,
            val sourceWidth: Int,
            val sourceHeight: Int,
            val embeddedEditSession: String? = null
        ) : Result()

        data class Failure(val reason: FailureReason) : Result()
    }

    enum class FailureReason {
        INVALID_URI,
        ACCESS_DENIED,
        DECODE_FAILED,
        UNSUPPORTED_OR_CORRUPT,
        TOO_LARGE
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val requestSequence = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    fun load(uri: Uri, maxEdgePx: Int, callback: (Result) -> Unit) {
        if (closed.get()) {
            return
        }

        val requestId = requestSequence.incrementAndGet()
        executor.execute {
            val result = decode(uri, maxEdgePx)
            if (closed.get() || requestId != requestSequence.get()) {
                recycleIfUnused(result)
                return@execute
            }

            mainHandler.post {
                if (closed.get() || requestId != requestSequence.get()) {
                    recycleIfUnused(result)
                    return@post
                }
                callback(result)
            }
        }
    }

    private fun decode(uri: Uri, maxEdgePx: Int): Result {
        if (uri.scheme !in SUPPORTED_SCHEMES) {
            return Result.Failure(FailureReason.INVALID_URI)
        }

        return try {
            val source = ImageDecoder.createSource(resolver, uri)
            var infoWidth = 0
            var infoHeight = 0
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                infoWidth = size.width
                infoHeight = size.height
                val longestEdge = max(size.width, size.height)
                if (longestEdge > maxEdgePx && longestEdge > 0) {
                    val scale = maxEdgePx.toFloat() / longestEdge
                    decoder.setTargetSize(
                        max(1, (size.width * scale).roundToInt()),
                        max(1, (size.height * scale).roundToInt())
                    )
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            }

            if (bitmap.width <= 0 || bitmap.height <= 0) {
                bitmap.recycle()
                Result.Failure(FailureReason.UNSUPPORTED_OR_CORRUPT)
            } else {
                val sourceSize = orientedSourceSize(bitmap, infoWidth, infoHeight)
                Result.Success(
                    bitmap = bitmap,
                    displayName = readDisplayName(uri),
                    sourceWidth = sourceSize.first,
                    sourceHeight = sourceSize.second,
                    embeddedEditSession = readEmbeddedEditSession(uri)
                )
            }
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
        }
    }

    private fun readEmbeddedEditSession(uri: Uri): String? {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                JpegMetadata.readSession(stream)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun orientedSourceSize(
        bitmap: Bitmap,
        infoWidth: Int,
        infoHeight: Int
    ): Pair<Int, Int> {
        if (infoWidth <= 0 || infoHeight <= 0) {
            return bitmap.width to bitmap.height
        }
        val previewAspect = bitmap.width.toDouble() / bitmap.height
        val directDifference = abs(infoWidth.toDouble() / infoHeight - previewAspect)
        val swappedDifference = abs(infoHeight.toDouble() / infoWidth - previewAspect)
        return if (directDifference <= swappedDifference) {
            infoWidth to infoHeight
        } else {
            infoHeight to infoWidth
        }
    }

    private fun readDisplayName(uri: Uri): String? {
        var name: String? = null

        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && !cursor.isNull(column)) {
                        name = cursor.getString(column)
                    }
                }
            }
        } catch (_: SecurityException) {
            name = null
        } catch (_: IllegalArgumentException) {
            name = null
        } catch (_: RuntimeException) {
            name = null
        }

        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun recycleIfUnused(result: Result) {
        if (result is Result.Success) {
            result.bitmap.recycle()
        }
    }

    override fun close() {
        closed.set(true)
        requestSequence.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private companion object {
        val SUPPORTED_SCHEMES = setOf("content", "file", "android.resource")
    }
}
