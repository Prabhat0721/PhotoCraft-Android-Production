package com.prabhat.photocraft.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

object GalleryExportTarget {
    private const val RELATIVE_PATH = "Pictures/PhotoCraft"

    fun create(resolver: ContentResolver, displayName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, ExportSettings.JPEG_MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(
                    MediaStore.Images.Media.DATE_TAKEN,
                    System.currentTimeMillis()
                )
            }
            resolver.insert(
                MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                ),
                values
            )
        } catch (_: Exception) {
            null
        }
    }

    fun publish(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun discard(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}
