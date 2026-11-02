package com.prabhat.photocraft.project

import com.prabhat.photocraft.edit.EditSessionCodec
import com.prabhat.photocraft.edit.EditingState
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ProjectDraftCodec {
    private const val VERSION = 1

    data class Draft(
        val snapshot: EditingState.Snapshot,
        val uiState: ProjectUiState
    )

    fun write(
        file: File,
        backup: File,
        snapshot: EditingState.Snapshot,
        uiState: ProjectUiState
    ): Boolean {
        val payload = encode(snapshot, uiState) ?: return false
        val directory = file.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val temp = File(directory, "${file.name}.part")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            val hadExisting = file.exists()
            if (hadExisting) {
                backup.delete()
                if (!file.renameTo(backup)) {
                    temp.delete()
                    return false
                }
            }
            if (!temp.renameTo(file)) {
                if (hadExisting && backup.exists()) {
                    backup.renameTo(file)
                }
                temp.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    fun read(file: File, backup: File): Draft? {
        return readFile(file) ?: readFile(backup)
    }

    private fun readFile(file: File): Draft? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            decode(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(
        snapshot: EditingState.Snapshot,
        uiState: ProjectUiState
    ): ByteArray? {
        return try {
            val session = EditSessionCodec.encodeFull(snapshot) ?: return null
            val root = JSONObject().apply {
                put("v", VERSION)
                put("session", session)
                put("ui", JSONObject().apply {
                    put("category", uiState.category)
                    put("quality", uiState.qualityOrdinal)
                    put("size", uiState.sizeOrdinal)
                    put("colorSpace", uiState.colorSpaceOrdinal)
                    put("includeHistory", uiState.includeEditHistory)
                    put("healingRadius", uiState.healingRadius.toDouble())
                    put("healingTool", uiState.healingToolActive)
                    put("curveChannel", uiState.curveChannelOrdinal)
                    put("maskOverlay", uiState.maskOverlayVisible)
                    put("maskTool", uiState.maskActiveTool)
                })
            }
            gzip(root.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray): Draft? {
        return try {
            val json = GZIPInputStream(ByteArrayInputStream(bytes)).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val root = JSONObject(json)
            if (root.optInt("v", 0) != VERSION) return null
            val snapshot = EditSessionCodec.decode(root.optString("session"))
                ?: return null
            val ui = root.optJSONObject("ui")
            Draft(
                snapshot = snapshot,
                uiState = ProjectUiState(
                    category = ui?.optString("category", "LIGHT") ?: "LIGHT",
                    qualityOrdinal = ui?.optInt("quality", 0) ?: 0,
                    sizeOrdinal = ui?.optInt("size", 0) ?: 0,
                    colorSpaceOrdinal = ui?.optInt("colorSpace", 0) ?: 0,
                    includeEditHistory = ui?.optBoolean("includeHistory", true) ?: true,
                    healingRadius = ui?.optDouble("healingRadius", 0.05)
                        ?.toFloat()
                        ?.coerceIn(0.01f, 0.15f)
                        ?: 0.05f,
                    healingToolActive = ui?.optBoolean("healingTool", false) ?: false,
                    curveChannelOrdinal = ui?.optInt("curveChannel", 0) ?: 0,
                    maskOverlayVisible = ui?.optBoolean("maskOverlay", false) ?: false,
                    maskActiveTool = ui?.optString("maskTool", "Brush") ?: "Brush"
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
