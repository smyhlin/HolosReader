package com.holos.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserNavigationTest {

    @Test
    fun decodesWindows1251AndUtf16BomUkrainianText() {
        val sample = "Привіт, світе! Замок стоїть на горі."
        val cp1251Html = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><html><body>$sample</body></html>"
        val decodedCp1251 = EpubStreamingParser.decodeTextBytes(cp1251Html.toByteArray(Charset.forName("windows-1251")))
        assertTrue(decodedCp1251.contains("Замок стоїть на горі."))

        val utf16Bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val decodedUtf16 = EpubStreamingParser.decodeTextBytes(utf16Bytes)
        assertEquals(sample, decodedUtf16)
    }

    @Test
    fun skipsLandmarksNavAndPreservesFragmentsAndUnresolvedEntries() {
        val tmp = File.createTempFile("nav_test_", ".epub")
        try {
            ZipOutputStream(tmp.outputStream()).use { zos ->
                fun put(name: String, text: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                put("mimetype", "application/epub+zip")
                put("META-INF/container.xml", """<container version="1.0"><rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles></container>""")
                put("OEBPS/package.opf", """
                    <package version="3.0">
                      <metadata><dc:title>Test</dc:title><dc:creator>Author</dc:creator></metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>
                """.trimIndent())
                put("OEBPS/nav.xhtml", """
                    <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                      <nav epub:type="landmarks"><ol><li><a href="ch1.xhtml">Wrong Landmark</a></li></ol></nav>
                      <nav epub:type="toc"><ol>
                        <li><a href="ch1.xhtml#sec2">Real Chapter</a></li>
                        <li><a href="missing.xhtml">Broken Link</a></li>
                      </ol></nav>
                    </body></html>
                """.trimIndent())
                put("OEBPS/ch1.xhtml", "<html><body><p id=\"sec2\">Hello</p></body></html>")
            }

            val meta = EpubStreamingParser.parsePackage(tmp)
            assertEquals(2, meta.toc.size)
            assertEquals("Real Chapter", meta.toc[0].title)
            assertEquals(0, meta.toc[0].spineIndex)
            assertTrue(meta.toc[0].href.endsWith("#sec2"))
            assertEquals("Broken Link", meta.toc[1].title)
            assertEquals(-1, meta.toc[1].spineIndex)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun singlePassEntityDecodeDoesNotDoubleDecode() {
        assertEquals("&lt;script&gt;", EpubStreamingParser.decodeEntities("&amp;lt;script&amp;gt;"))
    }
    @Test
    fun mixedHeadingAndLeafDivProsePreservesOrderAndAnchors() {
        val html = """
            <html><body>
              <h1 id="ch1">Розділ 1</h1>
              <div id="p1">Перший абзац у div.</div>
              <p>Другий абзац у p.</p>
              <div id="p3">Третій абзац у div.</div>
            </body></html>
        """.trimIndent()
        val blocks = EpubStreamingParser.parseHtmlToRichBlocks(html, "OEBPS")
        assertEquals(4, blocks.size)
        assertEquals("Розділ 1", blocks[0].text)
        assertEquals("ch1", blocks[0].anchorId)
        assertEquals("Перший абзац у div.", blocks[1].text)
        assertEquals("p1", blocks[1].anchorId)
        assertEquals("Другий абзац у p.", blocks[2].text)
        assertEquals("Третій абзац у div.", blocks[3].text)
        assertEquals("p3", blocks[3].anchorId)
    }
}
