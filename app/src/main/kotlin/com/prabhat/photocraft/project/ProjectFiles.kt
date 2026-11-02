package com.prabhat.photocraft.project

import android.content.Context
import java.io.File

object ProjectFiles {
    private const val ROOT_DIRECTORY = "projects"
    private const val SOURCE_FILE = "source"
    private const val DRAFT_FILE = "draft.json.gz"
    private const val DRAFT_BACKUP_FILE = "draft.json.gz.bak"
    private const val THUMBNAIL_FILE = "thumb.jpg"

    fun root(context: Context): File = File(context.filesDir, ROOT_DIRECTORY)

    fun projectDirectory(context: Context, projectId: String): File =
        File(root(context), projectId)

    fun sourceFile(context: Context, projectId: String, extension: String): File =
        File(projectDirectory(context, projectId), "$SOURCE_FILE.$extension")

    fun sourcePartFile(
        context: Context,
        projectId: String,
        extension: String
    ): File = File(projectDirectory(context, projectId), "$SOURCE_FILE.$extension.part")

    fun draftFile(context: Context, projectId: String): File =
        File(projectDirectory(context, projectId), DRAFT_FILE)

    fun draftBackupFile(context: Context, projectId: String): File =
        File(projectDirectory(context, projectId), DRAFT_BACKUP_FILE)

    fun thumbnailFile(context: Context, projectId: String): File =
        File(projectDirectory(context, projectId), THUMBNAIL_FILE)

    fun prepareDirectory(context: Context, projectId: String): File {
        val directory = projectDirectory(context, projectId)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create project directory")
        }
        return directory
    }

    fun deleteProject(context: Context, projectId: String): Boolean {
        val directory = projectDirectory(context, projectId)
        if (!directory.exists()) return true
        return directory.deleteRecursively()
    }

    fun cleanupPartFiles(context: Context) {
        val root = root(context)
        if (!root.exists()) return
        root.listFiles()?.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            directory.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part")) {
                    file.delete()
                }
            }
        }
    }

    fun sanitizeExtension(extension: String?): String {
        val value = extension
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?.filter { it.isLetterOrDigit() }
            .orEmpty()
        return value.takeIf { it.length in 1..8 } ?: "img"
    }

    fun extensionFromName(name: String?): String? {
        val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: return null
        val index = leaf.lastIndexOf('.')
        if (index <= 0 || index == leaf.lastIndex) return null
        return leaf.substring(index + 1)
    }
}
