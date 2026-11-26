package com.holos.reader.tts

import com.holos.tts.UnigramTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnigramTokenizerTest {

    private fun sampleTokenizer(): UnigramTokenizer = UnigramTokenizer.fromVocab(
        listOf(
            "<s>" to 0.0,
            "<pad>" to 0.0,
            "</s>" to 0.0,
            "<unk>" to 0.0,
            "▁замок" to -1.5,
            "▁стоїть" to -1.8,
            "▁на" to -1.0,
            "▁горі" to -2.0,
        )
    )

    @Test
    fun encodesUkrainianSentenceWithExactSpans() {
        val tok = sampleTokenizer()
        val enc = tok.encode("замок стоїть на горі")
        assertEquals(6, enc.inputIds.size)
        assertEquals(0L, enc.inputIds.first())
        assertEquals(2L, enc.inputIds.last())
        assertEquals(4L, enc.inputIds[1])
        assertEquals(5L, enc.inputIds[2])
        assertEquals(6L, enc.inputIds[3])
        assertEquals(7L, enc.inputIds[4])
        assertTrue(enc.spans[1].charStart == 0 && enc.spans[1].charEnd == 5)
    }

    @Test
    fun blankTextReturnsBosAndEosOnly() {
        val enc = sampleTokenizer().encode("   ")
        assertEquals(2, enc.inputIds.size)
        assertEquals(0L, enc.inputIds[0])
        assertEquals(2L, enc.inputIds[1])
    }

    @Test
    fun unknownCharactersFallBackToUnkAndRespectMaxLength() {
        val enc = sampleTokenizer().encode("замок 1234567890", maxLength = 5)
        assertEquals(5, enc.inputIds.size)
        assertEquals(0L, enc.inputIds.first())
        assertEquals(2L, enc.inputIds.last())
        assertTrue(enc.inputIds.contains(3L))
    }
    @Test
    fun leadingWhitespacePreservesOriginalUtf16Offsets() {
        val enc = sampleTokenizer().encode("   замок стоїть")
        assertEquals(3, enc.spans[1].charStart)
        assertEquals(8, enc.spans[1].charEnd)
    }
}
