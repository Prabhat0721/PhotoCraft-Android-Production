package com.prabhat.photocraft.project

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.prabhat.photocraft.edit.EditingState
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DraftAutosave(
    context: Context,
    private val store: ProjectStore,
    private val onSaved: (projectId: String, saved: Boolean) -> Unit
) {
    private data class Pending(
        val projectId: String,
        val snapshot: EditingState.Snapshot,
        val uiState: ProjectUiState,
        val revision: Int
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var pending: Pending? = null
    private val saveRunnable = Runnable { savePending() }

    fun schedule(
        projectId: String,
        snapshot: EditingState.Snapshot,
        uiState: ProjectUiState
    ) {
        pending = Pending(
            projectId = projectId,
            snapshot = snapshot,
            uiState = uiState,
            revision = (System.currentTimeMillis() / 1000L).toInt()
        )
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, DELAY_MS)
    }

    fun flush() {
        handler.removeCallbacks(saveRunnable)
        savePending()
    }

    fun cancel() {
        handler.removeCallbacks(saveRunnable)
        pending = null
    }

    fun close() {
        handler.removeCallbacks(saveRunnable)
        pending = null
        executor.shutdownNow()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun savePending() {
        val item = pending ?: return
        pending = null
        executor.execute {
            val file = ProjectFiles.draftFile(appContext, item.projectId)
            val backup = ProjectFiles.draftBackupFile(appContext, item.projectId)
            val saved = ProjectDraftCodec.write(
                file = file,
                backup = backup,
                snapshot = item.snapshot,
                uiState = item.uiState
            )
            if (saved) {
                store.saveDraft(
                    id = item.projectId,
                    draftPath = file.absolutePath,
                    revision = item.revision,
                    updatedAt = System.currentTimeMillis()
                )
            }
            handler.post { onSaved(item.projectId, saved) }
        }
    }

    private companion object {
        const val DELAY_MS = 700L
    }
}
