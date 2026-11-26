package com.holos.reader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * Keeps a single open [ZipFile] handle for the active book with a reference-counted
 * borrow/retire lifetime boundary (H02):
 * - Every read borrows the handle via [borrowZip]; new borrows return `null` once retired.
 * - [close] never blocks the UI thread waiting for in-flight reads; the last returning reader
 *   closes the underlying [ZipFile] cleanly so closed-handle crashes cannot happen.
 */
class EpubSession(private val epubFile: File) : Closeable {

    private val zip: ZipFile = ZipFile(epubFile)
    private val stateLock = Any()
    private val zipIoLock = Any()
    private var activeReaders: Int = 0
    private var retired: Boolean = false
    private var zipClosed: Boolean = false

    private val cacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceIn(8192, 65536)
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Volatile
    private var fontScanned = false
    private var embeddedRegularFont: Typeface? = null
    private var embeddedBoldFont: Typeface? = null
    private var cssHintFamily: Typeface? = null

    val isRetired: Boolean
        get() = synchronized(stateLock) { retired }

    fun <T> borrowZip(block: (ZipFile) -> T): T? {
        synchronized(stateLock) {
            if (retired || zipClosed) return null
            activeReaders++
        }
        return try {
            synchronized(zipIoLock) {
                if (zipClosed) null else block(zip)
            }
        } finally {
            var shouldCloseNow = false
            synchronized(stateLock) {
                activeReaders--
                if (retired && activeReaders == 0 && !zipClosed) {
                    zipClosed = true
                    shouldCloseNow = true
                }
            }
            if (shouldCloseNow) {
                closeZipQuietly()
            }
        }
    }

    fun loadImage(entryPath: String, maxWidthPx: Int): Bitmap? {
        val safeMaxWidth = maxWidthPx.coerceAtLeast(64)
        val key = "$entryPath@$safeMaxWidth"
        bitmapCache.get(key)?.let { return it }

        val bytes = borrowZip { z ->
            EpubStreamingParser.extractEntryBytes(z, entryPath)
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > safeMaxWidth * 2) {
            sampleSize *= 2
        }

        val hasAlpha = entryPath.endsWith(".png", ignoreCase = true) ||
            entryPath.endsWith(".webp", ignoreCase = true) ||
            entryPath.endsWith(".gif", ignoreCase = true)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        if (!isRetired) {
            bitmapCache.put(key, bmp)
        }
        return bmp
    }

    fun resolveBookTypeface(cacheDir: File, bold: Boolean): Typeface? {
        if (!fontScanned) {
            borrowZip { z ->
                if (!fontScanned) {
                    scanBookFonts(z, cacheDir)
                    fontScanned = true
                }
            }
        }
        if (bold && embeddedBoldFont != null) return embeddedBoldFont
        if (embeddedRegularFont != null) {
            return if (bold) Typeface.create(embeddedRegularFont, Typeface.BOLD) else embeddedRegularFont
        }
        val hint = cssHintFamily ?: return null
        return if (bold) Typeface.create(hint, Typeface.BOLD) else hint
    }

    private fun scanBookFonts(z: ZipFile, cacheDir: File) {
        val fontEntries = ArrayList<String>()
        val cssEntries = ArrayList<String>()
        try {
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val low = e.name.lowercase()
                when {
                    (low.endsWith(".ttf") || low.endsWith(".otf")) && e.size in 1L..16_777_216L ->
                        fontEntries.add(e.name)
                    low.endsWith(".css") && e.size in 1L..524_288L ->
                        cssEntries.add(e.name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan EPUB entries for fonts", e)
            return
        }

        if (fontEntries.isNotEmpty()) {
            val regularName = fontEntries.firstOrNull { n ->
                val l = n.lowercase()
                !l.contains("bold") && !l.contains("italic") && !l.contains("oblique") && !l.contains("mono")
            } ?: fontEntries.firstOrNull { !it.lowercase().contains("italic") } ?: fontEntries.first()

            val boldName = fontEntries.firstOrNull { n ->
                val l = n.lowercase()
                l.contains("bold") && !l.contains("italic") && !l.contains("oblique")
            }

            val fontDir = File(cacheDir, "epub_fonts").apply { mkdirs() }
            val bookPrefix = (epubFile.name + "_" + epubFile.length()).hashCode().toUInt().toString(16)
            embeddedRegularFont = extractAndLoadFont(z, fontDir, bookPrefix, regularName)
            if (boldName != null) {
                embeddedBoldFont = extractAndLoadFont(z, fontDir, bookPrefix, boldName)
            }
            if (embeddedRegularFont != null) return
        }

        for (cssName in cssEntries.take(4)) {
            val bytes = EpubStreamingParser.extractEntryBytes(z, cssName) ?: continue
            val css = String(bytes, Charsets.UTF_8)
            val m = RE_CSS_FONT_FAMILY.find(css) ?: continue
            val valLow = m.groupValues[1].lowercase()
            cssHintFamily = when {
                valLow.contains("monospace") -> Typeface.MONOSPACE
                valLow.contains("sans-serif") -> Typeface.SANS_SERIF
                valLow.contains("serif") -> Typeface.SERIF
                else -> null
            }
            if (cssHintFamily != null) break
        }
    }

    private fun extractAndLoadFont(z: ZipFile, fontDir: File, bookPrefix: String, entryName: String): Typeface? {
        return try {
            val safeName = entryName.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val target = File(fontDir, "${bookPrefix}_$safeName")
            if (!target.exists() || target.length() == 0L) {
                val bytes = EpubStreamingParser.extractEntryBytes(z, entryName) ?: return null
                target.writeBytes(bytes)
            }
            Typeface.createFromFile(target)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load embedded EPUB font $entryName", e)
            null
        }
    }

    override fun close() {
        bitmapCache.evictAll()
        var shouldCloseNow = false
        synchronized(stateLock) {
            retired = true
            if (activeReaders == 0 && !zipClosed) {
                zipClosed = true
                shouldCloseNow = true
            }
        }
        if (shouldCloseNow) {
            closeZipQuietly()
        }
    }

    private fun closeZipQuietly() {
        try {
            zip.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close EPUB zip", e)
        }
    }

    companion object {
        private const val TAG = "EpubSession"
        private val RE_CSS_FONT_FAMILY = Regex("""font-family\s*:\s*([^;}{]+)""", RegexOption.IGNORE_CASE)
    }
}
