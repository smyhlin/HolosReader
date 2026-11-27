package com.holos.reader.tts

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-book offline audio storage inventory and safe, generation-aware cache pruning (#53).
 * Groups `.wav`, `.idx`, `.ready`, and `.part` sidecars into logical chapter generations,
 * distinguishes valid current renders from stale/malformed caches, and skips files actively
 * in use by playback or offline rendering.
 */
object OfflineStorageManager {

    private val CACHE_FILE_RE = Regex("""^(.+)_ch(\d+)_([0-9a-fA-F]+)\.wav(?:\.(?:idx|ready|part|idx\.part|ready\.part))?$""")

    private val lock = Any()
    private val readers = HashMap<String, Int>()
    private val writers = HashSet<String>()

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    fun acquireRead(wavFile: File): Lease? = synchronized(lock) {
        val path = wavFile.absolutePath
        if (path in writers) return@synchronized null
        readers[path] = (readers[path] ?: 0) + 1
        Lease {
            synchronized(lock) {
                val count = readers.getValue(path)
                if (count == 1) readers.remove(path) else readers[path] = count - 1
            }
        }
    }

    fun acquireWrite(wavFile: File): Lease? = synchronized(lock) {
        val path = wavFile.absolutePath
        if (path in readers || !writers.add(path)) return@synchronized null
        Lease { synchronized(lock) { writers.remove(path) } }
    }

    data class GenerationGroup(
        val bookId: String,
        val chapterIndex: Int,
        val recipeHex: String,
        val wavFile: File,
        val files: List<File>,
        val totalBytes: Long,
        val isValid: Boolean,
        val isCurrentRecipe: Boolean,
        val lastModified: Long,
    )

    data class BookStorageSummary(
        val bookId: String,
        val totalBytes: Long,
        val validChapterCount: Int,
        val validBytes: Long,
        val staleBytes: Long,
        val generations: List<GenerationGroup>,
    )

    data class DeletionResult(
        val freedBytes: Long,
        val deletedFiles: Int,
        val skippedActiveFiles: Int,
        val failedFiles: Int,
    )

    fun scan(
        directories: List<File>,
        currentRecipeHex: String? = null,
    ): List<BookStorageSummary> {
        val grouped = LinkedHashMap<String, MutableList<File>>()
        val orphans = ArrayList<File>()

        for (dir in directories) {
            val list = dir.listFiles() ?: continue
            for (f in list) {
                if (!f.isFile) continue
                val m = CACHE_FILE_RE.matchEntire(f.name)
                if (m != null) {
                    val baseWavPath = File(dir, "${m.groupValues[1]}_ch${m.groupValues[2]}_${m.groupValues[3]}.wav").absolutePath
                    grouped.getOrPut(baseWavPath) { ArrayList(4) }.add(f)
                } else {
                    orphans.add(f)
                }
            }
        }

        val byBook = LinkedHashMap<String, MutableList<GenerationGroup>>()
        for ((wavPath, files) in grouped) {
            val wavFile = File(wavPath)
            val m = CACHE_FILE_RE.matchEntire(wavFile.name) ?: continue
            val bookId = m.groupValues[1]
            val chIdx = m.groupValues[2].toIntOrNull() ?: -1
            val recipeHex = m.groupValues[3].lowercase()
            val valid = OfflineAudioCache.validate(wavFile) != null
            val isCurrent = currentRecipeHex == null || recipeHex.equals(currentRecipeHex, ignoreCase = true)
            val bytes = files.sumOf { it.length() }
            val mtime = files.maxOfOrNull { it.lastModified() } ?: 0L
            byBook.getOrPut(bookId) { ArrayList() }.add(
                GenerationGroup(
                    bookId = bookId,
                    chapterIndex = chIdx,
                    recipeHex = recipeHex,
                    wavFile = wavFile,
                    files = files,
                    totalBytes = bytes,
                    isValid = valid,
                    isCurrentRecipe = isCurrent,
                    lastModified = mtime,
                )
            )
        }

        if (orphans.isNotEmpty()) {
            val bytes = orphans.sumOf { it.length() }
            val mtime = orphans.maxOfOrNull { it.lastModified() } ?: 0L
            byBook.getOrPut("__orphan__") { ArrayList() }.add(
                GenerationGroup(
                    bookId = "__orphan__",
                    chapterIndex = -1,
                    recipeHex = "",
                    wavFile = orphans.first(),
                    files = orphans,
                    totalBytes = bytes,
                    isValid = false,
                    isCurrentRecipe = false,
                    lastModified = mtime,
                )
            )
        }

        return byBook.map { (bookId, gens) ->
            var validBytes = 0L
            var staleBytes = 0L
            val validChapters = HashSet<Int>()
            for (g in gens) {
                if (g.isValid && g.isCurrentRecipe) {
                    validBytes += g.totalBytes
                    if (g.chapterIndex >= 0) validChapters.add(g.chapterIndex)
                } else {
                    staleBytes += g.totalBytes
                }
            }
            BookStorageSummary(
                bookId = bookId,
                totalBytes = validBytes + staleBytes,
                validChapterCount = validChapters.size,
                validBytes = validBytes,
                staleBytes = staleBytes,
                generations = gens.sortedBy { it.chapterIndex },
            )
        }.sortedByDescending { it.totalBytes }
    }

    fun deleteBook(
        directories: List<File>,
        bookId: String,
        protectedWavPaths: Set<String> = emptySet(),
        deleter: (File) -> Boolean = { it.delete() },
    ): DeletionResult {
        val summary = scan(directories).firstOrNull { it.bookId == bookId }
            ?: return DeletionResult(0L, 0, 0, 0)
        return deleteGenerations(summary.generations, protectedWavPaths, deleter)
    }

    /**
     * Prunes stale/invalid generations (never touching valid current-recipe chapters).
     * If [maxStaleBytes] > 0, prunes oldest stale generations until stale bytes <= [maxStaleBytes].
     */
    fun pruneStale(
        directories: List<File>,
        currentRecipeHex: String? = null,
        maxStaleBytes: Long = 0L,
        protectedWavPaths: Set<String> = emptySet(),
        deleter: (File) -> Boolean = { it.delete() },
    ): DeletionResult {
        val allStale = scan(directories, currentRecipeHex)
            .flatMap { it.generations }
            .filter { !it.isValid || !it.isCurrentRecipe }
            .sortedBy { it.lastModified }

        if (maxStaleBytes <= 0L) {
            return deleteGenerations(allStale, protectedWavPaths, deleter)
        }

        var remainingStale = allStale.sumOf { it.totalBytes }
        val toDelete = ArrayList<GenerationGroup>()
        for (g in allStale) {
            if (remainingStale <= maxStaleBytes) break
            toDelete.add(g)
            remainingStale -= g.totalBytes
        }
        return deleteGenerations(toDelete, protectedWavPaths, deleter)
    }

    fun clearAll(
        directories: List<File>,
        protectedWavPaths: Set<String> = emptySet(),
        deleter: (File) -> Boolean = { it.delete() },
    ): DeletionResult {
        val allGens = scan(directories).flatMap { it.generations }
        return deleteGenerations(allGens, protectedWavPaths, deleter)
    }

    private fun deleteGenerations(
        generations: List<GenerationGroup>,
        protectedWavPaths: Set<String>,
        deleter: (File) -> Boolean,
    ): DeletionResult {
        var freed = 0L
        var deleted = 0
        var skipped = 0
        var failed = 0
        for (g in generations) {
            synchronized(lock) {
                val path = g.wavFile.absolutePath
                if (path in readers || path in writers || path in protectedWavPaths) {
                    skipped += g.files.size
                } else {
                    for (f in g.files) {
                        val len = f.length()
                        if (deleter(f)) {
                            freed += len
                            deleted++
                        } else {
                            failed++
                        }
                    }
                }
            }
        }
        return DeletionResult(freed, deleted, skipped, failed)
    }
}
