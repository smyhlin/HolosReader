package com.holos.reader.epub

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream

/**
 * Local-first SQLite library & 6,000+ chapter index:
 * - Instant O(1) chapter lookup & searchable TOC across 6,000+ chapters
 * - Supports importing single `.epub`, whole directories (`ACTION_OPEN_DOCUMENT_TREE`), and pasted text
 * - Tracks local reading status, tags, bookmarks, and reading time statistics (no cloud/sync bloat)
 */
class BookDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {
    private val appCtx = context.applicationContext
    private val booksDir = File(appCtx.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(appCtx.filesDir, "covers").apply { mkdirs() }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        try { db.enableWriteAheadLogging() } catch (_: Exception) {}
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE books (
                id TEXT PRIMARY KEY,
                file_name TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                language TEXT NOT NULL,
                cover_file TEXT,
                chapter_count INTEGER NOT NULL,
                last_chapter INTEGER NOT NULL DEFAULT 0,
                last_block INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'UNREAD',
                tags TEXT NOT NULL DEFAULT '',
                reading_time_sec INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                last_read_at INTEGER NOT NULL DEFAULT 0,
                content_sha256 TEXT,
                parser_version INTEGER NOT NULL DEFAULT 1
            )
        """)
        db.execSQL("""
            CREATE TABLE spine (
                book_id TEXT NOT NULL,
                idx INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                href TEXT NOT NULL,
                media_type TEXT NOT NULL,
                PRIMARY KEY (book_id, idx)
            )
        """)
        db.execSQL("""
            CREATE TABLE toc (
                book_id TEXT NOT NULL,
                ord INTEGER NOT NULL,
                title TEXT NOT NULL,
                href TEXT NOT NULL,
                spine_idx INTEGER NOT NULL,
                depth INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (book_id, ord)
            )
        """)
        db.execSQL("CREATE INDEX idx_toc_spine ON toc(book_id, spine_idx)")
        db.execSQL("""
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_idx INTEGER NOT NULL,
                block_idx INTEGER NOT NULL,
                excerpt TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_bm_book ON bookmarks(book_id)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS reading_activity (
                day_key TEXT PRIMARY KEY,
                seconds INTEGER NOT NULL DEFAULT 0,
                pages INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    private fun existingColumns(db: SQLiteDatabase, table: String): Set<String> {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) cols.add(c.getString(1))
        }
        return cols
    }

    private fun ensureBookColumn(db: SQLiteDatabase, cols: MutableSet<String>, name: String, ddl: String) {
        if (name !in cols) {
            db.execSQL("ALTER TABLE books ADD COLUMN $name $ddl")
            cols.add(name)
        }
    }

    private fun upsertReadingActivity(db: SQLiteDatabase, dayKey: String, addSec: Long, addPages: Long) {
        // Stock Android 10 (API 29) ships SQLite 3.22.0 which does NOT support UPSERT (ON CONFLICT DO UPDATE).
        db.execSQL(
            "INSERT OR IGNORE INTO reading_activity(day_key, seconds, pages) VALUES(?, 0, 0)",
            arrayOf<Any>(dayKey),
        )
        db.execSQL(
            "UPDATE reading_activity SET seconds = seconds + ?, pages = pages + ? WHERE day_key = ?",
            arrayOf<Any>(addSec, addPages, dayKey),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val bookCols = existingColumns(db, "books").toMutableSet()
        if (oldVersion < 2) {
            ensureBookColumn(db, bookCols, "description", "TEXT NOT NULL DEFAULT ''")
            ensureBookColumn(db, bookCols, "status", "TEXT NOT NULL DEFAULT 'UNREAD'")
            ensureBookColumn(db, bookCols, "tags", "TEXT NOT NULL DEFAULT ''")
            ensureBookColumn(db, bookCols, "reading_time_sec", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS reading_activity (
                    day_key TEXT PRIMARY KEY,
                    seconds INTEGER NOT NULL DEFAULT 0,
                    pages INTEGER NOT NULL DEFAULT 0
                )
            """)
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            db.rawQuery("SELECT reading_time_sec, last_read_at, last_chapter FROM books WHERE reading_time_sec > 0", null).use { c ->
                while (c.moveToNext()) {
                    val sec = c.getLong(0)
                    val ts = c.getLong(1).takeIf { it > 0L } ?: System.currentTimeMillis()
                    val pages = c.getInt(2).coerceAtLeast(1).toLong()
                    val dayKey = fmt.format(Date(ts))
                    upsertReadingActivity(db, dayKey, sec, pages)
                }
            }
        }
        if (oldVersion < 4) {
            ensureBookColumn(db, bookCols, "content_sha256", "TEXT")
            ensureBookColumn(db, bookCols, "parser_version", "INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_sha256 ON books(content_sha256)")
        }
    }

    fun getBookFile(record: BookRecord): File = File(booksDir, record.fileName)

    fun getCoverFile(record: BookRecord): File? =
        record.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

    fun isDetached(record: BookRecord): Boolean = !getBookFile(record).exists()

    fun exportBookEpub(record: BookRecord, targetUri: Uri) {
        val src = getBookFile(record)
        require(src.exists() && src.length() > 0L) { "Файл книги відсутній на пристрої" }
        appCtx.contentResolver.openOutputStream(targetUri)?.use { out ->
            src.inputStream().use { input -> input.copyTo(out, 1 shl 16) }
        } ?: throw IllegalStateException("Не вдалося відкрити файл для експорту")
    }

    fun relinkBookFile(bookId: String, uri: Uri, forceLegacy: Boolean = false): BookRecord {
        val existing = getBook(bookId) ?: throw IllegalArgumentException("Книгу не знайдено")
        val staged = File(booksDir, "relink_${bookId}_${System.nanoTime()}.part")
        try {
            val sha256 = copyUriBoundedWithSha256(uri, staged)
            if (existing.contentSha256 != null) {
                require(existing.contentSha256.equals(sha256, ignoreCase = true)) {
                    "Обраний файл не збігається з оригінальною книгою (SHA-256 mismatch)"
                }
            } else {
                require(forceLegacy) {
                    "Неможливо автоматично підтвердити стару книгу без SHA-256; потрібне підтвердження"
                }
            }
            val meta = EpubStreamingParser.parsePackage(staged)
            require(meta.spine.isNotEmpty()) { "EPUB не містить жодного розділу" }
            val dest = getBookFile(existing)
            if (dest.exists()) dest.delete()
            check(staged.renameTo(dest)) { "Не вдалося прив’язати файл книги" }
            writableDatabase.update(
                "books",
                ContentValues().apply { put("content_sha256", sha256) },
                "id=?",
                arrayOf(bookId),
            )
            return getBook(bookId) ?: existing.copy(contentSha256 = sha256)
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }
    }

    private fun copyUriBoundedWithSha256(uri: Uri, target: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buf = ByteArray(1 shl 16)
        appCtx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    totalBytes += n
                    if (totalBytes > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException("Файл EPUB перевищує ліміт 512 МБ")
                    }
                    md.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
                output.fd.sync()
            }
        } ?: throw IllegalArgumentException("Не вдалося відкрити файл EPUB")
        require(totalBytes > 0L) { "Порожній файл EPUB" }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun importFromUri(uri: Uri, displayName: String?): BookRecord {
        val id = UUID.randomUUID().toString().substring(0, 12)
        val safeName = "$id.epub"
        val stagedFile = File(booksDir, "$safeName.part")
        val sha256 = try {
            copyUriBoundedWithSha256(uri, stagedFile)
        } catch (t: Throwable) {
            stagedFile.delete()
            throw t
        }
        return indexLocalEpubFile(id, safeName, stagedFile, displayName, sha256)
    }

    /**
     * Batch-imports all `.epub` files from a user-selected directory (ACTION_OPEN_DOCUMENT_TREE).
     */
    fun importFromDirectoryTree(treeUri: Uri): Int =
        importDirectoryRecursive(treeUri, DocumentsContract.getTreeDocumentId(treeUri), depth = 0)

    private fun importDirectoryRecursive(treeUri: Uri, parentDocId: String, depth: Int): Int {
        if (depth > 6) return 0
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var imported = 0
        appCtx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    imported += importDirectoryRecursive(treeUri, childId, depth + 1)
                } else if (name.endsWith(".epub", ignoreCase = true) || mime == "application/epub+zip") {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    try {
                        importFromUri(fileUri, name)
                        imported++
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return imported
    }

    /**
     * Converts pasted plain text into a valid local EPUB 3 file so it works seamlessly with
     * ReaderActivity, HolosTTS, bookmarks, and Offline Audio.
     */
    fun importFromPastedText(title: String, bodyText: String): BookRecord {
        val id = UUID.randomUUID().toString().substring(0, 12)
        val safeName = "$id.epub"
        val destFile = File(booksDir, safeName)
        val cleanTitle = title.trim().ifEmpty { "Вставлений текст" }
        val staged = File(booksDir, "$safeName.part")
        buildSingleChapterEpub(staged, cleanTitle, bodyText)
        val sha256 = computeFileSha256(staged)
        return indexLocalEpubFile(id, safeName, staged, cleanTitle, sha256)
    }

    private fun findHealthyBySha256(sha256: String): BookRecord? {
        readableDatabase.rawQuery(
            "SELECT id FROM books WHERE content_sha256=? LIMIT 1",
            arrayOf(sha256),
        ).use { c ->
            if (c.moveToFirst()) {
                val rec = getBook(c.getString(0))
                if (rec != null && !isDetached(rec)) return rec
            }
        }
        return null
    }

    private fun indexLocalEpubFile(
        id: String,
        safeName: String,
        stagedFile: File,
        displayName: String?,
        sha256: String,
    ): BookRecord {
        // Deduplicate strictly by exact content SHA-256 (only when existing file is healthy on disk)
        findHealthyBySha256(sha256)?.let { existing ->
            stagedFile.delete()
            return existing
        }

        val meta = try {
            EpubStreamingParser.parsePackage(stagedFile).also {
                require(it.spine.isNotEmpty()) { "EPUB не містить жодного розділу" }
            }
        } catch (e: Exception) {
            stagedFile.delete()
            throw e
        }

        var coverName: String? = null
        if (meta.coverEntryPath != null) {
            try {
                val bytes = ZipFile(stagedFile).use { zip ->
                    EpubStreamingParser.extractEntryBytes(zip, meta.coverEntryPath)
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    val rawExt = meta.coverEntryPath.substringAfterLast('.', "jpg").lowercase()
                    val ext = if (rawExt in setOf("jpg", "jpeg", "png", "webp", "gif")) rawExt else "jpg"
                    coverName = "$id.$ext"
                    File(coversDir, coverName).writeBytes(bytes)
                }
            } catch (_: Throwable) {
                coverName = null
            }
        }

        val destFile = File(booksDir, safeName)
        if (destFile.exists()) destFile.delete()
        if (!stagedFile.renameTo(destFile)) {
            stagedFile.delete()
            coverName?.let { File(coversDir, it).delete() }
            throw IllegalStateException("Не вдалося зберегти файл EPUB")
        }

        val fallbackTitle = displayName?.removeSuffix(".epub")?.takeIf { it.isNotBlank() }
        val resolvedTitle = meta.title.ifBlank { fallbackTitle ?: "Без назви" }

        val record = BookRecord(
            id = id,
            fileName = safeName,
            title = resolvedTitle,
            author = meta.author,
            description = meta.description.take(280),
            language = meta.language,
            coverFileName = coverName,
            chapterCount = meta.spine.size,
            status = ReadingStatus.UNREAD,
            addedAtMs = System.currentTimeMillis(),
            contentSha256 = sha256,
            parserVersion = CURRENT_PARSER_VERSION,
        )

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("books", null, ContentValues().apply {
                put("id", record.id)
                put("file_name", record.fileName)
                put("title", record.title)
                put("author", record.author)
                put("description", record.description)
                put("language", record.language)
                put("cover_file", record.coverFileName)
                put("chapter_count", record.chapterCount)
                put("last_chapter", 0)
                put("last_block", 0)
                put("status", record.status.code)
                put("tags", "")
                put("reading_time_sec", 0L)
                put("added_at", record.addedAtMs)
                put("last_read_at", 0L)
                put("content_sha256", record.contentSha256)
                put("parser_version", record.parserVersion)
            })

            val spineStmt = db.compileStatement("INSERT INTO spine(book_id, idx, item_id, href, media_type) VALUES(?,?,?,?,?)")
            for (s in meta.spine) {
                spineStmt.clearBindings()
                spineStmt.bindString(1, record.id)
                spineStmt.bindLong(2, s.index.toLong())
                spineStmt.bindString(3, s.id)
                spineStmt.bindString(4, s.href)
                spineStmt.bindString(5, s.mediaType)
                spineStmt.executeInsert()
            }

            val tocStmt = db.compileStatement("INSERT INTO toc(book_id, ord, title, href, spine_idx, depth) VALUES(?,?,?,?,?,?)")
            meta.toc.forEachIndexed { ord, t ->
                tocStmt.clearBindings()
                tocStmt.bindString(1, record.id)
                tocStmt.bindLong(2, ord.toLong())
                tocStmt.bindString(3, t.title)
                tocStmt.bindString(4, t.href)
                tocStmt.bindLong(5, t.spineIndex.toLong())
                tocStmt.bindLong(6, t.depth.toLong())
                tocStmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            destFile.delete()
            coverName?.let { File(coversDir, it).delete() }
            throw t
        } finally {
            db.endTransaction()
        }
        return record
    }

    fun listBooks(searchQuery: String = "", sortBy: String = "RECENT", statusFilter: String = "ALL"): List<BookRecord> {
        val out = mutableListOf<BookRecord>()
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (searchQuery.isNotBlank()) {
            where.add("(title LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')")
            val q = "%${escapeLike(searchQuery.trim())}%"
            args.add(q); args.add(q); args.add(q)
        }
        if (statusFilter != "ALL") {
            where.add("status = ?")
            args.add(statusFilter)
        }
        val whereSql = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        val orderSql = when (sortBy) {
            "TITLE" -> "ORDER BY title COLLATE NOCASE ASC"
            "AUTHOR" -> "ORDER BY author COLLATE NOCASE ASC"
            "PROGRESS" -> "ORDER BY (CAST(last_chapter AS REAL) / MAX(chapter_count, 1)) DESC"
            "ADDED" -> "ORDER BY added_at DESC"
            else -> "ORDER BY MAX(last_read_at, added_at) DESC"
        }
        readableDatabase.rawQuery(
            "SELECT id,file_name,title,author,description,language,cover_file,chapter_count," +
                "last_chapter,last_block,status,tags,reading_time_sec,added_at,last_read_at,content_sha256,parser_version " +
                "FROM books $whereSql $orderSql",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BookRecord(
                        id = c.getString(0),
                        fileName = c.getString(1),
                        title = c.getString(2),
                        author = c.getString(3),
                        description = c.getString(4),
                        language = c.getString(5),
                        coverFileName = if (c.isNull(6)) null else c.getString(6),
                        chapterCount = c.getInt(7),
                        lastChapterIndex = c.getInt(8),
                        lastBlockIndex = c.getInt(9),
                        status = ReadingStatus.fromCode(c.getString(10)),
                        tags = c.getString(11),
                        readingTimeSec = c.getLong(12),
                        addedAtMs = c.getLong(13),
                        lastReadAtMs = c.getLong(14),
                        contentSha256 = if (c.isNull(15)) null else c.getString(15),
                        parserVersion = c.getInt(16),
                    )
                )
            }
        }
        return out
    }

    fun getBook(bookId: String): BookRecord? {
        readableDatabase.rawQuery(
            "SELECT id,file_name,title,author,description,language,cover_file,chapter_count," +
                "last_chapter,last_block,status,tags,reading_time_sec,added_at,last_read_at,content_sha256,parser_version " +
                "FROM books WHERE id=? LIMIT 1",
            arrayOf(bookId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BookRecord(
                id = c.getString(0),
                fileName = c.getString(1),
                title = c.getString(2),
                author = c.getString(3),
                description = c.getString(4),
                language = c.getString(5),
                coverFileName = if (c.isNull(6)) null else c.getString(6),
                chapterCount = c.getInt(7),
                lastChapterIndex = c.getInt(8),
                lastBlockIndex = c.getInt(9),
                status = ReadingStatus.fromCode(c.getString(10)),
                tags = c.getString(11),
                readingTimeSec = c.getLong(12),
                addedAtMs = c.getLong(13),
                lastReadAtMs = c.getLong(14),
                contentSha256 = if (c.isNull(15)) null else c.getString(15),
                parserVersion = c.getInt(16),
            )
        }
    }

    fun getAllSpineHrefs(bookId: String): List<String> {
        val out = ArrayList<String>(256)
        readableDatabase.rawQuery(
            "SELECT href FROM spine WHERE book_id=? ORDER BY idx ASC",
            arrayOf(bookId)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun getSpineItem(bookId: String, index: Int): SpineItem? {
        readableDatabase.rawQuery(
            "SELECT idx, item_id, href, media_type FROM spine WHERE book_id=? AND idx=?",
            arrayOf(bookId, index.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return SpineItem(c.getInt(0), c.getString(1), c.getString(2), c.getString(3))
        }
    }

    fun getChapterTitle(bookId: String, spineIdx: Int, totalChapters: Int): String {
        readableDatabase.rawQuery(
            "SELECT title FROM toc WHERE book_id=? AND spine_idx=? ORDER BY ord ASC LIMIT 1",
            arrayOf(bookId, spineIdx.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "Розділ ${spineIdx + 1} / $totalChapters"
    }

    fun queryToc(bookId: String, filter: String = ""): List<TocEntry> {
        val out = ArrayList<TocEntry>(256)
        val sql: String
        val args: Array<String>
        if (filter.isBlank()) {
            sql = "SELECT title, href, spine_idx, depth FROM toc WHERE book_id=? ORDER BY ord ASC"
            args = arrayOf(bookId)
        } else {
            sql = "SELECT title, href, spine_idx, depth FROM toc WHERE book_id=? AND title LIKE ? ESCAPE '\\' ORDER BY ord ASC"
            args = arrayOf(bookId, "%${escapeLike(filter.trim())}%")
        }
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(TocEntry(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)))
            }
        }
        return out
    }

    fun updateProgress(
        bookId: String,
        chapterIndex: Int,
        blockIndex: Int,
        addSeconds: Long = 0L,
        reachedEnd: Boolean = false,
        event: com.holos.reader.tts.ProgressEvent = com.holos.reader.tts.ProgressEvent.CONSUMED_READING,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var prevStatus = ReadingStatus.UNREAD
            var prevChap = 0
            var prevBlock = 0
            var found = false
            db.rawQuery(
                "SELECT status, last_chapter, last_block FROM books WHERE id=? LIMIT 1",
                arrayOf(bookId),
            ).use { c ->
                if (c.moveToFirst()) {
                    found = true
                    prevStatus = ReadingStatus.fromCode(c.getString(0))
                    prevChap = c.getInt(1)
                    prevBlock = c.getInt(2)
                }
            }
            if (!found) return

            val newStatus = when {
                prevStatus == ReadingStatus.FINISHED -> ReadingStatus.FINISHED
                reachedEnd -> ReadingStatus.FINISHED
                else -> ReadingStatus.READING
            }
            val safeAddSec = addSeconds.coerceAtLeast(0L)
            val now = System.currentTimeMillis()
            db.execSQL(
                "UPDATE books SET last_chapter=?, last_block=?, status=?, reading_time_sec = reading_time_sec + ?, last_read_at=? WHERE id=?",
                arrayOf<Any>(chapterIndex, blockIndex, newStatus.code, safeAddSec, now, bookId),
            )
            val pagesDelta = com.holos.reader.tts.ReadingProgressPolicy.computePagesDelta(
                event = event,
                prevChapter = prevChap,
                prevBlock = prevBlock,
                newChapter = chapterIndex,
                newBlock = blockIndex,
            )
            if (safeAddSec > 0L || pagesDelta > 0L) {
                val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
                upsertReadingActivity(db, dayKey, safeAddSec, pagesDelta)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateStatus(bookIds: Collection<String>, status: ReadingStatus) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in bookIds) {
                db.update("books", ContentValues().apply { put("status", status.code) }, "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateTags(bookIds: Collection<String>, tags: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in bookIds) {
                db.update("books", ContentValues().apply { put("tags", tags.trim()) }, "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getReadingStats(): ReadingStats {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), " +
                "SUM(CASE WHEN status='READING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status='FINISHED' THEN 1 ELSE 0 END), " +
                "COALESCE(SUM(reading_time_sec), 0), " +
                "COALESCE(SUM(CASE WHEN status='FINISHED' THEN chapter_count ELSE last_chapter END), 0) " +
                "FROM books",
            null
        ).use { c ->
            if (!c.moveToFirst()) return ReadingStats(0, 0, 0, 0L, 0)
            return ReadingStats(
                totalBooks = c.getInt(0),
                readingBooks = c.getInt(1),
                finishedBooks = c.getInt(2),
                totalReadingSec = c.getLong(3),
                totalChaptersRead = c.getInt(4),
            )
        }
    }

    fun getDailyActivity(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        readableDatabase.rawQuery("SELECT day_key, seconds FROM reading_activity ORDER BY day_key ASC", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = c.getLong(1)
            }
        }
        return out
    }

    fun getTotalPagesRead(): Long {
        var activityPages = 0L
        readableDatabase.rawQuery("SELECT COALESCE(SUM(pages), 0) FROM reading_activity", null).use { c ->
            if (c.moveToFirst()) activityPages = c.getLong(0)
        }
        val chapters = getReadingStats().totalChaptersRead.toLong()
        return maxOf(activityPages, chapters)
    }

    fun findDuplicateBook(title: String, author: String, chapterCount: Int): BookRecord? {
        readableDatabase.rawQuery(
            "SELECT id FROM books WHERE LOWER(title)=LOWER(?) AND LOWER(author)=LOWER(?) AND chapter_count=? LIMIT 1",
            arrayOf(title.trim(), author.trim(), chapterCount.toString())
        ).use { c ->
            if (c.moveToFirst()) return getBook(c.getString(0))
        }
        return null
    }

    fun findBookmarkId(bookId: String, chapterIndex: Int, blockIndex: Int): Long? {
        readableDatabase.rawQuery(
            "SELECT id FROM bookmarks WHERE book_id=? AND chapter_idx=? AND ABS(block_idx - ?) <= 3 ORDER BY created_at DESC LIMIT 1",
            arrayOf(bookId, chapterIndex.toString(), blockIndex.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    fun deleteBookmark(bookmarkId: Long) {
        writableDatabase.delete("bookmarks", "id=?", arrayOf(bookmarkId.toString()))
    }

    fun addBookmark(bookId: String, chapterIndex: Int, blockIndex: Int, excerpt: String) {
        if (findBookmarkId(bookId, chapterIndex, blockIndex) != null) return
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("book_id", bookId)
            put("chapter_idx", chapterIndex)
            put("block_idx", blockIndex)
            put("excerpt", excerpt.take(120))
            put("created_at", System.currentTimeMillis())
        })
    }

    fun listBookmarks(bookId: String): List<BookmarkRecord> {
        val out = mutableListOf<BookmarkRecord>()
        readableDatabase.rawQuery(
            "SELECT id, book_id, chapter_idx, block_idx, excerpt, created_at FROM bookmarks WHERE book_id=? ORDER BY created_at DESC",
            arrayOf(bookId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(BookmarkRecord(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getLong(5)))
            }
        }
        return out
    }

    fun deleteBooks(bookIds: Collection<String>) {
        val records = bookIds.mapNotNull { getBook(it) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in bookIds) {
                db.delete("bookmarks", "book_id=?", arrayOf(id))
                db.delete("toc", "book_id=?", arrayOf(id))
                db.delete("spine", "book_id=?", arrayOf(id))
                db.delete("books", "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val offlineDir = File(appCtx.filesDir, "offline_chapters")
        for (rec in records) {
            getBookFile(rec).delete()
            getCoverFile(rec)?.delete()
            offlineDir.listFiles { f -> f.name.startsWith("${rec.id}_") }?.forEach { it.delete() }
        }
    }

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private const val DB_NAME = "holos_reader.db"
        private const val DB_VERSION = 4
        private const val MAX_IMPORT_BYTES = 512L * 1024L * 1024L
        const val CURRENT_PARSER_VERSION = 2

        fun buildSingleChapterEpub(outFile: File, title: String, bodyText: String) {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val paras = bodyText.split(Regex("\\r?\\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "<p>${esc(it)}</p>" }
            val chHtml = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${esc(title)}</title></head>
<body><h1>${esc(title)}</h1>$paras</body></html>"""
            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
    <dc:title>${esc(title)}</dc:title>
    <dc:creator>Локальний текст</dc:creator>
    <dc:language>uk</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""
            val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">${esc(title)}</a></li></ol></nav></body></html>"""

            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                val mimeEntry = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    compressedSize = mimeBytes.size.toLong()
                    crc = CRC32().apply { update(mimeBytes) }.value
                }
                zos.putNextEntry(mimeEntry)
                zos.write(mimeBytes)
                zos.closeEntry()
                put("META-INF/container.xml", """<container version="1.0"><rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles></container>""")
                put("OEBPS/package.opf", opf)
                put("OEBPS/nav.xhtml", nav)
                put("OEBPS/ch1.xhtml", chHtml)
            }
        }
    }
}
