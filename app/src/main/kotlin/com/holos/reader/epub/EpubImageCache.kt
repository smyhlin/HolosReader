package com.holos.reader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * Keeps a single open [ZipFile] handle for the active book (avoiding expensive 6,000-entry
 * ZIP central-directory re-parses) and decodes/caches downsampled inline illustrations via [LruCache].
 */
class EpubSession(epubFile: File) : Closeable {

    val zip: ZipFile = ZipFile(epubFile)
    private val cacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceIn(8192, 65536)
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun loadImage(entryPath: String, maxWidthPx: Int): Bitmap? {
        val key = "$entryPath@$maxWidthPx"
        bitmapCache.get(key)?.let { return it }

        val bytes = synchronized(zip) {
            EpubStreamingParser.extractEntryBytes(zip, entryPath)
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxWidthPx * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        bitmapCache.put(key, bmp)
        return bmp
    }

    override fun close() {
        bitmapCache.evictAll()
        try { zip.close() } catch (_: Exception) {}
    }
}
