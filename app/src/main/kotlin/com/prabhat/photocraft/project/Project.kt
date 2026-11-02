package com.prabhat.photocraft.project

import java.io.File

data class Project(
    val id: String,
    val displayName: String,
    val sourcePath: String,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val sourceSize: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val exportedAt: Long?,
    val exportUri: String?,
    val draftPath: String?,
    val thumbnailPath: String?,
    val revision: Int
) {
    val sourceFile: File
        get() = File(sourcePath)

    val draftFile: File?
        get() = draftPath?.let(::File)

    val thumbnailFile: File?
        get() = thumbnailPath?.let(::File)

    val isExported: Boolean
        get() = exportedAt != null
}
