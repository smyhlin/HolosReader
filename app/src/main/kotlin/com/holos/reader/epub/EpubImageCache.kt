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
/**
 * Pure-JVM bounded image sizing helper (#6): constrains width, height, and total decoded
 * pixel count using 64-bit arithmetic so tall/giant EPUB illustrations or covers cannot
 * exhaust heap memory.
 */
object BoundedImageSizing {
    const val DEFAULT_MAX_ILLUSTRATION_PIXELS: Long = 4_000_000L // ~8-16 MB decoded
    const val DEFAULT_MAX_COVER_PIXELS: Long = 500_000L          // ~1 MB RGB_565

    fun computeSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        maxWidthPx: Int,
        maxHeightPx: Int,
        maxPixels: Long = DEFAULT_MAX_ILLUSTRATION_PIXELS,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        val wLimit = maxWidthPx.coerceAtLeast(64).toLong()
        val hLimit = maxHeightPx.coerceAtLeast(64).toLong()
        val pxLimit = maxPixels.coerceAtLeast(16_384L)
        var sample = 1L
        while (sample < 1024L) {
            val outW = (srcWidth.toLong() + sample - 1L) / sample
            val outH = (srcHeight.toLong() + sample - 1L) / sample
            val outPx = outW * outH
            if (outW <= wLimit && outH <= hLimit && outPx <= pxLimit) break
            sample *= 2L
        }
        return sample.toInt().coerceAtLeast(1)
    }
}

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

    fun loadImage(
        entryPath: String,
        maxWidthPx: Int,
        maxHeightPx: Int = (maxWidthPx.coerceAtLeast(64) * 3).coerceAtMost(4096),
    ): Bitmap? {
        val safeMaxWidth = maxWidthPx.coerceIn(64, 2048)
        val safeMaxHeight = maxHeightPx.coerceIn(64, 4096)
        val key = "$entryPath@${safeMaxWidth}x$safeMaxHeight"
        bitmapCache.get(key)?.let { return it }

        val bytes = try {
            borrowZip { z -> EpubStreamingParser.extractEntryBytes(z, entryPath) }
        } catch (t: Throwable) {
            Log.w("EpubSession", "Failed reading image entry $entryPath: ${t.message}")
            null
        } ?: return null

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val hasAlpha = entryPath.endsWith(".png", ignoreCase = true) ||
                entryPath.endsWith(".webp", ignoreCase = true) ||
                entryPath.endsWith(".gif", ignoreCase = true)
            val bytesPerPx = if (hasAlpha) 4L else 2L
            val maxHeapPixels = ((Runtime.getRuntime().maxMemory() / 16L) / bytesPerPx)
                .coerceIn(500_000L, BoundedImageSizing.DEFAULT_MAX_ILLUSTRATION_PIXELS)

            val sampleSize = BoundedImageSizing.computeSampleSize(
                srcWidth = bounds.outWidth,
                srcHeight = bounds.outHeight,
                maxWidthPx = safeMaxWidth,
                maxHeightPx = safeMaxHeight,
                maxPixels = maxHeapPixels,
            )
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
            if (!isRetired) {
                bitmapCache.put(key, bmp)
            }
            bmp
        } catch (t: Throwable) {
            Log.w("EpubSession", "Bounded decode failed for $entryPath: ${t.message}")
            null
        }
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
            val safeName = entryName.substringAfterLast('/').replace(RE_UNSAFE_FILE_NAME, "_")
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
        private val RE_UNSAFE_FILE_NAME = Regex("[^a-zA-Z0-9._-]")
        private const val TAG = "EpubSession"
        private val RE_CSS_FONT_FAMILY = Regex("""font-family\s*:\s*([^;}{]+)""", RegexOption.IGNORE_CASE)
    }
}
