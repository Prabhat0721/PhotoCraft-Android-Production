package com.prabhat.photocraft.project

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("Recycle")
class ProjectImporter(
    context: Context,
    private val store: ProjectStore
) : Closeable {
    sealed class Result {
        data class Success(val project: Project) : Result()
        data class Failure(val reason: FailureReason) : Result()
    }

    enum class FailureReason {
        INVALID_URI,
        ACCESS_DENIED,
        INSUFFICIENT_STORAGE,
        DECODE_FAILED,
        UNSUPPORTED_OR_CORRUPT,
        TOO_LARGE,
        COPY_FAILED
    }

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    fun import(uri: Uri, callback: (Result) -> Unit) {
        if (closed.get()) return
        executor.execute {
            val result = importNow(uri)
            mainHandler.post {
                if (!closed.get()) {
                    callback(result)
                } else {
                    (result as? Result.Success)?.project?.let {
                        ProjectFiles.deleteProject(appContext, it.id)
                        store.delete(it.id)
                    }
                }
            }
        }
    }

    private fun importNow(uri: Uri): Result {
        if (uri.scheme !in SUPPORTED_SCHEMES) {
            return Result.Failure(FailureReason.INVALID_URI)
        }

        val projectId = UUID.randomUUID().toString()
        val metadata = readMetadata(uri)
        val displayName = metadata.name?.takeIf { it.isNotBlank() } ?: "Photo"
        val extension = ProjectFiles.sanitizeExtension(
            ProjectFiles.extensionFromName(displayName)
                ?: metadata.mimeType?.let {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                }
        )

        return try {
            ProjectFiles.prepareDirectory(appContext, projectId)
            val required = if (metadata.size > 0L) {
                metadata.size + MIN_FREE_SPACE_BYTES
            } else {
                MIN_FREE_SPACE_BYTES * 2
            }
            val available = StatFs(
                ProjectFiles.projectDirectory(appContext, projectId).absolutePath
            ).availableBytes
            if (available < required) {
                ProjectFiles.deleteProject(appContext, projectId)
                return Result.Failure(FailureReason.INSUFFICIENT_STORAGE)
            }

            val part = ProjectFiles.sourcePartFile(appContext, projectId, extension)
            val source = ProjectFiles.sourceFile(appContext, projectId, extension)
            copySource(uri, part)
            val sourceSize = part.length()

            val thumbnail = decodeThumbnail(part)
                ?: run {
                    ProjectFiles.deleteProject(appContext, projectId)
                    return Result.Failure(FailureReason.UNSUPPORTED_OR_CORRUPT)
                }

            val thumbFile = ProjectFiles.thumbnailFile(appContext, projectId)
            val thumbPart = File(thumbFile.parentFile, "${thumbFile.name}.part")
            val thumbSaved = writeThumbnail(thumbnail.bitmap, thumbPart)
            val width = thumbnail.width
            val height = thumbnail.height
            thumbnail.bitmap.recycle()
            if (!thumbSaved || !thumbPart.renameTo(thumbFile)) {
                thumbPart.delete()
                ProjectFiles.deleteProject(appContext, projectId)
                return Result.Failure(FailureReason.COPY_FAILED)
            }

            if (!part.renameTo(source)) {
                ProjectFiles.deleteProject(appContext, projectId)
                return Result.Failure(FailureReason.COPY_FAILED)
            }

            val now = System.currentTimeMillis()
            val project = Project(
                id = projectId,
                displayName = displayName,
                sourcePath = source.absolutePath,
                mimeType = metadata.mimeType,
                width = width,
                height = height,
                sourceSize = sourceSize,
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = now,
                exportedAt = null,
                exportUri = null,
                draftPath = null,
                thumbnailPath = thumbFile.absolutePath,
                revision = 0
            )
            if (!store.create(project)) {
                ProjectFiles.deleteProject(appContext, projectId)
                Result.Failure(FailureReason.COPY_FAILED)
            } else {
                Result.Success(project)
            }
        } catch (_: SecurityException) {
            ProjectFiles.deleteProject(appContext, projectId)
            Result.Failure(FailureReason.ACCESS_DENIED)
        } catch (_: ImageDecoder.DecodeException) {
            ProjectFiles.deleteProject(appContext, projectId)
            Result.Failure(FailureReason.UNSUPPORTED_OR_CORRUPT)
        } catch (_: OutOfMemoryError) {
            ProjectFiles.deleteProject(appContext, projectId)
            Result.Failure(FailureReason.TOO_LARGE)
        } catch (_: IOException) {
            ProjectFiles.deleteProject(appContext, projectId)
            Result.Failure(FailureReason.COPY_FAILED)
        } catch (_: RuntimeException) {
            ProjectFiles.deleteProject(appContext, projectId)
            Result.Failure(FailureReason.DECODE_FAILED)
        }
    }

    private fun copySource(uri: Uri, destination: File) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not open source")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                source.copyTo(output, COPY_BUFFER_BYTES)
                output.fd.sync()
            }
        }
    }

    private data class Thumbnail(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int
    )

    private fun decodeThumbnail(file: File): Thumbnail? {
        var infoWidth = 0
        var infoHeight = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {
                decoder,
                info,
                _ ->
            infoWidth = info.size.width
            infoHeight = info.size.height
            val longestEdge = max(infoWidth, infoHeight)
            if (longestEdge > THUMBNAIL_MAX_EDGE && longestEdge > 0) {
                val scale = THUMBNAIL_MAX_EDGE.toFloat() / longestEdge
                decoder.setTargetSize(
                    max(1, (infoWidth * scale).roundToInt()),
                    max(1, (infoHeight * scale).roundToInt())
                )
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            return null
        }
        val sourceSize = orientedSourceSize(bitmap, infoWidth, infoHeight)
        return Thumbnail(bitmap, sourceSize.first, sourceSize.second)
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

    private fun writeThumbnail(bitmap: Bitmap, file: File): Boolean {
        return try {
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)) {
                    return false
                }
                output.fd.sync()
            }
            true
        } catch (_: Exception) {
            file.delete()
            false
        }
    }

    private data class Metadata(
        val name: String?,
        val size: Long,
        val mimeType: String?
    )

    private fun readMetadata(uri: Uri): Metadata {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
        }
        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return Metadata(
            name = name ?: fallbackName,
            size = size,
            mimeType = try {
                resolver.getType(uri)
            } catch (_: Exception) {
                null
            }
        )
    }

    override fun close() {
        closed.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private companion object {
        val SUPPORTED_SCHEMES = setOf("content", "file")
        const val THUMBNAIL_MAX_EDGE = 512
        const val THUMBNAIL_QUALITY = 86
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L
    }
}
