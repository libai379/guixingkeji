package com.guixing.util

import android.net.Uri
import com.guixing.app

object UriUtils {
    fun uri2Bytes(uri: Uri): ByteArray {
        app.contentResolver.openInputStream(uri)?.use {
            return it.readBytes()
        }
        return ByteArray(0)
    }
}