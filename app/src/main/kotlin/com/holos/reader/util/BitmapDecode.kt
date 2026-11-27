package com.holos.reader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decodes a cover only as large as the UI needs, so a full-resolution EPUB cover cannot stall the main thread or the notification. */
object BitmapDecode {
    fun decodeFileBounded(path: String, maxEdgePx: Int): Bitmap? {
        if (maxEdgePx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdgePx || bounds.outHeight / sample > maxEdgePx) {
            val next = sample * 2
            if (next <= 0) break
            sample = next
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(path, opts)
    }
}
