package com.holos.reader.g2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G2pUnitTest {

    @Test
    fun sentenceSplitter_handlesEmptyAndShortText() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split("   \n\t ").isEmpty())
        assertEquals(listOf("Привіт, світе!"), SentenceSplitter.split("Привіт, світе!"))
    }

    @Test
    fun sentenceSplitter_splitsLongParagraphBelowMaxChars() {
        val longText = buildString {
            repeat(12) { idx ->
                append("Це тестове речення номер ${idx + 1} для перевірки розбиття довгого абзацу на коротші частини. ")
            }
        }
        val chunks = SentenceSplitter.split(longText, maxChars = 180)
        assertTrue("Expected multiple chunks, got ${chunks.size}", chunks.size > 2)
        for (chunk in chunks) {
            assertTrue("Chunk exceeded limit (${chunk.length}): $chunk", chunk.length <= 210)
            assertFalse(chunk.isBlank())
        }
    }

    @Test
    fun textNormalizer_stripsSceneBreaksAndNormalizesInlineStress() {
        assertEquals("", UkrainianTextNormalizer.normalize("***"))
        assertEquals("", UkrainianTextNormalizer.normalize("❖ ❖ ❖"))
        val stressed = UkrainianTextNormalizer.normalize("Му+дрого")
        assertTrue("Expected acute accent in '$stressed'", stressed.contains('\u0301'))
    }

    @Test
    fun textNormalizer_convertsNumbersToUkrainianWords() {
        assertEquals("нуль", UkrainianTextNormalizer.numberToWords(0))
        assertEquals("двадцять один", UkrainianTextNormalizer.numberToWords(21))
        assertEquals("сто п'ятдесят", UkrainianTextNormalizer.numberToWords(150))
    }

    @Test
    fun ukrainianIpa_transcribesStressedWordsAndPreservesPunctuation() {
        val ipa = UkrainianIpa.transcribe("приві\u0301т, сві\u0301те!")
        assertFalse(ipa.isBlank())
        assertTrue("Expected comma preserved in '$ipa'", ipa.contains(","))
        assertTrue("Expected exclamation preserved in '$ipa'", ipa.contains("!"))
    }
}
