package com.holos.reader.epub

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
                last_read_at INTEGER NOT NULL DEFAULT 0
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE books ADD COLUMN description TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE books ADD COLUMN status TEXT NOT NULL DEFAULT 'UNREAD'") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE books ADD COLUMN reading_time_sec INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        }
    }

    fun getBookFile(record: BookRecord): File = File(booksDir, record.fileName)

    fun getCoverFile(record: BookRecord): File? =
        record.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

    fun importFromUri(uri: Uri, displayName: String?): BookRecord {
        val id = UUID.randomUUID().toString().substring(0, 12)
        val safeName = "$id.epub"
        val destFile = File(booksDir, safeName)

        appCtx.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        } ?: throw IllegalArgumentException("Не вдалося відкрити файл EPUB")

        return indexLocalEpubFile(id, safeName, destFile, displayName)
    }

    /**
     * Batch-imports all `.epub` files from a user-selected directory (ACTION_OPEN_DOCUMENT_TREE).
     */
    fun importFromDirectoryTree(treeUri: Uri): Int {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
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
                if (name.endsWith(".epub", ignoreCase = true) || mime == "application/epub+zip") {
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
        buildSingleChapterEpub(destFile, cleanTitle, bodyText)
        return indexLocalEpubFile(id, safeName, destFile, cleanTitle)
    }

    private fun indexLocalEpubFile(id: String, safeName: String, destFile: File, displayName: String?): BookRecord {
        val meta = try {
            EpubStreamingParser.parsePackage(destFile)
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }

        var coverName: String? = null
        if (meta.coverEntryPath != null) {
            val bytes = ZipFile(destFile).use { zip ->
                EpubStreamingParser.extractEntryBytes(zip, meta.coverEntryPath)
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val ext = meta.coverEntryPath.substringAfterLast('.', "jpg").lowercase()
                coverName = "$id.$ext"
                File(coversDir, coverName).writeBytes(bytes)
            }
        }

        val fallbackTitle = displayName?.removeSuffix(".epub")?.takeIf { it.isNotBlank() }
        val record = BookRecord(
            id = id,
            fileName = safeName,
            title = meta.title.ifBlank { fallbackTitle ?: "Без назви" },
            author = meta.author,
            description = meta.description.take(280),
            language = meta.language,
            coverFileName = coverName,
            chapterCount = meta.spine.size,
            status = ReadingStatus.UNREAD,
            addedAtMs = System.currentTimeMillis(),
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
            where.add("(title LIKE ? OR author LIKE ? OR tags LIKE ?)")
            val q = "%${searchQuery.trim()}%"
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
                "last_chapter,last_block,status,tags,reading_time_sec,added_at,last_read_at " +
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
                    )
                )
            }
        }
        return out
    }

    fun getBook(bookId: String): BookRecord? = listBooks().firstOrNull { it.id == bookId }

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
            sql = "SELECT title, href, spine_idx, depth FROM toc WHERE book_id=? AND title LIKE ? ORDER BY ord ASC"
            args = arrayOf(bookId, "%${filter.trim()}%")
        }
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(TocEntry(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)))
            }
        }
        return out
    }

    fun updateProgress(bookId: String, chapterIndex: Int, blockIndex: Int, addSeconds: Long = 0L) {
        val existing = getBook(bookId) ?: return
        val newStatus = when {
            existing.chapterCount > 0 && chapterIndex >= existing.chapterCount - 1 && blockIndex > 0 -> ReadingStatus.FINISHED
            else -> ReadingStatus.READING
        }
        writableDatabase.update(
            "books",
            ContentValues().apply {
                put("last_chapter", chapterIndex)
                put("last_block", blockIndex)
                put("status", newStatus.code)
                put("reading_time_sec", existing.readingTimeSec + addSeconds.coerceAtLeast(0L))
                put("last_read_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(bookId)
        )
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
        val all = listBooks()
        return ReadingStats(
            totalBooks = all.size,
            readingBooks = all.count { it.status == ReadingStatus.READING },
            finishedBooks = all.count { it.status == ReadingStatus.FINISHED },
            totalReadingSec = all.sumOf { it.readingTimeSec },
            totalChaptersRead = all.sumOf { if (it.status == ReadingStatus.FINISHED) it.chapterCount else it.lastChapterIndex },
        )
    }

    fun addBookmark(bookId: String, chapterIndex: Int, blockIndex: Int, excerpt: String) {
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
        for (id in bookIds) {
            getBook(id)?.let {
                getBookFile(it).delete()
                getCoverFile(it)?.delete()
            }
        }
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
    }

    companion object {
        private const val DB_NAME = "holos_reader.db"
        private const val DB_VERSION = 2

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
                put("mimetype", "application/epub+zip")
                put("META-INF/container.xml", """<container version="1.0"><rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles></container>""")
                put("OEBPS/package.opf", opf)
                put("OEBPS/nav.xhtml", nav)
                put("OEBPS/ch1.xhtml", chHtml)
            }
        }
    }
}
