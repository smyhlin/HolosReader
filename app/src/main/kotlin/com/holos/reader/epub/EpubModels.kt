package com.holos.reader.epub

/**
 * Full EPUB 3 / EPUB 2 domain models optimized for massive 6,000+ chapter web novels.
 */
enum class ReadingStatus(val code: String, val label: String) {
    UNREAD("UNREAD", "Непрочитано"),
    READING("READING", "Читаю"),
    FINISHED("FINISHED", "Прочитано");

    companion object {
        fun fromCode(code: String?): ReadingStatus =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: UNREAD
    }
}

data class BookRecord(
    val id: String,
    val fileName: String,
    val title: String,
    val author: String,
    val description: String = "",
    val language: String = "uk",
    val coverFileName: String? = null,
    val chapterCount: Int = 1,
    val lastChapterIndex: Int = 0,
    val lastBlockIndex: Int = 0,
    val status: ReadingStatus = ReadingStatus.UNREAD,
    val tags: String = "",
    val readingTimeSec: Long = 0L,
    val addedAtMs: Long = System.currentTimeMillis(),
    val lastReadAtMs: Long = 0L,
    val contentSha256: String? = null,
    val parserVersion: Int = 3,
) {
    val progressPercent: Int
        get() {
            if (status == ReadingStatus.FINISHED) return 100
            if (chapterCount <= 1) return if (lastBlockIndex > 0) 50 else 0
            return ((lastChapterIndex * 100) / (chapterCount - 1).coerceAtLeast(1)).coerceIn(0, 99)
        }
}

data class ReadingStats(
    val totalBooks: Int,
    val readingBooks: Int,
    val finishedBooks: Int,
    val totalReadingSec: Long,
    val totalChaptersRead: Int,
)

data class SpineItem(
    val index: Int,
    val id: String,
    val href: String,
    val mediaType: String,
    val linear: Boolean = true,
)

data class TocEntry(
    val title: String,
    val href: String,
    val spineIndex: Int,
    val depth: Int = 0,
    val fragment: String? = null,
) {
    fun effectiveFragment(): String? = fragment?.takeIf { it.isNotBlank() }
}

data class BookmarkRecord(
    val id: Long = 0L,
    val bookId: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val excerpt: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

enum class SpanStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKE,
    CODE,
    SUP,
    SUB,
}

data class InlineSpan(
    val start: Int,
    val end: Int,
    val style: SpanStyle,
)

enum class BlockType {
    HEADING_1,
    HEADING_2,
    HEADING_3,
    PARAGRAPH,
    BLOCKQUOTE,
    LIST_ITEM,
    TABLE_ROW,
    IMAGE,
    CAPTION,
    FOOTNOTE,
    HR,
}

data class ReaderBlock(
    val index: Int,
    val type: BlockType,
    val text: String,
    val spans: List<InlineSpan> = emptyList(),
    val imageEntryPath: String? = null,
    val anchorId: String? = null,
) {
    val isSpeakable: Boolean
        get() = type != BlockType.HR && type != BlockType.IMAGE && text.isNotBlank()
}

data class ParsedEpubMeta(
    val title: String,
    val author: String,
    val description: String,
    val language: String,
    val coverEntryPath: String?,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
)

data class ParsedChapter(
    val blocks: List<ReaderBlock>,
    val anchorToBlock: Map<String, Int> = emptyMap(),
)
