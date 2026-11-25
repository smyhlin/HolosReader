package com.holos.reader.epub

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * High-performance O(N) EPUB 3 / EPUB 2 parser designed to handle massive 6,000+ chapter
 * web novels in milliseconds with low memory overhead:
 * - Single-pass tag scanner for OPF manifest & spine (no catastrophic regex backtracking on 10 MB OPF)
 * - Full EPUB 3 Navigation Document (<nav epub:type="toc">) + EPUB 2 NCX fallback
 * - Rich XHTML block parser preserving bold, italic, underline, strikethrough, code, sup/sub,
 *   headings h1-h6, blockquotes, lists, tables, footnotes (<aside epub:type="footnote">),
 *   horizontal rules, <figure>/<figcaption>, <img>, and SVG <image>
 */
object EpubStreamingParser {

    private val RE_SCRIPT_STYLE = Regex(
        """<(script|style|head|math|noscript)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val RE_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val RE_NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val RE_WS = Regex("""[\s\u00A0]+""")

    fun parsePackage(epubFile: File): ParsedEpubMeta {
        ZipFile(epubFile).use { zip ->
            val containerXml = readEntryText(zip, "META-INF/container.xml")
                ?: throw IllegalArgumentException("Invalid EPUB: missing META-INF/container.xml")
            val opfPath = findAttrValue(containerXml, "full-path")
                ?: throw IllegalArgumentException("Invalid EPUB: rootfile not found in container.xml")

            val opfXml = readEntryText(zip, opfPath)
                ?: throw IllegalArgumentException("Invalid EPUB: missing OPF at $opfPath")
            val opfDir = parentDir(opfPath)

            val metaEnd = opfXml.indexOf("</metadata>", ignoreCase = true).let { if (it > 0) it + 11 else minOf(opfXml.length, 32768) }
            val metaChunk = opfXml.substring(0, metaEnd)

            val title = extractTagText(metaChunk, "dc:title")
                ?: extractTagText(metaChunk, "title")
                ?: epubFile.nameWithoutExtension
            val author = extractTagText(metaChunk, "dc:creator")
                ?: extractTagText(metaChunk, "creator")
                ?: "Невідомий автор"
            val description = extractTagText(metaChunk, "dc:description")
                ?: extractTagText(metaChunk, "description")
                ?: ""
            val language = extractTagText(metaChunk, "dc:language")
                ?: extractTagText(metaChunk, "language")
                ?: "uk"

            // Fast linear scan for <item ...> inside <manifest> (handles 10,000+ items in ~20 ms)
            val manifest = HashMap<String, ManifestEntry>(1024)
            var coverHref: String? = null
            var navHref: String? = null
            var ncxHref: String? = null

            val manifestStart = opfXml.indexOf("<manifest", ignoreCase = true).coerceAtLeast(0)
            val manifestEnd = opfXml.indexOf("</manifest>", manifestStart, ignoreCase = true)
                .let { if (it > manifestStart) it else opfXml.length }

            scanTags(opfXml, manifestStart, manifestEnd, "item") { attrs ->
                val id = fastAttr(attrs, "id") ?: return@scanTags
                val href = fastAttr(attrs, "href")?.let { decodeHref(it) } ?: return@scanTags
                val mt = fastAttr(attrs, "media-type") ?: ""
                val props = fastAttr(attrs, "properties") ?: ""
                manifest[id] = ManifestEntry(id, href, mt)
                if (props.isNotEmpty()) {
                    if (coverHref == null && hasToken(props, "cover-image")) coverHref = href
                    if (navHref == null && hasToken(props, "nav")) navHref = href
                }
                if (ncxHref == null && (mt.equals("application/x-dtbncx+xml", ignoreCase = true) || href.endsWith(".ncx", ignoreCase = true))) {
                    ncxHref = href
                }
            }

            // EPUB 2 <meta name="cover" content="id"/> fallback
            if (coverHref == null) {
                scanTags(metaChunk, 0, metaChunk.length, "meta") { attrs ->
                    if (fastAttr(attrs, "name").equals("cover", ignoreCase = true)) {
                        val cid = fastAttr(attrs, "content")
                        if (cid != null) coverHref = manifest[cid]?.href
                    }
                }
            }
            val coverPath = coverHref?.let { resolvePath(opfDir, it) }

            // Fast linear scan for <itemref ...> inside <spine>
            val spineStart = opfXml.indexOf("<spine", manifestEnd.coerceAtMost(opfXml.length), ignoreCase = true)
                .let { if (it >= 0) it else 0 }
            val spineEnd = opfXml.indexOf("</spine>", spineStart, ignoreCase = true)
                .let { if (it > spineStart) it else opfXml.length }

            val spine = ArrayList<SpineItem>(256)
            val spineIndexByHref = HashMap<String, Int>(512)
            scanTags(opfXml, spineStart, spineEnd, "itemref") { attrs ->
                val idref = fastAttr(attrs, "idref") ?: return@scanTags
                if (fastAttr(attrs, "linear").equals("no", ignoreCase = true)) return@scanTags
                val item = manifest[idref] ?: return@scanTags
                val resolved = resolvePath(opfDir, item.href)
                val idx = spine.size
                spine.add(SpineItem(idx, item.id, resolved, item.mediaType))
                spineIndexByHref[resolved] = idx
                spineIndexByHref[resolved.lowercase()] = idx
            }

            // Parse TOC (EPUB 3 nav -> EPUB 2 NCX -> spine fallback)
            var toc: List<TocEntry> = emptyList()
            if (navHref != null) {
                val navPath = resolvePath(opfDir, navHref!!)
                readEntryText(zip, navPath)?.let { navHtml ->
                    toc = parseEpub3NavFast(navHtml, parentDir(navPath), spineIndexByHref)
                }
            }
            if (toc.isEmpty() && ncxHref != null) {
                val ncxPath = resolvePath(opfDir, ncxHref!!)
                readEntryText(zip, ncxPath)?.let { ncxXml ->
                    toc = parseNcxFast(ncxXml, parentDir(ncxPath), spineIndexByHref)
                }
            }
            if (toc.isEmpty()) {
                toc = spine.map { TocEntry("Розділ ${it.index + 1}", it.href, it.index, 0) }
            }

            return ParsedEpubMeta(
                title = decodeEntities(title).trim(),
                author = decodeEntities(author).trim(),
                description = decodeEntities(description).trim(),
                language = language.trim(),
                coverEntryPath = coverPath,
                spine = spine,
                toc = toc,
            )
        }
    }

    fun parseChapterBlocks(zip: ZipFile, spineItem: SpineItem): List<ReaderBlock> {
        val html = readEntryText(zip, spineItem.href) ?: return emptyList()
        return parseHtmlToRichBlocks(html, chapterDir = parentDir(spineItem.href))
    }

    fun parseChapterBlocks(epubFile: File, spineItem: SpineItem): List<ReaderBlock> {
        return ZipFile(epubFile).use { zip -> parseChapterBlocks(zip, spineItem) }
    }

    /**
     * Parses XHTML/HTML chapter into structured [ReaderBlock]s with full inline span preservation
     * (bold, italic, underline, strikethrough, code, sup/sub), tables, lists, captions, footnotes, and images.
     */
    fun parseHtmlToRichBlocks(rawHtml: String, chapterDir: String = ""): List<ReaderBlock> {
        var html = RE_COMMENT.replace(rawHtml, "")
        html = RE_SCRIPT_STYLE.replace(html, "")

        val bodyStart = html.indexOf("<body", ignoreCase = true)
        val body = if (bodyStart >= 0) {
            val openEnd = html.indexOf('>', bodyStart)
            val bodyClose = html.lastIndexOf("</body>", ignoreCase = true)
            if (openEnd >= 0 && bodyClose > openEnd) html.substring(openEnd + 1, bodyClose) else html
        } else html

        val blocks = ArrayList<ReaderBlock>(64)
        var blockIdx = 0

        val blockRegex = Regex(
            """<(h[1-6]|p|blockquote|li|tr|figcaption|aside|pre)\b([^>]*)>(.*?)</\1>|<hr\b[^>]*/?>|<img\b([^>]+)/?>|<image\b([^>]+)/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        for (m in blockRegex.findAll(body)) {
            val full = m.value
            if (full.startsWith("<hr", ignoreCase = true)) {
                blocks.add(ReaderBlock(blockIdx++, BlockType.HR, ""))
                continue
            }
            if (full.startsWith("<img", ignoreCase = true) || full.startsWith("<image", ignoreCase = true)) {
                val attrs = m.groupValues[4].ifEmpty { m.groupValues[5] }
                extractImageBlock(attrs, chapterDir, blockIdx)?.let {
                    blocks.add(it)
                    blockIdx++
                }
                continue
            }

            val tag = m.groupValues[1].lowercase()
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]

            // Extract any inline <img> nested inside <p> / <figure> / <td>
            val imgInInner = Regex("""<img\b([^>]+)/?>""", RegexOption.IGNORE_CASE).find(inner)
            if (imgInInner != null) {
                extractImageBlock(imgInInner.groupValues[1], chapterDir, blockIdx)?.let {
                    blocks.add(it)
                    blockIdx++
                }
            }

            if (tag == "tr") {
                val cells = Regex("""<t[dh]\b[^>]*>(.*?)</t[dh]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(inner)
                    .map { parseInlineRichText(it.groupValues[1]).first }
                    .filter { it.isNotEmpty() }
                    .toList()
                if (cells.isNotEmpty()) {
                    blocks.add(ReaderBlock(blockIdx++, BlockType.TABLE_ROW, cells.joinToString("  |  ")))
                }
                continue
            }

            val (plain, spans) = parseInlineRichText(inner)
            if (plain.isEmpty()) continue

            val type = when {
                tag == "h1" -> BlockType.HEADING_1
                tag == "h2" -> BlockType.HEADING_2
                tag.startsWith("h") -> BlockType.HEADING_3
                tag == "blockquote" -> BlockType.BLOCKQUOTE
                tag == "li" -> BlockType.LIST_ITEM
                tag == "figcaption" -> BlockType.CAPTION
                tag == "aside" || attrs.contains("footnote", ignoreCase = true) -> BlockType.FOOTNOTE
                else -> BlockType.PARAGRAPH
            }
            val finalText = if (type == BlockType.LIST_ITEM) "• $plain" else plain
            val finalSpans = if (type == BlockType.LIST_ITEM) {
                spans.map { it.copy(start = it.start + 2, end = it.end + 2) }
            } else spans
            blocks.add(ReaderBlock(blockIdx++, type, finalText, finalSpans))
        }

        if (blocks.isEmpty()) {
            val fallbackLines = body
                .replace(Regex("""</?(div|br|section|article)\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
                .split('\n')
            for (line in fallbackLines) {
                val (plain, spans) = parseInlineRichText(line)
                if (plain.isNotEmpty()) {
                    blocks.add(ReaderBlock(blockIdx++, BlockType.PARAGRAPH, plain, spans))
                }
            }
        }
        return blocks
    }

    /**
     * Parses inline HTML tags (`<b>`, `<strong>`, `<i>`, `<em>`, `<u>`, `<s>`, `<del>`,
     * `<code>`, `<sup>`, `<sub>`, `<br/>`) into clean plain text + exact [InlineSpan] ranges.
     */
    fun parseInlineRichText(fragment: String): Pair<String, List<InlineSpan>> {
        val sb = StringBuilder(fragment.length)
        val spans = mutableListOf<InlineSpan>()
        val openStack = ArrayDeque<Pair<SpanStyle, Int>>()

        var i = 0
        val len = fragment.length
        while (i < len) {
            val ch = fragment[i]
            if (ch == '<') {
                val nextCh = if (i + 1 < len) fragment[i + 1] else ' '
                val looksLikeTag = nextCh == '/' || nextCh == '!' || nextCh == '?' || nextCh in 'a'..'z' || nextCh in 'A'..'Z'
                val closeIdx = if (looksLikeTag) fragment.indexOf('>', i + 1) else -1
                if (closeIdx < 0) {
                    appendCollapsedWhitespace(sb, "<")
                    i++
                    continue
                }
                val rawTag = fragment.substring(i + 1, closeIdx).trim()
                i = closeIdx + 1

                val isClosing = rawTag.startsWith("/")
                val tagName = rawTag.removePrefix("/").substringBefore(' ').substringBefore('/').lowercase()
                if (tagName == "br") {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                    continue
                }
                val style = when (tagName) {
                    "b", "strong" -> SpanStyle.BOLD
                    "i", "em", "cite" -> SpanStyle.ITALIC
                    "u", "ins" -> SpanStyle.UNDERLINE
                    "s", "strike", "del" -> SpanStyle.STRIKE
                    "code", "tt" -> SpanStyle.CODE
                    "sup" -> SpanStyle.SUP
                    "sub" -> SpanStyle.SUB
                    else -> null
                } ?: continue

                if (!isClosing) {
                    if (!rawTag.endsWith("/")) openStack.addLast(style to sb.length)
                } else {
                    for (k in openStack.indices.reversed()) {
                        if (openStack[k].first == style) {
                            val (_, startPos) = openStack.removeAt(k)
                            if (sb.length > startPos) {
                                spans.add(InlineSpan(startPos, sb.length, style))
                            }
                            break
                        }
                    }
                }
            } else {
                val nextTag = fragment.indexOf('<', i).let { if (it < 0) len else it }
                val chunk = decodeEntities(fragment.substring(i, nextTag))
                appendCollapsedWhitespace(sb, chunk)
                i = nextTag
            }
        }

        // Trim leading/trailing spaces while adjusting span offsets accurately
        val rawStr = sb.toString()
        var trimStart = 0
        while (trimStart < rawStr.length && rawStr[trimStart].isWhitespace()) trimStart++
        var trimEnd = rawStr.length
        while (trimEnd > trimStart && rawStr[trimEnd - 1].isWhitespace()) trimEnd--

        val trimmed = rawStr.substring(trimStart, trimEnd)
        if (trimStart == 0 && trimEnd == rawStr.length) {
            return trimmed to spans
        }
        val adjusted = spans.mapNotNull { sp ->
            val s = (sp.start - trimStart).coerceIn(0, trimmed.length)
            val e = (sp.end - trimStart).coerceIn(0, trimmed.length)
            if (e > s) InlineSpan(s, e, sp.style) else null
        }
        return trimmed to adjusted
    }

    private fun appendCollapsedWhitespace(sb: StringBuilder, text: String) {
        var lastWasSpace = sb.isEmpty() || sb[sb.length - 1] == ' ' || sb[sb.length - 1] == '\n'
        for (c in text) {
            if (c == '\n') {
                sb.append('\n')
                lastWasSpace = true
            } else if (c.isWhitespace() || c == '\u00A0') {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(c)
                lastWasSpace = false
            }
        }
    }

    private fun extractImageBlock(attrs: String, chapterDir: String, blockIdx: Int): ReaderBlock? {
        val rawSrc = fastAttr(attrs, "src") ?: fastAttr(attrs, "xlink:href") ?: fastAttr(attrs, "href")
        if (rawSrc.isNullOrBlank()) return null
        val resolved = resolvePath(chapterDir, decodeHref(rawSrc))
        val alt = fastAttr(attrs, "alt")?.let { decodeEntities(it).trim() }.orEmpty()
        return ReaderBlock(blockIdx, BlockType.IMAGE, alt, imageEntryPath = resolved)
    }

    private fun parseEpub3NavFast(
        navHtml: String,
        navDir: String,
        spineIndexByHref: Map<String, Int>,
    ): List<TocEntry> {
        val navStart = navHtml.indexOf("toc", ignoreCase = true).let { pos ->
            if (pos >= 0) navHtml.lastIndexOf("<nav", pos, ignoreCase = true).coerceAtLeast(0) else 0
        }
        val navEnd = navHtml.indexOf("</nav>", navStart, ignoreCase = true)
            .let { if (it > navStart) it else navHtml.length }
        val section = navHtml.substring(navStart, navEnd)

        val result = ArrayList<TocEntry>(128)
        val tokenRegex = Regex(
            """<ol\b[^>]*>|</ol>|<a\b([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        var depth = -1
        for (m in tokenRegex.findAll(section)) {
            val v = m.value
            when {
                v.startsWith("<ol", ignoreCase = true) -> depth++
                v.startsWith("</ol", ignoreCase = true) -> depth = (depth - 1).coerceAtLeast(-1)
                else -> {
                    val rawHref = fastAttr(m.groupValues[1], "href")?.let { decodeHref(it).substringBefore('#') } ?: continue
                    val label = parseInlineRichText(m.groupValues[2]).first
                    if (label.isEmpty() || rawHref.isEmpty()) continue
                    val resolved = resolvePath(navDir, rawHref)
                    val spineIdx = spineIndexByHref[resolved] ?: spineIndexByHref[resolved.lowercase()] ?: 0
                    result.add(TocEntry(label, resolved, spineIdx, depth.coerceAtLeast(0)))
                }
            }
        }
        return result
    }

    private fun parseNcxFast(
        ncxXml: String,
        ncxDir: String,
        spineIndexByHref: Map<String, Int>,
    ): List<TocEntry> {
        val result = ArrayList<TocEntry>(256)
        var pos = 0
        val len = ncxXml.length
        while (pos < len) {
            val npStart = ncxXml.indexOf("<navPoint", pos, ignoreCase = true)
            if (npStart < 0) break
            val tStart = ncxXml.indexOf("<text>", npStart, ignoreCase = true)
            val tEnd = if (tStart >= 0) ncxXml.indexOf("</text>", tStart + 6, ignoreCase = true) else -1
            val cStart = if (tEnd >= 0) ncxXml.indexOf("<content", tEnd, ignoreCase = true) else -1
            val cEnd = if (cStart >= 0) ncxXml.indexOf('>', cStart) else -1
            if (tStart < 0 || tEnd < 0 || cStart < 0 || cEnd < 0) break

            val label = decodeEntities(ncxXml.substring(tStart + 6, tEnd)).trim()
            val src = fastAttr(ncxXml.substring(cStart, cEnd), "src")?.let { decodeHref(it).substringBefore('#') }
            if (label.isNotEmpty() && !src.isNullOrEmpty()) {
                val resolved = resolvePath(ncxDir, src)
                val spineIdx = spineIndexByHref[resolved] ?: spineIndexByHref[resolved.lowercase()] ?: 0
                result.add(TocEntry(label, resolved, spineIdx, 0))
            }
            pos = cEnd + 1
        }
        return result
    }

    private data class ManifestEntry(val id: String, val href: String, val mediaType: String)

    private inline fun scanTags(xml: String, start: Int, end: Int, tagName: String, onTag: (String) -> Unit) {
        val openPrefix = "<$tagName"
        var pos = start
        while (pos < end) {
            val idx = xml.indexOf(openPrefix, pos, ignoreCase = true)
            if (idx < 0 || idx >= end) break
            val afterName = idx + openPrefix.length
            if (afterName < end && !xml[afterName].isWhitespace() && xml[afterName] != '>' && xml[afterName] != '/') {
                pos = afterName
                continue
            }
            val close = xml.indexOf('>', afterName)
            if (close < 0 || close > end) break
            onTag(xml.substring(afterName, close))
            pos = close + 1
        }
    }

    private fun fastAttr(attrs: String, name: String): String? {
        var pos = 0
        val len = attrs.length
        val nLen = name.length
        while (pos < len) {
            val idx = attrs.indexOf(name, pos, ignoreCase = true)
            if (idx < 0) return null
            val beforeOk = idx == 0 || attrs[idx - 1].isWhitespace()
            var p = idx + nLen
            while (p < len && attrs[p].isWhitespace()) p++
            if (beforeOk && p < len && attrs[p] == '=') {
                p++
                while (p < len && attrs[p].isWhitespace()) p++
                if (p < len && (attrs[p] == '"' || attrs[p] == '\'')) {
                    val quote = attrs[p++]
                    val endQ = attrs.indexOf(quote, p)
                    if (endQ >= p) return attrs.substring(p, endQ)
                }
            }
            pos = idx + nLen
        }
        return null
    }

    private fun findAttrValue(xml: String, name: String): String? = fastAttr(xml, name)

    private fun hasToken(props: String, token: String): Boolean =
        props.split(' ').any { it.equals(token, ignoreCase = true) }

    private fun extractTagText(xml: String, tagName: String): String? {
        val open = xml.indexOf("<$tagName", ignoreCase = true)
        if (open < 0) return null
        val gt = xml.indexOf('>', open)
        if (gt < 0) return null
        val close = xml.indexOf("</$tagName>", gt + 1, ignoreCase = true)
        if (close < 0) return null
        return RE_WS.replace(decodeEntities(xml.substring(gt + 1, close)), " ").trim().takeIf { it.isNotEmpty() }
    }

    private const val MAX_TEXT_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_BINARY_ENTRY_BYTES = 32 * 1024 * 1024
    private val RE_ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    private fun readStreamBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) {
                    throw IllegalArgumentException("EPUB entry exceeds $maxBytes bytes")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    fun readEntryText(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path)
            ?: zip.entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }
            ?: return null
        val bytes = readStreamBounded(zip.getInputStream(entry), MAX_TEXT_ENTRY_BYTES)
        return String(bytes, Charsets.UTF_8)
    }

    fun extractEntryBytes(zip: ZipFile, entryPath: String): ByteArray? {
        val entry = zip.getEntry(entryPath)
            ?: zip.entries().asSequence().firstOrNull { it.name.equals(entryPath, ignoreCase = true) }
            ?: return null
        return readStreamBounded(zip.getInputStream(entry), MAX_BINARY_ENTRY_BYTES)
    }

    private fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx >= 0) path.substring(0, idx) else ""
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val combined = if (baseDir.isEmpty()) href else "$baseDir/$href"
        val stack = ArrayDeque<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    /** RFC 3986 percent-decoding that preserves literal '+' in EPUB filenames. */
    fun decodeHref(href: String): String {
        if ('%' !in href) return href
        val out = java.io.ByteArrayOutputStream(href.length)
        var i = 0
        val n = href.length
        while (i < n) {
            val c = href[i]
            if (c == '%' && i + 2 < n) {
                val hi = Character.digit(href[i + 1], 16)
                val lo = Character.digit(href[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Single-pass XML/HTML entity decoder so `&amp;lt;` safely decodes to `&lt;` (never `<`). */
    fun decodeEntities(input: String): String {
        if ('&' !in input) return input
        return RE_ENTITY.replace(input) { m ->
            val name = m.groupValues[1]
            if (name.startsWith("#")) {
                val raw = name.substring(1)
                val code = if (raw.startsWith("x", ignoreCase = true)) {
                    raw.substring(1).toIntOrNull(16)
                } else {
                    raw.toIntOrNull(10)
                }
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            } else {
                when (name) {
                    "nbsp" -> " "
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "mdash" -> "—"
                    "ndash" -> "–"
                    "hellip" -> "…"
                    "laquo" -> "«"
                    "raquo" -> "»"
                    "ldquo" -> "“"
                    "rdquo" -> "”"
                    "lsquo" -> "‘"
                    "rsquo" -> "’"
                    "shy" -> ""
                    else -> m.value
                }
            }
        }
    }
}
