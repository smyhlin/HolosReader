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

    @Test
    fun textNormalizer_collapsesStretchedScreamsAndStutters() {
        assertEquals("Аааргх!", UkrainianTextNormalizer.normalize("А-А-А-А-А-А-АРГХ!"))
        assertEquals("Ммгххааа…", UkrainianTextNormalizer.normalize("М-м-м-гх-х-х-а-а-а…"))
        assertEquals("Ннгхх…", UkrainianTextNormalizer.normalize("Н-н-нгххх…"))
        assertEquals("Кхх…", UkrainianTextNormalizer.normalize("Кх-х…"))
        assertEquals("Гмм?", UkrainianTextNormalizer.normalize("Гм-м?"))
        assertEquals("Аггх!", UkrainianTextNormalizer.normalize("Аггггх!"))
        assertEquals("Що?!", UkrainianTextNormalizer.normalize("Щ-що?!"))
        assertEquals("Хто ти?!", UkrainianTextNormalizer.normalize("Х-хто ти?!"))
        assertEquals("Хе-хе-хе…", UkrainianTextNormalizer.normalize("Хе-хе-хе-хе…"))
        // Normal Ukrainian hyphenated words must stay untouched
        assertEquals("по-українськи", UkrainianTextNormalizer.normalize("по-українськи"))
        assertEquals("темно-сірий", UkrainianTextNormalizer.normalize("темно-сірий"))
        assertEquals("з-за спин", UkrainianTextNormalizer.normalize("з-за спин"))
    }
    @Test
    fun sentenceSplitterSplitsQuotedAndBracketedBoundaries() {
        val text = "«Це перший рядок, досить довгий щоб не зливатися з наступним реченням.» (А це вже друге речення у дужках!) І третє речення наприкінці."
        val parts = SentenceSplitter.split(text, maxChars = 90)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= 90 })
    }
}
