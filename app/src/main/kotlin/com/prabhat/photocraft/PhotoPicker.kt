package com.prabhat.photocraft

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.MediaStore

object PhotoPicker {
    private const val MIME_TYPE = "image/*"

    @SuppressLint("InlinedApi")
    fun pickerIntent(): Intent =
        Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = MIME_TYPE
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun documentIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_TYPE
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
}
