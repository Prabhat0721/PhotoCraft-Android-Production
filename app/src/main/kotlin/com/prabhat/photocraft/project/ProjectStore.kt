package com.prabhat.photocraft.project

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

@SuppressLint("Recycle")
class ProjectStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_PROJECTS (
                $COLUMN_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_SOURCE_PATH TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT,
                $COLUMN_WIDTH INTEGER NOT NULL DEFAULT 0,
                $COLUMN_HEIGHT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SOURCE_SIZE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                $COLUMN_LAST_OPENED_AT INTEGER NOT NULL,
                $COLUMN_EXPORTED_AT INTEGER,
                $COLUMN_EXPORT_URI TEXT,
                $COLUMN_DRAFT_PATH TEXT,
                $COLUMN_THUMBNAIL_PATH TEXT,
                $COLUMN_REVISION INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX ${TABLE_PROJECTS}_opened ON $TABLE_PROJECTS " +
                "($COLUMN_LAST_OPENED_AT DESC, $COLUMN_UPDATED_AT DESC)"
        )
    }

    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    fun list(): List<Project> {
        readableDatabase.query(
            TABLE_PROJECTS,
            COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_LAST_OPENED_AT DESC, $COLUMN_UPDATED_AT DESC"
        ).use { cursor ->
            val projects = ArrayList<Project>(cursor.count)
            while (cursor.moveToNext()) {
                projects.add(readProject(cursor))
            }
            return projects
        }
    }

    fun latest(): Project? {
        readableDatabase.query(
            TABLE_PROJECTS,
            COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_LAST_OPENED_AT DESC, $COLUMN_UPDATED_AT DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readProject(cursor) else null
        }
    }

    fun get(id: String): Project? {
        readableDatabase.query(
            TABLE_PROJECTS,
            COLUMNS,
            "$COLUMN_ID = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readProject(cursor) else null
        }
    }

    fun create(project: Project): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_ID, project.id)
            put(COLUMN_DISPLAY_NAME, project.displayName)
            put(COLUMN_SOURCE_PATH, project.sourcePath)
            put(COLUMN_MIME_TYPE, project.mimeType)
            put(COLUMN_WIDTH, project.width)
            put(COLUMN_HEIGHT, project.height)
            put(COLUMN_SOURCE_SIZE, project.sourceSize)
            put(COLUMN_CREATED_AT, project.createdAt)
            put(COLUMN_UPDATED_AT, project.updatedAt)
            put(COLUMN_LAST_OPENED_AT, project.lastOpenedAt)
            put(COLUMN_EXPORTED_AT, project.exportedAt)
            put(COLUMN_EXPORT_URI, project.exportUri)
            put(COLUMN_DRAFT_PATH, project.draftPath)
            put(COLUMN_THUMBNAIL_PATH, project.thumbnailPath)
            put(COLUMN_REVISION, project.revision)
        }
        return writableDatabase.insert(TABLE_PROJECTS, null, values) != -1L
    }

    fun updateSourceInfo(
        id: String,
        displayName: String,
        width: Int,
        height: Int,
        thumbnailPath: String?
    ): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_DISPLAY_NAME, displayName)
            put(COLUMN_WIDTH, width)
            put(COLUMN_HEIGHT, height)
            if (thumbnailPath != null) {
                put(COLUMN_THUMBNAIL_PATH, thumbnailPath)
            }
        }
        return update(id, values)
    }

    fun saveDraft(
        id: String,
        draftPath: String,
        revision: Int,
        updatedAt: Long
    ): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_DRAFT_PATH, draftPath)
            put(COLUMN_REVISION, revision)
            put(COLUMN_UPDATED_AT, updatedAt)
        }
        return update(id, values)
    }

    fun markOpened(id: String, openedAt: Long): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_LAST_OPENED_AT, openedAt)
            put(COLUMN_UPDATED_AT, openedAt)
        }
        return update(id, values)
    }

    fun markExported(id: String, exportUri: String?, exportedAt: Long): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_EXPORTED_AT, exportedAt)
            put(COLUMN_EXPORT_URI, exportUri)
            put(COLUMN_UPDATED_AT, exportedAt)
        }
        return update(id, values)
    }

    fun delete(id: String): Boolean =
        writableDatabase.delete(TABLE_PROJECTS, "$COLUMN_ID = ?", arrayOf(id)) > 0

    private fun update(id: String, values: ContentValues): Boolean =
        writableDatabase.update(
            TABLE_PROJECTS,
            values,
            "$COLUMN_ID = ?",
            arrayOf(id)
        ) > 0

    private fun readProject(cursor: Cursor): Project = Project(
        id = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ID)),
        displayName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)),
        sourcePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE_PATH)),
        mimeType = cursor.getStringOrNull(COLUMN_MIME_TYPE),
        width = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_WIDTH)),
        height = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_HEIGHT)),
        sourceSize = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_SOURCE_SIZE)),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
        lastOpenedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LAST_OPENED_AT)),
        exportedAt = cursor.getLongOrNull(COLUMN_EXPORTED_AT),
        exportUri = cursor.getStringOrNull(COLUMN_EXPORT_URI),
        draftPath = cursor.getStringOrNull(COLUMN_DRAFT_PATH),
        thumbnailPath = cursor.getStringOrNull(COLUMN_THUMBNAIL_PATH),
        revision = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_REVISION))
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private companion object {
        const val DATABASE_NAME = "photocraft_projects.db"
        const val DATABASE_VERSION = 1

        const val TABLE_PROJECTS = "projects"
        const val COLUMN_ID = "id"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_SOURCE_PATH = "source_path"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_WIDTH = "width"
        const val COLUMN_HEIGHT = "height"
        const val COLUMN_SOURCE_SIZE = "source_size"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_LAST_OPENED_AT = "last_opened_at"
        const val COLUMN_EXPORTED_AT = "exported_at"
        const val COLUMN_EXPORT_URI = "export_uri"
        const val COLUMN_DRAFT_PATH = "draft_path"
        const val COLUMN_THUMBNAIL_PATH = "thumbnail_path"
        const val COLUMN_REVISION = "revision"

        val COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_SOURCE_PATH,
            COLUMN_MIME_TYPE,
            COLUMN_WIDTH,
            COLUMN_HEIGHT,
            COLUMN_SOURCE_SIZE,
            COLUMN_CREATED_AT,
            COLUMN_UPDATED_AT,
            COLUMN_LAST_OPENED_AT,
            COLUMN_EXPORTED_AT,
            COLUMN_EXPORT_URI,
            COLUMN_DRAFT_PATH,
            COLUMN_THUMBNAIL_PATH,
            COLUMN_REVISION
        )
    }
}
