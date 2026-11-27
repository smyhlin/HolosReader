package com.holos.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubParserIssuesTest {

    // Issue #1: Ordered streaming XHTML parser preserves inter-element prose, inline images, and nested blocks
    @Test
    fun preservesInterElementProseAcrossSections() {
        val html = "<body>Intro<section>Before<p>Middle</p>After</section>Outro</body>"
        val blocks = EpubStreamingParser.parseHtmlToRichBlocks(html)
        assertEquals(listOf("Intro", "Before", "Middle", "After", "Outro"), blocks.map { it.text })
        assertEquals((0 until blocks.size).toList(), blocks.map { it.index })
    }

    @Test
    fun preservesInlineImageOrderBetweenTextSpans() {
        val html = "<body><p>Before<img src='pic.png' alt='cover'/>After</p></body>"
        val blocks = EpubStreamingParser.parseHtmlToRichBlocks(html, chapterDir = "OEBPS")
        assertEquals(3, blocks.size)
        assertEquals(BlockType.PARAGRAPH, blocks[0].type)
        assertEquals("Before", blocks[0].text)
        assertEquals(BlockType.IMAGE, blocks[1].type)
        assertEquals("OEBPS/pic.png", blocks[1].imageEntryPath)
        assertEquals(BlockType.PARAGRAPH, blocks[2].type)
        assertEquals("After", blocks[2].text)
    }

    @Test
    fun splitsNestedBlockquoteParagraphsWithoutConcatenation() {
        val html = "<body><blockquote><p>One</p><p>Two</p></blockquote></body>"
        val blocks = EpubStreamingParser.parseHtmlToRichBlocks(html)
        assertEquals(2, blocks.size)
        assertEquals(BlockType.BLOCKQUOTE, blocks[0].type)
        assertEquals("One", blocks[0].text)
        assertEquals(BlockType.BLOCKQUOTE, blocks[1].type)
        assertEquals("Two", blocks[1].text)
    }

    // Issue #2: Per-chapter anchor map captures nested, section, empty, inline, image, and multiple anchors
    @Test
    fun resolvesNestedInlineSectionAndEmptyAnchors() {
        val html = """
            <body>
              <p>Intro</p>
              <section id="sec1">
                <a id="emptyA"></a>
                <p><a id="target"/>Target text</p>
              </section>
              <img id="fig1" src="img/map.png"/>
            </body>
        """.trimIndent()
        val parsed = EpubStreamingParser.parseHtmlToRichBlocksWithAnchors(html, chapterDir = "text")
        assertEquals(3, parsed.blocks.size)
        assertEquals(1, parsed.anchorToBlock["sec1"])
        assertEquals(1, parsed.anchorToBlock["emptyA"])
        assertEquals(1, parsed.anchorToBlock["target"])
        assertEquals(2, parsed.anchorToBlock["fig1"])
    }

    // Issue #3: Surrogate-safe percent-decoding and raw '#' fragment splitting
    @Test
    fun decodeHrefPreservesEmojiPlusCyrillicAndMalformedEscapes() {
        assertEquals("📚 book.xhtml", EpubStreamingParser.decodeHref("📚%20book.xhtml"))
        assertEquals("c++ guide.xhtml", EpubStreamingParser.decodeHref("c++%20guide.xhtml"))
        assertEquals("розділ 1.xhtml", EpubStreamingParser.decodeHref("%D1%80%D0%BE%D0%B7%D0%B4%D1%96%D0%BB%201.xhtml"))
        assertEquals("bad%ZZname.xhtml", EpubStreamingParser.decodeHref("bad%ZZname.xhtml"))
    }

    @Test
    fun splitRawHrefKeepsEncodedHashInFilenameSeparateFromFragment() {
        val (path, frag) = EpubStreamingParser.splitRawHref("part%231.xhtml#sec%201")
        assertEquals("part#1.xhtml", path)
        assertEquals("sec 1", frag)
    }

    // Issue #6: Bounded image sizing constrains width, height, and total decoded pixel budget
    @Test
    fun boundedImageSizingCapsTallAndOversizedImages() {
        val tallSample = BoundedImageSizing.computeSampleSize(
            srcWidth = 800,
            srcHeight = 20_000,
            maxWidthPx = 1080,
            maxHeightPx = 4096,
            maxPixels = 4_000_000L,
        )
        assertTrue("Expected sampleSize >= 8 for 800x20000 tall image, got $tallSample", tallSample >= 8)
        assertTrue(20_000 / tallSample <= 4096)

        val giantSample = BoundedImageSizing.computeSampleSize(
            srcWidth = 10_000,
            srcHeight = 10_000,
            maxWidthPx = 2048,
            maxHeightPx = 4096,
            maxPixels = 4_000_000L,
        )
        val outW = (10_000L + giantSample - 1) / giantSample
        val outH = (10_000L + giantSample - 1) / giantSample
        assertTrue("Expected <= 4MP decoded, got ${outW * outH}", outW * outH <= 4_000_000L)
    }
}
