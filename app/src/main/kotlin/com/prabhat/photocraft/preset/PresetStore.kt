package com.prabhat.photocraft.preset

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("ApplySharedPref", "UseKtx")
class PresetStore(context: Context) {
    enum class SaveResult {
        SAVED,
        DUPLICATE,
        STORAGE_FAILED
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): List<Preset> = PresetCodec.decodeAll(
        preferences.getStringSet(KEY_PRESETS, emptySet())
    )

    fun save(preset: Preset): SaveResult {
        val existing = load()
        if (existing.any { it.name.equals(preset.name, ignoreCase = true) }) {
            return SaveResult.DUPLICATE
        }
        return if (persist(existing + preset)) {
            SaveResult.SAVED
        } else {
            SaveResult.STORAGE_FAILED
        }
    }

    fun delete(name: String): Boolean {
        val existing = load()
        val remaining = existing.filterNot { it.name == name }
        if (remaining.size == existing.size) {
            return false
        }
        return persist(remaining)
    }

    private fun persist(presets: List<Preset>): Boolean = try {
        preferences.edit()
            .putStringSet(KEY_PRESETS, PresetCodec.encodeAll(presets))
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val PREFERENCES_NAME = "photocraft_presets"
        const val KEY_PRESETS = "presets_v1"
    }
}
