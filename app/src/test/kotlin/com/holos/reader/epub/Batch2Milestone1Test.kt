package com.holos.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Batch2Milestone1Test {

    private fun writeEpub(entries: Map<String, String>): File {
        val f = createTempFile("holos_b2m1_", ".epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write("""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="Book/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            zos.closeEntry()
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return f
    }

    // #24: XML-escaped ampersand & percent-encoded # in filename with separate fragment
    @Test
    fun resolvesAmpersandAndEncodedHashFilenamesWithFragments() {
        val opf = """
            <package version="3.0"><metadata><dc:title>Test</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="cats&amp;dogs.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="part%231.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
        """.trimIndent()
        val nav = """
            <html><body><nav epub:type="toc"><ol>
              <li><a href="cats&amp;dogs.xhtml">Cats &amp; Dogs</a></li>
              <li><a href="part%231.xhtml#late">Part #1 Late</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val f = writeEpub(mapOf(
            "Book/content.opf" to opf,
            "Book/nav.xhtml" to nav,
            "Book/cats&dogs.xhtml" to "<html><body><p>Meow</p></body></html>",
            "Book/part#1.xhtml" to "<html><body><p>First</p><h2 id='late'>Late</h2></body></html>",
        ))
        try {
            val meta = EpubStreamingParser.parsePackage(f)
            assertEquals("Book/cats&dogs.xhtml", meta.spine[0].href)
            assertEquals("Book/part#1.xhtml", meta.spine[1].href)
            assertEquals(1, meta.toc[1].spineIndex)
            assertEquals("late", meta.toc[1].effectiveFragment())
            assertEquals(listOf("Meow"), EpubStreamingParser.parseChapterBlocks(f, meta.spine[0]).map { it.text })
        } finally {
            f.delete()
        }
    }

    // #36: Repeated spine item maps TOC link to FIRST linear occurrence
    // #40: linear="no" appendix remains addressable via TOC while skipped by default linear traversal
    @Test
    fun repeatedSpineMapsToFirstAndNonLinearAppendixIsAddressable() {
        val opf = """
            <package version="3.0"><metadata><dc:title>Test</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                <item id="app" href="appendix.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="a"/>
                <itemref idref="app" linear="no"/>
                <itemref idref="b"/>
                <itemref idref="a"/>
              </spine>
            </package>
        """.trimIndent()
        val nav = """
            <html><body><nav epub:type="toc"><ol>
              <li><a href="a.xhtml">Chapter A</a></li>
              <li><a href="appendix.xhtml">Appendix</a></li>
              <li><a href="b.xhtml">Chapter B</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val f = writeEpub(mapOf(
            "Book/content.opf" to opf,
            "Book/nav.xhtml" to nav,
            "Book/a.xhtml" to "<html><body><p>A</p></body></html>",
            "Book/b.xhtml" to "<html><body><p>B</p></body></html>",
            "Book/appendix.xhtml" to "<html><body><p>Appendix</p></body></html>",
        ))
        try {
            val meta = EpubStreamingParser.parsePackage(f)
            assertEquals(4, meta.spine.size)
            assertTrue(meta.spine[0].linear)
            assertFalse(meta.spine[1].linear)
            assertEquals(0, meta.toc[0].spineIndex) // FIRST occurrence of a.xhtml (#36)
            assertEquals(1, meta.toc[1].spineIndex) // Addressable linear="no" appendix (#40)
            assertEquals(2, meta.toc[2].spineIndex)
            // Next linear chapter from index 0 skips non-linear appendix (1) and lands on 2
            assertEquals(2, BookmarkRemapper.nextLinearIndex(meta.spine, 0, +1))
            assertNull(BookmarkRemapper.nextLinearIndex(meta.spine, 3, +1))
        } finally {
            f.delete()
        }
    }

    // #30: Legacy bookmark index remapped by excerpt across parser version upgrade
    @Test
    fun bookmarkRemapperFindsShiftedParagraphByExcerpt() {
        val html = "<body>Opening directly in body.<section><p>An early paragraph.</p><p>My bookmarked paragraph.</p><p>A later paragraph.</p></section></body>"
        val blocks = EpubStreamingParser.parseHtmlToRichBlocks(html)
        // Old parser had "My bookmarked paragraph." at index 1; new streaming parser has it at index 2
        val remapped = BookmarkRemapper.remapBlockIndex(blocks, savedBlockIndex = 1, excerpt = "My bookmarked paragraph.")
        assertEquals(2, remapped)
        assertEquals("My bookmarked paragraph.", blocks[remapped].text)
    }
    @Test
    fun resolvesFragmentOnlyTocEntriesAgainstTheNavigationDocument() {
        val opf = """
            <package version="3.0"><metadata><dc:title>Sections</dc:title></metadata>
              <manifest>
                <item id="nav" href="text/part%231.xhtml" media-type="application/xhtml+xml" properties="nav"/>
              </manifest>
              <spine><itemref idref="nav"/></spine>
            </package>
        """.trimIndent()
        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
              <li><a href="#later%2Dsection">Later</a></li>
            </ol></nav><p>Opening</p><h2 id="later-section">Later</h2></body></html>
        """.trimIndent()
        val file = writeEpub(mapOf("Book/content.opf" to opf, "Book/text/part#1.xhtml" to nav))
        try {
            val meta = EpubStreamingParser.parsePackage(file)
            val entry = meta.toc.single()
            assertEquals("Later", entry.title)
            assertEquals(0, entry.spineIndex)
            assertEquals("later-section", entry.effectiveFragment())
            java.util.zip.ZipFile(file).use {
                val chapter = EpubStreamingParser.parseChapterWithAnchors(it, meta.spine.single())
                assertEquals("Later", chapter.anchorToBlock[entry.fragment]?.let { idx -> chapter.blocks[idx].text })
            }
        } finally {
            file.delete()
        }
    }

}
